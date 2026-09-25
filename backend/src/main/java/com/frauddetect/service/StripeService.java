package com.frauddetect.service;

import com.frauddetect.entity.User;
import com.frauddetect.repository.UserRepository;
import com.frauddetect.util.FrontendUrl;
import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.*;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.checkout.SessionCreateParams;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

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
                + (apiKey.startsWith("sk_test") ? " (mode TEST)" : " (mode LIVE)"));
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
        return renseigne(apiKey) && apiKey.startsWith("sk_")
            && renseigne(priceId) && priceId.startsWith("price_");
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

    public void handleWebhook(String payload, String sigHeader) throws Exception {
        Event event;
        try {
            event = Webhook.constructEvent(payload, sigHeader, webhookSecret);
        } catch (Exception e) {
            throw new IllegalArgumentException("Webhook signature invalide");
        }

        Optional<StripeObject> objet = deserialize(event);
        if (objet.isEmpty()) {
            // Acquitte quand meme : une reemission ne changerait rien.
            System.err.println("[STRIPE] Événement " + event.getType()
                + " illisible — version d'API incompatible");
            return;
        }

        switch (event.getType()) {
            case "checkout.session.completed" ->
                handleCheckoutCompleted((Session) objet.get());
            case "customer.subscription.deleted" ->
                handleSubscriptionCancelled((Subscription) objet.get());
            // Fin d'abonnement autrement que par suppression : resiliation en fin
            // de periode, echecs de paiement repetes, abonnement jamais finalise.
            // Sans ce cas, un abonnement impaye laissait l'acces Pro indefiniment.
            case "customer.subscription.updated" ->
                handleSubscriptionUpdated((Subscription) objet.get());
            default -> {
                // Tout autre evenement est acquitte sans traitement : Stripe
                // cesse alors de le reemettre.
            }
        }
    }

    /**
     * Objet porte par l'evenement.
     *
     * getObject() rend un Optional vide quand la version d'API de l'evenement
     * differe de celle attendue par la bibliotheque — cas frequent, le compte
     * Stripe fixant sa propre version. La deserialisation non verifiee est la
     * voie documentee pour ce cas ; si elle echoue a son tour, l'evenement est
     * acquitte sans traitement plutot que refuse, une reemission ne pouvant pas
     * mieux reussir que la premiere tentative.
     */
    private Optional<StripeObject> deserialize(Event event) {
        var deserializer = event.getDataObjectDeserializer();
        if (deserializer.getObject().isPresent()) return deserializer.getObject();
        try {
            return Optional.of(deserializer.deserializeUnsafe());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** Statuts qui ouvrent l'acces. past_due couvre les relances de Stripe, qui
     *  s'etalent sur plusieurs semaines : couper l'acces au premier echec de
     *  prelevement penaliserait un client dont la carte a simplement expire. */
    static boolean donneAccesPro(String status) {
        return "active".equals(status) || "trialing".equals(status)
            || "past_due".equals(status);
    }

    private void handleSubscriptionUpdated(Subscription subscription) {
        userRepository.findByStripeSubscriptionId(subscription.getId()).ifPresent(user -> {
            boolean pro = donneAccesPro(subscription.getStatus());
            User.Plan attendu = pro ? User.Plan.PRO : User.Plan.FREE;
            if (user.getPlan() == attendu) return;

            user.setPlan(attendu);
            if (!pro) user.setStripeSubscriptionId(null);
            userRepository.save(user);
            System.out.println("[STRIPE] Abonnement " + subscription.getStatus()
                + " — plan basculé sur " + attendu);
        });
    }

    private void handleCheckoutCompleted(Session session) {
        String customerId = session.getCustomer();
        userRepository.findByStripeCustomerId(customerId).ifPresent(user -> {
            user.setPlan(User.Plan.PRO);
            user.setStripeSubscriptionId(session.getSubscription());
            user.setProSince(LocalDateTime.now());
            userRepository.save(user);
        });
    }

    private void handleSubscriptionCancelled(Subscription subscription) {
        userRepository.findByStripeSubscriptionId(subscription.getId()).ifPresent(user -> {
            user.setPlan(User.Plan.FREE);
            user.setStripeSubscriptionId(null);
            userRepository.save(user);
        });
    }
}
