package com.frauddetect.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.frauddetect.entity.User;
import com.frauddetect.repository.UserRepository;
import com.frauddetect.util.FrontendUrl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StripeServiceTest {

    @Mock private UserRepository userRepository;
    private StripeService service;

    @BeforeEach
    void setUp() {
        service = new StripeService(userRepository);
    }

    private User client() {
        return User.builder()
            .id(1L).email("gestionnaire@agence.fr").password("x")
            .plan(User.Plan.FREE).documentsUsed(4)
            .stripeCustomerId("cus_123").build();
    }

    private com.fasterxml.jackson.databind.JsonNode json(String contenu) throws Exception {
        return new ObjectMapper().readTree(contenu);
    }

    // ── Traitement des evenements ────────────────────────────────────────────

    @Test
    void paiementAboutit_faitPasserEnPro() throws Exception {
        // Charge telle que Stripe l'envoie : les champs lus sont ceux du JSON
        // signe, independamment de la version d'API du compte.
        User user = client();
        when(userRepository.findByStripeCustomerId("cus_123")).thenReturn(Optional.of(user));

        service.traiterEvenement("checkout.session.completed",
            json("{\"customer\":\"cus_123\",\"subscription\":\"sub_456\"}"));

        assertThat(user.getPlan()).isEqualTo(User.Plan.PRO);
        assertThat(user.getStripeSubscriptionId()).isEqualTo("sub_456");
        assertThat(user.getProSince()).isNotNull();
        verify(userRepository).save(user);
    }

    @Test
    void abonnementSupprime_faitRevenirEnGratuit() throws Exception {
        User user = client();
        user.setPlan(User.Plan.PRO);
        user.setStripeSubscriptionId("sub_456");
        when(userRepository.findByStripeSubscriptionId("sub_456")).thenReturn(Optional.of(user));

        service.traiterEvenement("customer.subscription.deleted",
            json("{\"id\":\"sub_456\",\"status\":\"canceled\"}"));

        assertThat(user.getPlan()).isEqualTo(User.Plan.FREE);
        assertThat(user.getStripeSubscriptionId()).isNull();
    }

    @Test
    void abonnementImpaye_fermeLAcces() throws Exception {
        // Le cas que l'ancien code ignorait : seule la suppression etait ecoutee,
        // un abonnement impaye laissait donc l'acces Pro indefiniment.
        User user = client();
        user.setPlan(User.Plan.PRO);
        user.setStripeSubscriptionId("sub_456");
        when(userRepository.findByStripeSubscriptionId("sub_456")).thenReturn(Optional.of(user));

        service.traiterEvenement("customer.subscription.updated",
            json("{\"id\":\"sub_456\",\"status\":\"unpaid\"}"));

        assertThat(user.getPlan()).isEqualTo(User.Plan.FREE);
    }

    @Test
    void relanceEnCours_conserveLAcces() throws Exception {
        User user = client();
        user.setPlan(User.Plan.PRO);
        user.setStripeSubscriptionId("sub_456");
        when(userRepository.findByStripeSubscriptionId("sub_456")).thenReturn(Optional.of(user));

        service.traiterEvenement("customer.subscription.updated",
            json("{\"id\":\"sub_456\",\"status\":\"past_due\"}"));

        assertThat(user.getPlan()).isEqualTo(User.Plan.PRO);
        // Rien n'a change : inutile d'ecrire en base.
        verify(userRepository, never()).save(any());
    }

    @Test
    void champsSupplementaires_sontIgnores() throws Exception {
        // Une version d'API recente ajoute des champs : ils ne doivent rien
        // casser, c'est tout l'interet de lire le JSON plutot que les modeles.
        User user = client();
        when(userRepository.findByStripeCustomerId("cus_123")).thenReturn(Optional.of(user));

        service.traiterEvenement("checkout.session.completed",
            json("{\"customer\":\"cus_123\",\"subscription\":\"sub_456\","
                + "\"champ_futur\":{\"imbrique\":true},\"amount_total\":4900}"));

        assertThat(user.getPlan()).isEqualTo(User.Plan.PRO);
    }

    @Test
    void identifiantManquant_neFaitRien() throws Exception {
        service.traiterEvenement("checkout.session.completed", json("{\"subscription\":null}"));
        service.traiterEvenement("customer.subscription.deleted", json("{}"));

        verify(userRepository, never()).save(any());
    }

    @Test
    void evenementNonEcoute_estIgnore() throws Exception {
        service.traiterEvenement("invoice.created", json("{\"id\":\"in_1\"}"));

        verify(userRepository, never()).save(any());
    }

    // ── Detection de la configuration ────────────────────────────────────────

    private StripeService configure(String cle, String prix) {
        StripeService s = new StripeService(userRepository);
        org.springframework.test.util.ReflectionTestUtils.setField(s, "apiKey", cle);
        org.springframework.test.util.ReflectionTestUtils.setField(s, "priceId", prix);
        return s;
    }

    @Test
    void cleRestreinte_estAcceptee() {
        // Stripe propose par defaut une cle restreinte, prefixee rk_, sur un
        // compte de production. Ne reconnaitre que sk_ declarait le paiement
        // inactif avec une cle pourtant valide.
        assertThat(configure("rk_live_abc123", "price_abc").isConfigured()).isTrue();
    }

    @Test
    void cleSecreteClassique_estAcceptee() {
        assertThat(configure("sk_live_abc123", "price_abc").isConfigured()).isTrue();
    }

    @Test
    void valeursLivreesParDefaut_neComptentPas() {
        // Les marques a remplacer ne doivent pas passer pour une configuration.
        assertThat(configure("sk_test_REPLACE_WITH_YOUR_STRIPE_SECRET_KEY",
            "price_REPLACE_WITH_YOUR_PRICE_ID").isConfigured()).isFalse();
        assertThat(configure("YOUR_STRIPE_API_KEY_HERE", "price_abc")
            .isConfigured()).isFalse();
    }

    @Test
    void identifiantDeProduitAuLieuDuTarif_estRefuse() {
        // prod_ designe le produit, price_ le tarif : seul le second sert au
        // tunnel de paiement, et la confusion est facile.
        assertThat(configure("rk_live_abc123", "prod_abc").isConfigured()).isFalse();
    }

    @Test
    void cleAbsente_nActivePasLePaiement() {
        assertThat(configure("", "price_abc").isConfigured()).isFalse();
        assertThat(configure(null, "price_abc").isConfigured()).isFalse();
    }

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
