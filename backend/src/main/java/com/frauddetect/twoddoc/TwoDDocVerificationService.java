package com.frauddetect.twoddoc;

import com.frauddetect.model.AnalysisResult;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Detecte, verifie et rapproche le 2D-DOC d'un document.
 *
 * Le rapprochement procede par recherche de valeur : chaque donnee lue dans le
 * texte du PDF est cherchee dans l'ensemble des champs signes. Cette approche
 * evite de supposer la correspondance entre identifiant de champ et semantique,
 * que le catalogue officiel seul etablit. Elle detecte la falsification visee,
 * une modification du PDF sans regeneration du code, sans risquer de signaler a
 * tort un document authentique dont on aurait mal devine la structure.
 *
 * Elle ne detecte evidemment pas un faux integral accompagne de son propre
 * DataMatrix : seule la verification de signature l'ecarte.
 */
@Service
public class TwoDDocVerificationService {

    private static final String CATEGORY = "2D-DOC";

    private final TwoDDocDecoder decoder;
    private final TwoDDocParser parser;
    private final TwoDDocSignatureVerifier verifier;

    public TwoDDocVerificationService(TwoDDocDecoder decoder, TwoDDocParser parser,
                                      TwoDDocSignatureVerifier verifier) {
        this.decoder = decoder;
        this.parser = parser;
        this.verifier = verifier;
    }

    /** Resultat du rapprochement d'une donnee du PDF avec les champs signes. */
    public enum Comparison {
        MATCH,
        MISMATCH,
        /** Donnee absente du PDF ou du 2D-DOC : rien a comparer. */
        NOT_COMPARABLE
    }

    public TwoDDocData analyze(InputStream pdf) {
        try {
            List<String> codes = decoder.decodeAll(pdf);
            if (codes.isEmpty()) {
                return TwoDDocData.builder()
                    .detected(false)
                    .signatureStatus(SignatureStatus.NOT_VERIFIED)
                    .signatureDetail("Aucun 2D-DOC sur le document")
                    .fields(Map.of())
                    .build();
            }
            return verifier.verify(parser.parse(codes.get(0)));
        } catch (Exception e) {
            // Aucune trace du contenu : le brut porte des donnees nominatives.
            return TwoDDocData.builder()
                .detected(false)
                .signatureStatus(SignatureStatus.NOT_VERIFIED)
                .signatureDetail("Lecture du 2D-DOC impossible : "
                    + e.getClass().getSimpleName())
                .fields(Map.of())
                .build();
        }
    }

    /**
     * @param pdfValues donnees lues dans le texte du PDF, indexees par libelle
     *                  destine a l'utilisateur
     */
    public Map<String, Comparison> compare(TwoDDocData data, Map<String, String> pdfValues) {
        Map<String, Comparison> result = new LinkedHashMap<>();
        if (data == null || !data.isDetected() || data.getFields() == null) {
            pdfValues.keySet().forEach(k -> result.put(k, Comparison.NOT_COMPARABLE));
            return result;
        }

        String signedBlob = normalize(String.join(" ", data.getFields().values()));

        pdfValues.forEach((label, value) -> {
            if (value == null || value.isBlank()) {
                result.put(label, Comparison.NOT_COMPARABLE);
                return;
            }
            String needle = normalize(value);
            result.put(label, needle.isEmpty() ? Comparison.NOT_COMPARABLE
                : signedBlob.contains(needle) ? Comparison.MATCH : Comparison.MISMATCH);
        });
        return result;
    }

    public List<AnalysisResult.Check> toChecks(TwoDDocData data,
                                               Map<String, Comparison> comparisons) {
        List<AnalysisResult.Check> checks = new ArrayList<>();

        if (data == null || !data.isDetected()) {
            // Absence de code : information, pas anomalie. Les avis telecharges
            // depuis l'espace particulier n'en portent pas systematiquement.
            checks.add(check("Présence du 2D-DOC", "WARNING",
                "Aucun cachet 2D-DOC détecté — l'authenticité du document ne peut pas"
                    + " être confirmée cryptographiquement"));
            return checks;
        }

        checks.add(signatureCheck(data));
        checks.addAll(comparisonChecks(data, comparisons));
        return checks;
    }

    /**
     * Restitue le rapprochement.
     *
     * Les concordances sont regroupees en un seul controle : la signature
     * n'atteste que l'authenticite du cachet, pas que le texte du document lui
     * corresponde. Sans cette mention, un gestionnaire ne saurait pas que la
     * seconde verification a eu lieu, alors qu'elle seule ecarte une retouche du
     * PDF laissant le cachet intact. Les ecarts, eux, restent detailles par
     * donnee.
     */
    private List<AnalysisResult.Check> comparisonChecks(
        TwoDDocData data, Map<String, Comparison> comparisons) {

        List<AnalysisResult.Check> checks = new ArrayList<>();
        List<String> concordantes = new ArrayList<>();
        List<String> divergentes = new ArrayList<>();

        comparisons.forEach((label, comparison) -> {
            if (comparison == Comparison.MISMATCH) divergentes.add(label);
            else if (comparison == Comparison.MATCH) concordantes.add(label);
        });

        for (String label : divergentes) {
            checks.add(check("Cohérence 2D-DOC : " + label, "FAILED",
                "La valeur lue sur le document ne figure pas dans les données"
                    + " du cachet 2D-DOC — le texte du PDF a probablement été modifié"));
        }

        if (!concordantes.isEmpty()) {
            boolean authentifie = data.isSignatureTrusted();
            // Une concordance partielle ne rassure pas : un ecart ailleurs
            // suffit a rendre le document suspect.
            checks.add(check("Cohérence document / 2D-DOC",
                divergentes.isEmpty() ? "OK" : "WARNING",
                String.format("%d donnée%s du document confrontée%s au cachet : %s — %s",
                    concordantes.size(),
                    concordantes.size() > 1 ? "s" : "",
                    concordantes.size() > 1 ? "s" : "",
                    String.join(", ", concordantes),
                    authentifie
                        ? "le texte affiché correspond aux données authentifiées"
                        : "concordantes, mais le cachet n'est pas authentifié")));
        }
        return checks;
    }

    private AnalysisResult.Check signatureCheck(TwoDDocData data) {
        return switch (data.getSignatureStatus()) {
            case VALID -> check("Signature 2D-DOC", "OK",
                "Signature cryptographique vérifiée — document authentifié par "
                    + data.getAuthorityId());
            case INVALID -> check("Signature 2D-DOC", "FAILED",
                "Signature cryptographique invalide — les données du cachet ont été altérées");
            case CERTIFICATE_REVOKED -> check("Signature 2D-DOC", "FAILED",
                "Certificat de signature révoqué par son autorité");
            case CERTIFICATE_EXPIRED -> check("Signature 2D-DOC", "WARNING",
                "Certificat hors période de validité à la date de signature");
            case TRUST_CHAIN_INVALID -> check("Signature 2D-DOC", "FAILED",
                "Certificat non rattachable à une autorité de confiance");
            case CERTIFICATE_UNKNOWN -> check("Signature 2D-DOC", "WARNING",
                "Certificat de signature inconnu de la liste de confiance");
            case TSL_UNAVAILABLE -> check("Signature 2D-DOC", "WARNING",
                "Liste de confiance indisponible — signature non vérifiée");
            case UNSUPPORTED_ALGORITHM -> check("Signature 2D-DOC", "WARNING",
                "Algorithme de signature non pris en charge");
            case MALFORMED -> check("Signature 2D-DOC", "WARNING",
                "Structure du 2D-DOC non conforme");
            case NOT_VERIFIED -> check("Signature 2D-DOC", "WARNING",
                "Vérification cryptographique non effectuée");
        };
    }

    /** Insensible a la casse, aux accents, aux espaces et aux separateurs. */
    private String normalize(String value) {
        return Normalizer.normalize(value.toLowerCase(), Normalizer.Form.NFD)
            .replaceAll("[\\p{InCombiningDiacriticalMarks}]", "")
            .replaceAll("[^a-z0-9]", "");
    }

    private AnalysisResult.Check check(String label, String status, String detail) {
        return AnalysisResult.Check.builder()
            .category(CATEGORY).label(label).status(status).detail(detail).build();
    }
}
