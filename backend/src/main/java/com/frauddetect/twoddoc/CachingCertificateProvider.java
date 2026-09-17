package com.frauddetect.twoddoc;

import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Met en cache les certificats resolus par un fournisseur sous-jacent, afin de
 * ne pas interroger la source de confiance a chaque document analyse.
 *
 * Le cache est indexe par autorite et identifiant de certificat. Lorsque la
 * source devient injoignable, une entree non expiree est reutilisee : cela evite
 * de bloquer l'analyse sur une indisponibilite passagere. Une entree expiree
 * n'est jamais servie, et l'absence de secours donne TSL_UNAVAILABLE plutot
 * qu'une verification silencieusement ignoree.
 */
public class CachingCertificateProvider implements TwoDDocCertificateProvider {

    private final TwoDDocCertificateProvider delegate;
    private final Duration ttl;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();
    private final Map<String, List<X509Certificate>> anchorCache = new ConcurrentHashMap<>();

    public CachingCertificateProvider(TwoDDocCertificateProvider delegate, Duration ttl) {
        this.delegate = delegate;
        this.ttl = ttl;
    }

    @Override
    public Optional<X509Certificate> find(String authorityId, String certificateId)
        throws CertificateSourceUnavailableException {

        String key = authorityId + ":" + certificateId;
        Entry cached = cache.get(key);
        if (cached != null && !cached.isExpired(ttl)) {
            return Optional.ofNullable(cached.certificate);
        }

        try {
            Optional<X509Certificate> resolved = delegate.find(authorityId, certificateId);
            // Les absences sont memorisees aussi : un identifiant inconnu le reste,
            // inutile de solliciter la source a chaque document.
            cache.put(key, new Entry(resolved.orElse(null), Instant.now()));
            return resolved;
        } catch (CertificateSourceUnavailableException e) {
            if (cached != null) {
                return Optional.ofNullable(cached.certificate);
            }
            throw e;
        }
    }

    @Override
    public List<X509Certificate> trustAnchors(String authorityId) {
        return anchorCache.computeIfAbsent(authorityId, delegate::trustAnchors);
    }

    @Override
    public List<String> revokedSerials(String authorityId) {
        // Volontairement non mis en cache : une revocation doit prendre effet vite.
        return delegate.revokedSerials(authorityId);
    }

    /** Vide le cache, par exemple apres rotation des certificats d'une autorite. */
    public void invalidate() {
        cache.clear();
        anchorCache.clear();
    }

    private record Entry(X509Certificate certificate, Instant fetchedAt) {

        boolean isExpired(Duration ttl) {
            return Instant.now().isAfter(fetchedAt.plus(ttl));
        }
    }
}
