package com.frauddetect.service;

import com.frauddetect.entity.User;
import com.frauddetect.repository.UserRepository;
import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.Customer;
import com.stripe.model.Event;
import com.stripe.model.Subscription;
import com.stripe.net.Webhook;
import com.stripe.param.CustomerCreateParams;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

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
        com.stripe.model.checkout.Session checkoutSession = com.stripe.model.checkout.Session.create(
            com.stripe.param.checkout.SessionCreateParams.builder()
                .setMode(com.stripe.param.checkout.SessionCreateParams.Mode.SUBSCRIPTION)
                .setCustomer(customerId)
                .addLineItem(
                    com.stripe.param.checkout.SessionCreateParams.LineItem.builder()
                        .setPrice(priceId)
                        .setQuantity(1L)
                        .build()
                )
                .setSuccessUrl(baseUrl + "/dashboard?upgrade=success")
                .setCancelUrl(baseUrl + "/pricing?upgrade=cancelled")
                .build()
        );

        return checkoutSession.getUrl();
    }

    public String createPortalSession(User user) throws StripeException {
        com.stripe.param.billingportal.SessionCreateParams params =
            com.stripe.param.billingportal.SessionCreateParams.builder()
                .setCustomer(user.getStripeCustomerId())
                .setReturnUrl(baseUrl + "/dashboard")
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

        switch (event.getType()) {
            case "checkout.session.completed" -> {
                com.stripe.model.checkout.Session session =
                    (com.stripe.model.checkout.Session) event.getDataObjectDeserializer()
                        .getObject().orElseThrow();
                handleCheckoutCompleted(session);
            }
            case "customer.subscription.deleted" -> {
                Subscription sub = (Subscription) event.getDataObjectDeserializer()
                    .getObject().orElseThrow();
                handleSubscriptionCancelled(sub);
            }
        }
    }

    private void handleCheckoutCompleted(com.stripe.model.checkout.Session session) {
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
