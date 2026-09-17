package com.frauddetect.twoddoc;

import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Optional;

/**
 * Fournit le certificat de signature d'un 2D-DOC.
 *
 * La resolution suit la chaine officielle : liste de confiance de l'ANTS, puis
 * autorite identifiee par authorityId, puis son annuaire, puis le certificat
 * identifie par certificateId. Aucune implementation ne doit coder en dur une
 * cle publique ni deviner l'URL d'un certificat.
 */
public interface TwoDDocCertificateProvider {

    /**
     * @return le certificat de signature, vide s'il est introuvable
     * @throws CertificateSourceUnavailableException si la source de confiance
     *         est injoignable, cas distinct d'un certificat introuvable
     */
    Optional<X509Certificate> find(String authorityId, String certificateId)
        throws CertificateSourceUnavailableException;

    /**
     * Certificats d'autorite permettant de valider la chaine de confiance du
     * certificat de signature.
     */
    List<X509Certificate> trustAnchors(String authorityId);

    /** Numeros de serie revoques connus pour cette autorite, en hexadecimal. */
    default List<String> revokedSerials(String authorityId) {
        return List.of();
    }

    class CertificateSourceUnavailableException extends Exception {
        public CertificateSourceUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }

        public CertificateSourceUnavailableException(String message) {
            super(message);
        }
    }
}
