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

    private static final Pattern TRAITEMENTS_SALAIRES = Pattern.compile(
        "(?i)traitements,?\\s*(?:salaires|et salaires)[^0-9]{0,40}((?:\\d{1,3}(?:[\\s ]\\d{3})+|\\d+)(?:[.,]\\d{2})?)");

    private static final Pattern REVENU_FISCAL = Pattern.compile(
        "(?i)revenu fiscal de r[eé]f[eé]rence[^0-9]{0,40}((?:\\d{1,3}(?:[\\s ]\\d{3})+|\\d+)(?:[.,]\\d{2})?)");

    private static final Pattern ANNEE_REVENUS = Pattern.compile(
        "(?i)revenus?\\s*(?:de\\s*l'?ann[eé]e\\s*)?(20\\d{2})");

    /**
     * Extraction limitee au texte : l'analyse Vision et les controles forensiques
     * du pipeline des bulletins n'apportent rien sur un avis d'imposition et
     * consommeraient inutilement le quota d'API.
     */
    public AvisImposition extractFromPdf(InputStream inputStream) throws Exception {
        try (PDDocument document = Loader.loadPDF(inputStream.readAllBytes())) {
            return extract(new PDFTextStripper().getText(document));
        }
    }

    public AvisImposition extract(String text) {
        return AvisImposition.builder()
            .numeroFiscal(firstGroup(NUMERO_FISCAL, text, true))
            .referenceAvis(firstGroup(REFERENCE_AVIS, text, true))
            .anneeRevenus(parseInt(firstGroup(ANNEE_REVENUS, text, false)))
            .traitementsSalaires(parseAmount(firstGroup(TRAITEMENTS_SALAIRES, text, false)))
            .revenuFiscalReference(parseAmount(firstGroup(REVENU_FISCAL, text, false)))
            .build();
    }

    /**
     * @param netImposableMensuel net imposable releve sur les bulletins, ou null
     *                            si aucun bulletin n'a ete fourni.
     */
    public List<AnalysisResult.Check> verify(AvisImposition avis, Double netImposableMensuel) {
        List<AnalysisResult.Check> checks = new ArrayList<>();
        String category = "Avis d'imposition";

        if (avis.getNumeroFiscal() == null || avis.getReferenceAvis() == null) {
            checks.add(check(category, "Identifiants de l'avis", "WARNING",
                "Numéro fiscal ou référence de l'avis illisible — la vérification"
                    + " auprès de l'administration ne peut pas être préparée"));
        } else {
            checks.add(check(category, "Identifiants de l'avis", "OK",
                "Numéro fiscal et référence de l'avis extraits :"
                    + " utilisez le bouton de vérification pour confirmer l'authenticité"
                    + " auprès de l'administration fiscale"));
        }

        Double declare = avis.getTraitementsSalaires();
        if (declare == null) {
            checks.add(check(category, "Revenus déclarés", "WARNING",
                "Montant des traitements et salaires introuvable sur l'avis"));
            return checks;
        }

        if (netImposableMensuel == null || netImposableMensuel <= 0) {
            checks.add(check(category, "Revenus déclarés", "OK",
                String.format("Traitements et salaires déclarés : %.0f €"
                    + " — fournissez un bulletin pour permettre le rapprochement", declare)));
            return checks;
        }

        double attendu = netImposableMensuel * 12;
        double ecart = Math.abs(declare - attendu) / attendu;

        if (ecart > TOLERANCE) {
            checks.add(check(category, "Rapprochement avis / bulletins", "FAILED",
                String.format(
                    "Revenus déclarés (%.0f €) incohérents avec les bulletins"
                        + " (%.0f € attendus sur douze mois, écart de %.0f %%)"
                        + " — écart trop important pour une simple évolution de salaire",
                    declare, attendu, ecart * 100)));
        } else {
            checks.add(check(category, "Rapprochement avis / bulletins", "OK",
                String.format(
                    "Revenus déclarés (%.0f €) cohérents avec les bulletins"
                        + " (%.0f € attendus, écart de %.0f %%)",
                    declare, attendu, ecart * 100)));
        }
        return checks;
    }

    /**
     * Lien vers le service officiel de verification, avec les identifiants releves
     * sur l'avis. Retourne null si aucune URL n'est configuree : mieux vaut ne rien
     * proposer qu'envoyer le gestionnaire vers une adresse erronee.
     */
    public String verificationLink(AvisImposition avis) {
        if (verificationUrl == null || verificationUrl.isBlank()) return null;
        if (avis.getNumeroFiscal() == null || avis.getReferenceAvis() == null) return null;
        return verificationUrl;
    }

    private String firstGroup(Pattern pattern, String text, boolean stripSeparators) {
        Matcher m = pattern.matcher(text);
        if (!m.find()) return null;
        String value = m.group(1).trim();
        return stripSeparators ? value.replaceAll("[\\s.]", "") : value;
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
