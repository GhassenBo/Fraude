package com.frauddetect.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EmailServiceTest {

    private final EmailService service = new EmailService(null);

    @Test
    void sautDeLigne_retireDesEnTetes() {
        // Injection d'en-tete : sans purge, le nom saisi dans le formulaire de
        // contact ajouterait un destinataire cache au message relaye.
        String valeur = service.enTete("Jean Martin\r\nBcc: victime@example.com");

        assertThat(valeur).doesNotContain("\r").doesNotContain("\n");
        assertThat(valeur).isEqualTo("Jean Martin Bcc: victime@example.com");
    }

    @Test
    void valeurTropLongue_estTronquee() {
        assertThat(service.enTete("x".repeat(400))).hasSize(120);
    }

    @Test
    void valeurAbsente_donneUneChaineVide() {
        assertThat(service.enTete(null)).isEmpty();
    }
}
