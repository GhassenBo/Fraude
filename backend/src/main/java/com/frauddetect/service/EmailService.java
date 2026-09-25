package com.frauddetect.service;

import com.frauddetect.entity.User;
import com.frauddetect.util.FrontendUrl;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import jakarta.mail.internet.MimeMessage;

@Service
public class EmailService {

    private final JavaMailSender mailSender;

    @Value("${app.mail.from:noreply@frauddetect.fr}")
    private String from;

    @Value("${app.mail.from-name:FraudDetect}")
    private String fromName;

    @Value("${app.base.url}")
    private String baseUrl;

    @Value("${spring.mail.host:}")
    private String mailHost;

    /** Destinataire des messages du formulaire de contact. */
    @Value("${app.contact.to:}")
    private String contactTo;

    public EmailService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    public boolean isEnabled() {
        return mailHost != null && !mailHost.isBlank();
    }

    public void sendVerificationEmail(User user, String token) {
        if (!isEnabled()) {
            System.out.println("[MAIL] Desactive (spring.mail.host non defini) — lien de verification : "
                + verificationLink(token));
            return;
        }

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");
            helper.setFrom(from, fromName);
            helper.setTo(user.getEmail());
            helper.setSubject("Confirmez votre adresse email — FraudDetect");
            helper.setText(buildHtml(verificationLink(token)), true);
            mailSender.send(message);
        } catch (Exception e) {
            // L'inscription ne doit pas echouer si l'envoi echoue : l'utilisateur
            // peut demander un renvoi depuis l'application.
            System.err.println("[MAIL] Envoi impossible a " + user.getEmail() + " : " + e.getMessage());
        }
    }

    /**
     * Relaie un message du formulaire de contact.
     *
     * L'expediteur reste l'adresse du service : mettre celle du visiteur ferait
     * echouer SPF et DKIM, et le message serait classe en indesirable. Elle est
     * placee en Reply-To, de sorte qu'une reponse lui parvienne directement.
     *
     * Tout ce qui entre dans un en-tete est purge des retours a la ligne : un
     * nom contenant "\nBcc:" ajouterait sinon des destinataires au message.
     *
     * @return false si l'envoi a echoue. L'appelant doit le dire au visiteur :
     *         lui afficher une confirmation alors que rien n'est parti lui ferait
     *         attendre une reponse qui ne viendra pas.
     */
    public boolean sendContactMessage(String nom, String emailVisiteur,
                                      String societe, String message) {
        if (!isEnabled()) {
            System.out.println("[MAIL] Desactive — message de contact non relaye");
            return false;
        }

        String destinataire = (contactTo == null || contactTo.isBlank()) ? from : contactTo;

        try {
            MimeMessage mime = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, "UTF-8");
            helper.setFrom(from, fromName);
            helper.setTo(destinataire);
            helper.setSubject("[Contact] " + enTete(nom));
            helper.setReplyTo(enTete(emailVisiteur));
            helper.setText(contactTexte(nom, emailVisiteur, societe, message));
            mailSender.send(mime);
            return true;
        } catch (Exception e) {
            System.err.println("[MAIL] Message de contact non relaye : " + e.getMessage());
            return false;
        }
    }

    /**
     * Previent l'exploitant qu'un compte vient d'etre cree.
     *
     * Destinee a l'exploitant et non a l'utilisateur : le service n'a aucun autre
     * moyen de savoir que quelqu'un s'en sert, hors consultation de la base.
     *
     * L'envoi ne fait jamais echouer l'action qui l'a declenche : une inscription
     * ne doit pas etre perdue parce qu'une notification n'est pas partie.
     */
    public void notifieInscription(String emailInscrit) {
        notifie("Nouvelle inscription",
            "Un compte vient d'être créé sur FraudDetect.\n\n"
                + "Adresse : " + emailInscrit + "\n"
                + "Date : " + horodatage() + "\n\n"
                + "L'adresse n'est pas encore confirmée à cet instant : la personne"
                + " doit cliquer sur le lien reçu avant de pouvoir lancer une analyse.");
    }

    /**
     * Previent l'exploitant qu'un utilisateur vient d'analyser son premier
     * document.
     *
     * Plus revelateur qu'une inscription : la personne a compris le produit et
     * depose un vrai document. Le nom du fichier n'est pas transmis, il porte
     * souvent le nom du salarie.
     */
    public void notifiePremiereAnalyse(String emailUtilisateur, int score, String verdict) {
        notifie("Première analyse",
            "Un utilisateur vient d'analyser son premier document.\n\n"
                + "Adresse : " + emailUtilisateur + "\n"
                + "Résultat : " + score + "/100 — " + verdict + "\n"
                + "Date : " + horodatage());
    }

    /** Envoi vers l'exploitant, silencieux en cas d'echec. */
    private void notifie(String sujet, String corps) {
        if (!isEnabled()) return;

        String destinataire = (contactTo == null || contactTo.isBlank()) ? from : contactTo;
        try {
            MimeMessage mime = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, "UTF-8");
            helper.setFrom(from, fromName);
            helper.setTo(destinataire);
            helper.setSubject("[FraudDetect] " + sujet);
            helper.setText(corps);
            mailSender.send(mime);
        } catch (Exception e) {
            System.err.println("[MAIL] Notification « " + sujet + " » non envoyée : "
                + e.getMessage());
        }
    }

    private String horodatage() {
        return java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy à HH:mm"));
    }

    /** Valeur utilisable dans un en-tete : sans saut de ligne, longueur bornee. */
    String enTete(String valeur) {
        if (valeur == null) return "";
        String propre = valeur.replaceAll("[\\r\\n]+", " ").trim();
        return propre.length() > 120 ? propre.substring(0, 120) : propre;
    }

    // Texte brut : le corps reprend ce que le visiteur a ecrit, et l'interpreter
    // comme du HTML exposerait la boite de reception a une injection.
    private String contactTexte(String nom, String email, String societe, String message) {
        return "Nom : " + nom + "\n"
            + "Email : " + email + "\n"
            + (societe != null && !societe.isBlank() ? "Société : " + societe + "\n" : "")
            + "\n" + message + "\n";
    }

    private String verificationLink(String token) {
        return FrontendUrl.firstOrigin(baseUrl) + "/api/auth/verify?token=" + token;
    }

    private String buildHtml(String link) {
        return """
            <div style="font-family:-apple-system,Segoe UI,Roboto,sans-serif;max-width:520px;margin:0 auto;padding:32px 24px;color:#1a1a2e">
              <h1 style="font-size:22px;margin:0 0 8px">Confirmez votre adresse email</h1>
              <p style="color:#555;line-height:1.6;margin:0 0 24px">
                Bienvenue sur FraudDetect. Cliquez sur le bouton ci-dessous pour activer
                l'analyse de vos bulletins de salaire.
              </p>
              <a href="%s" style="display:inline-block;background:#00d4ff;color:#001018;text-decoration:none;font-weight:600;padding:13px 26px;border-radius:8px">
                Confirmer mon adresse
              </a>
              <p style="color:#888;font-size:13px;line-height:1.6;margin:24px 0 0">
                Ce lien expire dans 24 heures. Si le bouton ne fonctionne pas, copiez cette adresse
                dans votre navigateur :<br>
                <span style="color:#0088aa;word-break:break-all">%s</span>
              </p>
              <p style="color:#aaa;font-size:12px;margin:24px 0 0">
                Vous n'avez pas cree de compte ? Ignorez simplement ce message.
              </p>
            </div>
            """.formatted(link, link);
    }
}
