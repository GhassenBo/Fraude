package com.frauddetect.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.frauddetect.model.AnalysisResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;

@Service
public class AiAnalysisService {

    @Value("${openai.api.key:}")
    private String apiKey;

    @Value("${openai.model:gpt-4o}")
    private String model;

    private static final String OPENAI_URL = "https://api.openai.com/v1/chat/completions";
    private static final int MAX_TEXT_LENGTH = 3000;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public boolean isEnabled() {
        return apiKey != null && !apiKey.isBlank() && !apiKey.startsWith("YOUR_");
    }

    public List<AnalysisResult.Check> analyze(String pdfText, AnalysisResult.DocumentInfo docInfo) {
        if (!isEnabled()) {
            return List.of();
        }

        try {
            String truncatedText = pdfText.length() > MAX_TEXT_LENGTH
                ? pdfText.substring(0, MAX_TEXT_LENGTH) + "\n...[texte tronqué]"
                : pdfText;

            String prompt = buildPrompt(truncatedText, docInfo);
            String jsonResponse = callOpenAi(prompt);
            return parseResponse(jsonResponse);

        } catch (Exception e) {
            return List.of(AnalysisResult.Check.builder()
                .category("Analyse IA")
                .label("GPT-4 indisponible")
                .status("WARNING")
                .detail("L'analyse IA n'a pas pu être effectuée : " + e.getMessage())
                .build());
        }
    }

    private String buildPrompt(String pdfText, AnalysisResult.DocumentInfo docInfo) {
        return String.format("""
            Tu es un expert en détection de fraude documentaire sur les bulletins de salaire français.

            Voici les informations extraites automatiquement du document :
            - Employeur    : %s
            - Employé      : %s
            - Période      : %s
            - Salaire brut           : %s
            - Net avant PAS (avant prélèvement à la source) : %s

            Texte brut du bulletin :
            ---
            %s
            ---

            Analyse ce document et détecte toute anomalie, incohérence ou signe de falsification.
            Vérifie notamment :
            - La cohérence entre l'intitulé du poste et le niveau de salaire
            - Les libellés de cotisations inhabituels ou absents
            - La cohérence des dates (période, ancienneté, congés)
            - La présence de formulations copiées-collées ou génériques
            - Tout autre élément suspect

            Réponds UNIQUEMENT en JSON valide avec ce format exact (5 findings maximum) :
            {
              "findings": [
                { "label": "...", "status": "OK|WARNING|FAILED", "detail": "..." }
              ]
            }
            """,
            safe(docInfo.getEmployeur()),
            safe(docInfo.getEmploye()),
            safe(docInfo.getPeriode()),
            safe(docInfo.getSalaireBrut()),
            safe(docInfo.getSalaireNet()),
            pdfText
        );
    }

    private String safe(String value) {
        return value != null ? value : "Non détecté";
    }

    private String callOpenAi(String userPrompt) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        body.put("temperature", 0.1);

        ObjectNode responseFormat = objectMapper.createObjectNode();
        responseFormat.put("type", "json_object");
        body.set("response_format", responseFormat);

        ArrayNode messages = objectMapper.createArrayNode();

        ObjectNode systemMsg = objectMapper.createObjectNode();
        systemMsg.put("role", "system");
        systemMsg.put("content", "Tu es un expert en fraude documentaire. Réponds toujours en JSON valide.");
        messages.add(systemMsg);

        ObjectNode userMsg = objectMapper.createObjectNode();
        userMsg.put("role", "user");
        userMsg.put("content", userPrompt);
        messages.add(userMsg);

        body.set("messages", messages);

        HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(body), headers);
        ResponseEntity<String> response = restTemplate.exchange(OPENAI_URL, HttpMethod.POST, entity, String.class);

        JsonNode root = objectMapper.readTree(response.getBody());
        return root.path("choices").get(0).path("message").path("content").asText();
    }

    private List<AnalysisResult.Check> parseResponse(String jsonResponse) throws Exception {
        List<AnalysisResult.Check> checks = new ArrayList<>();
        JsonNode root = objectMapper.readTree(jsonResponse);
        JsonNode findings = root.path("findings");

        if (findings.isArray()) {
            for (JsonNode finding : findings) {
                String status = finding.path("status").asText("WARNING");
                if (!List.of("OK", "WARNING", "FAILED").contains(status)) {
                    status = "WARNING";
                }
                checks.add(AnalysisResult.Check.builder()
                    .category("Analyse IA")
                    .label(finding.path("label").asText("Vérification IA"))
                    .status(status)
                    .detail(finding.path("detail").asText(""))
                    .build());
            }
        }

        return checks;
    }
}
