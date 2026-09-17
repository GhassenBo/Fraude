package com.frauddetect.service;

import com.frauddetect.dto.HistoryDto;
import com.frauddetect.entity.Analysis;
import com.frauddetect.entity.User;
import com.frauddetect.model.AnalysisResult;
import com.frauddetect.model.BatchAnalysisResult;
import com.frauddetect.repository.AnalysisRepository;
import com.frauddetect.repository.UserRepository;
import com.frauddetect.util.PdfAnalyzer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class FraudDetectionService {

    private final PdfAnalyzer pdfAnalyzer;
    private final SiretVerificationService siretService;
    private final SalaryCalculationService salaryService;
    private final AiAnalysisService aiAnalysisService;
    private final ClaudeVisionService claudeVisionService;
    private final UserRepository userRepository;
    private final AnalysisRepository analysisRepository;

    @Value("${app.free.documents}")
    private int freeLimit;

    public FraudDetectionService(PdfAnalyzer pdfAnalyzer,
                                  SiretVerificationService siretService,
                                  SalaryCalculationService salaryService,
                                  AiAnalysisService aiAnalysisService,
                                  ClaudeVisionService claudeVisionService,
                                  UserRepository userRepository,
                                  AnalysisRepository analysisRepository) {
        this.pdfAnalyzer = pdfAnalyzer;
        this.siretService = siretService;
        this.salaryService = salaryService;
        this.aiAnalysisService = aiAnalysisService;
        this.claudeVisionService = claudeVisionService;
        this.userRepository = userRepository;
        this.analysisRepository = analysisRepository;
    }

    public AnalysisResult analyze(MultipartFile file, User user) throws Exception {
        if (!user.canAnalyze(freeLimit)) {
            throw new QuotaExceededException(
                "Vous avez atteint votre limite de " + freeLimit + " documents gratuits. " +
                "Passez au plan Pro pour des analyses illimitées."
            );
        }

        List<AnalysisResult.Check> allChecks = new ArrayList<>();

        // Rule-based analysis
        PdfAnalyzer.PdfAnalysisData pdfData = pdfAnalyzer.analyze(file.getInputStream());
        allChecks.addAll(pdfData.metadataChecks());
        allChecks.addAll(siretService.verify(pdfData.documentInfo().getSiret(), pdfData.documentInfo().getEmployeur()));
        allChecks.addAll(salaryService.analyzeCalculations(pdfData.rawText(), pdfData.documentInfo()));

        // AI analysis (GPT-4) — optional, runs only if API key is configured
        List<AnalysisResult.Check> aiChecks = aiAnalysisService.analyze(pdfData.rawText(), pdfData.documentInfo());
        allChecks.addAll(aiChecks);

        // Claude Vision forensic analysis — optional, runs only if ANTHROPIC_API_KEY is configured
        allChecks.addAll(claudeVisionService.detectForgery(pdfData.pdfBytes()));

        int score = computeScore(allChecks);
        String verdict = computeVerdictWithChecks(score, allChecks);
        String color = computeColor(score);

        user.setDocumentsUsed(user.getDocumentsUsed() + 1);
        userRepository.save(user);

        analysisRepository.save(Analysis.builder()
            .user(user).filename(file.getOriginalFilename())
            .score(score).verdict(verdict).color(color).build());

        return AnalysisResult.builder()
            .score(score).verdict(verdict).color(color)
            .checks(allChecks).documentInfo(pdfData.documentInfo())
            .remainingDocuments(user.remainingFreeDocuments(freeLimit))
            .isPro(user.getPlan() == User.Plan.PRO)
            .aiEnabled(aiAnalysisService.isEnabled())
            .build();
    }

    public BatchAnalysisResult analyzeBatch(List<MultipartFile> files, User user) throws Exception {
        int count = files.size();
        if (!user.canAnalyzeMultiple(count, freeLimit)) {
            int remaining = user.remainingFreeDocuments(freeLimit);
            throw new QuotaExceededException(
                "Quota insuffisant : " + remaining + " analyse(s) disponible(s) pour " + count + " documents. " +
                "Passez au plan Pro pour des analyses illimitées."
            );
        }

        List<AnalysisResult> results = new ArrayList<>();
        List<String> filenames = new ArrayList<>();
        for (MultipartFile file : files) {
            results.add(analyze(file, user));
            filenames.add(file.getOriginalFilename());
        }

        List<AnalysisResult.Check> crossChecks = checkCumulProgression(results);

        int globalScore = results.stream().mapToInt(AnalysisResult::getScore).min().orElse(0);
        if (crossChecks.stream().anyMatch(c -> "FAILED".equals(c.getStatus()))) {
            globalScore = Math.min(globalScore, 40);
        }

        return BatchAnalysisResult.builder()
            .globalScore(globalScore)
            .globalVerdict(computeVerdict(globalScore))
            .globalColor(computeColor(globalScore))
            .documentsAnalyzed(count)
            .results(results)
            .filenames(filenames)
            .crossChecks(crossChecks)
            .build();
    }

    /**
     * Sur des bulletins consecutifs, le cumul brut progresse exactement du brut
     * du mois : cumul(N) - cumul(N-1) = brut(N). C'est l'egalite la plus difficile
     * a falsifier, un faussaire mettant rarement les cumuls a jour de facon coherente.
     * On ne compare que des mois consecutifs de la meme annee et on exige que
     * toutes les valeurs soient disponibles.
     */
    private List<AnalysisResult.Check> checkCumulProgression(List<AnalysisResult> results) {
        List<AnalysisResult> usable = results.stream()
            .filter(r -> r.getDocumentInfo() != null
                && r.getDocumentInfo().getCumulBrut() != null
                && r.getDocumentInfo().getMoisPeriode() != null)
            .sorted(Comparator.comparingInt(r -> r.getDocumentInfo().getMoisPeriode()))
            .toList();

        if (usable.size() < 2) return List.of();

        List<AnalysisResult.Check> checks = new ArrayList<>();
        for (int i = 1; i < usable.size(); i++) {
            AnalysisResult prev = usable.get(i - 1);
            AnalysisResult curr = usable.get(i);

            int moisPrev = prev.getDocumentInfo().getMoisPeriode();
            int moisCurr = curr.getDocumentInfo().getMoisPeriode();
            if (moisCurr - moisPrev != 1) continue;

            Double brutCurr = parseAmount(curr.getDocumentInfo().getSalaireBrut());
            if (brutCurr == null) continue;

            double progression = curr.getDocumentInfo().getCumulBrut()
                - prev.getDocumentInfo().getCumulBrut();
            double ecart = Math.abs(progression - brutCurr);

            String periodes = "mois " + moisPrev + " → " + moisCurr;
            if (ecart > 1.0) {
                checks.add(AnalysisResult.Check.builder()
                    .category("Recoupement")
                    .label("Progression des cumuls (" + periodes + ")")
                    .status("FAILED")
                    .detail(String.format(
                        "Le cumul brut progresse de %.2f € alors que le brut du mois est de %.2f €"
                            + " (écart de %.2f €) — les cumuls n'ont pas été recalculés",
                        progression, brutCurr, ecart))
                    .build());
            } else {
                checks.add(AnalysisResult.Check.builder()
                    .category("Recoupement")
                    .label("Progression des cumuls (" + periodes + ")")
                    .status("OK")
                    .detail(String.format("Progression du cumul brut (%.2f €) conforme au brut du mois",
                        progression))
                    .build());
            }
        }
        return checks;
    }

    private Double parseAmount(String amount) {
        if (amount == null) return null;
        String cleaned = amount.replaceAll("[^0-9,.]", "").replace(",", ".");
        if (cleaned.isBlank()) return null;
        try {
            return Double.parseDouble(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Historique des analyses, du plus recent au plus ancien.
     *
     * La projection en DTO fait partie du contrat : renvoyer les entites
     * exposerait l'utilisateur rattache a chaque ligne, et leur serialisation
     * echoue de toute facon hors transaction.
     */
    public List<HistoryDto.Item> getHistory(User user) {
        return analysisRepository.findByUserOrderByCreatedAtDesc(user).stream()
            .map(HistoryDto.Item::from)
            .toList();
    }

    private int computeScore(List<AnalysisResult.Check> checks) {
        if (checks.isEmpty()) return 50;
        int total = 100;
        for (AnalysisResult.Check check : checks) {
            switch (check.getStatus()) {
                case "FAILED" -> total -= 25;
                case "WARNING" -> total -= 10;
            }
        }
        return Math.max(0, Math.min(100, total));
    }

    // Visibles dans le paquet : DossierVerificationService recalcule le verdict
    // d'un lot apres ses propres controles, et doit appliquer les memes seuils.
    String computeVerdict(int score) {
        if (score >= 75) return "AUTHENTIQUE";
        if (score >= 45) return "SUSPECT";
        return "FRAUDULEUX";
    }

    private String computeVerdictWithChecks(int score, List<AnalysisResult.Check> checks) {
        String base = computeVerdict(score);
        // A single FAILED in a critical category must prevent "AUTHENTIQUE" verdict
        if ("AUTHENTIQUE".equals(base)) {
            boolean hasCriticalFailed = checks.stream()
                .filter(c -> "FAILED".equals(c.getStatus()))
                .anyMatch(c -> "Calculs".equals(c.getCategory())
                    || "Employeur".equals(c.getCategory())
                    || "Intégrité PDF".equals(c.getCategory()));
            if (hasCriticalFailed) return "SUSPECT";
        }
        return base;
    }

    String computeColor(int score) {
        if (score >= 75) return "green";
        if (score >= 45) return "orange";
        return "red";
    }

    public static class QuotaExceededException extends RuntimeException {
        public QuotaExceededException(String message) { super(message); }
    }
}
