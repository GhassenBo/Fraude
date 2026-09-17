package com.frauddetect.twoddoc;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.util.Map;

/**
 * Contenu d'un 2D-DOC.
 *
 * Les champs de donnees sont conserves dans une table indexee par leur
 * identifiant plutot que projetes sur des attributs nommes : le catalogue
 * officiel associant identifiant et semantique n'est pas public, et supposer
 * cette correspondance produirait des incoherences sur des documents
 * authentiques. Le rapprochement avec le PDF se fait donc par recherche de
 * valeur dans l'ensemble des champs.
 *
 * rawData et les valeurs de champs contiennent des donnees fiscales
 * nominatives : ne jamais les journaliser.
 */
@Data
@Builder
public class TwoDDocData {

    private boolean detected;

    /** Contenu brut du DataMatrix. Donnee sensible. */
    private String rawData;

    // ── En-tete, non nominatif ────────────────────────────────────────────────

    /** Version du catalogue, determine la longueur de l'en-tete. */
    private String version;

    /** Identifiant de l'autorite de certification, "FR06" par exemple. */
    private String authorityId;

    /** Identifiant du certificat de signature au sein de l'autorite. */
    private String certificateId;

    /** Type de document au catalogue 2D-DOC. */
    private String documentType;

    private LocalDate emissionDate;

    /** Date de creation de la signature : borne la validite du certificat. */
    private LocalDate signatureDate;

    // ── Donnees signees ───────────────────────────────────────────────────────

    /** Champs indexes par identifiant. Valeurs sensibles. */
    private Map<String, String> fields;

    /**
     * Portion couverte par la signature : en-tete et zone de donnees, a
     * l'exclusion du separateur de signature et de ce qui le suit.
     */
    private String signedPayload;

    /** Signature brute decodee depuis le Base32. */
    private byte[] signature;

    private SignatureStatus signatureStatus;

    /** Precision sur le statut, destinee au diagnostic. Sans donnee nominative. */
    private String signatureDetail;

    public boolean isSignatureTrusted() {
        return signatureStatus == SignatureStatus.VALID;
    }
}
