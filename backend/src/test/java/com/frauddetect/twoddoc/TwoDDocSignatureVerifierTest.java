package com.frauddetect.twoddoc;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifie la mecanique cryptographique avec une chaine de test generee hors
 * ligne : autorite racine, certificat de signature signe par elle, et cle privee
 * correspondante.
 *
 * Les signatures sont produites ici avec la vraie cle privee et verifiees par le
 * code de production : aucune etape n'est simulee. Un test ne peut passer que si
 * la verification fonctionne reellement.
 */
class TwoDDocSignatureVerifierTest {

    private static final String KEYSTORE_PASSWORD = "changeit";
    private static final String CERTIFICATES = "classpath:2ddoc/certificates/";

    private static PrivateKey signingKey;
    private static X509Certificate signingCertificate;

    private final TwoDDocParser parser = new TwoDDocParser();

    @BeforeAll
    static void loadTestChain() throws Exception {
        KeyStore leaf = KeyStore.getInstance("PKCS12");
        try (InputStream in = open("classpath:2ddoc/leaf.p12")) {
            leaf.load(in, KEYSTORE_PASSWORD.toCharArray());
        }
        signingKey = (PrivateKey) leaf.getKey("fpe6", KEYSTORE_PASSWORD.toCharArray());

        signingCertificate = (X509Certificate) java.security.cert.CertificateFactory
            .getInstance("X.509")
            .generateCertificate(open(CERTIFICATES + "FR06_FPE6.cer"));

        assertThat(signingKey).as("cle privee de test").isNotNull();
        assertThat(signingCertificate).as("certificat de test").isNotNull();
    }

    private static InputStream open(String location) throws Exception {
        return new DefaultResourceLoader().getResource(location).getInputStream();
    }

    // ── Outils de fabrication d'un 2D-DOC de test ─────────────────────────────

    /** En-tete FR06/FPE6, date de signature du jour, type 28. */
    private String header() {
        int days = (int) java.time.temporal.ChronoUnit.DAYS.between(
            LocalDate.of(2000, 1, 1), LocalDate.now());
        return "DC04FR06FPE6FFFF" + String.format("%04X", days) + "28";
    }

    private String dataZone() {
        return "44" + "MARTIN JEAN" + TwoDDocParser.GS
             + "49" + "31560" + TwoDDocParser.GS
             + "4V" + "0200";
    }

    /** Signe reellement le payload, puis assemble le 2D-DOC complet. */
    private String buildSigned(String header, String data) throws Exception {
        String payload = header + data;
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(signingKey);
        signer.update(payload.getBytes(StandardCharsets.UTF_8));
        byte[] der = signer.sign();
        return payload + TwoDDocParser.US + base32(rawFromDer(der));
    }

    /** DER (SEQUENCE de deux INTEGER) vers r||s sur 32 octets chacun. */
    private static byte[] rawFromDer(byte[] der) {
        int i = 3;
        int rLen = der[i++] & 0xFF;
        byte[] r = java.util.Arrays.copyOfRange(der, i, i + rLen);
        i += rLen + 1;
        int sLen = der[i++] & 0xFF;
        byte[] s = java.util.Arrays.copyOfRange(der, i, i + sLen);

        byte[] raw = new byte[64];
        copyRightAligned(r, raw, 0);
        copyRightAligned(s, raw, 32);
        return raw;
    }

    private static void copyRightAligned(byte[] src, byte[] dest, int offset) {
        int len = Math.min(src.length, 32);
        System.arraycopy(src, src.length - len, dest, offset + 32 - len, len);
    }

    private static String base32(byte[] data) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        StringBuilder sb = new StringBuilder();
        int buffer = 0, bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                bits -= 5;
                sb.append(alphabet.charAt((buffer >> bits) & 0x1F));
            }
        }
        if (bits > 0) sb.append(alphabet.charAt((buffer << (5 - bits)) & 0x1F));
        return sb.toString();
    }

    private TwoDDocSignatureVerifier verifierWith(TwoDDocCertificateProvider provider) {
        return new TwoDDocSignatureVerifier(provider);
    }

    private TwoDDocCertificateProvider localProvider() {
        return new LocalCertificateProvider(new DefaultResourceLoader(), CERTIFICATES);
    }

    // ── Signature valide ──────────────────────────────────────────────────────

    @Test
    void signatureValide_estAcceptee() throws Exception {
        String raw = buildSigned(header(), dataZone());

        TwoDDocData result = verifierWith(localProvider()).verify(parser.parse(raw));

        assertThat(result.getSignatureStatus()).isEqualTo(SignatureStatus.VALID);
        assertThat(result.isSignatureTrusted()).isTrue();
    }

    @Test
    void signatureValide_enteteCorrectementLu() throws Exception {
        TwoDDocData result = parser.parse(buildSigned(header(), dataZone()));

        assertThat(result.getAuthorityId()).isEqualTo("FR06");
        assertThat(result.getCertificateId()).isEqualTo("FPE6");
        assertThat(result.getDocumentType()).isEqualTo("28");
        assertThat(result.getVersion()).isEqualTo("04");
        assertThat(result.getFields()).containsKeys("44", "49", "4V");
    }

    // ── Alteration des donnees signees ────────────────────────────────────────

    @Test
    void unCaractereModifieDansLesDonnees_invalideLaSignature() throws Exception {
        String raw = buildSigned(header(), dataZone());
        // 31560 devient 91560 : la signature ne couvre plus les donnees presentees.
        String altere = raw.replace("31560", "91560");
        assertThat(altere).isNotEqualTo(raw);

        TwoDDocData result = verifierWith(localProvider()).verify(parser.parse(altere));

        assertThat(result.getSignatureStatus()).isEqualTo(SignatureStatus.INVALID);
    }

    @Test
    void enteteModifie_invalideLaSignature() throws Exception {
        // Le type de document fait partie du payload signe.
            String raw = buildSigned(header(), dataZone());
        String altere = raw.substring(0, 20) + "99" + raw.substring(22);

        TwoDDocData result = verifierWith(localProvider()).verify(parser.parse(altere));

        assertThat(result.getSignatureStatus()).isEqualTo(SignatureStatus.INVALID);
    }

    @Test
    void signatureTronquee_estRejetee() throws Exception {
        String raw = buildSigned(header(), dataZone());
        int us = raw.indexOf(TwoDDocParser.US);
        String altere = raw.substring(0, us + 1) + raw.substring(us + 1, us + 40);

        TwoDDocData result = verifierWith(localProvider()).verify(parser.parse(altere));

        assertThat(result.getSignatureStatus())
            .isIn(SignatureStatus.UNSUPPORTED_ALGORITHM, SignatureStatus.INVALID);
    }

    // ── Problemes de certificat ───────────────────────────────────────────────

    @Test
    void certificatInconnu_nEstPasValide() throws Exception {
        // Identifiant de certificat absent des ressources.
        String raw = buildSigned(header().replace("FPE6", "ZZZZ"), dataZone());

        TwoDDocData result = verifierWith(localProvider()).verify(parser.parse(raw));

        assertThat(result.getSignatureStatus()).isEqualTo(SignatureStatus.CERTIFICATE_UNKNOWN);
    }

    @Test
    void certificatRevoque_nEstPasValide() throws Exception {
        String serial = signingCertificate.getSerialNumber().toString(16);
        TwoDDocCertificateProvider provider = new LocalCertificateProvider(
            new DefaultResourceLoader(), CERTIFICATES, List.of(serial));

        TwoDDocData result = verifierWith(provider).verify(
            parser.parse(buildSigned(header(), dataZone())));

        assertThat(result.getSignatureStatus()).isEqualTo(SignatureStatus.CERTIFICATE_REVOKED);
    }

    @Test
    void chaineDeConfianceAbsente_nEstPasValide() throws Exception {
        // Fournit le certificat de signature mais aucune autorite racine.
        TwoDDocCertificateProvider sansAncre = new TwoDDocCertificateProvider() {
            @Override
            public Optional<X509Certificate> find(String a, String c) {
                return Optional.of(signingCertificate);
            }

            @Override
            public List<X509Certificate> trustAnchors(String authorityId) {
                return List.of();
            }
        };

        TwoDDocData result = verifierWith(sansAncre).verify(
            parser.parse(buildSigned(header(), dataZone())));

        assertThat(result.getSignatureStatus()).isEqualTo(SignatureStatus.TRUST_CHAIN_INVALID);
    }

    @Test
    void certificatSigneParUneAutreAutorite_nEstPasValide() throws Exception {
        // L'autorite racine proposee est le certificat feuille lui-meme : il n'a
        // pas signe le certificat de signature.
        TwoDDocCertificateProvider mauvaiseAncre = new TwoDDocCertificateProvider() {
            @Override
            public Optional<X509Certificate> find(String a, String c) {
                return Optional.of(signingCertificate);
            }

            @Override
            public List<X509Certificate> trustAnchors(String authorityId) {
                return List.of(signingCertificate);
            }
        };

        TwoDDocData result = verifierWith(mauvaiseAncre).verify(
            parser.parse(buildSigned(header(), dataZone())));

        assertThat(result.getSignatureStatus()).isEqualTo(SignatureStatus.TRUST_CHAIN_INVALID);
    }

    @Test
    void sourceIndisponible_donneTslUnavailable() throws Exception {
        TwoDDocCertificateProvider indisponible = new TwoDDocCertificateProvider() {
            @Override
            public Optional<X509Certificate> find(String a, String c)
                throws CertificateSourceUnavailableException {
                throw new CertificateSourceUnavailableException("réseau indisponible");
            }

            @Override
            public List<X509Certificate> trustAnchors(String authorityId) {
                return List.of();
            }
        };

        TwoDDocData result = verifierWith(indisponible).verify(
            parser.parse(buildSigned(header(), dataZone())));

        assertThat(result.getSignatureStatus()).isEqualTo(SignatureStatus.TSL_UNAVAILABLE);
    }

    @Test
    void certificatExpireALaDateDeSignature_nEstPasValide() throws Exception {
        // Signature datee de 2001, anterieure a l'emission du certificat de test.
        int days2001 = (int) java.time.temporal.ChronoUnit.DAYS.between(
            LocalDate.of(2000, 1, 1), LocalDate.of(2001, 1, 1));
        String vieilEntete = "DC04FR06FPE6FFFF" + String.format("%04X", days2001) + "28";

        TwoDDocData result = verifierWith(localProvider())
            .verify(parser.parse(buildSigned(vieilEntete, dataZone())));

        assertThat(result.getSignatureStatus()).isEqualTo(SignatureStatus.CERTIFICATE_EXPIRED);
    }

    // ── Structure non conforme ────────────────────────────────────────────────

    @Test
    void marqueurAbsent_donneMalformed() {
        TwoDDocData result = parser.parse("XX04FR06FPE6FFFF25DC28donnees");

        assertThat(result.getSignatureStatus()).isEqualTo(SignatureStatus.MALFORMED);
    }

    @Test
    void separateurDeSignatureAbsent_donneMalformed() {
        TwoDDocData result = parser.parse("DC04FR06FPE6FFFF25DC28" + "44MARTIN");

        assertThat(result.getSignatureStatus()).isEqualTo(SignatureStatus.MALFORMED);
    }

    @Test
    void signatureNonBase32_donneMalformed() {
        TwoDDocData result = parser.parse(
            "DC04FR06FPE6FFFF25DC28" + "44MARTIN" + TwoDDocParser.US + "!!!!");

        assertThat(result.getSignatureStatus()).isEqualTo(SignatureStatus.MALFORMED);
    }

    @Test
    void aucunCode_donneNotVerified() {
        TwoDDocData result = verifierWith(localProvider()).verify(
            TwoDDocData.builder().detected(false).fields(Map.of()).build());

        assertThat(result.getSignatureStatus()).isEqualTo(SignatureStatus.NOT_VERIFIED);
    }
}
