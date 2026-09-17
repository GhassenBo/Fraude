package com.frauddetect.service;

import com.frauddetect.dto.ContactDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContactServiceTest {

    /** Messagerie de test : memorise les envois au lieu de les emettre. */
    private static class EmailEnregistreur extends EmailService {
        private final List<String[]> envois = new ArrayList<>();
        private boolean disponible = true;

        EmailEnregistreur() {
            super(null);
        }

        @Override
        public boolean sendContactMessage(String nom, String email, String societe,
                                          String message) {
            if (!disponible) return false;
            envois.add(new String[]{nom, email, societe, message});
            return true;
        }
    }

    private EmailEnregistreur email;
    private ContactService service;

    @BeforeEach
    void setUp() {
        email = new EmailEnregistreur();
        service = new ContactService(email);
    }

    private ContactDto.ContactRequest message(String texte) {
        ContactDto.ContactRequest r = new ContactDto.ContactRequest();
        r.setNom("Jean Martin");
        r.setEmail("jean.martin@agence.fr");
        r.setMessage(texte);
        return r;
    }

    @Test
    void messageValide_estRelaye() {
        ContactService.Resultat r = service.submit(
            message("Bonjour, je gere 40 lots et je voudrais une demonstration."),
            "203.0.113.10");

        assertThat(r).isEqualTo(ContactService.Resultat.ENVOYE);
        assertThat(email.envois).hasSize(1);
        assertThat(email.envois.get(0)[1]).isEqualTo("jean.martin@agence.fr");
    }

    @Test
    void champLeurreRempli_messageIgnore() {
        ContactDto.ContactRequest r = message("Achetez nos backlinks pas chers.");
        r.setSiteWeb("http://spam.example");

        assertThat(service.submit(r, "203.0.113.10"))
            .isEqualTo(ContactService.Resultat.REJETE);
        assertThat(email.envois).isEmpty();
    }

    @Test
    void quotaParIp_bloqueLeQuatriemeMessage() {
        for (int i = 0; i < ContactService.QUOTA_PAR_IP; i++) {
            assertThat(service.submit(message("Message numero " + i), "203.0.113.10"))
                .isEqualTo(ContactService.Resultat.ENVOYE);
        }

        assertThat(service.submit(message("Un de trop"), "203.0.113.10"))
            .isEqualTo(ContactService.Resultat.TROP_DE_MESSAGES);
        assertThat(email.envois).hasSize(ContactService.QUOTA_PAR_IP);
    }

    @Test
    void leQuotaEstParIp_uneAutreAdresseNEstPasPenalisee() {
        for (int i = 0; i < ContactService.QUOTA_PAR_IP; i++) {
            service.submit(message("Message numero " + i), "203.0.113.10");
        }

        assertThat(service.submit(message("Message d'un autre visiteur"), "198.51.100.7"))
            .isEqualTo(ContactService.Resultat.ENVOYE);
    }

    @Test
    void envoiEnEchec_neConsommePasLeQuota() {
        // Sinon une panne de messagerie priverait le visiteur de ses essais.
        email.disponible = false;

        assertThat(service.submit(message("Premiere tentative"), "203.0.113.10"))
            .isEqualTo(ContactService.Resultat.INDISPONIBLE);

        email.disponible = true;
        assertThat(service.submit(message("Nouvelle tentative"), "203.0.113.10"))
            .isEqualTo(ContactService.Resultat.ENVOYE);
    }

    @Test
    void leLeurreNeConsommePasLeQuota() {
        // Un robot ne doit pas pouvoir epuiser le quota d'une IP partagee, un
        // reseau d'agence sortant sous une seule adresse publique.
        ContactDto.ContactRequest robot = message("Spam");
        robot.setSiteWeb("http://spam.example");
        for (int i = 0; i < 10; i++) service.submit(robot, "203.0.113.10");

        assertThat(service.submit(message("Message legitime"), "203.0.113.10"))
            .isEqualTo(ContactService.Resultat.ENVOYE);
    }
}
