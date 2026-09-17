package com.frauddetect.service;

import com.frauddetect.model.AnalysisResult;
import com.frauddetect.model.AvisImposition;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rapproche un avis d'imposition des bulletins de salaire.
 *
 * L'avis est bien plus difficile a falsifier de facon credible qu'un bulletin, et
 * son authenticite peut etre confirmee aupres de l'administration a partir du
 * numero fiscal et de la reference de l'avis. Cette verification finale reste
 * manuelle : aucune API publique ne l'expose, et automatiser l'interrogation du
 * service en ligne contreviendrait a ses conditions d'utilisation.
 */
@Service
public class AvisImpositionService {

    /** Ecart tolere entre le revenu declare et le cumul des bulletins. */
    private static final double TOLERANCE = 0.15;

    @Value("${app.avis-imposition.verification-url:}")
    private String verificationUrl;

    // Numero fiscal : 13 chiffres, souvent presentes par groupes.
    private static final Pattern NUMERO_FISCAL = Pattern.compile(
        "(?i)num[eé]ro fiscal\\s*:?\\s*((?:\\d[\\s.]?){13})");

    // Reference de l'avis : 13 caracteres alphanumeriques.
    private static final Pattern REFERENCE_AVIS = Pattern.compile(
        "(?i)r[eé]f[eé]rence de l'?avis\\s*:?\\s*((?:[0-9A-Z][\\s.]?){13})");

    // Libelles reels du tableau de revenus, par ordre de pertinence.
    private static final List<String> LABELS_SALAIRES = List.of(
        "total des salaires et assimiles", "salaires, pensions, rentes nets",
        "traitements, salaires", "traitements et salaires", "salaires");

    private static final String LABEL_REVENU_FISCAL = "revenu fiscal de reference";

    // Les montants suivent les points de conduite. Un renvoi numerote est souvent
    // accole au libelle ("assimiles 2", "reference 25") : le decoupage l'ecarte.
    private static final Pattern POINTS_DE_CONDUITE = Pattern.compile("\\.{4,}");

    private static final Pattern MONTANT = Pattern.compile(
        "(?<![0-9.,])((?:\\d{1,3}(?:[\\s\u00a0]\\d{3})+|\\d+)(?:[.,]\\d{2})?)(?![0-9])");

    // "Declarant 1 - Nom de naissance : MARTIN  JEAN"
    private static final Pattern DECLARANT = Pattern.compile(
        "(?i)d[e\u00e9]clarant\\s*\\d\\s*-\\s*nom de naissance\\s*:\\s*([A-Z\u00c0-\u00dc' -]{3,60})");

    // Couvre "revenus 2025", "revenus de 2025" et "revenus de l'annee 2025".
    private static final Pattern ANNEE_REVENUS = Pattern.compile(
        "(?i)revenus?\\s*(?:de\\s*(?:l'?ann[eé]e\\s*)?)?(20\\d{2})");

    /**
     * Extraction limitee au texte : l'analyse Vision et les controles forensiques
     * du pipeline des bulletins n'apportent rien sur un avis d'imposition et
     * consommeraient inutilement le quota d'API.
     */
    public AvisImposition extractFromPdf(InputStream inputStream) throws Exception {
        try (PDDocument document = Loader.loadPDF(inputStream.readAllBytes())) {
            // Sans tri par position, la mise en page en colonnes de l'avis detache
            // les montants de leurs libelles : ils sortent dans des blocs distincts.
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return extract(stripper.getText(document));
        }
    }

    public AvisImposition extract(String rawText) {
        // La typographie francaise place une espace insecable avant les
        // deux-points, que \\s ne reconnait pas : les libelles du document en
        // contiennent, et toute expression les traversant echouerait.
        String text = rawText.replace('\u00a0', ' ');
        String[] lines = text.split("\\r?\\n");
        List<Double> salaires = amountsForLabel(lines, LABELS_SALAIRES);
        List<Double> revenuFiscal = amountsForLabel(lines, List.of(LABEL_REVENU_FISCAL));

        return AvisImposition.builder()
            .anneeRevenus(parseInt(firstGroup(ANNEE_REVENUS, text)))
            .salairesDeclares(salaires)
            .revenuFiscalReference(revenuFiscal.isEmpty() ? null : revenuFiscal.get(0))
            .nomsDeclarants(extractDeclarants(text))
            .build();
    }

    /** Noms de naissance des declarants, espaces multiples reduits. */
    private List<String> extractDeclarants(String text) {
        List<String> noms = new ArrayList<>();
        Matcher m = DECLARANT.matcher(text);
        while (m.find()) {
            String nom = m.group(1).trim().replaceAll("\\s+", " ");
            if (!nom.isBlank() && !noms.contains(nom)) noms.add(nom);
        }
        return noms;
    }

    /**
     * Montants portes par la premiere ligne correspondant a l'un des libelles.
     * Un avis de foyer presente une colonne par declarant et un total : tous sont
     * conserves, le rapprochement retenant celle qui concerne le candidat.
     */
    private List<Double> amountsForLabel(String[] lines, List<String> labels) {
        for (String label : labels) {
            for (String line : lines) {
                if (!normalize(line).contains(label)) continue;

                String[] parts = POINTS_DE_CONDUITE.split(line, 2);
                String zone = parts.length > 1 ? parts[1] : line;

                List<Double> amounts = new ArrayList<>();
                Matcher m = MONTANT.matcher(zone);
                while (m.find()) {
                    Double v = parseAmount(m.group(1));
                    if (v != null && v > 0) amounts.add(v);
                }
                if (!amounts.isEmpty()) return amounts;
            }
        }
        return List.of();
    }

    /**
     * @param netImposableMensuel net imposable releve sur les bulletins, ou null
     *                            si aucun bulletin n'a ete fourni.
     */
    public List<AnalysisResult.Check> verify(AvisImposition avis, Double netImposableMensuel) {
        List<AnalysisResult.Check> checks = new ArrayList<>();
        String category = "Avis d'imposition";

        List<Double> salaires = avis.getSalairesDeclares();
        if (salaires == null || salaires.isEmpty()) {
            checks.add(check(category, "Revenus déclarés", "WARNING",
                "Montant des salaires introuvable sur l'avis — vérifiez qu'il s'agit"
                    + " bien d'un avis d'imposition et non d'un autre document fiscal"));
            return checks;
        }

        if (netImposableMensuel == null || netImposableMensuel <= 0) {
            checks.add(check(category, "Revenus déclarés", "OK",
                String.format("Salaires déclarés relevés sur l'avis : %s"
                    + " — renseignez le net imposable d'un bulletin pour permettre"
                    + " le rapprochement", formatAmounts(salaires))));
            return checks;
        }

        double attendu = netImposableMensuel * 12;

        // Un avis de foyer cumule les revenus des deux declarants : comparer le
        // total aux bulletins d'une seule personne signalerait a tort tous les
        // couples. Le rapprochement retient donc la colonne la plus proche.
        double meilleurEcart = salaires.stream()
            .mapToDouble(v -> Math.abs(v - attendu) / attendu)
            .min().orElse(Double.MAX_VALUE);

        String mention = salaires.size() > 1
            ? String.format(" (avis de foyer, %d montants déclarés : %s — le plus proche"
                + " des bulletins est retenu)", salaires.size(), formatAmounts(salaires))
            : "";

        if (meilleurEcart > TOLERANCE) {
            checks.add(check(category, "Rapprochement avis / bulletins", "FAILED",
                String.format(
                    "Aucun revenu déclaré ne correspond aux bulletins : %.0f € attendus"
                        + " sur douze mois, écart minimal de %.0f %%%s",
                    attendu, meilleurEcart * 100, mention)));
        } else {
            checks.add(check(category, "Rapprochement avis / bulletins", "OK",
                String.format(
                    "Revenus déclarés cohérents avec les bulletins : %.0f € attendus"
                        + " sur douze mois, écart de %.0f %%%s",
                    attendu, meilleurEcart * 100, mention)));
        }
        return checks;
    }

    private String formatAmounts(List<Double> amounts) {
        List<String> formatted = new ArrayList<>();
        for (Double a : amounts) formatted.add(String.format("%.0f €", a));
        return String.join(", ", formatted);
    }

    /**
     * Lien vers le service officiel de verification, avec les identifiants releves
     * sur l'avis. Retourne null si aucune URL n'est configuree : mieux vaut ne rien
     * proposer qu'envoyer le gestionnaire vers une adresse erronee.
     */
    public String verificationLink() {
        return (verificationUrl == null || verificationUrl.isBlank()) ? null : verificationUrl;
    }

    private String firstGroup(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1).trim() : null;
    }

    private Integer parseInt(String raw) {
        if (raw == null) return null;
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Double parseAmount(String raw) {
        if (raw == null) return null;
        String cleaned = raw.replaceAll("[\\s ]", "").replace(",", ".");
        try {
            return Double.parseDouble(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private AnalysisResult.Check check(String category, String label, String status, String detail) {
        return AnalysisResult.Check.builder()
            .category(category).label(label).status(status).detail(detail).build();
    }

    static String normalize(String s) {
        return Normalizer.normalize(s.toLowerCase(), Normalizer.Form.NFD)
            .replaceAll("[\\p{InCombiningDiacriticalMarks}]", "");
    }
}
