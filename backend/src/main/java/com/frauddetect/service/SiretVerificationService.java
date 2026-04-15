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

/**
 * Verifies a SIRET number using the public French government API.
 * No API key required — open data maintained by beta.gouv.fr / DINUM.
 *
 * Steps:
 *  1. Format check (14 digits)
 *  2. Luhn checksum on the SIREN (first 9 digits) — WARNING only, not FAILED
 *  3. Live lookup via recherche-entreprises.api.gouv.fr
 *     → checks existence and active/closed status
 *  4. Cross-checks the official company name against the employeur on the payslip
 */
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

    // ── Public entry point ────────────────────────────────────────────────────

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

    // ── API lookup ────────────────────────────────────────────────────────────

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

            // Check establishment (établissement) status specifically
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

            // SIRET exists and is active
            checks.add(check("OK",
                "SIRET " + siret + " vérifié — " + nomOfficiel + " (actif) ✓"));

            // Cross-check company name against payslip employeur
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
            // Do not add a FAILED — format + Luhn were already evaluated above
            checks.add(check("WARNING",
                "SIRET " + siret + " — vérification en ligne impossible (API indisponible),"
                    + " format 14 chiffres valide"));
        }
        return checks;
    }

    // ── Luhn on SIREN (first 9 digits only) ──────────────────────────────────

    private boolean isValidSiretChecksum(String siret) {
        int sum = 0;
        for (int i = 0; i < 9; i++) {
            int digit = Character.getNumericValue(siret.charAt(i));
            if (i % 2 == 0) { digit *= 2; if (digit > 9) digit -= 9; }
            sum += digit;
        }
        return sum % 10 == 0;
    }

    // ── Name normalisation helpers ────────────────────────────────────────────

    private String normalize(String s) {
        return Normalizer.normalize(s.toLowerCase(), Normalizer.Form.NFD)
            .replaceAll("[\\p{InCombiningDiacriticalMarks}]", "")
            .replaceAll("[^a-z0-9\\s]", " ")
            .replaceAll("\\s+", " ")
            .trim();
    }

    /** Returns true if the two names share at least one word longer than 4 chars. */
    private boolean sharesSignificantWord(String a, String b) {
        for (String word : a.split("\\s+")) {
            if (word.length() > 4 && b.contains(word)) return true;
        }
        return false;
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private AnalysisResult.Check check(String status, String detail) {
        return AnalysisResult.Check.builder()
            .category("Employeur")
            .label("Vérification SIRET")
            .status(status)
            .detail(detail)
            .build();
    }
}
