package com.frauddetect.twoddoc;

/**
 * Issue de la verification cryptographique d'un 2D-DOC.
 *
 * Seul VALID atteste que les donnees ont bien ete signees par un certificat
 * rattache a une autorite de confiance. Tout autre statut signifie que
 * l'authenticite n'est pas etablie : un DataMatrix lisible ne prouve rien,
 * n'importe qui pouvant en generer un.
 */
public enum SignatureStatus {

    /** Signature verifiee, certificat valide et rattache a la chaine de confiance. */
    VALID,

    /** Signature ne correspondant pas aux donnees : document altere. */
    INVALID,

    /** Certificat introuvable dans la TSL ou l'annuaire de l'autorite. */
    CERTIFICATE_UNKNOWN,

    /** Certificat present dans la liste de revocation de l'autorite. */
    CERTIFICATE_REVOKED,

    /** Date de signature hors de la periode de validite du certificat. */
    CERTIFICATE_EXPIRED,

    /** Certificat non rattachable a une autorite racine de confiance. */
    TRUST_CHAIN_INVALID,

    /** Liste de confiance inaccessible et aucun certificat utilisable en cache. */
    TSL_UNAVAILABLE,

    /** Algorithme de signature non pris en charge. */
    UNSUPPORTED_ALGORITHM,

    /** Structure du 2D-DOC non conforme : en-tete ou zone de signature illisible. */
    MALFORMED,

    /** Verification non effectuee : fonctionnalite desactivee ou prerequis absent. */
    NOT_VERIFIED
}
