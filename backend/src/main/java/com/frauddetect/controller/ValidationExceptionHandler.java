package com.frauddetect.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Traduit un echec de validation dans la meme forme que les autres erreurs de
 * l'API, {"error": "..."}.
 *
 * Sans cela, Spring renvoie sa reponse par defaut, ou le motif du refus ne
 * figure pas sous la cle que lit le frontend : l'utilisateur voit une erreur
 * generique au lieu de savoir quel champ corriger.
 */
@RestControllerAdvice
public class ValidationExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> onInvalid(MethodArgumentNotValidException e) {
        // Le premier motif suffit : les formulaires concernes tiennent en
        // quelques champs, et empiler les messages nuit a la lisibilite.
        String message = e.getBindingResult().getFieldErrors().stream()
            .map(FieldError::getDefaultMessage)
            .filter(m -> m != null && !m.isBlank())
            .findFirst()
            .orElse("Requête invalide");

        return ResponseEntity.badRequest().body(Map.of("error", message));
    }
}
