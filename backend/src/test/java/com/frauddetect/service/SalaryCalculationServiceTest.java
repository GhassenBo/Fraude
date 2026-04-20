package com.frauddetect.service;

import com.frauddetect.model.AnalysisResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SalaryCalculationServiceTest {

    private SalaryCalculationService service;

    @BeforeEach
    void setUp() {
        service = new SalaryCalculationService();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private AnalysisResult.DocumentInfo docInfo(String brut, String net) {
        return AnalysisResult.DocumentInfo.builder()
            .salaireBrut(brut)
            .salaireNet(net)
            .build();
    }

    private AnalysisResult.Check findCheck(List<AnalysisResult.Check> checks, String label) {
        return checks.stream()
            .filter(c -> label.equals(c.getLabel()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Check not found: " + label));
    }

    // ── Ratio Net/Brut ────────────────────────────────────────────────────────

    @Test
    void ratioNetBrut_normal_shouldBeOK() {
        // 75% ratio — within the 60-90% normal range
        List<AnalysisResult.Check> checks = service.analyzeCalculations(
            "net a payer\nsiret\ncotisation\nconges payes\nconvention collective",
            docInfo("2500.00 €", "1875.00 €"), true);

        AnalysisResult.Check check = findCheck(checks, "Ratio Net/Brut");
        assertThat(check.getStatus()).isEqualTo("OK");
        assertThat(check.getDetail()).contains("75%");
    }

    @Test
    void ratioNetBrut_netSuperiorToBrut_shouldFail() {
        List<AnalysisResult.Check> checks = service.analyzeCalculations(
            "net a payer\nsiret\ncotisation\nconges payes\nconvention collective",
            docInfo("2000.00 €", "3000.00 €"), true);

        AnalysisResult.Check check = findCheck(checks, "Ratio Net/Brut");
        assertThat(check.getStatus()).isEqualTo("FAILED");
        assertThat(check.getDetail()).contains("supérieur au Brut");
    }

    @Test
    void ratioNetBrut_ratioAbove90_shouldFail() {
        // 95% — cotisations semblent manquantes
        List<AnalysisResult.Check> checks = service.analyzeCalculations(
            "net a payer\nsiret\ncotisation\nconges payes\nconvention collective",
            docInfo("2000.00 €", "1900.00 €"), true);

        AnalysisResult.Check check = findCheck(checks, "Ratio Net/Brut");
        assertThat(check.getStatus()).isEqualTo("FAILED");
        assertThat(check.getDetail()).contains("trop élevé");
    }

    @Test
    void ratioNetBrut_ratioBelow60_shouldWarn() {
        // 50% — inhabituel
        List<AnalysisResult.Check> checks = service.analyzeCalculations(
            "net a payer\nsiret\ncotisation\nconges payes\nconvention collective",
            docInfo("3000.00 €", "1500.00 €"), true);

        AnalysisResult.Check check = findCheck(checks, "Ratio Net/Brut");
        assertThat(check.getStatus()).isEqualTo("WARNING");
        assertThat(check.getDetail()).contains("inhabituel");
    }

    @Test
    void ratioNetBrut_visionDisabled_shouldWarnAboutVision() {
        // Sans Vision, le check doit retourner un WARNING explicite (pas de regex fallback)
        List<AnalysisResult.Check> checks = service.analyzeCalculations(
            "net a payer\nsiret\ncotisation\nconges payes\nconvention collective",
            docInfo("2500.00 €", "1875.00 €"), false);

        AnalysisResult.Check check = findCheck(checks, "Ratio Net/Brut");
        assertThat(check.getStatus()).isEqualTo("WARNING");
        assertThat(check.getDetail()).contains("Vision IA");
    }

    @Test
    void ratioNetBrut_missingValues_shouldWarn() {
        // Vision activée mais valeurs absentes
        List<AnalysisResult.Check> checks = service.analyzeCalculations(
            "aucun montant ici", null, true);

        AnalysisResult.Check check = findCheck(checks, "Ratio Net/Brut");
        assertThat(check.getStatus()).isEqualTo("WARNING");
        assertThat(check.getDetail()).contains("Impossible d'extraire");
    }

    // ── Comparaison SMIC ──────────────────────────────────────────────────────

    @Test
    void smicCheck_aboveSmic_shouldBeOK() {
        List<AnalysisResult.Check> checks = service.analyzeCalculations(
            "net a payer\nsiret\ncotisation\nconges payes\nconvention collective",
            docInfo("2500.00 €", "1875.00 €"));

        AnalysisResult.Check check = findCheck(checks, "Comparaison SMIC");
        assertThat(check.getStatus()).isEqualTo("OK");
    }

    @Test
    void smicCheck_belowSmic_shouldFail() {
        // 1766.92€ is SMIC — use 1000€ which is clearly below
        List<AnalysisResult.Check> checks = service.analyzeCalculations(
            "net a payer\nsiret\ncotisation\nconges payes\nconvention collective",
            docInfo("1000.00 €", "750.00 €"));

        AnalysisResult.Check check = findCheck(checks, "Comparaison SMIC");
        assertThat(check.getStatus()).isEqualTo("FAILED");
        assertThat(check.getDetail()).contains("inférieur au SMIC");
    }

    @Test
    void smicCheck_veryHighSalary_shouldWarn() {
        List<AnalysisResult.Check> checks = service.analyzeCalculations(
            "net a payer\nsiret\ncotisation\nconges payes\nconvention collective",
            docInfo("60000.00 €", "45000.00 €"));

        AnalysisResult.Check check = findCheck(checks, "Comparaison SMIC");
        assertThat(check.getStatus()).isEqualTo("WARNING");
        assertThat(check.getDetail()).contains("très élevé");
    }

    // ── Net social vs Net à payer ─────────────────────────────────────────────

    @Test
    void netSocialCheck_netPayerAboveNetSocial_shouldFail() {
        // Use docInfo to set salaireNet=2500 directly (avoids regex window overlapping netSocial line).
        // Text has netSocial=1500 — 2500 > 1500+50 → FAILED.
        String text = "montant net social 1500.00\n"
            + "net a payer\n"
            + "siret\ncotisation\nconges payes\nconvention collective";

        List<AnalysisResult.Check> checks = service.analyzeCalculations(
            text, docInfo("3000.00 €", "2500.00 €"), true);

        AnalysisResult.Check check = findCheck(checks, "Cohérence Net social / Net à payer");
        assertThat(check.getStatus()).isEqualTo("FAILED");
        assertThat(check.getDetail()).contains("falsifié");
    }

    @Test
    void netSocialCheck_netPayerBelowNetSocial_noFalsePositive() {
        String text = "montant net social 2507.16\n"
            + "net a payer 2400.00\n"
            + "siret\ncotisation\nconges payes\nconvention collective";

        List<AnalysisResult.Check> checks = service.analyzeCalculations(text, null);

        boolean hasFailed = checks.stream()
            .filter(c -> "Cohérence Net social / Net à payer".equals(c.getLabel()))
            .anyMatch(c -> "FAILED".equals(c.getStatus()));
        assertThat(hasFailed).isFalse();
    }

    // ── Champs obligatoires ───────────────────────────────────────────────────

    @Test
    void requiredFields_allPresent_shouldBeOK() {
        String text = "SIRET 12345678901234\nconvention collective\nconges payes\nnet a payer\ncotisation";
        List<AnalysisResult.Check> checks = service.analyzeCalculations(text, null);

        AnalysisResult.Check check = findCheck(checks, "Champs obligatoires");
        assertThat(check.getStatus()).isEqualTo("OK");
    }

    @Test
    void requiredFields_someAbsent_shouldWarn() {
        // Missing: conges payes, net a payer
        String text = "SIRET 12345678901234\nconvention collective\ncotisation";
        List<AnalysisResult.Check> checks = service.analyzeCalculations(text, null);

        AnalysisResult.Check check = findCheck(checks, "Champs obligatoires");
        assertThat(check.getStatus()).isEqualTo("WARNING");
    }

    @Test
    void requiredFields_mostAbsent_shouldFail() {
        String text = "aucun champ requis ici";
        List<AnalysisResult.Check> checks = service.analyzeCalculations(text, null);

        AnalysisResult.Check check = findCheck(checks, "Champs obligatoires");
        assertThat(check.getStatus()).isEqualTo("FAILED");
    }

    @Test
    void requiredFields_accentInsensitive_shouldFindNormalizedText() {
        // "NET A PAYER" (no accent) should match "net à payer" key
        String text = "SIRET 12345678901234\nCONVENTION COLLECTIVE\nCONGES PAYES\nNET A PAYER\nCOTISATION";
        List<AnalysisResult.Check> checks = service.analyzeCalculations(text, null);

        AnalysisResult.Check check = findCheck(checks, "Champs obligatoires");
        assertThat(check.getStatus()).isEqualTo("OK");
    }

    // ── Cohérence des cotisations ─────────────────────────────────────────────

    @Test
    void cotisationsCheck_coherent_shouldBeOK() {
        // brut=3000, net=2250, cotisations=750 → diff=750, tolerance=150
        String text = "total cotisations salariales 750.00\n"
            + "net a payer\nsiret\nconges payes\nconvention collective";

        List<AnalysisResult.Check> checks = service.analyzeCalculations(
            text, docInfo("3000.00 €", "2250.00 €"), true);

        AnalysisResult.Check check = findCheck(checks, "Cohérence des cotisations");
        assertThat(check.getStatus()).isEqualTo("OK");
    }

    @Test
    void cotisationsCheck_incoherent_shouldFail() {
        // brut=3000, net=2250, diff=750 but cotisations=200 → gap=550 > tolerance 150
        String text = "total cotisations salariales 200.00\n"
            + "net a payer\nsiret\nconges payes\nconvention collective";

        List<AnalysisResult.Check> checks = service.analyzeCalculations(
            text, docInfo("3000.00 €", "2250.00 €"), true);

        AnalysisResult.Check check = findCheck(checks, "Cohérence des cotisations");
        assertThat(check.getStatus()).isEqualTo("FAILED");
    }

    // ── docInfo parsing ───────────────────────────────────────────────────────

    @Test
    void docInfoAmount_withSpacesAndEuroSign_parsedCorrectly() {
        // "4 249,60 €" — formatted French amount
        List<AnalysisResult.Check> checks = service.analyzeCalculations(
            "net a payer\nsiret\ncotisation\nconges payes\nconvention collective",
            docInfo("4 249,60 €", "3 000,00 €"), true);

        AnalysisResult.Check check = findCheck(checks, "Ratio Net/Brut");
        assertThat(check.getStatus()).isEqualTo("OK");
    }

    // ── checkNetBrutRatio (CCN) ───────────────────────────────────────────────

    @Test
    void checkNetBrutRatio_syntec_usesNetAvantImpot_whenPresent() {
        // net avant impôt = 3 120,00 (78% de 4000) → dans la plage Syntec [74–82%].
        // net à payer final = 2 800,00 (70%) — hors plage, mais NE DOIT PAS être utilisé.
        // Format "3 120,00" nécessaire : largestAmountInLine ne parse pas les entiers >3 chiffres
        // sans séparateur (ex: "3120.00" serait capturé comme "312").
        String text = "net a payer avant impot 3 120,00\n" +
            "prelevement a la source 320,00\n" +
            "net a payer\nsiret\ncotisation\nconges payes\nConv. Collective : Syntec";

        List<AnalysisResult.Check> checks = service.analyzeCalculations(
            text, docInfo("4000.00 €", "2800.00 €"));

        AnalysisResult.Check check = findCheck(checks, "Ratio Net/Brut CCN");
        assertThat(check.getStatus()).isEqualTo("OK");
        assertThat(check.getDetail()).contains("Net avant PAS");
        assertThat(check.getDetail()).contains("Syntec");
    }

    @Test
    void checkNetBrutRatio_syntec_fallsBackToNetFinal_whenNoAvantImpot() {
        // Aucun label "net avant impôt" dans le texte → fallback sur net Vision (78%)
        List<AnalysisResult.Check> checks = service.analyzeCalculations(
            "net a payer\nsiret\ncotisation\nconges payes\nConv. Collective : Syntec",
            docInfo("4000.00 €", "3120.00 €"), true); // 78%

        AnalysisResult.Check check = findCheck(checks, "Ratio Net/Brut CCN");
        assertThat(check.getStatus()).isEqualTo("OK");
        assertThat(check.getDetail()).contains("Net avant PAS");
    }

    @Test
    void checkNetBrutRatio_syntec_belowRange_shouldFail() {
        // Syntec: [0.74, 0.82] — ratio 70% est sous 74%
        List<AnalysisResult.Check> checks = service.analyzeCalculations(
            "net a payer\nsiret\ncotisation\nconges payes\nConv. Collective : Syntec",
            docInfo("4000.00 €", "2800.00 €"), true); // 70%

        AnalysisResult.Check check = findCheck(checks, "Ratio Net/Brut CCN");
        assertThat(check.getStatus()).isEqualTo("FAILED");
        assertThat(check.getDetail()).contains("Syntec");
    }

    @Test
    void checkNetBrutRatio_syntec_aboveRange_shouldFail() {
        // Syntec: [0.74, 0.82] — ratio 85% is above 82%
        List<AnalysisResult.Check> checks = service.analyzeCalculations(
            "net a payer\nsiret\ncotisation\nconges payes\nConv. Collective : Syntec",
            docInfo("4000.00 €", "3400.00 €"), true); // 85%

        AnalysisResult.Check check = findCheck(checks, "Ratio Net/Brut CCN");
        assertThat(check.getStatus()).isEqualTo("FAILED");
    }

    @Test
    void checkNetBrutRatio_metallurgie_withinRange_shouldBeOK() {
        // Métallurgie: [0.72, 0.80] — ratio 76%
        List<AnalysisResult.Check> checks = service.analyzeCalculations(
            "net a payer\nsiret\ncotisation\nconges payes\nConvention Metallurgie",
            docInfo("3000.00 €", "2280.00 €"), true); // 76%

        AnalysisResult.Check check = findCheck(checks, "Ratio Net/Brut CCN");
        assertThat(check.getStatus()).isEqualTo("OK");
        assertThat(check.getDetail()).contains("Métallurgie");
    }

    @Test
    void checkNetBrutRatio_default_withinRange_shouldBeOK() {
        // Default: [0.70, 0.83] — ratio 75%
        List<AnalysisResult.Check> checks = service.analyzeCalculations(
            "net a payer\nsiret\ncotisation\nconges payes\nconvention collective",
            docInfo("3000.00 €", "2250.00 €"), true); // 75%

        AnalysisResult.Check check = findCheck(checks, "Ratio Net/Brut CCN");
        assertThat(check.getStatus()).isEqualTo("OK");
        assertThat(check.getDetail()).contains("standard");
    }

    @Test
    void checkNetBrutRatio_missingValues_returnsNoCheck() {
        List<AnalysisResult.Check> checks = service.analyzeCalculations(
            "aucun montant ici", null);

        boolean hasRatioCcn = checks.stream()
            .anyMatch(c -> "Ratio Net/Brut CCN".equals(c.getLabel()));
        assertThat(hasRatioCcn).isFalse();
    }

    // ── checkCotisationsSum ───────────────────────────────────────────────────

    private static final String COTIS_TEXT_COHERENT =
        "Assurance maladie 6,70 % 3000,00 201,00\n" +
        "Vieillesse deplafonnee 0,40 % 3000,00 12,00\n" +
        "Vieillesse plafonnee 6,90 % 3000,00 207,00\n" +
        "Retraite complementaire 4,72 % 3000,00 141,60\n" +
        "CSG deductible 6,80 % 2907,00 197,68\n" +
        // sum = 201+12+207+141.60+197.68 = 759,28
        // net = 3000 - 759.28 = 2240.72
        "net a payer\nsiret\nconges payes\nconvention collective";

    @Test
    void checkCotisationsSum_coherent_shouldBeOK() {
        List<AnalysisResult.Check> checks = service.analyzeCalculations(
            COTIS_TEXT_COHERENT,
            docInfo("3000.00 €", "2240.72 €"), true);

        AnalysisResult.Check check = findCheck(checks, "Somme des cotisations");
        assertThat(check.getStatus()).isEqualTo("OK");
        assertThat(check.getDetail()).contains("cohérent");
    }

    @Test
    void checkCotisationsSum_bigGap_shouldFail() {
        // Same cotisations sum ≈ 759€, but net declared as 1500 → gap > 50€
        List<AnalysisResult.Check> checks = service.analyzeCalculations(
            COTIS_TEXT_COHERENT,
            docInfo("3000.00 €", "1500.00 €"), true);

        AnalysisResult.Check check = findCheck(checks, "Somme des cotisations");
        assertThat(check.getStatus()).isEqualTo("FAILED");
        assertThat(check.getDetail()).contains("≠ Net");
    }

    @Test
    void checkCotisationsSum_smallGap_shouldWarn() {
        // net = 2265 → gap = |2240.72 - 2265| ≈ 24€ → between 20 and 50
        List<AnalysisResult.Check> checks = service.analyzeCalculations(
            COTIS_TEXT_COHERENT,
            docInfo("3000.00 €", "2265.00 €"), true);

        AnalysisResult.Check check = findCheck(checks, "Somme des cotisations");
        assertThat(check.getStatus()).isEqualTo("WARNING");
        assertThat(check.getDetail()).contains("arrondi");
    }

    // ── checkPAS ──────────────────────────────────────────────────────────────

    @Test
    void checkPAS_coherent_shouldBeOK() {
        // Vision returns net avant PAS = 3036.60 ; PAS = 170.88 ; net final après PAS = 2865.72
        String text = "net a payer avant impot 3 036,60\n" +
            "prelevement a la source 170,88\n" +
            "net a payer 2 865,72\nsiret\ncotisation\nconges payes\nconvention collective";

        List<AnalysisResult.Check> checks = service.analyzeCalculations(
            text, docInfo("4000.00 €", "3036.60 €"), true);

        AnalysisResult.Check check = findCheck(checks, "Prélèvement à la source");
        assertThat(check.getStatus()).isEqualTo("OK");
        assertThat(check.getDetail()).contains("3036.60");
        assertThat(check.getDetail()).contains("170.88");
    }

    @Test
    void checkPAS_netFinalFalsified_shouldWarn() {
        // Vision returns net avant PAS = 3036.60 ; PAS = 170.88 → expected = 2865.72
        // but net final in doc = 3507.16 (falsifié +641€)
        String text = "net a payer avant impot 3 036,60\n" +
            "prelevement a la source 170,88\n" +
            "net a payer 3 507,16\nsiret\ncotisation\nconges payes\nconvention collective";

        List<AnalysisResult.Check> checks = service.analyzeCalculations(
            text, docInfo("4000.00 €", "3036.60 €"), true);

        AnalysisResult.Check check = findCheck(checks, "Prélèvement à la source");
        assertThat(check.getStatus()).isEqualTo("WARNING");
        assertThat(check.getDetail()).contains("incohérence");
    }

    @Test
    void checkPAS_noPasLine_netsSimilar_checkSkipped() {
        String text = "net a payer avant impot 865,72\n" +
            "net a payer\nsiret\ncotisation\nconges payes\nconvention collective";

        List<AnalysisResult.Check> checks = service.analyzeCalculations(
            text, docInfo("1200.00 €", "865.72 €"), true);

        boolean hasPasCheck = checks.stream()
            .anyMatch(c -> "Prélèvement à la source".equals(c.getLabel()));
        assertThat(hasPasCheck).isFalse();
    }

    @Test
    void checkPAS_noNetAvantImpot_checkSkipped() {
        List<AnalysisResult.Check> checks = service.analyzeCalculations(
            "net a payer\nsiret\ncotisation\nconges payes\nconvention collective",
            docInfo("4000.00 €", "3000.00 €"), true);

        boolean hasPasCheck = checks.stream()
            .anyMatch(c -> "Prélèvement à la source".equals(c.getLabel()));
        assertThat(hasPasCheck).isFalse();
    }

    @Test
    void checkCotisationsSum_fewerThan3Lines_skipped() {
        String text = "Assurance maladie 6,70 % 3000,00 201,00\n" +
            "Vieillesse 0,40 % 3000,00 12,00\n" +
            "net a payer\nsiret\nconges payes\nconvention collective";

        List<AnalysisResult.Check> checks = service.analyzeCalculations(
            text, docInfo("3000.00 €", "2787.00 €"), true);

        boolean hasCotisSum = checks.stream()
            .anyMatch(c -> "Somme des cotisations".equals(c.getLabel()));
        assertThat(hasCotisSum).isFalse();
    }
}
