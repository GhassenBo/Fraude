package com.frauddetect.twoddoc;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;

import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Optional;

/**
 * Selectionne la source des certificats selon le mode configure.
 *
 * DISABLED est le defaut : sans configuration explicite, la verification
 * retourne NOT_VERIFIED plutot que de laisser croire a une authentification.
 */
@Configuration
public class TwoDDocConfiguration {

    @Bean
    public TwoDDocCertificateProvider twoDDocCertificateProvider(
        TwoDDocProperties properties, ResourceLoader resourceLoader) {

        TwoDDocProperties.Certificates config = properties.getCertificates();

        return switch (config.getMode()) {
            case LOCAL -> new LocalCertificateProvider(resourceLoader, config.getLocalPath());
            case REMOTE_WITH_CACHE -> new CachingCertificateProvider(
                new AntsTslCertificateProvider(config.getTslUrl()), config.getCacheTtl());
            case DISABLED -> disabledProvider();
        };
    }

    /**
     * Ne resout aucun certificat et signale la source comme indisponible : la
     * verification aboutit a TSL_UNAVAILABLE, jamais a VALID.
     */
    private TwoDDocCertificateProvider disabledProvider() {
        return new TwoDDocCertificateProvider() {
            @Override
            public Optional<X509Certificate> find(String authorityId, String certificateId)
                throws CertificateSourceUnavailableException {
                throw new CertificateSourceUnavailableException(
                    "Verification 2D-DOC non configuree (two-ddoc.certificates.mode)");
            }

            @Override
            public List<X509Certificate> trustAnchors(String authorityId) {
                return List.of();
            }
        };
    }
}
