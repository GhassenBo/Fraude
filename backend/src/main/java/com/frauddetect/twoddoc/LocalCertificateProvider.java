package com.frauddetect.twoddoc;

import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.InputStream;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Certificats lus depuis les ressources, selon la convention de nommage
 * {authorityId}_{certificateId}.cer, l'autorite racine etant
 * {authorityId}_CA.cer.
 *
 * Destine aux tests et aux environnements sans acces reseau : il rend la
 * verification cryptographique reproductible hors ligne, sans jamais la
 * contourner. Un certificat absent donne CERTIFICATE_UNKNOWN, comme en
 * production.
 */
public class LocalCertificateProvider implements TwoDDocCertificateProvider {

    private final ResourceLoader resourceLoader;
    private final String basePath;
    private final List<String> revokedSerials;

    public LocalCertificateProvider(ResourceLoader resourceLoader, String basePath) {
        this(resourceLoader, basePath, List.of());
    }

    public LocalCertificateProvider(ResourceLoader resourceLoader, String basePath,
                                    List<String> revokedSerials) {
        this.resourceLoader = resourceLoader;
        this.basePath = basePath.endsWith("/") ? basePath : basePath + "/";
        this.revokedSerials = revokedSerials;
    }

    @Override
    public Optional<X509Certificate> find(String authorityId, String certificateId) {
        if (authorityId == null || certificateId == null) return Optional.empty();
        return load(basePath + authorityId + "_" + certificateId + ".cer");
    }

    @Override
    public List<X509Certificate> trustAnchors(String authorityId) {
        if (authorityId == null) return List.of();
        List<X509Certificate> anchors = new ArrayList<>();
        load(basePath + authorityId + "_CA.cer").ifPresent(anchors::add);
        return anchors;
    }

    @Override
    public List<String> revokedSerials(String authorityId) {
        return revokedSerials;
    }

    private Optional<X509Certificate> load(String location) {
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) return Optional.empty();
        try (InputStream in = resource.getInputStream()) {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            return Optional.of((X509Certificate) factory.generateCertificate(in));
        } catch (Exception e) {
            // Ressource illisible : traitee comme absente, jamais comme valide.
            return Optional.empty();
        }
    }
}
