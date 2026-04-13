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
        "microsoft dynamics", "paye", "isapaye",
        // Government / URSSAF platforms
        "urssaf", "tese", "cea", "net-entreprises", "dsn",
        // Common PDF generation libraries used by payroll software
        "reportlab", "jasper", "itext", "fpdf", "tcpdf", "crystal reports",
        "fo2pdf", "apache fop", "xsl-fo"
    );

    private static final List<String> SUSPICIOUS_PRODUCERS = List.of(
        "microsoft word", "libreoffice writer", "openoffice", "adobe photoshop",
        "gimp", "canva", "google docs", "pages", "inkscape"
    );

    // Words that appear in table headers — not a valid company name
    private static final List<String> TABLE_HEADER_WORDS = List.of(
        "taux", "montant", "base", "salariale", "patronale", "employeur",
        "cotisation", "brut", "net", "total"
    );

    // Cotisation fund / institution names — must not be matched as employee names
    private static final List<String> NOT_EMPLOYEE_KEYWORDS = List.of(
        "agirc", "arrco", "malakoff", "humanis", "prevoyance", "prévoyance",
        "retraite", "complementaire", "complémentaire", "assurance", "mutuelle",
        "securite", "sécurité", "sociale", "chomage", "chômage", "prelevement",
        "prélèvement", "cotisation", "urssaf", "cipav", "ircantec",
        "ag2r", "apec", "fafiec", "opco", "prev", "soin", "tranche"
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

            // Normalize metadata — some PDF generators output "null" as a literal string
            String rawProducer = Optional.ofNullable(info.getProducer()).orElse("").toLowerCase();
            String rawCreator = Optional.ofNullable(info.getCreator()).orElse("").toLowerCase();
            final String producer = "null".equals(rawProducer) ? "" : rawProducer;
            final String creator = "null".equals(rawCreator) ? "" : rawCreator;
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
            } else if (producer.isBlank() && creator.isBlank()) {
                // No metadata at all — normal for government portals (URSSAF TESE) and
                // many enterprise payroll systems that don't embed software metadata
                checks.add(AnalysisResult.Check.builder()
                    .category("Métadonnées")
                    .label("Logiciel de création")
                    .status("OK")
                    .detail("Métadonnées logiciel absentes — courant pour les portails officiels et logiciels de paie professionnels")
                    .build());
            } else {
                // A value IS present but not in our whitelist — worth noting
                checks.add(AnalysisResult.Check.builder()
                    .category("Métadonnées")
                    .label("Logiciel de création")
                    .status("WARNING")
                    .detail("Logiciel non reconnu : " + (producer.isBlank() ? creator : producer).trim())
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

            AnalysisResult.DocumentInfo docInfo = extractDocumentInfo(rawText, producer, creationDate, modDate, wasModified, pageCount);
            return new PdfAnalysisData(rawText, docInfo, checks);
        }
    }

    private AnalysisResult.DocumentInfo extractDocumentInfo(String text, String producer,
                                                              String creationDate, String modDate,
                                                              boolean wasModified, int pageCount) {
        String[] lines = text.split("\\r?\\n");
        return AnalysisResult.DocumentInfo.builder()
            .employeur(extractEmployeur(text, lines))
            .siret(extractSiret(text))
            .employe(extractEmploye(text, lines))
            .periode(extractPeriode(text, lines))
            .salaireBrut(extractAmountNearLabel(lines, "salaire brut", "rémunération brute", "rémunération brut", "total rémunération brute", "total rémunération brut", "brut total", "total brut"))
            .salaireNet(extractAmountNearLabel(lines, "net à payer", "net a payer", "net payé", "net paye", "net versé", "net verse", "net imposable", "net fiscal"))
            .pdfCreatedWith(producer.isBlank() ? "Inconnu" : producer)
            .pdfCreationDate(creationDate)
            .pdfModifiedDate(modDate)
            .pdfModified(wasModified)
            .pageCount(pageCount)
            .build();
    }

    // ── Employeur ─────────────────────────────────────────────────────────────

    private String extractEmployeur(String text, String[] lines) {
        // 1. Prefer "Raison sociale : <NAME>" — most explicit label
        for (String line : lines) {
            Matcher m = Pattern.compile("(?i)raison\\s+soci[ae]le\\s*[:\\s]+(.+)").matcher(line);
            if (m.find()) {
                String name = m.group(1).trim();
                if (!name.isBlank() && name.length() > 2 && !isTableHeader(name)) return name;
            }
        }
        // 2. Look for a known legal entity prefix (SAS, SARL, etc.)
        Matcher m = Pattern.compile("(?m)\\b((?:SAS|SARL|SA|SCI|SASU|EURL|EIRL|SNC|SCP|SCOP)\\s+[A-ZÀÂÉÈÊÎÔÙÛÇ][A-ZÀÂÉÈÊÎÔÙÛÇ\\s&\\-']{1,60})").matcher(text);
        if (m.find()) return m.group(1).trim();
        // 3. Fall back: first non-table-header uppercase word group after "EMPLOYEUR" label
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].toLowerCase().contains("employeur") || lines[i].toLowerCase().contains("l'employeur")) {
                for (int j = i + 1; j < Math.min(i + 5, lines.length); j++) {
                    String candidate = lines[j].trim();
                    if (!candidate.isBlank() && candidate.length() > 3 && !isTableHeader(candidate)) {
                        if (candidate.matches("[A-ZÀÂÉÈÊÎÔÙÛÇ][A-ZÀÂÉÈÊÎÔÙÛÇ\\s&\\-']+")) return candidate;
                    }
                }
            }
        }
        return null;
    }

    private boolean isTableHeader(String text) {
        String lower = text.toLowerCase();
        int matches = 0;
        for (String word : TABLE_HEADER_WORDS) {
            if (lower.contains(word)) matches++;
        }
        return matches >= 2;
    }

    // ── Employé ───────────────────────────────────────────────────────────────

    private String extractEmploye(String text, String[] lines) {
        // 1. "Monsieur / Madame <FIRSTNAME LASTNAME>" — addressee block
        Matcher m = Pattern.compile("(?i)(?:Monsieur|Madame|M\\.?|Mme\\.?)\\s+([A-ZÀÂÉÈÊÎÔÙÛÇ][A-ZÀÂÉÈÊÎÔÙÛÇ\\s\\-]{2,50})").matcher(text);
        while (m.find()) {
            String name = m.group(1).trim();
            String lower = name.toLowerCase();
            // Exclude address lines and institution names
            if (!lower.contains("rue") && !lower.contains("avenue")
                    && !lower.contains("boulevard") && !lower.contains("allée")
                    && !isInstitutionName(lower)) {
                return name;
            }
        }
        // 2. Extract Prénom + Nom from label lines, skipping institution names
        String prenom = null, nom = null;
        for (String line : lines) {
            if (prenom == null) {
                Matcher pm = Pattern.compile("(?i)pr[eé]nom\\s*[:\\s]+([A-ZÀÂÉÈÊÎÔÙÛÇ][A-Za-zÀ-ÿ\\-]+)").matcher(line);
                if (pm.find()) {
                    String candidate = pm.group(1).trim();
                    if (!isInstitutionName(candidate.toLowerCase())) prenom = candidate;
                }
            }
            if (nom == null) {
                // "Nom :" only — require colon to avoid matching "Nombre", "Nominal", etc.
                Matcher nm = Pattern.compile("(?i)\\bnom\\s*:\\s*([A-ZÀÂÉÈÊÎÔÙÛÇ][A-Za-zÀ-ÿ\\-]+)").matcher(line);
                if (nm.find()) {
                    String candidate = nm.group(1).trim();
                    if (!isInstitutionName(candidate.toLowerCase())) nom = candidate;
                }
            }
        }
        if (prenom != null && nom != null) return prenom + " " + nom;
        if (prenom != null) return prenom;
        if (nom != null) return nom;
        return null;
    }

    private boolean isInstitutionName(String lowerName) {
        return NOT_EMPLOYEE_KEYWORDS.stream().anyMatch(lowerName::contains);
    }

    // ── Période ───────────────────────────────────────────────────────────────

    private String extractPeriode(String text, String[] lines) {
        // Match "novembre 2025" or "Novembre 2025"
        Matcher m = Pattern.compile("(?i)(janvier|f[eé]vrier|mars|avril|mai|juin|juillet|ao[uû]t|septembre|octobre|novembre|d[eé]cembre)\\s+(20\\d{2})").matcher(text);
        if (m.find()) return capitalize(m.group(1)) + " " + m.group(2);
        // Match "du 01/11/2025 au 30/11/2025" or "du 01/11/25 au 30/11/25"
        m = Pattern.compile("(?i)p[eé]riode[^\\n]{0,30}du\\s+(\\d{1,2}[/.]\\d{1,2}[/.]\\d{2,4})").matcher(text);
        if (m.find()) return "du " + m.group(1);
        // Match "Salaire versé le 30/11/2025"
        m = Pattern.compile("(?i)salaire\\s+vers[eé]\\s+le\\s+(\\d{1,2}[/.]\\d{1,2}[/.]\\d{2,4})").matcher(text);
        if (m.find()) return m.group(1);
        return null;
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1).toLowerCase();
    }

    // ── Amount near label ─────────────────────────────────────────────────────

    /**
     * Finds a monetary amount on the same line as a label, or on the immediately
     * following line. Handles multi-column PDF table layouts.
     */
    private String extractAmountNearLabel(String[] lines, String... labels) {
        for (int i = 0; i < lines.length; i++) {
            String lower = lines[i].toLowerCase();
            for (String label : labels) {
                if (lower.contains(label.toLowerCase())) {
                    // Try same line first
                    String amount = findMonetaryAmount(lines[i]);
                    if (amount != null) return amount + " €";
                    // Try next line
                    if (i + 1 < lines.length) {
                        amount = findMonetaryAmount(lines[i + 1]);
                        if (amount != null) return amount + " €";
                    }
                }
            }
        }
        return null;
    }

    /**
     * Extracts the first plausible salary amount (100–200000) from a line of text.
     */
    private String findMonetaryAmount(String text) {
        // Match numbers like "3 224,64" or "3224.64" or "3224,64" or "3 224.64"
        Matcher m = Pattern.compile("([0-9]{1,3}(?:[\\s\u00a0][0-9]{3})*(?:[.,][0-9]{1,2})?)").matcher(text);
        while (m.find()) {
            String raw = m.group(1).replaceAll("[\\s\u00a0]", "").replace(",", ".");
            try {
                double val = Double.parseDouble(raw);
                if (val >= 100 && val <= 200000) return m.group(1).trim();
            } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    // ── SIRET ─────────────────────────────────────────────────────────────────

    private String extractSiret(String text) {
        // Explicit "SIRET : XXXXXXXXXXXXXX" label
        Matcher m = Pattern.compile("(?i)siret\\s*[:\\s]+([0-9]{3}[\\s]?[0-9]{3}[\\s]?[0-9]{3}[\\s]?[0-9]{5})").matcher(text);
        if (m.find()) return m.group(1).replaceAll("\\s", "");
        // Bare 14-digit number
        m = Pattern.compile("\\b([0-9]{14})\\b").matcher(text);
        if (m.find()) return m.group(1);
        return null;
    }
}
