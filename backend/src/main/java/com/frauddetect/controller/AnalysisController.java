package com.frauddetect.controller;

import com.frauddetect.entity.Analysis;
import com.frauddetect.entity.User;
import com.frauddetect.model.AnalysisResult;
import com.frauddetect.model.BatchAnalysisResult;
import com.frauddetect.service.TaxDocumentVerificationService;
import com.frauddetect.service.FraudDetectionService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class AnalysisController {

    private final FraudDetectionService fraudDetectionService;
    private final TaxDocumentVerificationService taxDocumentVerificationService;

    public AnalysisController(FraudDetectionService fraudDetectionService,
                              TaxDocumentVerificationService taxDocumentVerificationService) {
        this.fraudDetectionService = fraudDetectionService;
        this.taxDocumentVerificationService = taxDocumentVerificationService;
    }

    @PostMapping("/analyze")
    public ResponseEntity<?> analyze(
        @RequestParam("file") MultipartFile file,
        @AuthenticationPrincipal User user
    ) {
        if (file.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "Fichier vide"));

        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase().endsWith(".pdf"))
            return ResponseEntity.badRequest().body(Map.of("error", "Seuls les fichiers PDF sont acceptés"));

        if (file.getSize() > 10 * 1024 * 1024)
            return ResponseEntity.badRequest().body(Map.of("error", "Fichier trop volumineux (max 10MB)"));

        if (!user.isEmailVerified())
            return ResponseEntity.status(403).body(Map.of(
                "error", "Confirmez votre adresse email pour lancer une analyse",
                "emailNotVerified", true));

        try {
            AnalysisResult result = fraudDetectionService.analyze(file, user);
            return ResponseEntity.ok(result);
        } catch (FraudDetectionService.QuotaExceededException e) {
            return ResponseEntity.status(402).body(Map.of("error", e.getMessage(), "quotaExceeded", true));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(Map.of("error", "Erreur lors de l'analyse : " + e.getMessage()));
        }
    }

    @PostMapping("/analyze/batch")
    public ResponseEntity<?> analyzeBatch(
        @RequestParam("files") List<MultipartFile> files,
        @AuthenticationPrincipal User user
    ) {
        if (files == null || files.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "Aucun fichier fourni"));

        if (files.size() > 10)
            return ResponseEntity.badRequest().body(Map.of("error", "Maximum 10 fichiers par lot"));

        for (MultipartFile file : files) {
            String filename = file.getOriginalFilename();
            if (filename == null || !filename.toLowerCase().endsWith(".pdf"))
                return ResponseEntity.badRequest().body(Map.of("error", "Seuls les fichiers PDF sont acceptés : " + filename));
            if (file.getSize() > 10 * 1024 * 1024)
                return ResponseEntity.badRequest().body(Map.of("error", "Fichier trop volumineux (max 10MB) : " + filename));
        }

        if (!user.isEmailVerified())
            return ResponseEntity.status(403).body(Map.of(
                "error", "Confirmez votre adresse email pour lancer une analyse",
                "emailNotVerified", true));

        try {
            BatchAnalysisResult result = fraudDetectionService.analyzeBatch(files, user);
            return ResponseEntity.ok(result);
        } catch (FraudDetectionService.QuotaExceededException e) {
            return ResponseEntity.status(402).body(Map.of("error", e.getMessage(), "quotaExceeded", true));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(Map.of("error", "Erreur lors de l'analyse du lot : " + e.getMessage()));
        }
    }

    /**
     * Rapproche un avis d'imposition des bulletins deja analyses.
     *
     * @param netImposableMensuel net imposable releve sur les bulletins ; absent,
     *                            seules les donnees de l'avis sont restituees.
     */
    @PostMapping("/analyze/avis-imposition")
    public ResponseEntity<?> analyzeAvisImposition(
        @RequestParam("file") MultipartFile file,
        @RequestParam(value = "netImposableMensuel", required = false) Double netImposableMensuel,
        @AuthenticationPrincipal User user
    ) {
        if (file.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "Fichier vide"));

        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase().endsWith(".pdf"))
            return ResponseEntity.badRequest().body(Map.of("error", "Seuls les fichiers PDF sont acceptés"));

        if (file.getSize() > 10 * 1024 * 1024)
            return ResponseEntity.badRequest().body(Map.of("error", "Fichier trop volumineux (max 10MB)"));

        if (!user.isEmailVerified())
            return ResponseEntity.status(403).body(Map.of(
                "error", "Confirmez votre adresse email pour lancer une analyse",
                "emailNotVerified", true));

        try {
            TaxDocumentVerificationService.Result result =
                taxDocumentVerificationService.verify(file.getBytes(), netImposableMensuel);

            Map<String, Object> body = new java.util.HashMap<>();
            body.put("avis", result.avis());
            body.put("checks", result.checks());
            body.put("twoDDoc", twoDDocSummary(result));

            return ResponseEntity.ok(body);
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(Map.of("error", "Erreur lors de l'analyse de l'avis : " + e.getMessage()));
        }
    }

    /**
     * Restitution du 2D-DOC sans donnee nominative : ni contenu brut, ni valeurs
     * de champs, qui portent identite et montants.
     */
    private Map<String, Object> twoDDocSummary(TaxDocumentVerificationService.Result result) {
        var data = result.twoDDoc();
        Map<String, Object> summary = new java.util.HashMap<>();
        summary.put("detected", data.isDetected());
        summary.put("signatureStatus", data.getSignatureStatus());
        if (data.isDetected()) {
            summary.put("issuer", data.getAuthorityId());
            summary.put("documentType", data.getDocumentType());
            summary.put("signatureDate", data.getSignatureDate());
            summary.put("comparisons", result.comparisons());
        }
        return summary;
    }

    @GetMapping("/history")
    public ResponseEntity<List<Analysis>> history(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(fraudDetectionService.getHistory(user));
    }

    @GetMapping("/health")
    public ResponseEntity<?> health() {
        return ResponseEntity.ok(Map.of("status", "OK", "service", "FraudDetect API"));
    }
}
