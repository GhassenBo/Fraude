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
        String[] lines = text.split("\\r?\\n");

        // Line-by-line extraction handles multi-column PDF table layouts (URSSAF TESE, Sage, ADP, etc.)
        Double salaireBrut = extractAmountNearLabel(lines,
            "salaire brut", "rémunération brute", "rémunération brut",
            "total rémunération brute", "total rémunération brut",
            "brut total", "total brut", "salaire de base");
        Double salaireNet = extractAmountNearLabel(lines,
            "net à payer", "net a payer", "net payé", "net paye",
            "net versé", "net verse", "net imposable", "net fiscal",
            "net avant impôt", "net avant impot");
        Double totalCotisations = extractAmountNearLabel(lines,
            "total cotisations", "total retenues", "total prélèvements",
            "total prelevements", "total charges salariales");


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

    /**
     * Searches each line for a label keyword, then extracts a monetary amount
     * from the same line or the line immediately following.
     * This handles PDF table layouts where label and value are in separate columns.
     */
    private Double extractAmountNearLabel(String[] lines, String... labels) {
        for (int i = 0; i < lines.length; i++) {
            String lower = lines[i].toLowerCase();
            for (String label : labels) {
                if (lower.contains(label.toLowerCase())) {
                    Double amount = parseMonetaryAmount(lines[i]);
                    if (amount != null) return amount;
                    if (i + 1 < lines.length) {
                        amount = parseMonetaryAmount(lines[i + 1]);
                        if (amount != null) return amount;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Finds the first plausible salary amount (≥100, ≤200000) in a line of text.
     * Handles French number format: "3 224,64" or "3224.64" or "3 224.64".
     */
    private Double parseMonetaryAmount(String line) {
        // Match numbers: optional thousands separator (space or nbsp), decimal comma or dot
        Matcher m = Pattern.compile("([0-9]{1,3}(?:[\\s\u00a0][0-9]{3})*(?:[.,][0-9]{1,2})?)").matcher(line);
        while (m.find()) {
            String raw = m.group(1).replaceAll("[\\s\u00a0]", "").replace(",", ".");
            try {
                double val = Double.parseDouble(raw);
                if (val >= 100 && val <= 200000) return val;
            } catch (NumberFormatException ignored) {}
        }
        return null;
    }
}
