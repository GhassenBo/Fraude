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
                "net a payer", "net paye", "net verse");
        }

        Double totalCotisations = extractAmountNearLabel(lines,
            "total cotisations salariales", "total retenues salariales",
            "total prelevements salariales", "total charges salariales");

        // Net social extracted independently — used to cross-validate NET À PAYER
        Double netSocial = extractAmountNearLabel(lines,
            "montant net social", "net social");

        // Net avant impôt sur le revenu (= net avant PAS).
        // Distinct du net à payer final : le PAS varie de 0% à 45% selon l'employé
        // et ne doit pas fausser le ratio CCN ni le check cohérence cotisations.
        Double netAvantImpot = extractAmountNearLabel(lines,
            "net a payer avant impot sur le revenu",
            "net a payer avant impot",
            "net avant prelevement a la source",
            "net avant prelevement",
            "net imposable");

        // Montant du prélèvement à la source — extraction sur la même ligne uniquement.
        // La fenêtre ±4 lignes de extractAmountNearLabel retournerait le net avant impôt
        // (valeur plus grande, adjacente) au lieu du PAS lui-même.
        Double montantPAS = extractAmountOnSameLine(lines,
            "prelevement a la source",
            "retenue a la source");

        // Check 0: Net social vs Net à payer cross-validation
        // Invariant comptable : Net à payer = Net social − Impôt ≤ Net social (toujours)
        // On évite d'extraire l'impôt (labels ambigus, confusions avec bases CSG)
        // et on vérifie simplement que Net à payer ne dépasse pas Net social.
        if (salaireNet != null && netSocial != null && salaireNet > netSocial + 50) {
            checks.add(AnalysisResult.Check.builder()
                .category("Calculs")
                .label("Cohérence Net social / Net à payer")
                .status("FAILED")
                .detail(String.format(
                    "Net à payer (%.2f€) supérieur au Net social (%.2f€) — impossible,"
                        + " le net à payer a probablement été falsifié",
                    salaireNet, netSocial))
                .build());
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

        // Check 5: Ratio Net/Brut par CCN — utilise le net avant impôt (avant PAS)
        AnalysisResult.Check ratioCcn = checkNetBrutRatio(salaireBrut, netAvantImpot, salaireNet, text);
        if (ratioCcn != null) checks.add(ratioCcn);

        // Check 6: Somme individuelle des lignes de cotisations
        AnalysisResult.Check cotisSum = checkCotisationsSum(text, salaireBrut, salaireNet);
        if (cotisSum != null) checks.add(cotisSum);

        // Check 7: Cohérence prélèvement à la source
        AnalysisResult.Check pasCheck = checkPAS(netAvantImpot, montantPAS, salaireNet);
        if (pasCheck != null) checks.add(pasCheck);

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
        requiredFields.put("conges payes", "Congés payés");
        requiredFields.put("net a payer", "Net à payer");
        requiredFields.put("cotisation", "Lignes de cotisations");

        int missing = 0;
        List<String> missingList = new ArrayList<>();
        // Convention collective — vérification élargie (labels abrégés, IDCC, noms de CCN courants)
        if (!hasConventionCollective(normalized)) {
            missing++;
            missingList.add("Convention collective");
        }
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
     * Détecte la mention de convention collective sous ses multiples formes :
     * libellé complet, abréviation (CCN, IDCC), ou nom d'une CCN courante.
     * Le texte passé doit être déjà normalisé (minuscules + sans diacritiques).
     */
    private boolean hasConventionCollective(String normalized) {
        String[] indicators = {
            "convention collective", "conv. collective", "conv.collective",
            "convention coll", " ccn ", "ccn:", "ccn\t", "idcc",
            "syntec", "metallurgie", "batiment", "commerce de detail",
            "transports routiers", "bureaux d'etudes", "bureaux d\u2019etudes"
        };
        for (String indicator : indicators) {
            if (normalized.contains(indicator)) return true;
        }
        return false;
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

    // ── Check 5 : Ratio Net/Brut par CCN ─────────────────────────────────────

    /**
     * Vérifie que le ratio Net/Brut est dans la plage attendue pour la CCN détectée.
     * Utilise le net avant impôt (avant PAS) en priorité : le PAS varie de 0% à 45%
     * selon le taux personnel de l'employé et fausserait le ratio attendu.
     * Fall-back sur le net à payer final si net avant impôt non disponible (bulletin pré-2019).
     * Plages basées sur les charges salariales françaises 2024 (régime général).
     */
    private AnalysisResult.Check checkNetBrutRatio(Double brut, Double netAvantImpot,
                                                    Double netFinal, String text) {
        if (brut == null || brut <= 0) return null;
        Double net = netAvantImpot != null ? netAvantImpot : netFinal;
        if (net == null) return null;

        String netLabel = netAvantImpot != null ? "Net avant impôt" : "Net à payer";

        String normalized = normalizeDiacritics(text);
        double min, max;
        String ccn;
        if (normalized.contains("syntec")) {
            min = 0.74; max = 0.82; ccn = "Syntec";
        } else if (normalized.contains("metallurgie")) {
            min = 0.72; max = 0.80; ccn = "Métallurgie";
        } else {
            min = 0.70; max = 0.83; ccn = "standard";
        }

        double ratio = net / brut;
        if (ratio < min || ratio > max) {
            return AnalysisResult.Check.builder()
                .category("Calculs")
                .label("Ratio Net/Brut CCN")
                .status("FAILED")
                .detail(String.format(
                    "%s/Brut de %.1f%% hors plage attendue [%.0f%%–%.0f%%] pour la CCN %s",
                    netLabel, ratio * 100, min * 100, max * 100, ccn))
                .build();
        }
        return AnalysisResult.Check.builder()
            .category("Calculs")
            .label("Ratio Net/Brut CCN")
            .status("OK")
            .detail(String.format(
                "%s/Brut de %.1f%% dans la plage [%.0f%%–%.0f%%] pour la CCN %s",
                netLabel, ratio * 100, min * 100, max * 100, ccn))
            .build();
    }

    // ── Check 7 : Cohérence prélèvement à la source ───────────────────────────

    /**
     * Vérifie l'équation : Net avant PAS − PAS = Net à payer final (tolérance 2€).
     * Un écart > 2€ indique que le net à payer a pu être falsifié après génération.
     * Ignoré silencieusement si net avant impôt ou net final ne sont pas disponibles.
     */
    private AnalysisResult.Check checkPAS(Double netAvantImpot, Double pas, Double netFinal) {
        if (netAvantImpot == null || netFinal == null) return null;

        if (pas == null) {
            // Pas de ligne PAS trouvée — soit taux 0%, soit label non reconnu.
            // Si les deux nets sont proches, le PAS est effectivement nul (OK).
            // Sinon, on ne peut pas conclure → check ignoré.
            return Math.abs(netAvantImpot - netFinal) <= 2 ? null : null;
        }

        double calculatedNet = netAvantImpot - pas;
        double ecart = Math.abs(calculatedNet - netFinal);

        if (ecart > 2) {
            return AnalysisResult.Check.builder()
                .category("Calculs")
                .label("Prélèvement à la source")
                .status("WARNING")
                .detail(String.format(
                    "Net avant PAS (%.2f€) − PAS (%.2f€) = %.2f€ ≠ Net à payer (%.2f€)"
                        + " — écart de %.2f€, incohérence prélèvement à la source",
                    netAvantImpot, pas, calculatedNet, netFinal, ecart))
                .build();
        }
        return AnalysisResult.Check.builder()
            .category("Calculs")
            .label("Prélèvement à la source")
            .status("OK")
            .detail(String.format(
                "Net avant PAS (%.2f€) − PAS (%.2f€) = %.2f€ ≈ Net à payer (%.2f€) — cohérent",
                netAvantImpot, pas, calculatedNet, netFinal))
            .build();
    }

    // ── Check 6 : Somme des cotisations salarié ───────────────────────────────

    // Regex cotisation — format standard : "Label   taux%   base   montant_sal"
    // Exclut les lignes résumé (total, cumul, net, brut…) et les lignes sans taux.
    // [0-9]{1,6} pour la base couvre les salaires jusqu'à 999 999 € (ex: "3000,00" ou "3 000,00").
    private static final Pattern COTIS_LINE = Pattern.compile(
        "(?im)" +
        "^(?!\\s*(?:total|cumul|net\\b|brut\\b|salaire\\b|remun|base\\b|libelle|periode|conge|prime\\b)).{0,65}?" +
        "\\d{1,2}[.,]\\d{2,4}\\s*%" +                                // taux (non capturé)
        "\\s+[0-9]{1,6}(?:[\\s\u00a0][0-9]{3})*[.,][0-9]{2}(?![0-9%])" + // base (non capturé, jusqu'à 6 chiffres)
        "\\s+([0-9]{1,5}(?:[\\s\u00a0][0-9]{3})*[.,][0-9]{2})(?![0-9%])"  // montant salarié (capturé)
    );

    /**
     * Somme les montants salarié de chaque ligne de cotisation (format : label taux% base montant).
     * Vérifie que Brut − somme ≈ Net (tolérance 50€).
     * Retourne null si moins de 3 lignes sont détectées (pas assez pour être fiable).
     */
    private AnalysisResult.Check checkCotisationsSum(String text, Double brut, Double net) {
        if (brut == null || net == null || brut <= 0) return null;

        double sum = 0;
        int count = 0;
        Matcher m = COTIS_LINE.matcher(text);
        while (m.find()) {
            String raw = m.group(1).replaceAll("[\\s\u00a0]", "").replace(",", ".");
            try {
                double val = Double.parseDouble(raw);
                if (val >= 0.5 && val < 5000) { sum += val; count++; }
            } catch (NumberFormatException ignored) {}
        }

        if (count < 3) return null; // pas assez de lignes extraites pour être fiable

        double expectedNet = brut - sum;
        double ecart = Math.abs(expectedNet - net);

        if (ecart > 50) {
            return AnalysisResult.Check.builder()
                .category("Calculs")
                .label("Somme des cotisations")
                .status("FAILED")
                .detail(String.format(
                    "Brut (%.2f€) − cotisations salarié (%.2f€, %d lignes) = %.2f€ ≠ Net (%.2f€) — écart de %.2f€",
                    brut, sum, count, expectedNet, net, ecart))
                .build();
        } else if (ecart > 20) {
            return AnalysisResult.Check.builder()
                .category("Calculs")
                .label("Somme des cotisations")
                .status("WARNING")
                .detail(String.format(
                    "Brut − cotisations salarié (%.2f€, %d lignes) = %.2f€, Net = %.2f€ — écart %.2f€ (possible prime ou arrondi)",
                    sum, count, expectedNet, net, ecart))
                .build();
        }
        return AnalysisResult.Check.builder()
            .category("Calculs")
            .label("Somme des cotisations")
            .status("OK")
            .detail(String.format(
                "Brut (%.2f€) − cotisations salarié (%.2f€, %d lignes) ≈ Net (%.2f€) — cohérent (écart %.2f€)",
                brut, sum, count, net, ecart))
            .build();
    }

    /**
     * Like extractAmountNearLabel but scans the SAME LINE ONLY (no window expansion).
     * Used for values that are always on the same line as their label (e.g. PAS amount),
     * to avoid cross-contaminating with adjacent lines that carry larger amounts.
     */
    private Double extractAmountOnSameLine(String[] lines, String... labels) {
        for (String line : lines) {
            String norm = normalizeDiacritics(line);
            for (String label : labels) {
                if (norm.contains(label)) {
                    Double val = largestAmountInLine(line);
                    if (val != null) return val;
                }
            }
        }
        return null;
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
