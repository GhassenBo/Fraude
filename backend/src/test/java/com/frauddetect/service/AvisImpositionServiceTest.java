package com.frauddetect.service;

import com.frauddetect.model.AnalysisResult;
import com.frauddetect.model.AvisImposition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AvisImpositionServiceTest {

    private AvisImpositionService service;

    @BeforeEach
    void setUp() {
        service = new AvisImpositionService();
    }

    // Mise en page typique d'un avis d'imposition, valeurs fictives.
    private static final String AVIS =
        "DIRECTION GENERALE DES FINANCES PUBLIQUES\n"
        + "Avis d'impôt 2026 sur les revenus de l'année 2025\n"
        + "Votre numéro fiscal : 12 34 567 890 123\n"
        + "Référence de l'avis : 26 12 345678 901\n"
        + "Traitements, salaires 31 560\n"
        + "Revenu fiscal de référence 28 404\n";

    private AnalysisResult.Check find(List<AnalysisResult.Check> checks, String label) {
        return checks.stream().filter(c -> label.equals(c.getLabel())).findFirst()
            .orElseThrow(() -> new AssertionError("Check absent : " + label));
    }

    // ── Extraction ────────────────────────────────────────────────────────────

    @Test
    void extraitLesIdentifiantsSansSeparateurs() {
        AvisImposition avis = service.extract(AVIS);

        assertThat(avis.getNumeroFiscal()).isEqualTo("1234567890123");
        assertThat(avis.getReferenceAvis()).isEqualTo("2612345678901");
    }

    @Test
    void extraitLesMontantsEtLAnnee() {
        AvisImposition avis = service.extract(AVIS);

        assertThat(avis.getTraitementsSalaires()).isEqualTo(31560.0);
        assertThat(avis.getRevenuFiscalReference()).isEqualTo(28404.0);
        assertThat(avis.getAnneeRevenus()).isEqualTo(2025);
    }

    @Test
    void documentIllisible_champsNuls() {
        AvisImposition avis = service.extract("Document sans rapport");

        assertThat(avis.getNumeroFiscal()).isNull();
        assertThat(avis.getTraitementsSalaires()).isNull();
    }

    // ── Rapprochement avec les bulletins ──────────────────────────────────────

    @Test
    void revenusCoherents_shouldBeOK() {
        // 2 630 x 12 = 31 560, exactement le montant declare
        List<AnalysisResult.Check> checks = service.verify(service.extract(AVIS), 2630.0);

        assertThat(find(checks, "Rapprochement avis / bulletins").getStatus()).isEqualTo("OK");
    }

    @Test
    void ecartModere_resteOK() {
        // 2 400 x 12 = 28 800 contre 31 560 declares, soit 9,6 % :
        // compatible avec une augmentation ou des primes.
        List<AnalysisResult.Check> checks = service.verify(service.extract(AVIS), 2400.0);

        assertThat(find(checks, "Rapprochement avis / bulletins").getStatus()).isEqualTo("OK");
    }

    @Test
    void bulletinGonfle_detecte() {
        // Bulletin annoncant 4 000 EUR de net imposable, soit 48 000 sur l'annee,
        // quand l'administration n'en a enregistre que 31 560.
        List<AnalysisResult.Check> checks = service.verify(service.extract(AVIS), 4000.0);

        AnalysisResult.Check check = find(checks, "Rapprochement avis / bulletins");
        assertThat(check.getStatus()).isEqualTo("FAILED");
        assertThat(check.getDetail()).contains("incohérents");
    }

    @Test
    void sansBulletin_pasDeRapprochement() {
        List<AnalysisResult.Check> checks = service.verify(service.extract(AVIS), null);

        assertThat(checks).noneMatch(c -> "Rapprochement avis / bulletins".equals(c.getLabel()));
        assertThat(find(checks, "Revenus déclarés").getStatus()).isEqualTo("OK");
    }

    @Test
    void identifiantsIllisibles_warning() {
        String sansIdentifiants = "Traitements, salaires 31 560\n";

        List<AnalysisResult.Check> checks = service.verify(
            service.extract(sansIdentifiants), 2630.0);

        assertThat(find(checks, "Identifiants de l'avis").getStatus()).isEqualTo("WARNING");
    }

    @Test
    void urlNonConfiguree_aucunLienPropose() {
        // Sans URL configuree, mieux vaut ne rien proposer qu'une adresse erronee.
        assertThat(service.verificationLink(service.extract(AVIS))).isNull();
    }
}
