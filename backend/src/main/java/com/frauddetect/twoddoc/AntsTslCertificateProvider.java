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

    /** Elements de la TSL, au sens ETSI TS 119 612. */
    private static final String TAG_SERVICE_INFO = "TSPServiceInformation";
    private static final String TAG_SERVICE_NAME = "ServiceName";
    private static final String TAG_CERTIFICATE = "X509Certificate";
    private static final String TAG_SUPPLY_POINT = "ServiceSupplyPoint";

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

    /** Entree de la TSL decrivant une autorite 2D-DOC. */
    private ServiceEntry findService(String authorityId)
        throws CertificateSourceUnavailableException {

        Document tsl = fetchXml(tslUrl);
        NodeList services = tsl.getElementsByTagNameNS("*", TAG_SERVICE_INFO);

        for (int i = 0; i < services.getLength(); i++) {
            Element service = (Element) services.item(i);
            String name = textOf(service, TAG_SERVICE_NAME);
            if (name == null || !name.toUpperCase().contains(authorityId.toUpperCase())) {
                continue;
            }
            return new ServiceEntry(
                parseCertificate(textOf(service, TAG_CERTIFICATE)),
                textOf(service, TAG_SUPPLY_POINT));
        }
        return null;
    }

    /**
     * L'annuaire d'une autorite peut etre un document XML listant les
     * certificats, ou un certificat unique. Les deux formes sont acceptees, sans
     * construire d'URL par convention.
     */
    private Optional<X509Certificate> findInDirectory(String supplyPoint, String certificateId)
        throws CertificateSourceUnavailableException {

        byte[] body = fetch(supplyPoint);

        // Cas d'un annuaire XML : on cherche l'entree portant l'identifiant.
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
            // Non XML : tente une lecture directe en X.509 ci-dessous.
        }

        try (ByteArrayInputStream in = new ByteArrayInputStream(body)) {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            X509Certificate candidate = (X509Certificate) factory.generateCertificate(in);
            return matchesIdentifier(candidate, certificateId)
                ? Optional.of(candidate) : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
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
