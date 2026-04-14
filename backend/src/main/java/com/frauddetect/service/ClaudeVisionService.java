package com.frauddetect.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

/**
 * Uses the Anthropic Claude Vision API to extract payslip fields from a PDF rendered as an image.
 * Activated when the ANTHROPIC_API_KEY environment variable (or anthropic.api.key property) is set.
 * Falls back gracefully (returns null) when disabled or on any error.
 */
@Service
public class ClaudeVisionService {

    private static final String ANTHROPIC_API_URL = "https://api.anthropic.com/v1/messages";
    private static final String MODEL = "claude-opus-4-6";

    @Value("${anthropic.api.key:}")
    private String apiKey;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public boolean isEnabled() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** Structured result from Claude Vision extraction. */
    public record VisionExtraction(
        String employeur,
        String siret,
        String employe,
        String periode,
        String salaireBrut,
        String salaireNet
    ) {}

    /**
     * Renders the first page of the PDF to a PNG image and asks Claude to extract
     * payslip fields. Returns null if disabled, rendering fails, or API call fails.
     */
    public VisionExtraction extractFields(byte[] pdfBytes) {
        if (!isEnabled()) return null;
        try {
            String base64Image = renderFirstPageToBase64(pdfBytes);
            if (base64Image == null) return null;
            String jsonResponse = callClaudeApi(base64Image);
            return parseExtraction(jsonResponse);
        } catch (Exception e) {
            System.err.println("[ClaudeVision] Extraction failed: " + e.getMessage());
            return null;
        }
    }

    // ── PDF rendering ─────────────────────────────────────────────────────────

    private String renderFirstPageToBase64(byte[] pdfBytes) {
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            PDFRenderer renderer = new PDFRenderer(doc);
            // 150 DPI gives a good balance between clarity and payload size
            BufferedImage image = renderer.renderImageWithDPI(0, 150);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "png", baos);
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (Exception e) {
            System.err.println("[ClaudeVision] PDF rendering failed: " + e.getMessage());
            return null;
        }
    }

    // ── API call ──────────────────────────────────────────────────────────────

    private String callClaudeApi(String base64Image) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-api-key", apiKey);
        headers.set("anthropic-version", "2023-06-01");

        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", MODEL);
        body.put("max_tokens", 512);

        ArrayNode messages = objectMapper.createArrayNode();
        ObjectNode userMsg = objectMapper.createObjectNode();
        userMsg.put("role", "user");

        ArrayNode content = objectMapper.createArrayNode();

        // Image block
        ObjectNode imageBlock = objectMapper.createObjectNode();
        imageBlock.put("type", "image");
        ObjectNode source = objectMapper.createObjectNode();
        source.put("type", "base64");
        source.put("media_type", "image/png");
        source.put("data", base64Image);
        imageBlock.set("source", source);
        content.add(imageBlock);

        // Text prompt block
        ObjectNode textBlock = objectMapper.createObjectNode();
        textBlock.put("type", "text");
        textBlock.put("text", buildExtractionPrompt());
        content.add(textBlock);

        userMsg.set("content", content);
        messages.add(userMsg);
        body.set("messages", messages);

        HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(body), headers);
        ResponseEntity<String> response = restTemplate.exchange(ANTHROPIC_API_URL, HttpMethod.POST, entity, String.class);

        JsonNode root = objectMapper.readTree(response.getBody());
        return root.path("content").get(0).path("text").asText();
    }

    private String buildExtractionPrompt() {
        return """
                Tu es un expert en bulletins de salaire français.
                Analyse cette image d'un bulletin de salaire et extrait les informations suivantes.
                Réponds UNIQUEMENT avec un objet JSON valide, sans texte avant ou après :
                {
                  "employeur": "nom de l'entreprise employeur (ex: ACME SAS), ou null si absent",
                  "siret": "numéro SIRET exactement 14 chiffres sans espaces, ou null si absent",
                  "employe": "prénom et nom complet de l'employé, ou null si absent",
                  "periode": "mois et année de paie (ex: Novembre 2025), ou null si absent",
                  "salaireBrut": valeur numérique décimale du salaire brut en euros (ex: 3224.64), ou null,
                  "salaireNet": valeur numérique décimale du net à payer en euros (ex: 2512.23), ou null
                }
                Pour salaireBrut cherche le libellé "Salaire brut" ou "Rémunération brute" ou "Total brut".
                Pour salaireNet cherche le libellé "Net à payer" ou "Net versé" ou "Net avant impôt".
                """;
    }

    // ── Response parsing ──────────────────────────────────────────────────────

    private VisionExtraction parseExtraction(String json) {
        try {
            // Claude sometimes wraps JSON in markdown code fences — strip them
            String cleaned = json.trim();
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.replaceAll("(?s)^```[a-z]*\\n?", "").replaceAll("\\n?```$", "").trim();
            }

            JsonNode node = objectMapper.readTree(cleaned);

            String employeur = getText(node, "employeur");
            String siret     = getText(node, "siret");
            String employe   = getText(node, "employe");
            String periode   = getText(node, "periode");

            Double brutVal = getDouble(node, "salaireBrut");
            Double netVal  = getDouble(node, "salaireNet");

            // Net cannot exceed brut — discard net if extraction is suspicious
            if (brutVal != null && netVal != null && netVal > brutVal) netVal = null;

            String salaireBrut = brutVal != null ? String.format("%.2f €", brutVal) : null;
            String salaireNet  = netVal  != null ? String.format("%.2f €", netVal)  : null;

            return new VisionExtraction(employeur, siret, employe, periode, salaireBrut, salaireNet);
        } catch (Exception e) {
            System.err.println("[ClaudeVision] JSON parse failed: " + e.getMessage() + " | raw: " + json);
            return null;
        }
    }

    private String getText(JsonNode node, String field) {
        JsonNode val = node.get(field);
        if (val == null || val.isNull()) return null;
        String s = val.asText("").trim();
        return (s.isEmpty() || "null".equalsIgnoreCase(s)) ? null : s;
    }

    private Double getDouble(JsonNode node, String field) {
        JsonNode val = node.get(field);
        if (val == null || val.isNull()) return null;
        if (val.isNumber()) {
            double d = val.asDouble();
            return d > 0 ? d : null;
        }
        // Sometimes Claude returns a string like "3224.64" — handle it
        try {
            double d = Double.parseDouble(val.asText().replace(",", ".").replaceAll("[^0-9.]", ""));
            return d > 0 ? d : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
