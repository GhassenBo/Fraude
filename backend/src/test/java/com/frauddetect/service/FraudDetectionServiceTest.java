package com.frauddetect.service;

import com.frauddetect.model.AnalysisResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the scoring and verdict logic in FraudDetectionService via reflection,
 * since computeScore / computeVerdict / computeColor are private methods.
 */
@ExtendWith(MockitoExtension.class)
class FraudDetectionServiceTest {

    @Mock private com.frauddetect.util.PdfAnalyzer pdfAnalyzer;
    @Mock private SiretVerificationService siretService;
    @Mock private SalaryCalculationService salaryService;
    @Mock private AiAnalysisService aiAnalysisService;
    @Mock private ClaudeVisionService claudeVisionService;
    @Mock private com.frauddetect.repository.UserRepository userRepository;
    @Mock private com.frauddetect.repository.AnalysisRepository analysisRepository;

    private FraudDetectionService service;

    @BeforeEach
    void setUp() {
        service = new FraudDetectionService(
            pdfAnalyzer, siretService, salaryService,
            aiAnalysisService, claudeVisionService,
            userRepository, analysisRepository);
        ReflectionTestUtils.setField(service, "freeLimit", 10);
    }

    // ── computeScore ──────────────────────────────────────────────────────────

    @Test
    void computeScore_emptyChecks_returns50() {
        assertThat(invokeComputeScore(List.of())).isEqualTo(50);
    }

    @Test
    void computeScore_allOK_returns100() {
        List<AnalysisResult.Check> checks = List.of(
            check("OK"), check("OK"), check("OK"));
        assertThat(invokeComputeScore(checks)).isEqualTo(100);
    }

    @Test
    void computeScore_oneFailed_returns75() {
        List<AnalysisResult.Check> checks = List.of(check("FAILED"));
        assertThat(invokeComputeScore(checks)).isEqualTo(75);
    }

    @Test
    void computeScore_oneWarning_returns90() {
        List<AnalysisResult.Check> checks = List.of(check("WARNING"));
        assertThat(invokeComputeScore(checks)).isEqualTo(90);
    }

    @Test
    void computeScore_mixedChecks() {
        // 100 - 25(FAILED) - 10(WARNING) - 10(WARNING) = 55
        List<AnalysisResult.Check> checks = List.of(
            check("FAILED"), check("WARNING"), check("WARNING"), check("OK"));
        assertThat(invokeComputeScore(checks)).isEqualTo(55);
    }

    @Test
    void computeScore_manyFailures_clampedAtZero() {
        List<AnalysisResult.Check> checks = new ArrayList<>();
        for (int i = 0; i < 10; i++) checks.add(check("FAILED"));
        assertThat(invokeComputeScore(checks)).isEqualTo(0);
    }

    @Test
    void computeScore_neverExceeds100() {
        List<AnalysisResult.Check> checks = List.of(check("OK"));
        assertThat(invokeComputeScore(checks)).isLessThanOrEqualTo(100);
    }

    // ── computeVerdict ────────────────────────────────────────────────────────

    @Test
    void computeVerdict_score100_isAuthentique() {
        assertThat(invokeComputeVerdict(100)).isEqualTo("AUTHENTIQUE");
    }

    @Test
    void computeVerdict_score75_isAuthentique() {
        assertThat(invokeComputeVerdict(75)).isEqualTo("AUTHENTIQUE");
    }

    @Test
    void computeVerdict_score74_isSuspect() {
        assertThat(invokeComputeVerdict(74)).isEqualTo("SUSPECT");
    }

    @Test
    void computeVerdict_score45_isSuspect() {
        assertThat(invokeComputeVerdict(45)).isEqualTo("SUSPECT");
    }

    @Test
    void computeVerdict_score44_isFrauduleux() {
        assertThat(invokeComputeVerdict(44)).isEqualTo("FRAUDULEUX");
    }

    @Test
    void computeVerdict_score0_isFrauduleux() {
        assertThat(invokeComputeVerdict(0)).isEqualTo("FRAUDULEUX");
    }

    // ── computeColor ──────────────────────────────────────────────────────────

    @Test
    void computeColor_score75_isGreen() {
        assertThat(invokeComputeColor(75)).isEqualTo("green");
    }

    @Test
    void computeColor_score74_isOrange() {
        assertThat(invokeComputeColor(74)).isEqualTo("orange");
    }

    @Test
    void computeColor_score45_isOrange() {
        assertThat(invokeComputeColor(45)).isEqualTo("orange");
    }

    @Test
    void computeColor_score44_isRed() {
        assertThat(invokeComputeColor(44)).isEqualTo("red");
    }

    @Test
    void computeColor_score0_isRed() {
        assertThat(invokeComputeColor(0)).isEqualTo("red");
    }

    // ── Score + Verdict consistency ───────────────────────────────────────────

    @Test
    void scoreAndVerdict_twoFailedOneWarning_isSuspect() {
        // 100 - 25 - 25 - 10 = 40 → FRAUDULEUX
        List<AnalysisResult.Check> checks = List.of(
            check("FAILED"), check("FAILED"), check("WARNING"));
        int score = invokeComputeScore(checks);
        assertThat(score).isEqualTo(40);
        assertThat(invokeComputeVerdict(score)).isEqualTo("FRAUDULEUX");
    }

    @Test
    void scoreAndVerdict_oneFailedOneWarning_isSuspect() {
        // 100 - 25 - 10 = 65 → SUSPECT
        List<AnalysisResult.Check> checks = List.of(check("FAILED"), check("WARNING"));
        int score = invokeComputeScore(checks);
        assertThat(score).isEqualTo(65);
        assertThat(invokeComputeVerdict(score)).isEqualTo("SUSPECT");
        assertThat(invokeComputeColor(score)).isEqualTo("orange");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private AnalysisResult.Check check(String status) {
        return AnalysisResult.Check.builder()
            .category("Test").label("Test").status(status).detail("").build();
    }

    private int invokeComputeScore(List<AnalysisResult.Check> checks) {
        return (int) invoke("computeScore", List.class, checks);
    }

    private String invokeComputeVerdict(int score) {
        return (String) invoke("computeVerdict", int.class, score);
    }

    private String invokeComputeColor(int score) {
        return (String) invoke("computeColor", int.class, score);
    }

    private Object invoke(String methodName, Class<?> paramType, Object arg) {
        try {
            Method m = FraudDetectionService.class.getDeclaredMethod(methodName, paramType);
            m.setAccessible(true);
            return m.invoke(service, arg);
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke " + methodName, e);
        }
    }
}
