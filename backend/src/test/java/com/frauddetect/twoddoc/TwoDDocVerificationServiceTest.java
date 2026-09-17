package com.frauddetect.twoddoc;

import com.frauddetect.model.AnalysisResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class TwoDDocVerificationServiceTest {

    private TwoDDocVerificationService service;

    @BeforeEach
    void setUp() {
        TwoDDocParser parser = new TwoDDocParser();
        // Source jamais disponible : isole le rapprochement de la cryptographie.
        TwoDDocCertificateProvider aucun = new TwoDDocCertificateProvider() {
            @Override
            public Optional<X509Certificate> find(String a, String c) {
                return Optional.empty();
            }

            @Override
            public List<X509Certificate> trustAnchors(String authorityId) {
                return List.of();
            }
        };
        service = new TwoDDocVerificationService(
            new TwoDDocDecoder(), parser, new TwoDDocSignatureVerifier(aucun));
    }

    private TwoDDocData avecChamps(Map<String, String> fields) {
        return TwoDDocData.builder()
            .detected(true)
            .authorityId("FR06")
            .certificateId("FPE6")
            .fields(fields)
            .signatureStatus(SignatureStatus.CERTIFICATE_UNKNOWN)
            .build();
    }

    // ── Rapprochement par recherche de valeur ─────────────────────────────────

    @Test
    void valeurPresenteDansLesChampsSignes_donneMatch() {
        TwoDDocData data = avecChamps(Map.of("49", "31560", "44", "MARTIN JEAN"));

        Map<String, String> duPdf = new LinkedHashMap<>();
        duPdf.put("revenu fiscal de référence", "31 560");
        duPdf.put("nom", "Martin");

        Map<String, TwoDDocVerificationService.Comparison> result = service.compare(data, duPdf);

        assertThat(result).containsEntry("revenu fiscal de référence",
            TwoDDocVerificationService.Comparison.MATCH);
        assertThat(result).containsEntry("nom",
            TwoDDocVerificationService.Comparison.MATCH);
    }

    @Test
    void valeurAbsenteDesChampsSignes_donneMismatch() {
        // Le PDF annonce 65 000 quand le cachet signe porte 31 560.
        TwoDDocData data = avecChamps(Map.of("49", "31560"));

        Map<String, TwoDDocVerificationService.Comparison> result =
            service.compare(data, Map.of("revenu fiscal de référence", "65 000"));

        assertThat(result).containsEntry("revenu fiscal de référence",
            TwoDDocVerificationService.Comparison.MISMATCH);
    }

    @Test
    void comparaisonInsensibleAuxAccentsEtSeparateurs() {
        TwoDDocData data = avecChamps(Map.of("44", "HELENE DUPRE"));

        Map<String, TwoDDocVerificationService.Comparison> result =
            service.compare(data, Map.of("nom", "Hélène  Dupré"));

        assertThat(result).containsEntry("nom", TwoDDocVerificationService.Comparison.MATCH);
    }

    @Test
    void valeurAbsenteDuPdf_nEstPasComparable() {
        TwoDDocData data = avecChamps(Map.of("49", "31560"));

        Map<String, String> duPdf = new LinkedHashMap<>();
        duPdf.put("nombre de parts", null);

        assertThat(service.compare(data, duPdf))
            .containsEntry("nombre de parts",
                TwoDDocVerificationService.Comparison.NOT_COMPARABLE);
    }

    @Test
    void sansCode_rienNEstComparable() {
        TwoDDocData data = TwoDDocData.builder().detected(false).fields(Map.of()).build();

        assertThat(service.compare(data, Map.of("rfr", "31560")))
            .containsEntry("rfr", TwoDDocVerificationService.Comparison.NOT_COMPARABLE);
    }

    // ── Restitution en controles ──────────────────────────────────────────────

    @Test
    void absenceDeCode_estUnAvertissementPasUnEchec() {
        // Les avis telecharges en ligne n'en portent pas toujours : ce n'est pas
        // une preuve de falsification.
        TwoDDocData data = TwoDDocData.builder().detected(false).fields(Map.of()).build();

        List<AnalysisResult.Check> checks = service.toChecks(data, Map.of());

        assertThat(checks).hasSize(1);
        assertThat(checks.get(0).getStatus()).isEqualTo("WARNING");
    }

    @Test
    void signatureValide_donneUnControleOk() {
        TwoDDocData data = TwoDDocData.builder()
            .detected(true).authorityId("FR06").fields(Map.of())
            .signatureStatus(SignatureStatus.VALID).build();

        List<AnalysisResult.Check> checks = service.toChecks(data, Map.of());

        assertThat(checks.get(0).getStatus()).isEqualTo("OK");
        assertThat(checks.get(0).getDetail()).contains("FR06");
    }

    @Test
    void signatureInvalide_donneUnEchec() {
        TwoDDocData data = TwoDDocData.builder()
            .detected(true).fields(Map.of())
            .signatureStatus(SignatureStatus.INVALID).build();

        assertThat(service.toChecks(data, Map.of()).get(0).getStatus()).isEqualTo("FAILED");
    }

    @Test
    void certificatInconnu_resteUnAvertissement() {
        // L'authenticite n'est pas etablie, mais rien ne prouve une falsification.
        TwoDDocData data = TwoDDocData.builder()
            .detected(true).fields(Map.of())
            .signatureStatus(SignatureStatus.CERTIFICATE_UNKNOWN).build();

        assertThat(service.toChecks(data, Map.of()).get(0).getStatus()).isEqualTo("WARNING");
    }

    @Test
    void divergenceDeValeur_donneUnEchec() {
        TwoDDocData data = avecChamps(Map.of("49", "31560"));
        Map<String, TwoDDocVerificationService.Comparison> comparaisons =
            service.compare(data, Map.of("revenu fiscal de référence", "65000"));

        List<AnalysisResult.Check> checks = service.toChecks(data, comparaisons);

        assertThat(checks).anyMatch(c -> "FAILED".equals(c.getStatus())
            && c.getLabel().contains("revenu fiscal"));
    }

    // ── Cache de certificats ──────────────────────────────────────────────────

    @Test
    void cache_nInterrogeLaSourceQuUneFois() throws Exception {
        AtomicInteger appels = new AtomicInteger();
        TwoDDocCertificateProvider compteur = new TwoDDocCertificateProvider() {
            @Override
            public Optional<X509Certificate> find(String a, String c) {
                appels.incrementAndGet();
                return Optional.empty();
            }

            @Override
            public List<X509Certificate> trustAnchors(String authorityId) {
                return List.of();
            }
        };
        CachingCertificateProvider cache =
            new CachingCertificateProvider(compteur, Duration.ofHours(1));

        cache.find("FR06", "FPE6");
        cache.find("FR06", "FPE6");
        cache.find("FR06", "FPE6");

        assertThat(appels.get()).isEqualTo(1);
    }

    @Test
    void cache_sertDeSecoursQuandLaSourceTombe() throws Exception {
        AtomicInteger appels = new AtomicInteger();
        TwoDDocCertificateProvider intermittent = new TwoDDocCertificateProvider() {
            @Override
            public Optional<X509Certificate> find(String a, String c)
                throws CertificateSourceUnavailableException {
                if (appels.getAndIncrement() == 0) return Optional.empty();
                throw new CertificateSourceUnavailableException("coupure");
            }

            @Override
            public List<X509Certificate> trustAnchors(String authorityId) {
                return List.of();
            }
        };
        // TTL nul : la seconde resolution retourne vers la source, qui echoue,
        // et doit alors se rabattre sur l'entree memorisee.
        CachingCertificateProvider cache =
            new CachingCertificateProvider(intermittent, Duration.ZERO);

        cache.find("FR06", "FPE6");
        assertThat(cache.find("FR06", "FPE6")).isEmpty();
        assertThat(appels.get()).isEqualTo(2);
    }

    @Test
    void cache_sansSecours_propageLIndisponibilite() {
        TwoDDocCertificateProvider indisponible = new TwoDDocCertificateProvider() {
            @Override
            public Optional<X509Certificate> find(String a, String c)
                throws CertificateSourceUnavailableException {
                throw new CertificateSourceUnavailableException("coupure");
            }

            @Override
            public List<X509Certificate> trustAnchors(String authorityId) {
                return List.of();
            }
        };
        CachingCertificateProvider cache =
            new CachingCertificateProvider(indisponible, Duration.ofHours(1));

        org.junit.jupiter.api.Assertions.assertThrows(
            TwoDDocCertificateProvider.CertificateSourceUnavailableException.class,
            () -> cache.find("FR06", "FPE6"));
    }
}
