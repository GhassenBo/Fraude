package com.frauddetect.service;

import com.frauddetect.model.AnalysisResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SiretVerificationServiceTest {

    @Mock
    private RestTemplate restTemplate;

    private SiretVerificationService service;

    @BeforeEach
    void setUp() {
        service = new SiretVerificationService();
        ReflectionTestUtils.setField(service, "restTemplate", restTemplate);
    }

    // ── Format validation ─────────────────────────────────────────────────────

    @Test
    void verify_nullSiret_shouldReturnWarning() {
        List<AnalysisResult.Check> checks = service.verify(null, null);
        assertThat(checks).hasSize(1);
        assertThat(checks.get(0).getStatus()).isEqualTo("WARNING");
        assertThat(checks.get(0).getDetail()).contains("non trouvé");
    }

    @Test
    void verify_blankSiret_shouldReturnWarning() {
        List<AnalysisResult.Check> checks = service.verify("   ", null);
        assertThat(checks).hasSize(1);
        assertThat(checks.get(0).getStatus()).isEqualTo("WARNING");
    }

    @Test
    void verify_siretTooShort_shouldFail() {
        List<AnalysisResult.Check> checks = service.verify("123456789", null);
        assertThat(checks).hasSize(1);
        assertThat(checks.get(0).getStatus()).isEqualTo("FAILED");
        assertThat(checks.get(0).getDetail()).contains("Format SIRET invalide");
    }

    @Test
    void verify_siretWithSpaces_strippedAndValidated() {
        // 852 070 796 00028 → 14 digits after stripping spaces; invalid Luhn → WARNING not FAILED
        String apiResponse = """
            {"total_results":1,"results":[{
              "nom_complet":"TEST SAS",
              "etat_administratif":"A",
              "matching_etablissements":[{"etat_administratif":"A"}]
            }]}""";
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(), eq(String.class)))
            .thenReturn(ResponseEntity.ok(apiResponse));

        List<AnalysisResult.Check> checks = service.verify("852 070 796 00028", "TEST SAS");
        // Should not fail on format (14 digits after stripping)
        assertThat(checks).allMatch(c -> !"FAILED".equals(c.getStatus())
            || !c.getDetail().contains("Format SIRET invalide"));
    }

    // ── Luhn checksum ─────────────────────────────────────────────────────────

    @Test
    void verify_invalidLuhn_shouldWarnNotFail() {
        // SIREN with invalid Luhn — 14 digits but bad checksum
        // We mock the API so only the Luhn warning is the concern here
        String apiResponse = """
            {"total_results":1,"results":[{
              "nom_complet":"EXAMPLE SAS",
              "etat_administratif":"A",
              "matching_etablissements":[{"etat_administratif":"A"}]
            }]}""";
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(), eq(String.class)))
            .thenReturn(ResponseEntity.ok(apiResponse));

        // 99999999900099 — SIREN 999999999, Luhn fails
        List<AnalysisResult.Check> checks = service.verify("99999999900099", null);

        boolean hasLuhnWarning = checks.stream()
            .anyMatch(c -> "WARNING".equals(c.getStatus())
                && c.getDetail().contains("clé de contrôle SIREN incorrecte"));
        assertThat(hasLuhnWarning).isTrue();

        // No FAILED for Luhn alone
        boolean hasLuhnFailed = checks.stream()
            .anyMatch(c -> "FAILED".equals(c.getStatus())
                && c.getDetail().contains("clé de contrôle"));
        assertThat(hasLuhnFailed).isFalse();
    }

    // ── API lookup — active company ───────────────────────────────────────────

    @Test
    void verify_validSiret_activeCompany_shouldBeOK() {
        // 85207079600028 — this is the test SIRET from the real project
        String apiResponse = """
            {"total_results":1,"results":[{
              "nom_complet":"ACME SAS",
              "etat_administratif":"A",
              "matching_etablissements":[{"etat_administratif":"A"}]
            }]}""";
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(), eq(String.class)))
            .thenReturn(ResponseEntity.ok(apiResponse));

        List<AnalysisResult.Check> checks = service.verify("85207079600028", "ACME SAS");

        boolean hasOk = checks.stream().anyMatch(c -> "OK".equals(c.getStatus())
            && c.getDetail().contains("vérifié"));
        assertThat(hasOk).isTrue();
    }

    // ── API lookup — closed company ───────────────────────────────────────────

    @Test
    void verify_closedCompany_shouldFail() {
        String apiResponse = """
            {"total_results":1,"results":[{
              "nom_complet":"OLD COMPANY SARL",
              "etat_administratif":"C",
              "matching_etablissements":[{"etat_administratif":"A"}]
            }]}""";
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(), eq(String.class)))
            .thenReturn(ResponseEntity.ok(apiResponse));

        List<AnalysisResult.Check> checks = service.verify("85207079600028", null);

        boolean hasFailed = checks.stream()
            .anyMatch(c -> "FAILED".equals(c.getStatus()) && c.getDetail().contains("FERMÉ"));
        assertThat(hasFailed).isTrue();
    }

    @Test
    void verify_closedEtablissement_shouldFail() {
        String apiResponse = """
            {"total_results":1,"results":[{
              "nom_complet":"OLD COMPANY SARL",
              "etat_administratif":"A",
              "matching_etablissements":[{"etat_administratif":"F"}]
            }]}""";
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(), eq(String.class)))
            .thenReturn(ResponseEntity.ok(apiResponse));

        List<AnalysisResult.Check> checks = service.verify("85207079600028", null);

        boolean hasFailed = checks.stream()
            .anyMatch(c -> "FAILED".equals(c.getStatus()) && c.getDetail().contains("FERMÉ"));
        assertThat(hasFailed).isTrue();
    }

    // ── API lookup — company not found ────────────────────────────────────────

    @Test
    void verify_siretNotFound_shouldWarn() {
        String apiResponse = """
            {"total_results":0,"results":[]}""";
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(), eq(String.class)))
            .thenReturn(ResponseEntity.ok(apiResponse));

        List<AnalysisResult.Check> checks = service.verify("85207079600028", null);

        boolean hasWarning = checks.stream()
            .anyMatch(c -> "WARNING".equals(c.getStatus()) && c.getDetail().contains("introuvable"));
        assertThat(hasWarning).isTrue();

        // Must NOT be FAILED
        assertThat(checks).noneMatch(c -> "FAILED".equals(c.getStatus()));
    }

    // ── API unavailable ───────────────────────────────────────────────────────

    @Test
    void verify_apiUnavailable_shouldWarnNotFail() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(), eq(String.class)))
            .thenThrow(new RuntimeException("Connection refused"));

        List<AnalysisResult.Check> checks = service.verify("85207079600028", null);

        boolean hasWarning = checks.stream()
            .anyMatch(c -> "WARNING".equals(c.getStatus()) && c.getDetail().contains("indisponible"));
        assertThat(hasWarning).isTrue();
        assertThat(checks).noneMatch(c -> "FAILED".equals(c.getStatus()));
    }

    // ── Employer name cross-check ─────────────────────────────────────────────

    @Test
    void verify_nameMismatch_shouldAddWarning() {
        String apiResponse = """
            {"total_results":1,"results":[{
              "nom_complet":"SOCIETE DUPONT FRERES SAS",
              "etat_administratif":"A",
              "matching_etablissements":[{"etat_administratif":"A"}]
            }]}""";
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(), eq(String.class)))
            .thenReturn(ResponseEntity.ok(apiResponse));

        List<AnalysisResult.Check> checks = service.verify("85207079600028", "COMPLETELY DIFFERENT NAME");

        boolean hasNameWarning = checks.stream()
            .anyMatch(c -> "WARNING".equals(c.getStatus()) && c.getDetail().contains("Nom employeur"));
        assertThat(hasNameWarning).isTrue();
    }

    @Test
    void verify_nameMatch_noExtraWarning() {
        String apiResponse = """
            {"total_results":1,"results":[{
              "nom_complet":"SOCIETE DUPONT FRERES SAS",
              "etat_administratif":"A",
              "matching_etablissements":[{"etat_administratif":"A"}]
            }]}""";
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(), eq(String.class)))
            .thenReturn(ResponseEntity.ok(apiResponse));

        List<AnalysisResult.Check> checks = service.verify("85207079600028", "SOCIETE DUPONT FRERES");

        boolean hasNameWarning = checks.stream()
            .anyMatch(c -> "WARNING".equals(c.getStatus()) && c.getDetail().contains("Nom employeur"));
        assertThat(hasNameWarning).isFalse();
    }
}
