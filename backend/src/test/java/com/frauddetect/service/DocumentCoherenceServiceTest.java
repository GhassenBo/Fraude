package com.frauddetect.service;

import com.frauddetect.model.AnalysisResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentCoherenceServiceTest {

    private DocumentCoherenceService service;

    @BeforeEach
    void setUp() {
        service = new DocumentCoherenceService();
    }

    private AnalysisResult.Check find(List<AnalysisResult.Check> checks, String label) {
        return checks.stream().filter(c -> label.equals(c.getLabel())).findFirst()
            .orElseThrow(() -> new AssertionError("Contrôle absent : " + label));
    }

    // Mise en page reelle : l'employeur figure en tete et dans le bloc
    // d'identification, le salarie dans ce bloc et dans le recapitulatif.
    private static final String BULLETIN =
        "DEMO PAY SAS - BULLETIN DE PAIE\n"
        + "1 rue du Test, 75000 Ville-Test\n"
        + "EMPLOYEUR SALARIÉ(E)\n"
        + "DEMO PAY SAS Camille EXEMPLE\n"
        + "Salaire de base 151,67 h 3 500,00\n"
        + "NET À PAYER 2 728,35\n"
        + "Salarié récapitulatif Camille EXEMPLE\n";

    // ── Employeur ────────────────────────────────────────────────────────────

    @Test
    void unSeulEmployeur_aucunControle() {
        assertThat(service.analyze(BULLETIN))
            .noneMatch(c -> "Cohérence de l'employeur".equals(c.getLabel()));
    }

    @Test
    void deuxEmployeurs_sontSignales() {
        String altere = BULLETIN.replace("DEMO PAY SAS Camille EXEMPLE",
            "LABORATOIRE EXEMPLE SARL Camille EXEMPLE");

        AnalysisResult.Check check = find(service.analyze(altere),
            "Cohérence de l'employeur");

        // Un second employeur se rencontre en portage salarial : le constat
        // oriente la verification humaine, il ne conclut pas.
        assertThat(check.getStatus()).isEqualTo("WARNING");
        assertThat(check.getDetail())
            .contains("DEMO PAY SAS").contains("LABORATOIRE EXEMPLE SARL");
    }

    @Test
    void memeEmployeurAvecEspacementDifferent_nEstPasCompteDeuxFois() {
        String text = BULLETIN.replace("DEMO PAY SAS Camille EXEMPLE",
            "DEMO   PAY   SAS Camille EXEMPLE");

        assertThat(service.analyze(text))
            .noneMatch(c -> "Cohérence de l'employeur".equals(c.getLabel()));
    }

    @Test
    void denominationNeFranchitPasUnSautDeLigne() {
        // Le titre de bloc precede le nom sur la ligne suivante. En laissant la
        // recherche traverser le saut de ligne, "IDENTITÉ FICTIVE DEMO PAY SAS"
        // passait pour un second employeur sur tous les bulletins.
        String text = "EMPLOYEUR SALARIÉ(E) - IDENTITÉ FICTIVE\n"
            + "DEMO PAY SAS Camille EXEMPLE\n"
            + "NET À PAYER 2 728,35\n";

        assertThat(service.analyze(text))
            .noneMatch(c -> "Cohérence de l'employeur".equals(c.getLabel()));
    }

    @Test
    void formeJuridiqueAvantLeNom_estReconnue() {
        String text = "SAS WAGE PORTAGE\nRaison sociale : SARL AUTRE SOCIETE\n";

        assertThat(find(service.analyze(text), "Cohérence de l'employeur")
            .getDetail()).contains("WAGE PORTAGE").contains("AUTRE SOCIETE");
    }

    // ── Identite du salarie ──────────────────────────────────────────────────

    @Test
    void identiteCoherente_aucunControle() {
        assertThat(service.analyze(BULLETIN))
            .noneMatch(c -> "Cohérence de l'identité".equals(c.getLabel()));
    }

    @Test
    void deuxPatronymesPourUnPrenom_sontUnEchec() {
        // La trace d'une retouche : le recapitulatif a ete modifie, le bloc
        // d'identification est resté en place.
        String altere = BULLETIN.replace(
            "Salarié récapitulatif Camille EXEMPLE",
            "Salarié récapitulatif Camille MODIFI\u00c9");

        AnalysisResult.Check check = find(service.analyze(altere),
            "Cohérence de l'identité");

        assertThat(check.getStatus()).isEqualTo("FAILED");
        assertThat(check.getDetail()).contains("EXEMPLE").contains("MODIFI\u00c9");
    }

    @Test
    void nomDeNaissanceMentionne_ramenaAUnAvertissement() {
        // Apres un mariage, un meme prenom precede legitimement deux patronymes.
        String text = BULLETIN.replace(
                "Salarié récapitulatif Camille EXEMPLE",
                "Salarié récapitulatif Camille MARTIN")
            + "Nom de naissance : EXEMPLE\n";

        assertThat(find(service.analyze(text), "Cohérence de l'identité")
            .getStatus()).isEqualTo("WARNING");
    }

    @Test
    void motContenantNee_neDesamorcePasLeControle() {
        // "aucune donnée réelle" contient la sequence "nee" : cherchee en simple
        // sous-chaine, elle faisait passer une retouche pour un nom de naissance.
        String text = BULLETIN.replace(
                "Salarié récapitulatif Camille EXEMPLE",
                "Salarié récapitulatif Camille MODIFI\u00c9")
            + "Aucune donnée réelle sur ce document\n";

        assertThat(find(service.analyze(text), "Cohérence de l'identité")
            .getStatus()).isEqualTo("FAILED");
    }

    @Test
    void civilitesEtLibelles_neSontPasPrisPourDesPrenoms() {
        // "Monsieur BORGI" puis "Monsieur SKANDER" ne designent pas deux
        // personnes differentes, et "Total BRUT" n'est pas une identite.
        String text = "Monsieur BORGI GHASSEN\n"
            + "Monsieur SKANDER habite Paris\n"
            + "Total BRUT 3 500,00\n"
            + "Montant NET 2 728,35\n";

        assertThat(service.analyze(text))
            .noneMatch(c -> "Cohérence de l'identité".equals(c.getLabel()));
    }

    @Test
    void texteVide_aucunControle() {
        assertThat(service.analyze("")).isEmpty();
        assertThat(service.analyze(null)).isEmpty();
    }
}
