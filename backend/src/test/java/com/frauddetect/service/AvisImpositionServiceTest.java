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

    // Mise en page reelle d'un avis : points de conduite, renvoi numerote accole
    // au libelle, puis une colonne de montants par declarant et un total.
    private static final String AVIS_FOYER =
        "Impot sur les revenus de 2025\n"
        + "Detail des revenus   Declar. 1   Declar. 2       Total\n"
        + "Salaires..................................................................       31560       24000\n"
        + "Total des salaires et assimiles 2.............................       31560       24000       55560\n"
        + "Revenu fiscal de reference 25..............................       49104\n";

    private static final String AVIS_CELIBATAIRE =
        "Impot sur les revenus de 2025\n"
        + "Total des salaires et assimiles 2.............................       31560\n"
        + "Revenu fiscal de reference 25..............................       28404\n";

    private AnalysisResult.Check find(List<AnalysisResult.Check> checks, String label) {
        return checks.stream().filter(c -> label.equals(c.getLabel())).findFirst()
            .orElseThrow(() -> new AssertionError("Check absent : " + label));
    }

    // ── Extraction ────────────────────────────────────────────────────────────

    @Test
    void ignoreLeRenvoiNumeroteAccoleAuLibelle() {
        // "Revenu fiscal de reference 25...." : le 25 est un renvoi de bas de page,
        // pas un montant. Seule la zone situee apres les points compte.
        AvisImposition avis = service.extract(AVIS_CELIBATAIRE);

        assertThat(avis.getRevenuFiscalReference()).isEqualTo(28404.0);
    }

    @Test
    void extraitUnMontantParDeclarant() {
        AvisImposition avis = service.extract(AVIS_FOYER);

        assertThat(avis.getSalairesDeclares()).containsExactly(31560.0, 24000.0, 55560.0);
    }

    @Test
    void extraitLAnneeDesRevenus() {
        assertThat(service.extract(AVIS_FOYER).getAnneeRevenus()).isEqualTo(2025);
    }

    @Test
    void documentSansRapport_champsVides() {
        AvisImposition avis = service.extract("Facture de telephone\nMontant 45,90");

        assertThat(avis.getSalairesDeclares()).isEmpty();
        assertThat(avis.getRevenuFiscalReference()).isNull();
    }

    // ── Rapprochement ─────────────────────────────────────────────────────────

    @Test
    void revenusCoherents_shouldBeOK() {
        // 2 630 x 12 = 31 560, exactement le montant declare
        List<AnalysisResult.Check> checks =
            service.verify(service.extract(AVIS_CELIBATAIRE), 2630.0);

        assertThat(find(checks, "Rapprochement avis / bulletins").getStatus()).isEqualTo("OK");
    }

    @Test
    void ecartModere_resteOK() {
        // 2 400 x 12 = 28 800 contre 31 560 : 8,8 %, compatible avec une
        // augmentation ou des primes en cours d'annee.
        List<AnalysisResult.Check> checks =
            service.verify(service.extract(AVIS_CELIBATAIRE), 2400.0);

        assertThat(find(checks, "Rapprochement avis / bulletins").getStatus()).isEqualTo("OK");
    }

    @Test
    void bulletinGonfle_detecte() {
        // Bulletin annoncant 4 000 EUR de net imposable, soit 48 000 sur l'annee,
        // quand l'administration n'en a enregistre que 31 560.
        List<AnalysisResult.Check> checks =
            service.verify(service.extract(AVIS_CELIBATAIRE), 4000.0);

        AnalysisResult.Check check = find(checks, "Rapprochement avis / bulletins");
        assertThat(check.getStatus()).isEqualTo("FAILED");
    }

    @Test
    void avisDeFoyer_leSecondDeclarantEstAccepte() {
        // Le candidat est le declarant 2 (24 000 EUR). Comparer au total du foyer
        // signalerait a tort tous les couples : la colonne la plus proche est retenue.
        List<AnalysisResult.Check> checks = service.verify(service.extract(AVIS_FOYER), 2000.0);

        AnalysisResult.Check check = find(checks, "Rapprochement avis / bulletins");
        assertThat(check.getStatus()).isEqualTo("OK");
        assertThat(check.getDetail()).contains("avis de foyer");
    }

    @Test
    void avisDeFoyer_aucuneColonneNeCorrespond_detecte() {
        // 8 000 x 12 = 96 000 : ni 31 560, ni 24 000, ni le total 55 560.
        List<AnalysisResult.Check> checks = service.verify(service.extract(AVIS_FOYER), 8000.0);

        assertThat(find(checks, "Rapprochement avis / bulletins").getStatus()).isEqualTo("FAILED");
    }

    @Test
    void sansBulletin_pasDeRapprochement() {
        List<AnalysisResult.Check> checks =
            service.verify(service.extract(AVIS_CELIBATAIRE), null);

        assertThat(checks).noneMatch(c -> "Rapprochement avis / bulletins".equals(c.getLabel()));
        assertThat(find(checks, "Revenus déclarés").getStatus()).isEqualTo("OK");
    }

    @Test
    void urlNonConfiguree_aucunLienPropose() {
        // Sans URL confirmee, mieux vaut ne rien proposer qu'une adresse erronee.
        assertThat(service.verificationLink()).isNull();
    }

    // ── Identite des declarants ──────────────────────────────────────────────

    @Test
    void extraitLesNomsDesDeclarants() {
        String text = "Declarant 1 - Nom de naissance : MARTIN  JEAN\n"
            + "Declarant 2 - Nom de naissance : DUPONT  MARIE\n"
            + "Total des salaires et assimiles 2........       31560\n";

        AvisImposition avis = service.extract(text);

        assertThat(avis.getNomsDeclarants())
            .containsExactly("MARTIN JEAN", "DUPONT MARIE");
    }

    @Test
    void espacesMultiplesReduits() {
        // Le texte extrait separe nom et prenom par plusieurs espaces.
        AvisImposition avis = service.extract(
            "Declarant 1 - Nom de naissance : MARTIN    JEAN\n");

        assertThat(avis.getNomsDeclarants()).containsExactly("MARTIN JEAN");
    }

    @Test
    void declarantAvecAccentEtTiret_estExtrait() {
        AvisImposition avis = service.extract(
            "Déclarant 1 - Nom de naissance : LE GOFF  ANNE-MARIE\n");

        assertThat(avis.getNomsDeclarants()).containsExactly("LE GOFF ANNE-MARIE");
    }

    @Test
    void sansDeclarant_listeVide() {
        assertThat(service.extract("Total des salaires 31560").getNomsDeclarants()).isEmpty();
    }

    @Test
    void espaceInsecableAvantLesDeuxPoints_neBloquePasLExtraction() {
        // Typographie francaise : U+00A0 precede les deux-points sur le document
        // reel, et \s ne le reconnait pas.
        AvisImposition avis = service.extract(
            "D\u00e9clarant 1 - Nom de naissance\u00a0: MARTIN  JEAN\n");

        assertThat(avis.getNomsDeclarants()).containsExactly("MARTIN JEAN");
    }
}
