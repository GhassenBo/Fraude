package com.frauddetect.dto;

import com.frauddetect.entity.Analysis;

import java.time.LocalDateTime;

public class HistoryDto {

    /**
     * Ligne d'historique restituee au client.
     *
     * L'entite Analysis ne doit jamais etre serialisee telle quelle. Elle porte
     * une relation vers User, chargee paresseusement : hors transaction — le cas
     * en production, ou spring.jpa.open-in-view est a false — sa serialisation
     * echoue et l'historique remonte en erreur. Et la ou elle aboutit, elle
     * expose l'utilisateur entier, empreinte du mot de passe, jeton de
     * verification et identifiants Stripe compris.
     */
    public record Item(
        Long id,
        String filename,
        Integer score,
        String verdict,
        String color,
        LocalDateTime createdAt
    ) {
        public static Item from(Analysis analysis) {
            return new Item(
                analysis.getId(),
                analysis.getFilename(),
                analysis.getScore(),
                analysis.getVerdict(),
                analysis.getColor(),
                analysis.getCreatedAt());
        }
    }
}
