package com.frauddetect.util;

import com.frauddetect.service.ClaudeVisionService;
import com.frauddetect.service.PdfForensicsService;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La provenance du net a payer traverse-t-elle bien le pipeline.
 *
 * Le defaut que ces tests couvrent n'etait visible dans aucun test unitaire :
 * SalaryCalculationService recevait un argument par defaut annoncant un net non
 * fiable, alors que l'extraction visuelle l'avait bien fourni. Les controles
 * portant sur ce montant ne s'executaient donc jamais en production, tout en
 * passant en test.
 */
class PdfAnalyzerVisionWiringTest {

    /** Analyse visuelle simulee : aucun appel reseau. */
    private static class VisionSimulee extends ClaudeVisionService {
        private final VisionExtraction extraction;

        VisionSimulee(VisionExtraction extraction) {
            this.extraction = extraction;
        }

        @Override
        public VisionExtraction extractFields(byte[] pdfBytes) {
            return extraction;
        }
    }

    private PdfAnalyzer.PdfAnalysisData analyse(ClaudeVisionService vision) throws Exception {
        return new PdfAnalyzer(vision, new PdfForensicsService())
            .analyze(new ByteArrayInputStream(pdfMinimal()));
    }

    private byte[] pdfMinimal() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage(PDRectangle.A4));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void netFourniParLAnalyseVisuelle_estSignaleFiable() throws Exception {
        ClaudeVisionService vision = new VisionSimulee(new ClaudeVisionService.VisionExtraction(
            "AGENCE MARTIN", "85207079600028", "MARTIN JEAN", "Février 2026",
            "3200.00 €", "2527.86 €"));

        PdfAnalyzer.PdfAnalysisData data = analyse(vision);

        assertThat(data.netFromVision()).isTrue();
        assertThat(data.documentInfo().getSalaireNet()).isEqualTo("2527.86 €");
    }

    @Test
    void netAbsentDeLAnalyseVisuelle_nEstPasFiable() throws Exception {
        // Le brut est lu, le net non : seul le net conditionne les controles qui
        // le comparent, et le repli par regex le confond avec d'autres montants.
        ClaudeVisionService vision = new VisionSimulee(new ClaudeVisionService.VisionExtraction(
            "AGENCE MARTIN", "85207079600028", "MARTIN JEAN", "Février 2026",
            "3200.00 €", null));

        assertThat(analyse(vision).netFromVision()).isFalse();
    }

    @Test
    void analyseVisuelleIndisponible_nEstPasFiable() throws Exception {
        // Cle d'API absente : extractFields retourne null.
        assertThat(analyse(new VisionSimulee(null)).netFromVision()).isFalse();
    }
}
