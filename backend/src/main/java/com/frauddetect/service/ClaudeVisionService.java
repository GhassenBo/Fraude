package com.frauddetect.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.frauddetect.model.AnalysisResult;
import jakarta.annotation.PostConstruct;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

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

    @PostConstruct
    public void init() {
        if (isEnabled()) {
            System.out.println("[ClaudeVision] ✓ API key detected — Vision extraction ACTIVE (model: " + MODEL + ")");
        } else {
            System.out.println("[ClaudeVision] ✗ No ANTHROPIC_API_KEY found — Vision extraction DISABLED, falling back to regex");
        }
    }

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

    // ── Forgery detection ─────────────────────────────────────────────────────

    /**
     * Sends a high-res render of the PDF to Claude with a forensic prompt.
     * Asks Claude to look for visual signs of tampering: font inconsistencies,
     * pixel artefacts, misaligned columns, white-out zones, etc.
     * Returns an empty list if Vision is disabled or the call fails.
     */
    public List<AnalysisResult.Check> detectForgery(byte[] pdfBytes) {
        if (!isEnabled()) return List.of();
        try {
            // Render at 250 DPI for maximum sharpness on forensic analysis
            String base64Image = renderPageToBase64(pdfBytes, 0, 250);
            if (base64Image == null) return List.of();
            String jsonResponse = callClaudeForensicApi(base64Image);
            return parseForensicResponse(jsonResponse);
        } catch (Exception e) {
            System.err.println("[ClaudeVision] Forgery detection failed: " + e.getMessage());
            return List.of();
        }
    }

    private String callClaudeForensicApi(String base64Image) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-api-key", apiKey);
        headers.set("anthropic-version", "2023-06-01");

        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", MODEL);
        body.put("max_tokens", 1024);

        ArrayNode messages = objectMapper.createArrayNode();
        ObjectNode userMsg = objectMapper.createObjectNode();
        userMsg.put("role", "user");
        ArrayNode content = objectMapper.createArrayNode();

        ObjectNode imageBlock = objectMapper.createObjectNode();
        imageBlock.put("type", "image");
        ObjectNode source = objectMapper.createObjectNode();
        source.put("type", "base64");
        source.put("media_type", "image/png");
        source.put("data", base64Image);
        imageBlock.set("source", source);
        content.add(imageBlock);

        ObjectNode textBlock = objectMapper.createObjectNode();
        textBlock.put("type", "text");
        textBlock.put("text", buildForensicPrompt());
        content.add(textBlock);

        userMsg.set("content", content);
        messages.add(userMsg);
        body.set("messages", messages);

        HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(body), headers);
        ResponseEntity<String> response = restTemplate.exchange(
            ANTHROPIC_API_URL, HttpMethod.POST, entity, String.class);
        JsonNode root = objectMapper.readTree(response.getBody());
        return root.path("content").get(0).path("text").asText();
    }

    private String buildForensicPrompt() {
        return """
                Tu es un expert légiste spécialisé dans la détection de falsification de bulletins de salaire.
                Ton rôle est de détecter des signes CLAIRS et INDÉNIABLES de falsification.

                RÈGLE FONDAMENTALE — Sois CONSERVATEUR :
                - Les différences de netteté dues à la compression JPEG/PNG sont NORMALES → ne pas signaler
                - Les légères variations de fond dues au scan ou à l'impression sont NORMALES → ne pas signaler
                - Les formats de polices légèrement différents entre libellés et chiffres sont NORMAUX → ne pas signaler
                - Une analyse mathématique "inhabituelle mais cohérente" n'est PAS une falsification → ne pas signaler
                - En cas de doute, choisis OK. Un faux positif est pire qu'un faux négatif.

                Cherche UNIQUEMENT ces signes GRAVES et ÉVIDENTS :

                1. REMPLACEMENT VISIBLE D'UN CHIFFRE : Un chiffre visuellement isolé — \
                entouré d'un halo blanc net, pixels de fond clairement différents autour d'une \
                seule valeur, ou flou localisé sur un nombre précis alors que tout le reste est net. \
                → WARNING uniquement si la différence est NETTE et localisée sur 1-2 valeurs.

                2. RECTANGLE BLANC DE MASQUAGE : Une zone rectangulaire manifestement plus blanche \
                que le fond, avec des bords nets visibles, recouvrant du texte. \
                → FAILED si clairement visible.

                3. INCOHÉRENCE MATHÉMATIQUE FLAGRANTE : Brut - total cotisations ≠ net à payer \
                avec un écart supérieur à 50€ (pas des arrondis normaux, pas une prime séparée). \
                → FAILED uniquement si l'erreur est > 50€ et inexplicable.

                4. ALIGNEMENT MANIFESTEMENT CASSÉ : Un chiffre décalé de plusieurs millimètres \
                par rapport à toute sa colonne — visible à l'œil nu, pas un léger décalage PDF normal. \
                → WARNING uniquement si évident.

                Réponds UNIQUEMENT avec ce JSON :
                {
                  "findings": [
                    {"status": "OK" | "WARNING" | "FAILED", "label": "nom court", "detail": "explication précise"}
                  ]
                }
                Si aucun signe clair de falsification : un seul finding \
                {"status": "OK", "label": "Analyse visuelle", "detail": "Aucun signe visuel clair de falsification détecté"}.
                Maximum 3 findings. Ne signale que ce qui est CERTAIN et ÉVIDENT.
                """;
    }

    private List<AnalysisResult.Check> parseForensicResponse(String json) {
        try {
            String cleaned = json.trim();
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.replaceAll("(?s)^```[a-z]*\\n?", "").replaceAll("\\n?```$", "").trim();
            }
            JsonNode root = objectMapper.readTree(cleaned);
            JsonNode findings = root.path("findings");
            List<AnalysisResult.Check> checks = new ArrayList<>();
            if (findings.isArray()) {
                for (JsonNode f : findings) {
                    String status = f.path("status").asText("WARNING");
                    if (!List.of("OK", "WARNING", "FAILED").contains(status)) status = "WARNING";
                    checks.add(AnalysisResult.Check.builder()
                        .category("Vision IA — Forensique")
                        .label(f.path("label").asText("Analyse visuelle"))
                        .status(status)
                        .detail(f.path("detail").asText(""))
                        .build());
                }
            }
            return checks;
        } catch (Exception e) {
            System.err.println("[ClaudeVision] Forensic parse failed: " + e.getMessage());
            return List.of();
        }
    }

    // ── PDF rendering ─────────────────────────────────────────────────────────

    private String renderFirstPageToBase64(byte[] pdfBytes) {
        return renderPageToBase64(pdfBytes, 0, 200);
    }

    private String renderPageToBase64(byte[] pdfBytes, int page, int dpi) {
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            PDFRenderer renderer = new PDFRenderer(doc);
            BufferedImage image = renderer.renderImageWithDPI(
                Math.min(page, doc.getNumberOfPages() - 1), dpi);
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
                Réponds UNIQUEMENT avec un objet JSON valide, sans texte avant ou après.

                RÈGLES IMPORTANTES pour les montants :
                - SALAIRE BRUT : c'est le TOTAL de la rémunération brute AVANT toutes les cotisations, \
                souvent libellé "Salaire Brut", "Brut Fiscal", "Total Brut" ou "Rémunération Brute". \
                Ce montant est typiquement entre 1 000 € et 15 000 €. \
                IGNORE les colonnes "Taux", "Base", "Unité", "Parts patronales", "Parts salariales" — \
                ce sont des taux et bases de calcul, pas des montants de salaire total.
                - NET À PAYER : c'est le montant FINAL versé à l'employé, souvent dans un encadré \
                séparé en bas du bulletin. Cherche "NET A PAYER", "Net à payer", "Net versé". \
                C'est le PLUS GRAND montant visible dans cet encadré NET A PAYER. \
                IGNORE "Net fiscal" et "Net imposable" — ce sont des montants fiscaux annuels.
                - PÉRIODE : si les dates sont numériques (ex: 01-07-2021 au 31-07-2021), \
                convertis en "Juillet 2021".

                Format JSON attendu :
                {
                  "employeur": "nom de l'entreprise employeur, ou null",
                  "siret": "14 chiffres sans espaces, ou null",
                  "employe": "prénom et nom complet de l'employé, ou null",
                  "periode": "mois et année (ex: Juillet 2021), ou null",
                  "salaireBrut": valeur numérique décimale du brut total (ex: 4249.60), ou null,
                  "salaireNet": valeur numérique décimale du net à payer (ex: 2865.70), ou null
                }
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
