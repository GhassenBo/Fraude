package com.frauddetect.controller;

import com.frauddetect.entity.User;
import com.frauddetect.service.StripeService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/stripe")
public class StripeController {

    private final StripeService stripeService;

    public StripeController(StripeService stripeService) {
        this.stripeService = stripeService;
    }

    @PostMapping("/checkout")
    public ResponseEntity<?> createCheckout(@AuthenticationPrincipal User user) {
        try {
            String url = stripeService.createCheckoutSession(user);
            return ResponseEntity.ok(Map.of("url", url));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(Map.of("error", "Erreur lors de la création du paiement : " + e.getMessage()));
        }
    }

    @PostMapping("/portal")
    public ResponseEntity<?> createPortal(@AuthenticationPrincipal User user) {
        try {
            if (user.getStripeCustomerId() == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "Pas d'abonnement actif"));
            }
            String url = stripeService.createPortalSession(user);
            return ResponseEntity.ok(Map.of("url", url));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(Map.of("error", e.getMessage()));
        }
    }

    // Webhook — no auth required, Stripe signs the payload
    @PostMapping("/webhook")
    public ResponseEntity<?> webhook(
        @RequestBody String payload,
        @RequestHeader("Stripe-Signature") String sigHeader
    ) {
        try {
            stripeService.handleWebhook(payload, sigHeader);
            return ResponseEntity.ok(Map.of("received", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(400).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}
