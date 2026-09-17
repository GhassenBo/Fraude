package com.frauddetect.service;

import com.frauddetect.dto.ContactDto;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Recoit les messages du formulaire de contact et les relaie par email.
 *
 * L'endpoint est public : c'est le seul du service a l'etre sans compte, et donc
 * le seul qu'un robot puisse marteler. Trois garde-fous, tous sans captcha pour
 * ne pas decourager un prospect :
 *
 *   - un quota par adresse IP ;
 *   - un champ leurre, que seuls les robots remplissent ;
 *   - des bornes de taille, deja posees par la validation du DTO.
 */
@Service
public class ContactService {

    /** Messages acceptes par adresse IP et par fenetre. */
    static final int QUOTA_PAR_IP = 3;
    static final Duration FENETRE = Duration.ofHours(1);

    /**
     * Au-dela, la table est purgee entierement : elle ne sert qu'a limiter un
     * abus en cours, et la perdre ne fait qu'accorder un quota neuf. Cette borne
     * evite qu'une attaque distribuee ne la fasse grossir sans fin en memoire.
     */
    private static final int IPS_MEMORISEES_MAX = 10_000;

    private final EmailService emailService;

    // Horodatages des derniers envois, par IP.
    private final Map<String, Deque<Instant>> envois = new ConcurrentHashMap<>();

    public ContactService(EmailService emailService) {
        this.emailService = emailService;
    }

    public enum Resultat {
        ENVOYE,
        /** Quota depasse pour cette adresse IP. */
        TROP_DE_MESSAGES,
        /** Champ leurre rempli : message ignore, sans le dire a l'expediteur. */
        REJETE,
        /** Messagerie indisponible : l'expediteur doit etre averti de l'echec. */
        INDISPONIBLE
    }

    public Resultat submit(ContactDto.ContactRequest request, String ip) {
        if (request.getSiteWeb() != null && !request.getSiteWeb().isBlank()) {
            // Un robot. On ne renvoie pas d'erreur : signaler le piege apprend a
            // le contourner. Cote appelant, la reponse est celle d'un succes.
            return Resultat.REJETE;
        }

        if (!quotaDisponible(ip)) return Resultat.TROP_DE_MESSAGES;

        boolean envoye = emailService.sendContactMessage(
            request.getNom(), request.getEmail(), request.getSociete(),
            request.getMessage());

        if (!envoye) return Resultat.INDISPONIBLE;

        enregistre(ip);
        return Resultat.ENVOYE;
    }

    private synchronized boolean quotaDisponible(String ip) {
        if (envois.size() > IPS_MEMORISEES_MAX) envois.clear();

        Deque<Instant> horodatages = envois.get(ip);
        if (horodatages == null) return true;

        Instant limite = Instant.now().minus(FENETRE);
        while (!horodatages.isEmpty() && horodatages.peekFirst().isBefore(limite)) {
            horodatages.pollFirst();
        }
        if (horodatages.isEmpty()) envois.remove(ip);

        return horodatages.size() < QUOTA_PAR_IP;
    }

    private synchronized void enregistre(String ip) {
        envois.computeIfAbsent(ip, k -> new ArrayDeque<>()).addLast(Instant.now());
    }
}
