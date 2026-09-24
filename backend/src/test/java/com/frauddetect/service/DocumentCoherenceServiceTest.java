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
    // Aucune mention de specimen, aucun terme evoquant un essai : la fixture doit
    // representer un bulletin ordinaire.
    private static final String BULLETIN =
        "MARTIN & FILS SARL - BULLETIN DE PAIE\n"
        + "12 rue de la Paix, 75002 Paris\n"
        + "EMPLOYEUR SALARIÉ(E)\n"
        + "MARTIN & FILS SARL Camille DURAND\n"
        + "Salaire de base 151,67 h 3 500,00\n"
        + "NET À PAYER 2 728,35\n"
        + "Salarié récapitulatif Camille DURAND\n";

    // ── Mentions de document sans valeur ─────────────────────────────────────

    @Test
    void bulletinSansMention_aucunControle() {
        assertThat(service.analyze(BULLETIN))
            .noneMatch(c -> "Mention de document sans valeur".equals(c.getLabel()));
    }

    @Test
    void mentionExplicite_estUnEchec() {
        String text = BULLETIN + "DOCUMENT FICTIF - LOGICIEL UNIQUEMENT\n";

        AnalysisResult.Check check = find(service.analyze(text),
            "Mention de document sans valeur");

        assertThat(check.getStatus()).isEqualTo("FAILED");
        assertThat(check.getDetail()).contains("document fictif");
    }

    @Test
    void exempleDeBulletin_estUnEchec() {
        // Signature des generateurs en ligne : la piece qu'un candidat telecharge
        // en quelques secondes. Ses calculs sont coherents, mais ce n'est pas un
        // justificatif de revenus.
        String text = BULLETIN
            + "Exemple de bulletin de paie  ·  Généré avec QuickPaie.com\n";

        AnalysisResult.Check check = find(service.analyze(text),
            "Mention de document sans valeur");

        assertThat(check.getStatus()).isEqualTo("FAILED");
        assertThat(check.getDetail()).contains("exemple de bulletin");
    }

    @Test
    void unSeulTermeFaible_neConclutPas() {
        // Une societe peut s'appeler Test : un terme isole ne suffit pas.
        String text = BULLETIN.replace("MARTIN & FILS SARL", "TEST INDUSTRIES SARL");

        assertThat(service.analyze(text))
            .noneMatch(c -> "Mention de document sans valeur".equals(c.getLabel()));
    }

    @Test
    void deuxTermesFaibles_donnentUnAvertissement() {
        String text = BULLETIN + "Bulletin de demonstration - apercu\n";

        AnalysisResult.Check check = find(service.analyze(text),
            "Mention de document sans valeur");

        assertThat(check.getStatus()).isEqualTo("WARNING");
        assertThat(check.getDetail()).contains("demonstration").contains("apercu");
    }

    @Test
    void parExempleDansUnePhrase_neDeclencheRien() {
        // Le mot "exemple" seul est trop courant : seules les formulations qui
        // qualifient le document comptent.
        String text = BULLETIN + "Les taux sont ceux du régime général, par exemple.\n";

        assertThat(service.analyze(text))
            .noneMatch(c -> "Mention de document sans valeur".equals(c.getLabel()));
    }

    // ── Employeur ────────────────────────────────────────────────────────────

    @Test
    void unSeulEmployeur_aucunControle() {
        assertThat(service.analyze(BULLETIN))
            .noneMatch(c -> "Cohérence de l'employeur".equals(c.getLabel()));
    }

    @Test
    void deuxEmployeurs_sontSignales() {
        String altere = BULLETIN.replace("MARTIN & FILS SARL Camille DURAND",
            "LABORATOIRE DUPONT SARL Camille DURAND");

        AnalysisResult.Check check = find(service.analyze(altere),
            "Cohérence de l'employeur");

        // Un second employeur se rencontre en portage salarial : le constat
        // oriente la verification humaine, il ne conclut pas.
        assertThat(check.getStatus()).isEqualTo("WARNING");
        assertThat(check.getDetail())
            .contains("MARTIN & FILS SARL").contains("LABORATOIRE DUPONT SARL");
    }

    @Test
    void memeEmployeurAvecEspacementDifferent_nEstPasCompteDeuxFois() {
        String text = BULLETIN.replace("MARTIN & FILS SARL Camille DURAND",
            "MARTIN  &  FILS  SARL Camille DURAND");

        assertThat(service.analyze(text))
            .noneMatch(c -> "Cohérence de l'employeur".equals(c.getLabel()));
    }

    @Test
    void denominationNeFranchitPasUnSautDeLigne() {
        // Le titre de bloc precede le nom sur la ligne suivante. En laissant la
        // recherche traverser le saut de ligne, le titre s'agregeait au nom et
        // passait pour un second employeur sur tous les bulletins.
        String text = "EMPLOYEUR SALARIÉ(E) - IDENTITÉ DU SALARIÉ\n"
            + "MARTIN & FILS SARL Camille DURAND\n"
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
            "Salarié récapitulatif Camille DURAND",
            "Salarié récapitulatif Camille MODIFI\u00c9");

        AnalysisResult.Check check = find(service.analyze(altere),
            "Cohérence de l'identité");

        assertThat(check.getStatus()).isEqualTo("FAILED");
        assertThat(check.getDetail()).contains("DURAND").contains("MODIFI\u00c9");
    }

    @Test
    void patronymeAccentue_nEstPasTronque() {
        // En semantique ASCII, \\b ne reconnait pas les lettres accentuees comme
        // des caracteres de mot : la recherche reculait d'un cran et "DUPRÉ" se
        // reduisait a "DUPR".
        String altere = BULLETIN.replace(
            "Salarié récapitulatif Camille DURAND",
            "Salarié récapitulatif Camille DUPR\u00c9");

        assertThat(find(service.analyze(altere), "Cohérence de l'identité")
            .getDetail()).contains("DUPR\u00c9");
    }

    @Test
    void nomDeNaissanceMentionne_ramenaAUnAvertissement() {
        // Apres un mariage, un meme prenom precede legitimement deux patronymes.
        String text = BULLETIN.replace(
                "Salarié récapitulatif Camille DURAND",
                "Salarié récapitulatif Camille MARTIN")
            + "Nom de naissance : DURAND\n";

        assertThat(find(service.analyze(text), "Cohérence de l'identité")
            .getStatus()).isEqualTo("WARNING");
    }

    @Test
    void motContenantNee_neDesamorcePasLeControle() {
        // "aucune donnée réelle" contient la sequence "nee" : cherchee en simple
        // sous-chaine, elle faisait passer une retouche pour un nom de naissance.
        String text = BULLETIN.replace(
                "Salarié récapitulatif Camille DURAND",
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
