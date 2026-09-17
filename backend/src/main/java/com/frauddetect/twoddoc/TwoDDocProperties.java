package com.frauddetect.twoddoc;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Data
@Component
@ConfigurationProperties(prefix = "two-ddoc")
public class TwoDDocProperties {

    private Certificates certificates = new Certificates();

    @Data
    public static class Certificates {

        public enum Mode {
            /** Certificats lus depuis les ressources : tests reproductibles hors reseau. */
            LOCAL,
            /** Liste de confiance ANTS interrogee puis mise en cache. */
            REMOTE_WITH_CACHE,
            /** Aucune resolution : la verification retourne NOT_VERIFIED. */
            DISABLED
        }

        private Mode mode = Mode.DISABLED;

        /** Liste de confiance de production de l'ANTS. */
        private String tslUrl = "";

        /** Emplacement des certificats en mode LOCAL. */
        private String localPath = "classpath:2ddoc/certificates/";

        /**
         * Duree pendant laquelle un certificat deja resolu reste utilisable, y
         * compris si la source devient injoignable.
         */
        private Duration cacheTtl = Duration.ofDays(7);
    }
}
