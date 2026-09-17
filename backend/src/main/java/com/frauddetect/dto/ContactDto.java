package com.frauddetect.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

public class ContactDto {

    @Data
    public static class ContactRequest {
        @NotBlank(message = "Votre nom est requis")
        @Size(max = 80, message = "Nom trop long")
        private String nom;

        @Email(message = "Adresse email invalide")
        @NotBlank(message = "Votre adresse email est requise")
        @Size(max = 120, message = "Adresse email trop longue")
        private String email;

        @Size(max = 120, message = "Nom de société trop long")
        private String societe;

        @NotBlank(message = "Votre message est requis")
        @Size(min = 10, max = 3000,
            message = "Le message doit comporter entre 10 et 3000 caractères")
        private String message;

        /**
         * Champ leurre, invisible dans le formulaire. Un humain le laisse vide ;
         * les robots qui remplissent tous les champs d'un formulaire s'y prennent.
         * Ne coute rien et n'impose aucun captcha a l'utilisateur.
         */
        private String siteWeb;
    }
}
