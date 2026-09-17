package com.frauddetect.twoddoc;

import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Optional;

/**
 * Verifie la signature d'un 2D-DOC.
 *
 * Deroulement, dans cet ordre, chaque etape conditionnant la suivante :
 *
 *   1. resolution du certificat de signature aupres de la source de confiance
 *      (liste ANTS, puis autorite, puis son annuaire) ;
 *   2. rattachement du certificat a une autorite racine de confiance ;
 *   3. controle de revocation ;
 *   4. validite du certificat a la date de signature du document, et non a la
 *      date du jour : un document signe hier reste valide apres l'expiration du
 *      certificat ;
 *   5. verification de la signature sur les octets couverts.
 *
 * Signature : ECDSA sur courbe P-256, condensat SHA-256. Les 64 octets extraits
 * du Base32 sont la concatenation brute de r et s, format attendu par la
 * specification mais non par le JDK, qui exige un encodage DER : la conversion
 * est faite ici.
 *
 * Donnees couvertes : l'en-tete et la zone de donnees, a l'exclusion du
 * separateur de signature et de ce qui le suit.
 */
@Component
public class TwoDDocSignatureVerifier {

    /** Taille d'une composante r ou s sur P-256. */
    private static final int P256_COMPONENT_BYTES = 32;

    private static final String SIGNATURE_ALGORITHM = "SHA256withECDSA";

    private final TwoDDocCertificateProvider certificateProvider;

    public TwoDDocSignatureVerifier(TwoDDocCertificateProvider certificateProvider) {
        this.certificateProvider = certificateProvider;
    }

    /**
     * @return les donnees enrichies du statut de verification ; l'instance
     *         fournie n'est pas modifiee
     */
    public TwoDDocData verify(TwoDDocData data) {
        if (data == null || !data.isDetected()) {
            return withStatus(data, SignatureStatus.NOT_VERIFIED, "Aucun 2D-DOC");
        }
        if (data.getSignatureStatus() == SignatureStatus.MALFORMED) {
            return data;
        }
        if (data.getSignature() == null || data.getSignedPayload() == null) {
            return withStatus(data, SignatureStatus.MALFORMED, "Signature ou donnees absentes");
        }
        if (data.getSignature().length != 2 * P256_COMPONENT_BYTES) {
            return withStatus(data, SignatureStatus.UNSUPPORTED_ALGORITHM,
                "Longueur de signature de " + data.getSignature().length
                    + " octets, non conforme a ECDSA P-256");
        }

        Optional<X509Certificate> certificate;
        try {
            certificate = certificateProvider.find(data.getAuthorityId(), data.getCertificateId());
        } catch (TwoDDocCertificateProvider.CertificateSourceUnavailableException e) {
            return withStatus(data, SignatureStatus.TSL_UNAVAILABLE, e.getMessage());
        }
        if (certificate.isEmpty()) {
            return withStatus(data, SignatureStatus.CERTIFICATE_UNKNOWN,
                "Certificat " + data.getAuthorityId() + "/" + data.getCertificateId()
                    + " absent de la source de confiance");
        }

        X509Certificate cert = certificate.get();

        if (isRevoked(cert, data.getAuthorityId())) {
            return withStatus(data, SignatureStatus.CERTIFICATE_REVOKED,
                "Certificat revoque par son autorite");
        }
        if (!isChainTrusted(cert, data.getAuthorityId())) {
            return withStatus(data, SignatureStatus.TRUST_CHAIN_INVALID,
                "Certificat non rattachable a une autorite racine de confiance");
        }

        SignatureStatus validity = checkValidityAt(cert, data.getSignatureDate());
        if (validity != null) {
            return withStatus(data, validity,
                "Certificat hors periode de validite a la date de signature");
        }

        return verifySignature(data, cert.getPublicKey());
    }

    private TwoDDocData verifySignature(TwoDDocData data, PublicKey publicKey) {
        try {
            Signature verifier = Signature.getInstance(SIGNATURE_ALGORITHM);
            verifier.initVerify(publicKey);
            verifier.update(data.getSignedPayload().getBytes(StandardCharsets.UTF_8));

            boolean ok = verifier.verify(toDer(data.getSignature()));
            return ok
                ? withStatus(data, SignatureStatus.VALID, "Signature verifiee")
                : withStatus(data, SignatureStatus.INVALID,
                    "Signature ne correspondant pas aux donnees signees");
        } catch (java.security.NoSuchAlgorithmException e) {
            return withStatus(data, SignatureStatus.UNSUPPORTED_ALGORITHM, SIGNATURE_ALGORITHM);
        } catch (Exception e) {
            // Signature structurellement inexploitable : ne jamais conclure a VALID.
            return withStatus(data, SignatureStatus.INVALID,
                "Signature illisible : " + e.getClass().getSimpleName());
        }
    }

    /**
     * Le certificat doit etre signe par une des autorites racines declarees pour
     * cette autorite. La verification porte sur la signature du certificat, pas
     * sur une simple comparaison de noms.
     */
    private boolean isChainTrusted(X509Certificate cert, String authorityId) {
        List<X509Certificate> anchors = certificateProvider.trustAnchors(authorityId);
        if (anchors == null || anchors.isEmpty()) return false;

        for (X509Certificate anchor : anchors) {
            try {
                cert.verify(anchor.getPublicKey());
                return true;
            } catch (Exception e) {
                // Cette racine n'a pas signe le certificat : on essaie la suivante.
            }
        }
        return false;
    }

    private boolean isRevoked(X509Certificate cert, String authorityId) {
        List<String> revoked = certificateProvider.revokedSerials(authorityId);
        if (revoked == null || revoked.isEmpty()) return false;
        String serial = cert.getSerialNumber().toString(16);
        return revoked.stream().anyMatch(s -> s != null && s.equalsIgnoreCase(serial));
    }

    /**
     * Compare des jours et non des instants : le 2D-DOC ne porte qu'une date de
     * signature, sans heure. Ramener cette date a minuit la placerait avant le
     * debut de validite d'un certificat emis plus tard dans la journee, et
     * rejetterait a tort tout document signe le jour de l'emission.
     *
     * @return null si le certificat couvre cette date, le statut sinon
     */
    private SignatureStatus checkValidityAt(X509Certificate cert, LocalDate signatureDate) {
        // Sans date exploitable, on se rabat sur aujourd'hui : moins precis, mais
        // le controle n'est pas saute.
        LocalDate reference = signatureDate != null ? signatureDate : LocalDate.now();
        LocalDate notBefore = toLocalDate(cert.getNotBefore());
        LocalDate notAfter = toLocalDate(cert.getNotAfter());

        boolean couvert = !reference.isBefore(notBefore) && !reference.isAfter(notAfter);
        return couvert ? null : SignatureStatus.CERTIFICATE_EXPIRED;
    }

    private LocalDate toLocalDate(Date date) {
        return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }

    /**
     * Convertit une signature ECDSA brute r||s en structure DER.
     *
     * La specification 2D-DOC encode r et s concatenes sur 32 octets chacun,
     * tandis que java.security.Signature attend
     * SEQUENCE { INTEGER r, INTEGER s }.
     */
    static byte[] toDer(byte[] raw) {
        BigInteger r = new BigInteger(1, java.util.Arrays.copyOfRange(raw, 0, P256_COMPONENT_BYTES));
        BigInteger s = new BigInteger(1, java.util.Arrays.copyOfRange(
            raw, P256_COMPONENT_BYTES, 2 * P256_COMPONENT_BYTES));

        byte[] rBytes = r.toByteArray();
        byte[] sBytes = s.toByteArray();

        int length = 2 + rBytes.length + 2 + sBytes.length;
        byte[] der = new byte[2 + length];
        int i = 0;
        der[i++] = 0x30;                    // SEQUENCE
        der[i++] = (byte) length;
        der[i++] = 0x02;                    // INTEGER
        der[i++] = (byte) rBytes.length;
        System.arraycopy(rBytes, 0, der, i, rBytes.length);
        i += rBytes.length;
        der[i++] = 0x02;                    // INTEGER
        der[i++] = (byte) sBytes.length;
        System.arraycopy(sBytes, 0, der, i, sBytes.length);
        return der;
    }

    private TwoDDocData withStatus(TwoDDocData data, SignatureStatus status, String detail) {
        if (data == null) {
            return TwoDDocData.builder()
                .detected(false).signatureStatus(status).signatureDetail(detail).build();
        }
        return TwoDDocData.builder()
            .detected(data.isDetected())
            .rawData(data.getRawData())
            .version(data.getVersion())
            .authorityId(data.getAuthorityId())
            .certificateId(data.getCertificateId())
            .documentType(data.getDocumentType())
            .emissionDate(data.getEmissionDate())
            .signatureDate(data.getSignatureDate())
            .fields(data.getFields())
            .signedPayload(data.getSignedPayload())
            .signature(data.getSignature())
            .signatureStatus(status)
            .signatureDetail(detail)
            .build();
    }
}
