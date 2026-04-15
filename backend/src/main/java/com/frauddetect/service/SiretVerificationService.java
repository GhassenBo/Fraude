package com.frauddetect.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.frauddetect.model.AnalysisResult;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.Base64;
import java.util.Map;

@Service
public class SiretVerificationService {

    private static final String INSEE_TOKEN_URL = "https://portail-api.insee.fr/token";
    private static final String INSEE_SIRET_URL  = "https://api.insee.fr/entreprises/sirene/V3.11/siret/";

    @Value("${insee.api.key:}")
    private String apiKey;          // clé API directe (mode simple)

    @Value("${insee.consumer.key:}")
    private String consumerKey;     // Consumer Key OAuth2 (mode avancé)

    @Value("${insee.consumer.secret:}")
    private String consumerSecret;  // Consumer Secret OAuth2 (mode avancé)

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Cached Bearer token and its expiry timestamp. */
    private String cachedToken;
    private Instant tokenExpiry = Instant.EPOCH;

    @PostConstruct
    public void init() {
        if (hasDirectApiKey()) {
            System.out.println("[INSEE] ✓ Clé API directe détectée — vérification SIRET en ligne ACTIVE");
        } else if (hasOAuth2Credentials()) {
            System.out.println("[INSEE] ✓ Consumer Key/Secret détectés — vérification SIRET en ligne ACTIVE (OAuth2)");
            try { refreshToken(); }
            catch (Exception e) {
                System.err.println("[INSEE] Impossible de récupérer le token au démarrage : " + e.getMessage());
            }
        } else {
            System.out.println("[INSEE] ✗ Aucune clé configurée — vérification SIRET hors-ligne (Luhn uniquement)");
        }
    }

    private boolean hasDirectApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    private boolean hasOAuth2Credentials() {
        return consumerKey != null && !consumerKey.isBlank()
            && consumerSecret != null && !consumerSecret.isBlank();
    }

    private boolean isInseeEnabled() {
        return hasDirectApiKey() || hasOAuth2Credentials();
    }

    // ── Main check ────────────────────────────────────────────────────────────

    public AnalysisResult.Check verify(String siret) {
        if (siret == null || siret.isBlank()) {
            return check("WARNING", "SIRET non trouvé dans le document");
        }

        String cleaned = siret.replaceAll("[^0-9]", "");

        if (cleaned.length() != 14) {
            return check("FAILED", "Format SIRET invalide : " + siret
                + " (" + cleaned.length() + " chiffres au lieu de 14)");
        }

        if (!isValidSiretChecksum(cleaned)) {
            return check("WARNING",
                "SIRET " + cleaned + " — clé de contrôle Luhn incorrecte"
                + " (peut être valide pour un organisme public ou une ancienne attribution)");
        }

        if (isInseeEnabled()) {
            return verifyWithInsee(cleaned);
        }

        return check("OK", "SIRET " + cleaned + " — format et clé de contrôle Luhn valides ✓");
    }

    // ── INSEE API ─────────────────────────────────────────────────────────────

    private AnalysisResult.Check verifyWithInsee(String siret) {
        try {
            String token = hasDirectApiKey() ? apiKey : getValidToken();
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(token);
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<Map> resp = restTemplate.exchange(
                INSEE_SIRET_URL + siret, HttpMethod.GET, entity, Map.class);

            if (resp.getStatusCode().is2xxSuccessful()) {
                // Extract company name and status if available
                String detail = "SIRET " + siret + " vérifié et actif dans la base INSEE ✓";
                try {
                    JsonNode body = objectMapper.valueToTree(resp.getBody());
                    JsonNode etablissement = body.path("etablissement");
                    String etat = etablissement.path("periodeEtablissement").get(0)
                        .path("etatAdministratifEtablissement").asText("");
                    if ("F".equals(etat)) {
                        return check("FAILED", "SIRET " + siret
                            + " trouvé dans la base INSEE mais établissement FERMÉ");
                    }
                } catch (Exception ignored) {}
                return check("OK", detail);
            }
        } catch (HttpClientErrorException.NotFound e) {
            return check("FAILED", "SIRET " + siret
                + " introuvable dans la base INSEE — entreprise inexistante ou fermée");
        } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.Forbidden e) {
            // Token expired mid-request — retry once after refresh
            try {
                refreshToken();
                return verifyWithInsee(siret);
            } catch (Exception ignored) {}
            return check("WARNING", "INSEE : token expiré, vérification impossible");
        } catch (Exception e) {
            System.err.println("[INSEE] Erreur API : " + e.getMessage());
            return check("WARNING", "INSEE indisponible — vérification Luhn uniquement : SIRET " + siret + " valide");
        }
        return check("WARNING", "Réponse INSEE inattendue");
    }

    // ── OAuth2 token management ───────────────────────────────────────────────

    private String getValidToken() throws Exception {
        if (cachedToken != null && Instant.now().isBefore(tokenExpiry)) {
            return cachedToken;
        }
        refreshToken();
        return cachedToken;
    }

    private void refreshToken() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        // Basic auth: Base64(consumerKey:consumerSecret)
        String credentials = Base64.getEncoder()
            .encodeToString((consumerKey + ":" + consumerSecret).getBytes());
        headers.set("Authorization", "Basic " + credentials);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "client_credentials");

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.exchange(
            INSEE_TOKEN_URL, HttpMethod.POST, request, String.class);

        JsonNode json = objectMapper.readTree(response.getBody());
        cachedToken = json.path("access_token").asText();
        long expiresIn = json.path("expires_in").asLong(3600);
        // Refresh 60s before expiry to avoid edge cases
        tokenExpiry = Instant.now().plusSeconds(expiresIn - 60);
        System.out.println("[INSEE] Token Bearer obtenu, valide " + expiresIn / 3600 + "h");
    }

    // ── Luhn checksum ─────────────────────────────────────────────────────────

    private boolean isValidSiretChecksum(String siret) {
        int sum = 0;
        for (int i = 0; i < 14; i++) {
            int digit = Character.getNumericValue(siret.charAt(i));
            if (i % 2 == 0) {
                digit *= 2;
                if (digit > 9) digit -= 9;
            }
            sum += digit;
        }
        return sum % 10 == 0;
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private AnalysisResult.Check check(String status, String detail) {
        return AnalysisResult.Check.builder()
            .category("Employeur")
            .label("Numéro SIRET")
            .status(status)
            .detail(detail)
            .build();
    }
}
