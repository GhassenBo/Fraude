package com.frauddetect.service;

import com.frauddetect.model.AnalysisResult;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Forensic analysis of a PDF document to detect post-generation tampering.
 *
 * Checks:
 *  1. Incremental updates  — raw startxref/%%EOF count in bytes
 *  2. Multiple content streams per page — overlay technique
 *  3. Suspicious annotations — FreeText / Stamp added by editors
 *  4. White fill rectangles — "white-out" masking technique
 *  5. Overlapping text positions — text pasted over existing text
 */
@Service
public class PdfForensicsService {

    /**
     * Run all forensic checks.
     * Called from PdfAnalyzer while the PDDocument is still open (avoids double-loading).
     */
    public List<AnalysisResult.Check> analyze(byte[] pdfBytes, PDDocument document) {
        List<AnalysisResult.Check> checks = new ArrayList<>();
        checks.add(checkIncrementalUpdates(pdfBytes));
        checks.add(checkMultipleContentStreams(document));
        checks.add(checkSuspiciousAnnotations(document));
        checks.add(checkWhiteFillRects(document));
        checks.add(checkTextOverlay(document));
        return checks;
    }

    // ── Check 1 : Incremental updates (raw bytes) ─────────────────────────────

    private AnalysisResult.Check checkIncrementalUpdates(byte[] pdfBytes) {
        String raw = new String(pdfBytes, StandardCharsets.ISO_8859_1);
        boolean isLinearized = raw.contains("/Linearized");
        int startxrefCount = countOccurrences(raw, "startxref");
        int eofCount       = countOccurrences(raw, "%%EOF");
        int sections       = Math.max(startxrefCount, eofCount);
        // Linearized PDFs normally have 2 sections; non-linearized: 1
        int normalSections = isLinearized ? 2 : 1;
        int modifications  = sections - normalSections;

        if (modifications > 0) {
            return check("Intégrité PDF", "Modifications post-création",
                modifications > 1 ? "FAILED" : "WARNING",
                modifications + " modification(s) incrémentielle(s) détectée(s) après la génération du PDF"
                    + (isLinearized ? " (PDF linéarisé, base 2 sections)" : "")
                    + " — document altéré après création");
        }
        return check("Intégrité PDF", "Modifications post-création", "OK",
            "Structure PDF intacte — aucune modification incrémentielle détectée");
    }

    // ── Check 2 : Multiple content streams per page ───────────────────────────

    private AnalysisResult.Check checkMultipleContentStreams(PDDocument document) {
        List<Integer> pages = new ArrayList<>();
        for (int i = 0; i < document.getNumberOfPages(); i++) {
            COSBase contents = document.getPage(i).getCOSObject()
                .getDictionaryObject(COSName.CONTENTS);
            if (contents instanceof COSArray arr && arr.size() > 1) {
                pages.add(i + 1);
            }
        }
        if (!pages.isEmpty()) {
            return check("Intégrité PDF", "Flux de contenu superposés", "WARNING",
                "Page(s) " + pages + " : flux multiples détectés — technique utilisée"
                    + " pour superposer du contenu sans modifier le flux original");
        }
        return check("Intégrité PDF", "Flux de contenu superposés", "OK",
            "Flux de contenu unique par page — structure normale");
    }

    // ── Check 3 : Suspicious annotations ─────────────────────────────────────

    private AnalysisResult.Check checkSuspiciousAnnotations(PDDocument document) {
        int count = 0;
        try {
            for (int i = 0; i < document.getNumberOfPages(); i++) {
                for (PDAnnotation ann : document.getPage(i).getAnnotations()) {
                    String sub = ann.getSubtype();
                    if ("FreeText".equals(sub) || "Ink".equals(sub)
                            || "Stamp".equals(sub) || "Highlight".equals(sub)
                            || "Redact".equals(sub)) {
                        count++;
                    }
                }
            }
        } catch (IOException e) {
            return check("Intégrité PDF", "Annotations suspectes", "WARNING",
                "Impossible d'analyser les annotations : " + e.getMessage());
        }
        if (count > 0) {
            return check("Intégrité PDF", "Annotations suspectes", "FAILED",
                count + " annotation(s) de type texte libre / tampon / surlignage"
                    + " — ajout de contenu post-génération détecté");
        }
        return check("Intégrité PDF", "Annotations suspectes", "OK",
            "Aucune annotation suspecte (FreeText, Ink, Stamp, Redact)");
    }

    // ── Check 4 : White fill rectangles ("white-out" technique) ──────────────

    private AnalysisResult.Check checkWhiteFillRects(PDDocument document) {
        int total = 0;
        for (int p = 0; p < document.getNumberOfPages(); p++) {
            COSBase contents = document.getPage(p).getCOSObject()
                .getDictionaryObject(COSName.CONTENTS);
            for (COSStream stream : resolveStreams(contents)) {
                try (InputStream is = stream.createInputStream()) {
                    total += countWhiteFilledRects(
                        new String(is.readAllBytes(), StandardCharsets.ISO_8859_1));
                } catch (Exception ignored) {}
            }
        }
        // Allow a few (table layout), flag an unusually high count
        if (total > 10) {
            return check("Intégrité PDF", "Rectangles de masquage", "WARNING",
                total + " rectangle(s) blanc(s) remplis détectés — possible technique"
                    + " de masquage pour dissimuler le texte original");
        }
        return check("Intégrité PDF", "Rectangles de masquage", "OK",
            "Nombre de rectangles blancs dans la norme (" + total + ")");
    }

    /**
     * Tokenises a PDF content stream and counts white-fill + rectangle + fill sequences.
     * Handles DeviceGray (g), DeviceRGB (rg) and DeviceCMYK (k) colour operators.
     */
    private int countWhiteFilledRects(String content) {
        String[] tokens = content.split("\\s+");
        int count = 0;
        boolean whiteFill = false;
        boolean hasRect   = false;
        List<String> ops  = new ArrayList<>();

        for (String t : tokens) {
            if (t.isBlank()) continue;
            if (isFloatToken(t)) { ops.add(t); continue; }

            switch (t) {
                case "g" -> { // DeviceGray fill: 1 g = white
                    if (!ops.isEmpty())
                        whiteFill = toFloat(ops.get(ops.size() - 1)) > 0.85f;
                    hasRect = false;
                }
                case "rg" -> { // DeviceRGB fill: 1 1 1 rg = white
                    if (ops.size() >= 3)
                        whiteFill = toFloat(ops.get(ops.size() - 3)) > 0.85f
                                 && toFloat(ops.get(ops.size() - 2)) > 0.85f
                                 && toFloat(ops.get(ops.size() - 1)) > 0.85f;
                    hasRect = false;
                }
                case "k" -> { // DeviceCMYK fill: 0 0 0 0 k = white
                    if (ops.size() >= 4)
                        whiteFill = toFloat(ops.get(ops.size() - 4)) < 0.1f
                                 && toFloat(ops.get(ops.size() - 3)) < 0.1f
                                 && toFloat(ops.get(ops.size() - 2)) < 0.1f
                                 && toFloat(ops.get(ops.size() - 1)) < 0.1f;
                    hasRect = false;
                }
                case "re" ->          hasRect = true;
                case "f", "F", "f*",
                     "b", "B", "b*", "B*" -> {
                    if (whiteFill && hasRect) count++;
                    hasRect = false;
                }
                case "n" ->           hasRect = false; // discard path, no fill
                case "cs", "sc", "scn", "SC", "SCN" -> whiteFill = false;
            }
            ops.clear();
        }
        return count;
    }

    // ── Check 5 : Overlapping text positions ─────────────────────────────────

    private AnalysisResult.Check checkTextOverlay(PDDocument document) {
        try {
            OverlapDetector detector = new OverlapDetector();
            detector.getText(document);
            int overlaps = detector.overlapCount;
            if (overlaps > 0) {
                return check("Intégrité PDF", "Texte superposé", "FAILED",
                    overlaps + " caractère(s) placé(s) exactement sur un caractère existant"
                        + " — indicateur fort de falsification par superposition de texte");
            }
            return check("Intégrité PDF", "Texte superposé", "OK",
                "Aucune superposition de texte — positions des caractères cohérentes");
        } catch (Exception e) {
            return check("Intégrité PDF", "Texte superposé", "WARNING",
                "Analyse impossible : " + e.getMessage());
        }
    }

    // ── Inner class : overlap detector ───────────────────────────────────────

    private static class OverlapDetector extends PDFTextStripper {
        final List<float[]> pagePos = new ArrayList<>();
        int overlapCount = 0;

        OverlapDetector() throws IOException { super(); }

        @Override
        protected void startPage(PDPage page) throws IOException {
            pagePos.clear();
            super.startPage(page);
        }

        @Override
        protected void processTextPosition(TextPosition text) {
            String ch = text.getUnicode();
            if (ch == null || ch.isBlank()) return;
            float x = text.getXDirAdj();
            float y = text.getYDirAdj();
            float w = Math.max(text.getWidthDirAdj(), 1f);
            // Tight tolerance: legitimate kerning moves characters > 1pt
            for (float[] p : pagePos) {
                if (Math.abs(y - p[1]) < 1.0f
                        && x >= p[0] - 0.5f
                        && x <= p[0] + p[2] + 0.5f) {
                    overlapCount++;
                    return;
                }
            }
            pagePos.add(new float[]{x, y, w});
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private List<COSStream> resolveStreams(COSBase base) {
        List<COSStream> result = new ArrayList<>();
        if (base == null) return result;
        if (base instanceof COSObject obj) base = obj.getObject();
        if (base instanceof COSStream s) {
            result.add(s);
        } else if (base instanceof COSArray arr) {
            for (int i = 0; i < arr.size(); i++) {
                COSBase item = arr.get(i);
                if (item instanceof COSObject co) item = co.getObject();
                if (item instanceof COSStream s) result.add(s);
            }
        }
        return result;
    }

    private AnalysisResult.Check check(String cat, String label, String status, String detail) {
        return AnalysisResult.Check.builder()
            .category(cat).label(label).status(status).detail(detail).build();
    }

    private int countOccurrences(String text, String pattern) {
        int count = 0, idx = 0;
        while ((idx = text.indexOf(pattern, idx)) != -1) { count++; idx++; }
        return count;
    }

    private boolean isFloatToken(String s) {
        try { Float.parseFloat(s); return true; }
        catch (NumberFormatException e) { return false; }
    }

    private float toFloat(String s) {
        try { return Float.parseFloat(s); }
        catch (NumberFormatException e) { return 0f; }
    }
}
