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
            docInfo("2500.00 €", "1875.00 €"));

        AnalysisResult.Check check = findCheck(checks, "Ratio Net/Brut");
        assertThat(check.getStatus()).isEqualTo("OK");
        assertThat(check.getDetail()).contains("75%");
    }

    @Test
    void ratioNetBrut_netSuperiorToBrut_shouldFail() {
        List<AnalysisResult.Check> checks = service.analyzeCalculations(
            "net a payer\nsiret\ncotisation\nconges payes\nconvention collective",
            docInfo("2000.00 €", "3000.00 €"));

        AnalysisResult.Check check = findCheck(checks, "Ratio Net/Brut");
        assertThat(check.getStatus()).isEqualTo("FAILED");
        assertThat(check.getDetail()).contains("supérieur au Brut");
    }

    @Test
    void ratioNetBrut_ratioAbove90_shouldFail() {
        // 95% — cotisations semblent manquantes
        List<AnalysisResult.Check> checks = service.analyzeCalculations(
            "net a payer\nsiret\ncotisation\nconges payes\nconvention collective",
            docInfo("2000.00 €", "1900.00 €"));

        AnalysisResult.Check check = findCheck(checks, "Ratio Net/Brut");
        assertThat(check.getStatus()).isEqualTo("FAILED");
        assertThat(check.getDetail()).contains("trop élevé");
    }

    @Test
    void ratioNetBrut_ratioBelow60_shouldWarn() {
        // 50% — inhabituel
        List<AnalysisResult.Check> checks = service.analyzeCalculations(
            "net a payer\nsiret\ncotisation\nconges payes\nconvention collective",
            docInfo("3000.00 €", "1500.00 €"));

        AnalysisResult.Check check = findCheck(checks, "Ratio Net/Brut");
        assertThat(check.getStatus()).isEqualTo("WARNING");
        assertThat(check.getDetail()).contains("inhabituel");
    }

    @Test
    void ratioNetBrut_missingValues_shouldWarn() {
        List<AnalysisResult.Check> checks = service.analyzeCalculations(
            "aucun montant ici", null);

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
            text, docInfo("3000.00 €", "2500.00 €"));

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
            text, docInfo("3000.00 €", "2250.00 €"));

        AnalysisResult.Check check = findCheck(checks, "Cohérence des cotisations");
        assertThat(check.getStatus()).isEqualTo("OK");
    }

    @Test
    void cotisationsCheck_incoherent_shouldFail() {
        // brut=3000, net=2250, diff=750 but cotisations=200 → gap=550 > tolerance 150
        String text = "total cotisations salariales 200.00\n"
            + "net a payer\nsiret\nconges payes\nconvention collective";

        List<AnalysisResult.Check> checks = service.analyzeCalculations(
            text, docInfo("3000.00 €", "2250.00 €"));

        AnalysisResult.Check check = findCheck(checks, "Cohérence des cotisations");
        assertThat(check.getStatus()).isEqualTo("FAILED");
    }

    // ── docInfo parsing ───────────────────────────────────────────────────────

    @Test
    void docInfoAmount_withSpacesAndEuroSign_parsedCorrectly() {
        // "4 249,60 €" — formatted French amount
        List<AnalysisResult.Check> checks = service.analyzeCalculations(
            "net a payer\nsiret\ncotisation\nconges payes\nconvention collective",
            docInfo("4 249,60 €", "3 000,00 €"));

        AnalysisResult.Check check = findCheck(checks, "Ratio Net/Brut");
        assertThat(check.getStatus()).isEqualTo("OK");
    }
}
