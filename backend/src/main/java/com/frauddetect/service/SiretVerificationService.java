package com.frauddetect.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.frauddetect.model.AnalysisResult;
import jakarta.annotation.PostConstruct;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;

@Service
public class SiretVerificationService {

    private static final String API_URL =
        "https://recherche-entreprises.api.gouv.fr/search?q=%s";

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    public void init() {
        System.out.println("[SIRET] Vérification via recherche-entreprises.api.gouv.fr (aucune clé API requise)");
    }

    public List<AnalysisResult.Check> verify(String siret, String employeur) {
        List<AnalysisResult.Check> checks = new ArrayList<>();

        if (siret == null || siret.isBlank()) {
            checks.add(check("WARNING", "SIRET non trouvé dans le document"));
            return checks;
        }

        String cleaned = siret.replaceAll("[^0-9]", "");

        if (cleaned.length() != 14) {
            checks.add(check("FAILED",
                "Format SIRET invalide : " + siret + " (" + cleaned.length() + " chiffres au lieu de 14)"));
            return checks;
        }

        if (!isValidSiretChecksum(cleaned)) {
            checks.add(check("WARNING",
                "SIRET " + cleaned + " — clé de contrôle SIREN incorrecte"
                    + " (peut être valide pour certains organismes publics)"));
        }

        checks.addAll(lookupApi(cleaned, employeur));
        return checks;
    }

    private List<AnalysisResult.Check> lookupApi(String siret, String employeur) {
        List<AnalysisResult.Check> checks = new ArrayList<>();
        try {
            String url = String.format(API_URL, siret);
            ResponseEntity<String> response = restTemplate.exchange(
                url, HttpMethod.GET, null, String.class);

            JsonNode root = objectMapper.readTree(response.getBody());
            int total = root.path("total_results").asInt(0);

            if (total == 0) {
                checks.add(check("WARNING",
                    "SIRET " + siret + " introuvable dans la base officielle"
                        + " — numéro potentiellement mal extrait du document ou entreprise inexistante"));
                return checks;
            }

            JsonNode company = root.path("results").get(0);
            String nomOfficiel = company.path("nom_complet").asText("").trim();
            String etatUniteLegale = company.path("etat_administratif").asText("A");

            boolean etablissementActif = true;
            JsonNode etablissements = company.path("matching_etablissements");
            if (etablissements.isArray() && etablissements.size() > 0) {
                String etatEtab = etablissements.get(0).path("etat_administratif").asText("A");
                etablissementActif = "A".equals(etatEtab);
            }

            if (!etablissementActif || "C".equals(etatUniteLegale) || "F".equals(etatUniteLegale)) {
                checks.add(check("FAILED",
                    "SIRET " + siret + " — établissement FERMÉ (" + nomOfficiel + ")"
                        + " dans la base officielle"));
                return checks;
            }

            checks.add(check("OK",
                "SIRET " + siret + " vérifié — " + nomOfficiel + " (actif) ✓"));

            if (employeur != null && !employeur.isBlank() && !nomOfficiel.isBlank()) {
                String normPayslip  = normalize(employeur);
                String normOfficiel = normalize(nomOfficiel);
                if (!normOfficiel.contains(normPayslip)
                        && !normPayslip.contains(normOfficiel)
                        && !sharesSignificantWord(normPayslip, normOfficiel)) {
                    checks.add(check("WARNING",
                        "Nom employeur sur la fiche (\"" + employeur + "\")"
                            + " ≠ raison sociale officielle (\"" + nomOfficiel + "\")"
                            + " — vérifier s'il s'agit d'une filiale ou d'une abréviation"));
                }
            }

        } catch (Exception e) {
            System.err.println("[SIRET] API indisponible : " + e.getMessage());
            checks.add(check("WARNING",
                "SIRET " + siret + " — vérification en ligne impossible (API indisponible),"
                    + " format 14 chiffres valide"));
        }
        return checks;
    }

    private boolean isValidSiretChecksum(String siret) {
        int sum = 0;
        for (int i = 0; i < 9; i++) {
            int digit = Character.getNumericValue(siret.charAt(i));
            if (i % 2 == 1) { digit *= 2; if (digit > 9) digit -= 9; }
            sum += digit;
        }
        return sum % 10 == 0;
    }

    private String normalize(String s) {
        return Normalizer.normalize(s.toLowerCase(), Normalizer.Form.NFD)
            .replaceAll("[\\p{InCombiningDiacriticalMarks}]", "")
            .replaceAll("[^a-z0-9\\s]", " ")
            .replaceAll("\\s+", " ")
            .trim();
    }

    private boolean sharesSignificantWord(String a, String b) {
        for (String word : a.split("\\s+")) {
            if (word.length() > 4 && b.contains(word)) return true;
        }
        return false;
    }

    private AnalysisResult.Check check(String status, String detail) {
        return AnalysisResult.Check.builder()
            .category("Employeur")
            .label("Vérification SIRET")
            .status(status)
            .detail(detail)
            .build();
    }
}
