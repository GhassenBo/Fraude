package com.frauddetect.service;

import com.frauddetect.model.AnalysisResult;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.*;

@Service
public class SalaryCalculationService {

    // French SMIC 2024
    private static final double SMIC_MENSUEL = 1766.92;
    private static final double SMIC_HORAIRE = 11.65;

    // Approximate charge rates
    private static final double CHARGES_SALARIALES_MIN = 0.20;
    private static final double CHARGES_SALARIALES_MAX = 0.30;

    public List<AnalysisResult.Check> analyzeCalculations(String text) {
        List<AnalysisResult.Check> checks = new ArrayList<>();

        Double salaireBrut = extractFirstMontant(text, "(?i)salaire\\s*brut[\\s:€]*([\\d\\s,.]+)");
        Double salaireNet = extractFirstMontant(text, "(?i)net\\s+[àa]\\s+payer[\\s:€]*([\\d\\s,.]+)");
        Double totalCotisations = extractFirstMontant(text, "(?i)(total\\s+cotisations|total\\s+retenues)[\\s:€]*([\\d\\s,.]+)");

        // Check 1: Brut vs Net ratio
        if (salaireBrut != null && salaireNet != null && salaireBrut > 0) {
            double ratio = salaireNet / salaireBrut;
            if (ratio > 1.0) {
                checks.add(AnalysisResult.Check.builder()
                    .category("Calculs")
                    .label("Ratio Net/Brut")
                    .status("FAILED")
                    .detail(String.format("Net (%.2f€) supérieur au Brut (%.2f€) — impossible", salaireNet, salaireBrut))
                    .build());
            } else if (ratio > 0.90) {
                checks.add(AnalysisResult.Check.builder()
                    .category("Calculs")
                    .label("Ratio Net/Brut")
                    .status("FAILED")
                    .detail(String.format("Ratio Net/Brut de %.0f%% — trop élevé, les cotisations semblent manquantes", ratio * 100))
                    .build());
            } else if (ratio < 0.60) {
                checks.add(AnalysisResult.Check.builder()
                    .category("Calculs")
                    .label("Ratio Net/Brut")
                    .status("WARNING")
                    .detail(String.format("Ratio Net/Brut de %.0f%% — inhabituel, vérifier les cotisations", ratio * 100))
                    .build());
            } else {
                checks.add(AnalysisResult.Check.builder()
                    .category("Calculs")
                    .label("Ratio Net/Brut")
                    .status("OK")
                    .detail(String.format("Ratio Net/Brut de %.0f%% — cohérent avec les cotisations françaises", ratio * 100))
                    .build());
            }
        } else if (salaireBrut == null || salaireNet == null) {
            checks.add(AnalysisResult.Check.builder()
                .category("Calculs")
                .label("Ratio Net/Brut")
                .status("WARNING")
                .detail("Impossible d'extraire le salaire brut et/ou net du document")
                .build());
        }

        // Check 2: Cotisations coherence
        if (salaireBrut != null && salaireNet != null && totalCotisations != null) {
            double expectedDiff = salaireBrut - salaireNet;
            double tolerance = salaireBrut * 0.05;
            if (Math.abs(expectedDiff - totalCotisations) > tolerance) {
                checks.add(AnalysisResult.Check.builder()
                    .category("Calculs")
                    .label("Cohérence des cotisations")
                    .status("FAILED")
                    .detail(String.format("Écart entre Brut-Net (%.2f€) et cotisations déclarées (%.2f€) — incohérence comptable", expectedDiff, totalCotisations))
                    .build());
            } else {
                checks.add(AnalysisResult.Check.builder()
                    .category("Calculs")
                    .label("Cohérence des cotisations")
                    .status("OK")
                    .detail(String.format("Brut - Net = %.2f€, cotisations déclarées = %.2f€ — cohérent", expectedDiff, totalCotisations))
                    .build());
            }
        }

        // Check 3: Salary vs SMIC
        if (salaireBrut != null) {
            if (salaireBrut < SMIC_MENSUEL) {
                checks.add(AnalysisResult.Check.builder()
                    .category("Calculs")
                    .label("Comparaison SMIC")
                    .status("FAILED")
                    .detail(String.format("Salaire brut %.2f€ inférieur au SMIC mensuel (%.2f€) — illégal", salaireBrut, SMIC_MENSUEL))
                    .build());
            } else if (salaireBrut > 50000) {
                checks.add(AnalysisResult.Check.builder()
                    .category("Calculs")
                    .label("Comparaison SMIC")
                    .status("WARNING")
                    .detail(String.format("Salaire brut %.2f€ — très élevé, vérifier la cohérence avec le poste", salaireBrut))
                    .build());
            } else {
                checks.add(AnalysisResult.Check.builder()
                    .category("Calculs")
                    .label("Comparaison SMIC")
                    .status("OK")
                    .detail(String.format("Salaire brut %.2f€ — au-dessus du SMIC (%.2f€)", salaireBrut, SMIC_MENSUEL))
                    .build());
            }
        }

        // Check 4: Required fields present
        checks.addAll(checkRequiredFields(text));

        return checks;
    }

    private List<AnalysisResult.Check> checkRequiredFields(String text) {
        List<AnalysisResult.Check> checks = new ArrayList<>();
        String lower = text.toLowerCase();

        // Required fields on a French pay slip
        Map<String, String> requiredFields = new LinkedHashMap<>();
        requiredFields.put("siret", "Numéro SIRET employeur");
        requiredFields.put("convention collective", "Convention collective");
        requiredFields.put("congés payés", "Congés payés");
        requiredFields.put("net à payer", "Net à payer");
        requiredFields.put("cotisation", "Lignes de cotisations");

        int missing = 0;
        List<String> missingList = new ArrayList<>();
        for (Map.Entry<String, String> entry : requiredFields.entrySet()) {
            if (!lower.contains(entry.getKey())) {
                missing++;
                missingList.add(entry.getValue());
            }
        }

        if (missing == 0) {
            checks.add(AnalysisResult.Check.builder()
                .category("Structure")
                .label("Champs obligatoires")
                .status("OK")
                .detail("Tous les champs obligatoires d'un bulletin français sont présents")
                .build());
        } else if (missing <= 2) {
            checks.add(AnalysisResult.Check.builder()
                .category("Structure")
                .label("Champs obligatoires")
                .status("WARNING")
                .detail("Champs manquants : " + String.join(", ", missingList))
                .build());
        } else {
            checks.add(AnalysisResult.Check.builder()
                .category("Structure")
                .label("Champs obligatoires")
                .status("FAILED")
                .detail("Nombreux champs obligatoires absents : " + String.join(", ", missingList))
                .build());
        }

        return checks;
    }

    private Double extractFirstMontant(String text, String regex) {
        try {
            Matcher m = Pattern.compile(regex).matcher(text);
            if (m.find()) {
                String raw = m.group(m.groupCount())
                    .replaceAll("\\s", "")
                    .replace(",", ".")
                    .replaceAll("[^0-9.]", "");
                if (!raw.isBlank()) return Double.parseDouble(raw);
            }
        } catch (Exception ignored) {}
        return null;
    }
}
