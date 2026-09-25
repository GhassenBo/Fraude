package com.frauddetect.service;

import com.frauddetect.entity.User;
import com.frauddetect.repository.UserRepository;
import com.frauddetect.util.FrontendUrl;
import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.model.Customer;
import com.stripe.model.Event;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.checkout.SessionCreateParams;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;

@Service
public class StripeService {

    @Value("${stripe.api.key}")
    private String apiKey;

    @Value("${stripe.webhook.secret}")
    private String webhookSecret;

    @Value("${stripe.price.id}")
    private String priceId;

    @Value("${app.base.url}")
    private String baseUrl;

    private final UserRepository userRepository;

    public StripeService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @PostConstruct
    public void init() {
        Stripe.apiKey = apiKey;
        if (isConfigured()) {
            System.out.println("[STRIPE] ✓ Paiement actif"
                + (apiKey.contains("_test_") ? " (mode TEST)" : " (mode LIVE)"));
        } else {
            System.out.println("[STRIPE] Paiement inactif — clé ou identifiant de"
                + " tarif absent ou encore à remplacer");
        }
        if (webhookSecret == null || webhookSecret.isBlank()
                || webhookSecret.contains("REPLACE")) {
            System.out.println("[STRIPE] Secret de webhook absent : les"
                + " abonnements payés ne seront pas enregistrés");
        }
    }

    /**
     * Le paiement est configure.
     *
     * Les valeurs livrees par defaut sont des marques a remplacer. Sans ce
     * controle, un clic sur l'abonnement remontait l'erreur brute de Stripe a
     * l'utilisateur, qui n'y comprenait rien et croyait a une panne.
     */
    public boolean isConfigured() {
        return renseigne(apiKey) && estUneCleStripe(apiKey)
            && renseigne(priceId) && priceId.startsWith("price_");
    }

    /**
     * Prefixes des cles utilisables cote serveur.
     *
     * rk_ designe une cle restreinte, dont les autorisations sont limitees aux
     * seules ressources necessaires. C'est la forme que Stripe propose par
     * defaut sur un compte de production, et la plus sure : ne reconnaitre que
     * sk_ aurait declare le paiement inactif avec une cle pourtant valide.
     */
    private boolean estUneCleStripe(String cle) {
        return cle.startsWith("sk_") || cle.startsWith("rk_");
    }

    private boolean renseigne(String valeur) {
        return valeur != null && !valeur.isBlank() && !valeur.contains("REPLACE")
            && !valeur.contains("YOUR_");
    }

    public String createCheckoutSession(User user) throws StripeException {
        // Create or retrieve Stripe customer
        String customerId = user.getStripeCustomerId();
        if (customerId == null) {
            Customer customer = Customer.create(
                CustomerCreateParams.builder()
                    .setEmail(user.getEmail())
                    .putMetadata("userId", user.getId().toString())
                    .build()
            );
            customerId = customer.getId();
            user.setStripeCustomerId(customerId);
            userRepository.save(user);
        }

        // Create checkout session
        Session session = Session.create(
            SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.SUBSCRIPTION)
                .setCustomer(customerId)
                .addLineItem(
                    SessionCreateParams.LineItem.builder()
                        .setPrice(priceId)
                        .setQuantity(1L)
                        .build()
                )
                // FRONTEND_URL porte plusieurs origines pour CORS : concatener
                // la liste entiere produit une adresse que Stripe refuse, et la
                // session de paiement ne se cree pas du tout.
                .setSuccessUrl(FrontendUrl.firstOrigin(baseUrl) + "/?upgrade=success")
                .setCancelUrl(FrontendUrl.firstOrigin(baseUrl) + "/?upgrade=cancelled")
                .build()
        );

        return session.getUrl();
    }

    public String createPortalSession(User user) throws StripeException {
        com.stripe.param.billingportal.SessionCreateParams params =
            com.stripe.param.billingportal.SessionCreateParams.builder()
                .setCustomer(user.getStripeCustomerId())
                .setReturnUrl(FrontendUrl.firstOrigin(baseUrl) + "/?page=dashboard")
                .build();

        com.stripe.model.billingportal.Session portalSession =
            com.stripe.model.billingportal.Session.create(params);

        return portalSession.getUrl();
    }

    /**
     * Traite un evenement Stripe, signature verifiee.
     *
     * Les champs sont lus directement dans le JSON signe plutot que dans les
     * classes de la bibliotheque. Le compte Stripe fixe sa propre version d'API
     * — 2026-08-26 ici, sans possibilite d'en choisir une plus ancienne — quand
     * la bibliotheque en attend une de 2023. Deserialiser les evenements dans
     * ses modeles reviendrait a dependre d'un alignement que rien ne garantit et
     * qui se rompra au prochain changement de version.
     *
     * Les quatre champs utilises — l'identifiant du client, celui de
     * l'abonnement, son statut — sont stables depuis les origines de l'API, et
     * la signature garantit deja que la charge vient bien de Stripe.
     */
    public void handleWebhook(String payload, String sigHeader) throws Exception {
        Event event;
        try {
            event = Webhook.constructEvent(payload, sigHeader, webhookSecret);
        } catch (Exception e) {
            throw new IllegalArgumentException("Webhook signature invalide");
        }

        JsonNode objet = new ObjectMapper().readTree(payload).path("data").path("object");
        traiterEvenement(event.getType(), objet);
    }

    /** Separe de la verification de signature, qui exige une vraie cle Stripe. */
    void traiterEvenement(String type, JsonNode objet) {
        switch (type) {
            case "checkout.session.completed" ->
                activerAbonnement(texte(objet, "customer"), texte(objet, "subscription"));
            case "customer.subscription.deleted" ->
                cloturerAbonnement(texte(objet, "id"));
            // Fin d'abonnement autrement que par suppression : resiliation en fin
            // de periode, echecs de paiement repetes, abonnement jamais finalise.
            // Sans ce cas, un abonnement impaye laissait l'acces Pro indefiniment.
            case "customer.subscription.updated" ->
                majAbonnement(texte(objet, "id"), texte(objet, "status"));
            default -> {
                // Tout autre evenement est acquitte sans traitement : Stripe
                // cesse alors de le reemettre.
            }
        }
    }

    /** @return null plutot qu'une chaine vide, pour ne jamais chercher un
     *          abonnement dont l'identifiant serait vide */
    private String texte(JsonNode objet, String champ) {
        JsonNode valeur = objet.path(champ);
        if (valeur.isMissingNode() || valeur.isNull()) return null;
        String s = valeur.asText("").trim();
        return s.isEmpty() ? null : s;
    }

    /** Statuts qui ouvrent l'acces. past_due couvre les relances de Stripe, qui
     *  s'etalent sur plusieurs semaines : couper l'acces au premier echec de
     *  prelevement penaliserait un client dont la carte a simplement expire. */
    static boolean donneAccesPro(String status) {
        return "active".equals(status) || "trialing".equals(status)
            || "past_due".equals(status);
    }

    private void activerAbonnement(String customerId, String subscriptionId) {
        if (customerId == null) return;
        userRepository.findByStripeCustomerId(customerId).ifPresent(user -> {
            user.setPlan(User.Plan.PRO);
            user.setStripeSubscriptionId(subscriptionId);
            user.setProSince(LocalDateTime.now());
            userRepository.save(user);
            System.out.println("[STRIPE] Abonnement activé");
        });
    }

    private void cloturerAbonnement(String subscriptionId) {
        if (subscriptionId == null) return;
        userRepository.findByStripeSubscriptionId(subscriptionId).ifPresent(user -> {
            user.setPlan(User.Plan.FREE);
            user.setStripeSubscriptionId(null);
            userRepository.save(user);
            System.out.println("[STRIPE] Abonnement clôturé");
        });
    }

    private void majAbonnement(String subscriptionId, String status) {
        if (subscriptionId == null) return;
        userRepository.findByStripeSubscriptionId(subscriptionId).ifPresent(user -> {
            boolean pro = donneAccesPro(status);
            User.Plan attendu = pro ? User.Plan.PRO : User.Plan.FREE;
            if (user.getPlan() == attendu) return;

            user.setPlan(attendu);
            if (!pro) user.setStripeSubscriptionId(null);
            userRepository.save(user);
            System.out.println("[STRIPE] Abonnement " + status
                + " — plan basculé sur " + attendu);
        });
    }
}
