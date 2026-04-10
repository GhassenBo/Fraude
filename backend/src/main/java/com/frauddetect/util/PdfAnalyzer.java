package com.frauddetect.util;

import com.frauddetect.model.AnalysisResult;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.*;
import java.util.regex.*;

@Component
public class PdfAnalyzer {

    // Known payroll software producers
    private static final List<String> LEGIT_PRODUCERS = List.of(
        "sage", "adp", "silae", "cegid", "hr access", "peopledoc",
        "payfit", "lucca", "workday", "sap", "oracle", "quadratus",
        "microsoft dynamics", "paye", "isapaye"
    );

    private static final List<String> SUSPICIOUS_PRODUCERS = List.of(
        "microsoft word", "libreoffice writer", "openoffice", "adobe photoshop",
        "gimp", "canva", "google docs", "pages", "inkscape"
    );

    public record PdfAnalysisData(
        String rawText,
        AnalysisResult.DocumentInfo documentInfo,
        List<AnalysisResult.Check> metadataChecks
    ) {}

    public PdfAnalysisData analyze(InputStream inputStream) throws Exception {
        byte[] bytes = inputStream.readAllBytes();
        List<AnalysisResult.Check> checks = new ArrayList<>();

        try (PDDocument document = Loader.loadPDF(bytes)) {
            PDDocumentInformation info = document.getDocumentInformation();
            PDFTextStripper stripper = new PDFTextStripper();
            String rawText = stripper.getText(document);

            String producer = Optional.ofNullable(info.getProducer()).orElse("").toLowerCase();
            String creator = Optional.ofNullable(info.getCreator()).orElse("").toLowerCase();
            String creationDate = info.getCreationDate() != null ? info.getCreationDate().getTime().toString() : "Inconnue";
            String modDate = info.getModificationDate() != null ? info.getModificationDate().getTime().toString() : null;
            boolean wasModified = modDate != null && !modDate.equals(creationDate);
            int pageCount = document.getNumberOfPages();

            // Check 1: Producer software
            boolean isSuspiciousProducer = SUSPICIOUS_PRODUCERS.stream().anyMatch(s -> producer.contains(s) || creator.contains(s));
            boolean isLegitProducer = LEGIT_PRODUCERS.stream().anyMatch(s -> producer.contains(s) || creator.contains(s));

            if (isSuspiciousProducer) {
                checks.add(AnalysisResult.Check.builder()
                    .category("Métadonnées")
                    .label("Logiciel de création")
                    .status("FAILED")
                    .detail("Document créé avec " + (producer.isBlank() ? creator : producer) + " — pas un logiciel de paie")
                    .build());
            } else if (isLegitProducer) {
                checks.add(AnalysisResult.Check.builder()
                    .category("Métadonnées")
                    .label("Logiciel de création")
                    .status("OK")
                    .detail("Logiciel de paie reconnu : " + (producer.isBlank() ? creator : producer))
                    .build());
            } else {
                checks.add(AnalysisResult.Check.builder()
                    .category("Métadonnées")
                    .label("Logiciel de création")
                    .status("WARNING")
                    .detail("Logiciel non reconnu : " + (producer.isBlank() && creator.isBlank() ? "Inconnu" : producer + " " + creator))
                    .build());
            }

            // Check 2: Modification after creation
            if (wasModified) {
                checks.add(AnalysisResult.Check.builder()
                    .category("Métadonnées")
                    .label("Modification du document")
                    .status("FAILED")
                    .detail("Document modifié après sa création — date de modification : " + modDate)
                    .build());
            } else {
                checks.add(AnalysisResult.Check.builder()
                    .category("Métadonnées")
                    .label("Modification du document")
                    .status("OK")
                    .detail("Aucune modification détectée après création")
                    .build());
            }

            // Check 3: Page count
            if (pageCount > 3) {
                checks.add(AnalysisResult.Check.builder()
                    .category("Métadonnées")
                    .label("Nombre de pages")
                    .status("WARNING")
                    .detail("Bulletin de " + pageCount + " pages — inhabituellement long")
                    .build());
            } else {
                checks.add(AnalysisResult.Check.builder()
                    .category("Métadonnées")
                    .label("Nombre de pages")
                    .status("OK")
                    .detail(pageCount + " page(s) — normal pour un bulletin de salaire")
                    .build());
            }

            // Extract document info from text
            AnalysisResult.DocumentInfo docInfo = extractDocumentInfo(rawText, producer, creationDate, modDate, wasModified, pageCount);

            return new PdfAnalysisData(rawText, docInfo, checks);
        }
    }

    private AnalysisResult.DocumentInfo extractDocumentInfo(String text, String producer,
                                                              String creationDate, String modDate,
                                                              boolean wasModified, int pageCount) {
        return AnalysisResult.DocumentInfo.builder()
            .employeur(extractField(text, "(?i)(employeur|société|entreprise|raison sociale)[\\s:]*([A-Z][^\\n]{2,50})"))
            .siret(extractSiret(text))
            .employe(extractField(text, "(?i)(nom|salarié|employé)[\\s:]*([A-Z][A-Z\\s-]{2,40})"))
            .periode(extractField(text, "(?i)(période|mois|du)[\\s:]*([A-Za-zéàû]+\\s+\\d{4})"))
            .salaireBrut(extractMontant(text, "(?i)(salaire\\s*brut|brut\\s+total)[\\s:€]*([\\d\\s,.]+)"))
            .salaireNet(extractMontant(text, "(?i)(net\\s+[àa]\\s+payer|net\\s+payé|net\\s+imposable)[\\s:€]*([\\d\\s,.]+)"))
            .pdfCreatedWith(producer.isBlank() ? "Inconnu" : producer)
            .pdfCreationDate(creationDate)
            .pdfModifiedDate(modDate)
            .pdfModified(wasModified)
            .pageCount(pageCount)
            .build();
    }

    private String extractField(String text, String regex) {
        try {
            Matcher m = Pattern.compile(regex).matcher(text);
            if (m.find() && m.groupCount() >= 2) return m.group(2).trim();
        } catch (Exception ignored) {}
        return null;
    }

    private String extractSiret(String text) {
        Matcher m = Pattern.compile("(?i)siret[\\s:]*([0-9]{14}|[0-9]{3}\\s[0-9]{3}\\s[0-9]{3}\\s[0-9]{5})").matcher(text);
        if (m.find()) return m.group(1).replaceAll("\\s", "");
        // Try raw 14-digit number
        m = Pattern.compile("\\b([0-9]{14})\\b").matcher(text);
        if (m.find()) return m.group(1);
        return null;
    }

    private String extractMontant(String text, String regex) {
        try {
            Matcher m = Pattern.compile(regex).matcher(text);
            if (m.find() && m.groupCount() >= 2) {
                String raw = m.group(2).trim().replaceAll("\\s", "");
                return raw + " €";
            }
        } catch (Exception ignored) {}
        return null;
    }
}
