package com.frauddetect.twoddoc;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Valide la navigation dans la liste de confiance, sur une TSL de test
 * reproduisant la structure reelle publiee par l'ANTS.
 *
 * Le client HTTP est remplace par un double servant des reponses en memoire :
 * les tests restent reproductibles hors reseau, sans dependre de la
 * disponibilite d'un service externe.
 */
class AntsTslCertificateProviderTest {

    private static final String TSL_URL = "http://exemple.test/tsl_signed.xml";
    private static final String DIRECTORY_URL = "http://exemple.test/annuaire-fr06.der";

    private String tslContent;
    private byte[] signingCertificateDer;

    @BeforeEach
    void loadResources() throws Exception {
        DefaultResourceLoader loader = new DefaultResourceLoader();
        tslContent = new String(loader.getResource("classpath:2ddoc/tsl_signed.xml")
            .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        signingCertificateDer = loader.getResource("classpath:2ddoc/certificates/FR06_FPE6.cer")
            .getInputStream().readAllBytes();
    }

    /** Client HTTP servant des reponses fixees, ou echouant si l'URL est inconnue. */
    private static class StubHttpClient extends HttpClient {

        private final Map<String, byte[]> responses = new HashMap<>();
        private final Map<String, Integer> statuses = new HashMap<>();

        void serve(String url, byte[] body) {
            responses.put(url, body);
            statuses.put(url, 200);
        }

        void fail(String url, int status) {
            responses.put(url, new byte[0]);
            statuses.put(url, status);
        }

        @SuppressWarnings("unchecked")
        @Override
        public <T> HttpResponse<T> send(HttpRequest request,
                                        HttpResponse.BodyHandler<T> handler) throws IOException {
            String url = request.uri().toString();
            if (!responses.containsKey(url)) {
                throw new IOException("URL non servie par le double : " + url);
            }
            return (HttpResponse<T>) new StubResponse(
                statuses.get(url), responses.get(url), request);
        }

        // Methodes non utilisees par le fournisseur.
        @Override public Optional<java.net.CookieHandler> cookieHandler() { return Optional.empty(); }
        @Override public Optional<java.time.Duration> connectTimeout() { return Optional.empty(); }
        @Override public Redirect followRedirects() { return Redirect.NEVER; }
        @Override public Optional<java.net.ProxySelector> proxy() { return Optional.empty(); }
        @Override public javax.net.ssl.SSLContext sslContext() { return null; }
        @Override public javax.net.ssl.SSLParameters sslParameters() { return null; }
        @Override public Optional<java.net.Authenticator> authenticator() { return Optional.empty(); }
        @Override public Version version() { return Version.HTTP_1_1; }
        @Override public Optional<java.util.concurrent.Executor> executor() { return Optional.empty(); }

        @Override
        public <T> java.util.concurrent.CompletableFuture<HttpResponse<T>> sendAsync(
            HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> java.util.concurrent.CompletableFuture<HttpResponse<T>> sendAsync(
            HttpRequest request, HttpResponse.BodyHandler<T> handler,
            HttpResponse.PushPromiseHandler<T> pushHandler) {
            throw new UnsupportedOperationException();
        }
    }

    private record StubResponse(int status, byte[] body, HttpRequest request)
        implements HttpResponse<byte[]> {

        @Override public int statusCode() { return status; }
        @Override public HttpRequest request() { return request; }
        @Override public Optional<HttpResponse<byte[]>> previousResponse() { return Optional.empty(); }
        @Override public java.net.http.HttpHeaders headers() {
            return java.net.http.HttpHeaders.of(Map.of(), (a, b) -> true);
        }
        @Override public byte[] body() { return body; }
        @Override public Optional<javax.net.ssl.SSLSession> sslSession() { return Optional.empty(); }
        @Override public java.net.URI uri() { return request.uri(); }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
    }

    private AntsTslCertificateProvider providerWith(StubHttpClient client) {
        return new AntsTslCertificateProvider(TSL_URL, client);
    }

    // ── Ancre de confiance ────────────────────────────────────────────────────

    @Test
    void ancreDeConfianceExtraiteDuServiceEnVigueur() {
        StubHttpClient client = new StubHttpClient();
        client.serve(TSL_URL, tslContent.getBytes(StandardCharsets.UTF_8));

        List<X509Certificate> anchors = providerWith(client).trustAnchors("FR06");

        assertThat(anchors).hasSize(1);
        assertThat(anchors.get(0).getSubjectX500Principal().getName()).contains("Root CA");
    }

    @Test
    void serviceRetire_neFournitPasDAncre() {
        // FR99 est declare avec le statut withdrawn dans la TSL de test.
        StubHttpClient client = new StubHttpClient();
        client.serve(TSL_URL, tslContent.getBytes(StandardCharsets.UTF_8));

        assertThat(providerWith(client).trustAnchors("FR99")).isEmpty();
    }

    @Test
    void autoriteAbsente_neFournitPasDAncre() {
        StubHttpClient client = new StubHttpClient();
        client.serve(TSL_URL, tslContent.getBytes(StandardCharsets.UTF_8));

        assertThat(providerWith(client).trustAnchors("FR42")).isEmpty();
    }

    // ── Resolution du certificat de signature ─────────────────────────────────

    @Test
    void certificatTrouveDansLAnnuaireDeLAutorite() throws Exception {
        StubHttpClient client = new StubHttpClient();
        client.serve(TSL_URL, tslContent.getBytes(StandardCharsets.UTF_8));
        client.serve(DIRECTORY_URL, signingCertificateDer);

        Optional<X509Certificate> found = providerWith(client).find("FR06", "FPE6");

        assertThat(found).isPresent();
        assertThat(found.get().getSubjectX500Principal().getName()).contains("FPE6");
    }

    @Test
    void identifiantNeCorrespondantPas_estIgnore() throws Exception {
        StubHttpClient client = new StubHttpClient();
        client.serve(TSL_URL, tslContent.getBytes(StandardCharsets.UTF_8));
        client.serve(DIRECTORY_URL, signingCertificateDer);

        // L'annuaire ne contient que FPE6 : un autre identifiant ne doit pas
        // etre satisfait par le premier certificat rencontre.
        assertThat(providerWith(client).find("FR06", "ZZZZ")).isEmpty();
    }

    @Test
    void autoriteAbsenteDeLaTsl_donneCertificatIntrouvable() throws Exception {
        StubHttpClient client = new StubHttpClient();
        client.serve(TSL_URL, tslContent.getBytes(StandardCharsets.UTF_8));

        assertThat(providerWith(client).find("FR42", "FPE6")).isEmpty();
    }

    // ── Indisponibilite ───────────────────────────────────────────────────────

    @Test
    void tslInjoignable_leveUneIndisponibilite() {
        StubHttpClient client = new StubHttpClient();
        client.fail(TSL_URL, 503);

        assertThatThrownBy(() -> providerWith(client).find("FR06", "FPE6"))
            .isInstanceOf(TwoDDocCertificateProvider.CertificateSourceUnavailableException.class);
    }

    @Test
    void annuaireInjoignable_leveUneIndisponibilite() {
        StubHttpClient client = new StubHttpClient();
        client.serve(TSL_URL, tslContent.getBytes(StandardCharsets.UTF_8));
        client.fail(DIRECTORY_URL, 404);

        assertThatThrownBy(() -> providerWith(client).find("FR06", "FPE6"))
            .isInstanceOf(TwoDDocCertificateProvider.CertificateSourceUnavailableException.class);
    }

    @Test
    void urlNonConfiguree_leveUneIndisponibilite() {
        assertThatThrownBy(() ->
            new AntsTslCertificateProvider("", new StubHttpClient()).find("FR06", "FPE6"))
            .isInstanceOf(TwoDDocCertificateProvider.CertificateSourceUnavailableException.class);
    }

    @Test
    void tslIllisible_leveUneIndisponibilite() {
        StubHttpClient client = new StubHttpClient();
        client.serve(TSL_URL, "ceci n'est pas du XML".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> providerWith(client).find("FR06", "FPE6"))
            .isInstanceOf(TwoDDocCertificateProvider.CertificateSourceUnavailableException.class);
    }

    // ── Annuaire en conteneur MIME ────────────────────────────────────────────

    /**
     * Reproduit la forme publiee par FR06 : parties application/pkix-cert
     * separees par une frontiere, chacune portant un certificat en DER.
     */
    private byte[] mimeDirectory(byte[]... certificates) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (byte[] der : certificates) {
            out.write("\r\n--End\r\n".getBytes(StandardCharsets.US_ASCII));
            out.write("Content-type: application/pkix-cert\r\n\r\n"
                .getBytes(StandardCharsets.US_ASCII));
            out.write(der);
        }
        out.write("\r\n--End--\r\n".getBytes(StandardCharsets.US_ASCII));
        return out.toByteArray();
    }

    @Test
    void certificatTrouveDansUnAnnuaireMime() throws Exception {
        StubHttpClient client = new StubHttpClient();
        client.serve(TSL_URL, tslContent.getBytes(StandardCharsets.UTF_8));
        client.serve(DIRECTORY_URL, mimeDirectory(signingCertificateDer));

        Optional<X509Certificate> found = providerWith(client).find("FR06", "FPE6");

        assertThat(found).isPresent();
        assertThat(found.get().getSubjectX500Principal().getName()).contains("FPE6");
    }

    @Test
    void annuaireMimeAPlusieursCertificats_leBonEstRetenu() throws Exception {
        byte[] autreCertificat = new DefaultResourceLoader()
            .getResource("classpath:2ddoc/certificates/FR06_CA.cer")
            .getInputStream().readAllBytes();

        StubHttpClient client = new StubHttpClient();
        client.serve(TSL_URL, tslContent.getBytes(StandardCharsets.UTF_8));
        // L'autorite racine precede le certificat de signature : c'est bien le
        // second qui doit etre retenu, et non le premier rencontre.
        client.serve(DIRECTORY_URL, mimeDirectory(autreCertificat, signingCertificateDer));

        Optional<X509Certificate> found = providerWith(client).find("FR06", "FPE6");

        assertThat(found).isPresent();
        assertThat(found.get().getSubjectX500Principal().getName()).contains("FPE6");
    }

    @Test
    void annuaireMimeSansLIdentifiant_donneIntrouvable() throws Exception {
        StubHttpClient client = new StubHttpClient();
        client.serve(TSL_URL, tslContent.getBytes(StandardCharsets.UTF_8));
        client.serve(DIRECTORY_URL, mimeDirectory(signingCertificateDer));

        assertThat(providerWith(client).find("FR06", "ZZZZ")).isEmpty();
    }

    @Test
    void annuaireIllisible_donneIntrouvable() throws Exception {
        StubHttpClient client = new StubHttpClient();
        client.serve(TSL_URL, tslContent.getBytes(StandardCharsets.UTF_8));
        client.serve(DIRECTORY_URL, "aucun certificat ici".getBytes(StandardCharsets.UTF_8));

        assertThat(providerWith(client).find("FR06", "FPE6")).isEmpty();
    }
}
