package com.frauddetect.twoddoc;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * Resout les certificats de signature via la liste de confiance de l'ANTS.
 *
 * Chaine suivie, conformement au processus officiel :
 *
 *   tsl_signed.xml
 *     -> entree de service correspondant a authorityId
 *        -> certificat de l'autorite, porte par la TSL
 *        -> point de publication de son annuaire
 *           -> certificat identifie par certificateId
 *
 * Aucune cle publique n'est codee en dur et aucune URL de certificat n'est
 * devinee : la TSL reste la source de verite. Une indisponibilite leve
 * CertificateSourceUnavailableException, distincte d'un certificat introuvable,
 * afin que l'appelant puisse distinguer TSL_UNAVAILABLE de
 * CERTIFICATE_UNKNOWN.
 *
 * Limite assumee : la signature de la TSL elle-meme n'est pas verifiee ici,
 * faute d'ancre de confiance ANTS embarquee. Le transport HTTPS authentifie le
 * domaine de publication, ce qui est insuffisant pour un usage a fort enjeu ;
 * embarquer le certificat racine de l'ANTS et valider la signature XML de la
 * TSL reste a faire.
 */
public class AntsTslCertificateProvider implements TwoDDocCertificateProvider {

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(15);

    // Elements de la TSL, au sens ETSI TS 119 612, tels que publies par l'ANTS.
    // L'identifiant d'autorite et l'URI de publication sont portes par le
    // fournisseur, le certificat et le statut par chacun de ses services.
    private static final String TAG_PROVIDER = "TrustServiceProvider";
    private static final String TAG_TRADE_NAME = "TSPTradeName";
    private static final String TAG_INFORMATION_URI = "TSPInformationURI";
    private static final String TAG_SERVICE = "TSPService";
    private static final String TAG_SERVICE_NAME = "ServiceName";
    private static final String TAG_SERVICE_STATUS = "ServiceStatus";
    private static final String TAG_CERTIFICATE = "X509Certificate";
    private static final String TAG_URI = "URI";

    /** Statut ETSI d'un service en vigueur. */
    private static final String STATUS_GRANTED = "inaccord";

    private final String tslUrl;
    private final HttpClient httpClient;

    public AntsTslCertificateProvider(String tslUrl) {
        this(tslUrl, HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build());
    }

    AntsTslCertificateProvider(String tslUrl, HttpClient httpClient) {
        this.tslUrl = tslUrl;
        this.httpClient = httpClient;
    }

    @Override
    public Optional<X509Certificate> find(String authorityId, String certificateId)
        throws CertificateSourceUnavailableException {

        if (authorityId == null || certificateId == null) return Optional.empty();

        ServiceEntry entry = findService(authorityId);
        if (entry == null) return Optional.empty();
        if (entry.supplyPoint == null || entry.supplyPoint.isBlank()) {
            // L'autorite est connue mais ne publie pas d'annuaire exploitable.
            return Optional.empty();
        }
        return findInDirectory(entry.supplyPoint, certificateId);
    }

    @Override
    public List<X509Certificate> trustAnchors(String authorityId) {
        try {
            ServiceEntry entry = findService(authorityId);
            return entry == null || entry.certificate == null
                ? List.of() : List.of(entry.certificate);
        } catch (CertificateSourceUnavailableException e) {
            return List.of();
        }
    }

    /**
     * Entree de la TSL decrivant une autorite 2D-DOC.
     *
     * L'identifiant, "FR06" par exemple, est porte par TSPTradeName au niveau du
     * fournisseur ; il reapparait dans le nom de chacun de ses services. Le
     * certificat servant d'ancre de confiance et le statut du service sont
     * portes par le service lui-meme.
     */
    private ServiceEntry findService(String authorityId)
        throws CertificateSourceUnavailableException {

        Document tsl = fetchXml(tslUrl);
        NodeList providers = tsl.getElementsByTagNameNS("*", TAG_PROVIDER);

        for (int i = 0; i < providers.getLength(); i++) {
            Element provider = (Element) providers.item(i);
            if (!mentions(provider, TAG_TRADE_NAME, authorityId)
                && !mentions(provider, TAG_SERVICE_NAME, authorityId)) {
                continue;
            }

            String supplyPoint = firstUriOf(provider, TAG_INFORMATION_URI);

            // Parmi les services du fournisseur, seul un service en vigueur et
            // portant l'identifiant recherche fournit une ancre de confiance.
            NodeList services = provider.getElementsByTagNameNS("*", TAG_SERVICE);
            for (int j = 0; j < services.getLength(); j++) {
                Element service = (Element) services.item(j);
                if (!mentions(service, TAG_SERVICE_NAME, authorityId)) continue;

                String status = textOf(service, TAG_SERVICE_STATUS);
                if (status != null && !status.toLowerCase().contains(STATUS_GRANTED)) {
                    continue;
                }
                X509Certificate certificate = parseCertificate(textOf(service, TAG_CERTIFICATE));
                if (certificate != null) {
                    return new ServiceEntry(certificate, supplyPoint);
                }
            }
        }
        return null;
    }

    /** Vrai si l'un des elements localName contient la valeur cherchee. */
    private boolean mentions(Element parent, String localName, String value) {
        NodeList nodes = parent.getElementsByTagNameNS("*", localName);
        for (int i = 0; i < nodes.getLength(); i++) {
            String text = nodes.item(i).getTextContent();
            if (text != null && text.toUpperCase().contains(value.toUpperCase())) {
                return true;
            }
        }
        return false;
    }

    /** Premiere URI portee par l'element localName. */
    private String firstUriOf(Element parent, String localName) {
        NodeList holders = parent.getElementsByTagNameNS("*", localName);
        for (int i = 0; i < holders.getLength(); i++) {
            NodeList uris = ((Element) holders.item(i)).getElementsByTagNameNS("*", TAG_URI);
            if (uris.getLength() > 0) {
                String uri = uris.item(0).getTextContent();
                if (uri != null && !uri.isBlank()) return uri.trim();
            }
        }
        return null;
    }

    /**
     * Cherche le certificat portant l'identifiant dans l'annuaire de l'autorite.
     *
     * Trois formes rencontrees, toutes acceptees sans construire d'URL par
     * convention : un document XML listant des certificats en base64, un
     * conteneur MIME multipart de parties application/pkix-cert, ou un
     * certificat isole. FR06 publie la deuxieme forme.
     */
    private Optional<X509Certificate> findInDirectory(String supplyPoint, String certificateId)
        throws CertificateSourceUnavailableException {

        byte[] body = fetch(supplyPoint);

        // Annuaire XML : entrees en base64.
        try {
            Document directory = parseXml(body);
            NodeList certificates = directory.getElementsByTagNameNS("*", TAG_CERTIFICATE);
            for (int i = 0; i < certificates.getLength(); i++) {
                Node node = certificates.item(i);
                X509Certificate candidate = parseCertificate(node.getTextContent());
                if (candidate != null && matchesIdentifier(candidate, certificateId)) {
                    return Optional.of(candidate);
                }
            }
        } catch (Exception notXml) {
            // Ni XML ni exploitable comme tel : on tente les formes binaires.
        }

        for (X509Certificate candidate : parseDerSequence(body)) {
            if (matchesIdentifier(candidate, certificateId)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /**
     * Extrait tous les certificats d'un flux binaire, qu'ils soient isoles ou
     * separes par des en-tetes MIME.
     *
     * La fabrique X.509 est d'abord sollicitee telle quelle : elle lit un
     * certificat isole, un flux PEM et un conteneur PKCS#7. Si elle ne reconnait
     * rien, chaque debut de structure DER est repere dans les octets, une
     * SEQUENCE dont la longueur tient sur deux octets, soit 0x30 0x82 ; la
     * fabrique lit alors cette longueur et s'arrete a la fin du certificat.
     * L'extraction devient ainsi independante du format d'enveloppe, ce que
     * requiert le conteneur MIME multipart publie par FR06.
     */
    private List<X509Certificate> parseDerSequence(byte[] body) {
        List<X509Certificate> found = new ArrayList<>();
        CertificateFactory factory;
        try {
            factory = CertificateFactory.getInstance("X.509");
        } catch (Exception e) {
            return found;
        }

        // La fabrique lit d'elle-meme un certificat isole, un flux PEM et un
        // conteneur PKCS#7 : on la laisse essayer avant de balayer les octets.
        try (ByteArrayInputStream in = new ByteArrayInputStream(body)) {
            for (java.security.cert.Certificate certificate : factory.generateCertificates(in)) {
                if (certificate instanceof X509Certificate x509) found.add(x509);
            }
        } catch (Exception notDirectlyReadable) {
            // Enveloppe non reconnue : le balayage ci-dessous prend le relais.
        }
        if (!found.isEmpty()) return found;

        for (int i = 0; i + 1 < body.length; i++) {
            if ((body[i] & 0xFF) != 0x30 || (body[i + 1] & 0xFF) != 0x82) continue;
            try {
                X509Certificate certificate = (X509Certificate) factory.generateCertificate(
                    new ByteArrayInputStream(body, i, body.length - i));
                if (certificate != null) found.add(certificate);
            } catch (Exception notACertificate) {
                // Sequence DER sans rapport : on poursuit le balayage.
            }
        }
        return found;
    }

    /**
     * L'identifiant de certificat du 2D-DOC apparait dans le sujet du
     * certificat. La comparaison est volontairement large : la position exacte
     * n'est pas normalisee entre autorites.
     */
    private boolean matchesIdentifier(X509Certificate certificate, String certificateId) {
        String subject = certificate.getSubjectX500Principal().getName();
        return subject != null && subject.toUpperCase().contains(certificateId.toUpperCase());
    }

    private Document fetchXml(String url) throws CertificateSourceUnavailableException {
        try {
            return parseXml(fetch(url));
        } catch (CertificateSourceUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new CertificateSourceUnavailableException(
                "Liste de confiance illisible", e);
        }
    }

    private byte[] fetch(String url) throws CertificateSourceUnavailableException {
        if (url == null || url.isBlank()) {
            throw new CertificateSourceUnavailableException("Aucune URL de liste de confiance");
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(HTTP_TIMEOUT).GET().build();
            HttpResponse<byte[]> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new CertificateSourceUnavailableException(
                    "Source de confiance injoignable, code " + response.statusCode());
            }
            return response.body();
        } catch (CertificateSourceUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new CertificateSourceUnavailableException(
                "Source de confiance injoignable", e);
        }
    }

    private Document parseXml(byte[] content) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // Entites externes desactivees : le document provient du reseau.
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(content));
    }

    private String textOf(Element parent, String localName) {
        NodeList nodes = parent.getElementsByTagNameNS("*", localName);
        if (nodes.getLength() == 0) return null;
        String text = nodes.item(0).getTextContent();
        return text == null ? null : text.trim();
    }

    private X509Certificate parseCertificate(String base64) {
        if (base64 == null || base64.isBlank()) return null;
        try {
            byte[] der = Base64.getMimeDecoder().decode(base64.trim());
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            return (X509Certificate) factory.generateCertificate(new ByteArrayInputStream(der));
        } catch (Exception e) {
            return null;
        }
    }

    private record ServiceEntry(X509Certificate certificate, String supplyPoint) {
    }
}
