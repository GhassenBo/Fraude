package com.frauddetect.controller;

import com.frauddetect.dto.ContactDto;
import com.frauddetect.service.ContactService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class ContactController {

    private final ContactService contactService;

    public ContactController(ContactService contactService) {
        this.contactService = contactService;
    }

    @PostMapping("/contact")
    public ResponseEntity<?> contact(@Valid @RequestBody ContactDto.ContactRequest request,
                                     HttpServletRequest http) {
        ContactService.Resultat resultat = contactService.submit(request, clientIp(http));

        return switch (resultat) {
            // Un robot piege recoit la meme reponse qu'un envoi reussi : lui
            // signaler le rejet lui apprendrait a contourner le leurre.
            case ENVOYE, REJETE -> ResponseEntity.ok(Map.of(
                "message", "Message envoyé. Nous répondons sous 24 heures ouvrées."));
            case TROP_DE_MESSAGES -> ResponseEntity.status(429).body(Map.of(
                "error", "Trop de messages envoyés depuis cette connexion."
                    + " Réessayez dans une heure."));
            case INDISPONIBLE -> ResponseEntity.status(503).body(Map.of(
                "error", "L'envoi a échoué. Réessayez dans quelques minutes."));
        };
    }

    /**
     * Adresse du client derriere nginx.
     *
     * X-Forwarded-For peut etre forge par l'appelant, mais nginx y ajoute
     * l'adresse reelle en fin de liste (proxy_add_x_forwarded_for) : c'est donc
     * la derniere valeur qu'il faut lire, et non la premiere. Prendre la
     * premiere permettrait de contourner le quota en changeant l'en-tete a
     * chaque requete.
     */
    private String clientIp(HttpServletRequest http) {
        String forwarded = http.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String[] parts = forwarded.split(",");
            String derniere = parts[parts.length - 1].trim();
            if (!derniere.isEmpty()) return derniere;
        }
        return http.getRemoteAddr();
    }
}
