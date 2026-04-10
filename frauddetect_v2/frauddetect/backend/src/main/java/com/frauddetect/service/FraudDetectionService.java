package com.frauddetect.service;

import com.frauddetect.entity.Analysis;
import com.frauddetect.entity.User;
import com.frauddetect.model.AnalysisResult;
import com.frauddetect.repository.AnalysisRepository;
import com.frauddetect.repository.UserRepository;
import com.frauddetect.util.PdfAnalyzer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;

@Service
public class FraudDetectionService {

    private final PdfAnalyzer pdfAnalyzer;
    private final SiretVerificationService siretService;
    private final SalaryCalculationService salaryService;
    private final UserRepository userRepository;
    private final AnalysisRepository analysisRepository;

    @Value("${app.free.documents}")
    private int freeLimit;

    public FraudDetectionService(PdfAnalyzer pdfAnalyzer,
                                  SiretVerificationService siretService,
                                  SalaryCalculationService salaryService,
                                  UserRepository userRepository,
                                  AnalysisRepository analysisRepository) {
        this.pdfAnalyzer = pdfAnalyzer;
        this.siretService = siretService;
        this.salaryService = salaryService;
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

        PdfAnalyzer.PdfAnalysisData pdfData = pdfAnalyzer.analyze(file.getInputStream());
        allChecks.addAll(pdfData.metadataChecks());
        allChecks.add(siretService.verify(pdfData.documentInfo().getSiret()));
        allChecks.addAll(salaryService.analyzeCalculations(pdfData.rawText()));

        int score = computeScore(allChecks);
        String verdict = computeVerdict(score);
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
            .build();
    }

    public List<Analysis> getHistory(User user) {
        return analysisRepository.findByUserOrderByCreatedAtDesc(user);
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

    private String computeVerdict(int score) {
        if (score >= 75) return "AUTHENTIQUE";
        if (score >= 45) return "SUSPECT";
        return "FRAUDULEUX";
    }

    private String computeColor(int score) {
        if (score >= 75) return "green";
        if (score >= 45) return "orange";
        return "red";
    }

    public static class QuotaExceededException extends RuntimeException {
        public QuotaExceededException(String message) { super(message); }
    }
}
