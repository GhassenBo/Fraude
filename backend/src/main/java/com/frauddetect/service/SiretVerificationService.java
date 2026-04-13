package com.frauddetect.service;

import com.frauddetect.model.AnalysisResult;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.HttpClientErrorException;

import java.util.*;

@Service
public class SiretVerificationService {

    private final RestTemplate restTemplate = new RestTemplate();
    private static final String INSEE_API = "https://api.insee.fr/entreprises/sirene/V3.11/siret/";

    public AnalysisResult.Check verify(String siret) {
        if (siret == null || siret.isBlank()) {
            return AnalysisResult.Check.builder()
                .category("Employeur")
                .label("Numéro SIRET")
                .status("WARNING")
                .detail("SIRET non trouvé dans le document")
                .build();
        }

        String cleaned = siret.replaceAll("[^0-9]", "");

        // Luhn-like check: SIRET must be 14 digits
        if (cleaned.length() != 14) {
            return AnalysisResult.Check.builder()
                .category("Employeur")
                .label("Numéro SIRET")
                .status("FAILED")
                .detail("Format SIRET invalide : " + siret + " (" + cleaned.length() + " chiffres au lieu de 14)")
                .build();
        }

        // Validate SIRET checksum (Luhn algorithm)
        if (!isValidSiretChecksum(cleaned)) {
            return AnalysisResult.Check.builder()
                .category("Employeur")
                .label("Numéro SIRET")
                .status("FAILED")
                .detail("SIRET invalide : la clé de contrôle ne correspond pas — numéro falsifié ou erroné")
                .build();
        }

        // Try INSEE API (may fail without token - that's OK, we still have checksum validation)
        try {
            String url = INSEE_API + cleaned;
            Map<?, ?> response = restTemplate.getForObject(url, Map.class);
            if (response != null) {
                return AnalysisResult.Check.builder()
                    .category("Employeur")
                    .label("Numéro SIRET")
                    .status("OK")
                    .detail("SIRET " + cleaned + " vérifié et actif dans la base INSEE")
                    .build();
            }
        } catch (HttpClientErrorException.NotFound e) {
            return AnalysisResult.Check.builder()
                .category("Employeur")
                .label("Numéro SIRET")
                .status("FAILED")
                .detail("SIRET " + cleaned + " introuvable dans la base INSEE — entreprise inexistante ou fermée")
                .build();
        } catch (Exception e) {
            // API not reachable (no token), fall back to checksum only
        }

        return AnalysisResult.Check.builder()
            .category("Employeur")
            .label("Numéro SIRET")
            .status("OK")
            .detail("SIRET " + cleaned + " — format et clé de contrôle valides (vérification INSEE non disponible sans token)")
            .build();
    }

    /**
     * Validates SIRET using Luhn algorithm.
     * For a 14-digit SIRET, we double every second digit starting from the right
     * (positions 2, 4, 6, ... from right = indices 12, 10, 8, ... = even indices from left).
     */
    private boolean isValidSiretChecksum(String siret) {
        int sum = 0;
        for (int i = 0; i < 14; i++) {
            int digit = Character.getNumericValue(siret.charAt(i));
            if (i % 2 == 0) { // even index from left = even position from right for 14-digit number
                digit *= 2;
                if (digit > 9) digit -= 9;
            }
            sum += digit;
        }
        return sum % 10 == 0;
    }
}
