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
 * Uses GPT-4o Vision (OpenAI) to extract payslip fields from a PDF rendered as an image,
 * and to detect visual signs of forgery.
 * Reuses the same openai.api.key and openai.model as AiAnalysisService.
 * Falls back gracefully (returns null / empty list) when disabled or on any error.
 */
@Service
public class ClaudeVisionService {

    private static final String OPENAI_URL = "https://api.openai.com/v1/chat/completions";

    @Value("${openai.api.key:}")
    private String apiKey;

    @Value("${openai.model:gpt-4o}")
    private String model;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    public void init() {
        if (isEnabled()) {
            System.out.println("[VisionService] ✓ OpenAI API key detected — Vision extraction ACTIVE (model: " + model + ")");
        } else {
            System.out.println("[VisionService] ✗ No OpenAI API key found — Vision extraction DISABLED, falling back to regex");
        }
    }

    public boolean isEnabled() {
        return apiKey != null && !apiKey.isBlank() && !apiKey.startsWith("YOUR_");
    }

    /** Structured result from GPT Vision extraction. */
    public record VisionExtraction(
        String employeur,
        String siret,
        String employe,
        String periode,
        String salaireBrut,
        String salaireNet
    ) {}

    /**
     * Renders the first page of the PDF to a PNG image and asks GPT-4o Vision to extract
     * payslip fields. Returns null if disabled, rendering fails, or API call fails.
     */
    public VisionExtraction extractFields(byte[] pdfBytes) {
        if (!isEnabled()) return null;
        try {
            String base64Image = renderPageToBase64(pdfBytes, 0, 200);
            if (base64Image == null) return null;
            String jsonResponse = callVisionApi(base64Image, buildExtractionPrompt(), 512, true);
            return parseExtraction(jsonResponse);
        } catch (Exception e) {
            System.err.println("[VisionService] Extraction failed: " + e.getMessage());
            return null;
        }
    }

    // ── Forgery detection ─────────────────────────────────────────────────────

    /**
     * Sends a high-res render of the PDF to GPT-4o Vision with a forensic prompt.
     * Returns an empty list if Vision is disabled or the call fails.
     */
    public List<AnalysisResult.Check> detectForgery(byte[] pdfBytes) {
        if (!isEnabled()) return List.of();
        try {
            String base64Image = renderPageToBase64(pdfBytes, 0, 250);
            if (base64Image == null) return List.of();
            String jsonResponse = callVisionApi(base64Image, buildForensicPrompt(), 1024, false);
            return parseForensicResponse(jsonResponse);
        } catch (Exception e) {
            System.err.println("[VisionService] Forgery detection failed: " + e.getMessage());
            return List.of();
        }
    }

    // ── API call ──────────────────────────────────────────────────────────────

    private String callVisionApi(String base64Image, String prompt, int maxTokens, boolean jsonMode) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        body.put("temperature", 0);

        if (jsonMode) {
            ObjectNode responseFormat = objectMapper.createObjectNode();
            responseFormat.put("type", "json_object");
            body.set("response_format", responseFormat);
        }

        ArrayNode messages = objectMapper.createArrayNode();
        ObjectNode userMsg = objectMapper.createObjectNode();
        userMsg.put("role", "user");

        ArrayNode content = objectMapper.createArrayNode();

        // Image block (GPT Vision format)
        ObjectNode imageBlock = objectMapper.createObjectNode();
        imageBlock.put("type", "image_url");
        ObjectNode imageUrl = objectMapper.createObjectNode();
        imageUrl.put("url", "data:image/png;base64," + base64Image);
        imageUrl.put("detail", "high");
        imageBlock.set("image_url", imageUrl);
        content.add(imageBlock);

        // Text prompt block
        ObjectNode textBlock = objectMapper.createObjectNode();
        textBlock.put("type", "text");
        textBlock.put("text", prompt);
        content.add(textBlock);

        userMsg.set("content", content);
        messages.add(userMsg);
        body.set("messages", messages);

        HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(body), headers);
        ResponseEntity<String> response = restTemplate.exchange(OPENAI_URL, HttpMethod.POST, entity, String.class);

        JsonNode root = objectMapper.readTree(response.getBody());
        return root.path("choices").get(0).path("message").path("content").asText();
    }

    // ── Prompts ───────────────────────────────────────────────────────────────

    private String buildExtractionPrompt() {
        return """
                Tu es un expert en bulletins de salaire français.
                Analyse cette image d'un bulletin de salaire et extrait les informations suivantes.
                Réponds UNIQUEMENT avec un objet JSON valide, sans texte avant ou après.

                RÈGLE CRITIQUE pour le SIRET :
                - Le SIRET est une séquence de 14 chiffres consécutifs. \
                Lis-le chiffre par chiffre, de gauche à droite, sans jamais inverser ni regrouper. \
                Attention aux confusions visuelles fréquentes : 0/6, 1/7, 5/6, 3/8. \
                Si tu lis "SIRET : 852 070 796 00028", retourne "85207079600028" (sans espaces). \
                Ne jamais approximer — chaque chiffre compte.

                RÈGLES IMPORTANTES pour les montants :
                - SALAIRE BRUT : c'est le TOTAL de la rémunération brute AVANT toutes les cotisations, \
                souvent libellé "Salaire Brut", "Brut Fiscal", "Total Brut" ou "Rémunération Brute". \
                Ce montant est typiquement entre 1 000 € et 15 000 €. \
                IGNORE les colonnes "Taux", "Base", "Unité", "Parts patronales", "Parts salariales" — \
                ce sont des taux et bases de calcul, pas des montants de salaire total.
                - NET À PAYER (salaireNet) : c'est le "net à payer AVANT prélèvement à la source". \
                C'est le montant dans l'encadré qui PRÉCÈDE la ligne du prélèvement à la source (PAS). \
                Cherche "NET A PAYER AVANT PRELEVEMENT A LA SOURCE", "Net à payer avant impôt sur le revenu". \
                IGNORE absolument : "Montant net social", "Net imposable", "Net fiscal", \
                et le net APRÈS prélèvement à la source (montant final après déduction du PAS). \
                salaireNet = net à payer AVANT prélèvement à la source.
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

    // ── PDF rendering ─────────────────────────────────────────────────────────

    private String renderPageToBase64(byte[] pdfBytes, int page, int dpi) {
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            PDFRenderer renderer = new PDFRenderer(doc);
            BufferedImage image = renderer.renderImageWithDPI(
                Math.min(page, doc.getNumberOfPages() - 1), dpi);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "png", baos);
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (Exception e) {
            System.err.println("[VisionService] PDF rendering failed: " + e.getMessage());
            return null;
        }
    }

    // ── Response parsing ──────────────────────────────────────────────────────

    private VisionExtraction parseExtraction(String json) {
        try {
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

            String salaireBrut = brutVal != null ? String.format("%.2f €", brutVal) : null;
            String salaireNet  = netVal  != null ? String.format("%.2f €", netVal)  : null;

            return new VisionExtraction(employeur, siret, employe, periode, salaireBrut, salaireNet);
        } catch (Exception e) {
            System.err.println("[VisionService] JSON parse failed: " + e.getMessage() + " | raw: " + json);
            return null;
        }
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
            System.err.println("[VisionService] Forensic parse failed: " + e.getMessage());
            return List.of();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

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
        try {
            double d = Double.parseDouble(val.asText().replace(",", ".").replaceAll("[^0-9.]", ""));
            return d > 0 ? d : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
