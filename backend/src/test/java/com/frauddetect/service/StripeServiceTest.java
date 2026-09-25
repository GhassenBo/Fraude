package com.frauddetect.service;

import com.frauddetect.util.FrontendUrl;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StripeServiceTest {

    // ── Origine du frontend ──────────────────────────────────────────────────

    @Test
    void plusieursOrigines_seuleLaPremiereEstRetenue() {
        // FRONTEND_URL porte le domaine avec et sans www pour CORS. Concatener la
        // liste entiere produisait une adresse de retour que Stripe refuse, et la
        // session de paiement ne se creait pas du tout.
        assertThat(FrontendUrl.firstOrigin(
            "https://frauddetect.fr,https://www.frauddetect.fr"))
            .isEqualTo("https://frauddetect.fr");
    }

    @Test
    void barreObliqueFinale_estRetiree() {
        // Sinon l'adresse construite comporte deux barres obliques de suite.
        assertThat(FrontendUrl.firstOrigin("https://frauddetect.fr/"))
            .isEqualTo("https://frauddetect.fr");
    }

    @Test
    void espacesAutourDesVirgules_sontIgnores() {
        assertThat(FrontendUrl.firstOrigin(" https://frauddetect.fr , https://www.x.fr"))
            .isEqualTo("https://frauddetect.fr");
    }

    @Test
    void valeurAbsente_donneUneChaineVide() {
        // Jamais null : une adresse relative vaut mieux qu'une concatenation
        // avec le mot "null", qui produirait un lien mort.
        assertThat(FrontendUrl.firstOrigin(null)).isEmpty();
        assertThat(FrontendUrl.firstOrigin("   ")).isEmpty();
    }

    // ── Statuts d'abonnement ─────────────────────────────────────────────────

    @Test
    void abonnementActif_ouvreLAcces() {
        assertThat(StripeService.donneAccesPro("active")).isTrue();
        assertThat(StripeService.donneAccesPro("trialing")).isTrue();
    }

    @Test
    void impayeEnCoursDeRelance_conserveLAcces() {
        // Stripe relance pendant plusieurs semaines. Couper l'acces au premier
        // echec penaliserait un client dont la carte a simplement expire.
        assertThat(StripeService.donneAccesPro("past_due")).isTrue();
    }

    @Test
    void abonnementTermineOuImpaye_fermeLAcces() {
        // Sans ce traitement, un abonnement impaye laissait l'acces Pro
        // indefiniment : seule la suppression de l'abonnement etait ecoutee.
        assertThat(StripeService.donneAccesPro("canceled")).isFalse();
        assertThat(StripeService.donneAccesPro("unpaid")).isFalse();
        assertThat(StripeService.donneAccesPro("incomplete_expired")).isFalse();
        assertThat(StripeService.donneAccesPro("paused")).isFalse();
    }

    @Test
    void statutInconnuOuAbsent_fermeLAcces() {
        // Par prudence : un statut que Stripe ajouterait ne doit pas ouvrir
        // l'acces par defaut.
        assertThat(StripeService.donneAccesPro("statut_futur")).isFalse();
        assertThat(StripeService.donneAccesPro(null)).isFalse();
    }
}
