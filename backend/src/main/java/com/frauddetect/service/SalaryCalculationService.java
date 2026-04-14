package com.frauddetect.service;

import com.frauddetect.model.AnalysisResult;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.*;
import java.text.Normalizer;

@Service
public class SalaryCalculationService {

    // French SMIC 2024
    private static final double SMIC_MENSUEL = 1766.92;
    private static final double SMIC_HORAIRE = 11.65;

    // Approximate charge rates
    private static final double CHARGES_SALARIALES_MIN = 0.20;
    private static final double CHARGES_SALARIALES_MAX = 0.30;

    public List<AnalysisResult.Check> analyzeCalculations(String text, AnalysisResult.DocumentInfo docInfo) {
        List<AnalysisResult.Check> checks = new ArrayList<>();
        String[] lines = text.split("\\r?\\n");

        // Prefer Claude Vision values from docInfo (already validated, no column confusion).
        // Fall back to regex extraction only when Vision values are absent.
        Double salaireBrut = parseDocInfoAmount(docInfo != null ? docInfo.getSalaireBrut() : null);
        Double salaireNet  = parseDocInfoAmount(docInfo != null ? docInfo.getSalaireNet()  : null);

        if (salaireBrut == null) {
            salaireBrut = extractAmountNearLabel(lines,
                "salaire brut", "remuneration brute", "remuneration brut",
                "total remuneration brute", "total remuneration brut",
                "brut total", "total brut", "brut fiscal", "brut :");
        }
        if (salaireNet == null) {
            salaireNet = extractAmountNearLabel(lines,
                "net a payer", "net paye", "net verse",
                "net avant impot");
        }

        Double totalCotisations = extractAmountNearLabel(lines,
            "total cotisations salariales", "total retenues salariales",
            "total prelevements salariales", "total charges salariales");

        // If net > brut both values are likely extraction failures — discard to avoid false positives
        if (salaireBrut != null && salaireNet != null && salaireNet > salaireBrut) {
            salaireBrut = null;
            salaireNet = null;
        }


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

    /** Parses a DocumentInfo amount string like "4249.60 €" or "4 249,60 €" to a Double. */
    private Double parseDocInfoAmount(String amount) {
        if (amount == null || amount.isBlank()) return null;
        try {
            double d = Double.parseDouble(
                amount.replaceAll("[\\s\u00a0€]", "").replace(",", "."));
            return d > 0 ? d : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Strip diacritical marks so "NET A PAYER" matches the accented key "net à payer". */
    private String normalizeDiacritics(String text) {
        return Normalizer.normalize(text.toLowerCase(), Normalizer.Form.NFD)
            .replaceAll("[\\p{InCombiningDiacriticalMarks}]", "");
    }

    private List<AnalysisResult.Check> checkRequiredFields(String text) {
        List<AnalysisResult.Check> checks = new ArrayList<>();
        // Normalize once — handles PDFs that strip accents (e.g. "NET A PAYER" vs "net à payer")
        String normalized = normalizeDiacritics(text);

        // Required fields on a French pay slip — keys are already diacritic-free
        Map<String, String> requiredFields = new LinkedHashMap<>();
        requiredFields.put("siret", "Numéro SIRET employeur");
        requiredFields.put("convention collective", "Convention collective");
        requiredFields.put("conges payes", "Congés payés");
        requiredFields.put("net a payer", "Net à payer");
        requiredFields.put("cotisation", "Lignes de cotisations");

        int missing = 0;
        List<String> missingList = new ArrayList<>();
        for (Map.Entry<String, String> entry : requiredFields.entrySet()) {
            if (!normalized.contains(entry.getKey())) {
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
     * Searches for a label (diacritic-insensitive), then returns the LARGEST monetary amount
     * found within 1 line before + same line + up to 4 following lines.
     * Scanning 1 line before handles PDFs where the value is extracted before the label
     * due to right-column-first text ordering.
     * "Largest" avoids small incidental numbers (hours, %) and correctly identifies
     * multi-row labels like "NET A PAYER AVANT IMPOT SUR\nLE REVENU\n1563,67".
     */
    private Double extractAmountNearLabel(String[] lines, String... labels) {
        Double best = null;
        for (int i = 0; i < lines.length; i++) {
            String norm = normalizeDiacritics(lines[i]);
            boolean found = false;
            for (String label : labels) {
                if (norm.contains(label)) { found = true; break; }
            }
            if (!found) continue;
            for (int j = Math.max(0, i - 1); j <= Math.min(i + 4, lines.length - 1); j++) {
                Double val = largestAmountInLine(lines[j]);
                if (val != null && (best == null || val > best)) best = val;
            }
        }
        return best;
    }

    /** Returns the LARGEST plausible salary amount (100–200 000) found in a single line. */
    private Double largestAmountInLine(String line) {
        Matcher m = Pattern.compile("([0-9]{1,3}(?:[\\s\u00a0][0-9]{3})*(?:[.,][0-9]{1,2})?)").matcher(line);
        Double best = null;
        while (m.find()) {
            String raw = m.group(1).replaceAll("[\\s\u00a0]", "").replace(",", ".");
            try {
                double val = Double.parseDouble(raw);
                if (val >= 100 && val <= 200000 && (best == null || val > best)) best = val;
            } catch (NumberFormatException ignored) {}
        }
        return best;
    }
}
