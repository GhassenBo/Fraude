package com.frauddetect.service;

import com.frauddetect.model.AnalysisResult;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Controles portant sur le texte du document, hors calculs : les noms qu'il
 * repete d'une zone a l'autre, et les mentions par lesquelles il se declare
 * lui-meme sans valeur.
 *
 * Un bulletin nomme plusieurs fois l'employeur et le salarie : en tete, dans le
 * bloc d'identification, dans le recapitulatif de paiement. Un faussaire
 * retouche l'occurrence qu'il a sous les yeux et laisse les autres, faute de
 * savoir qu'elles existent. Aucun calcul n'est en cause, d'ou ces controles a
 * part : ils ne lisent que des noms.
 *
 * Les deux regles ont ete mesurees avant d'etre ecrites. Sur quinze documents
 * sains — neuf bulletins reels de cinq editeurs et six documents de test —
 * aucune divergence n'apparait ; sur les deux documents alteres, elles
 * apparaissent toutes les deux.
 */
@Service
public class DocumentCoherenceService {

    private static final String CATEGORY = "Cohérence interne";

    // Denomination sociale, forme juridique avant ou apres le nom.
    private static final String FORMES = "SAS|SARL|SASU|EURL|SELARL|SCOP|SCP|SNC|SCI|SA";

    // Les separateurs excluent le saut de ligne : une denomination ne s'etend pas
    // d'une ligne a l'autre. Avec \\s, la recherche agregeait la fin d'une ligne au
    // debut de la suivante et inventait un second employeur sur tout le jeu de
    // tests, dont le titre de bloc precede le nom.
    // UNICODE_CHARACTER_CLASS est indispensable : en semantique ASCII, \b ne
    // reconnait pas les lettres accentuees comme des caracteres de mot, et la
    // recherche recule d'un cran sur un nom qui en porte une — "MODIFIE" se
    // reduisait a "MODIFI".
    private static final Pattern DENOMINATION = Pattern.compile(
        "\\b([A-Z\u00c0-\u00dc][A-Za-z\u00c0-\u00ff&'.-]*"
            + "(?:[ \\t]+[A-Za-z\u00c0-\u00ff&'.-]+){0,4}[ \\t]+(?:" + FORMES + "))\\b"
            + "|\\b((?:" + FORMES + ")[ \\t]+[A-Z\u00c0-\u00dc][A-Za-z\u00c0-\u00ff&'.-]*"
            + "(?:[ \\t]+[A-Za-z\u00c0-\u00ff&'.-]+){0,3})\\b",
        Pattern.UNICODE_CHARACTER_CLASS);

    // "Camille EXEMPLE" : prenom capitalise suivi d'un patronyme en majuscules.
    private static final Pattern IDENTITE = Pattern.compile(
        "\\b([A-Z\u00c0-\u00dc][a-z\u00e0-\u00ff]{2,})\\s+"
            + "([A-Z\u00c0-\u00dc]{3,}(?:[-'][A-Z\u00c0-\u00dc]{2,})*)\\b",
        Pattern.UNICODE_CHARACTER_CLASS);

    // Mentions d'un nom de naissance distinct du nom d'usage : un meme prenom
    // peut alors legitimement preceder deux patronymes.
    //
    // La recherche porte sur des mots entiers. En simple sous-chaine, "nee"
    // se retrouvait dans "aucune donnee reelle" et desamorcait le controle.
    private static final Pattern MENTION_NOM_DE_NAISSANCE = Pattern.compile(
        "\\b(?:nom de naissance|nom d'usage|nom de jeune fille|nee|ne le"
            + "|epouse|veuve)\\b", Pattern.UNICODE_CHARACTER_CLASS);

    // Mots qui ne sont pas des prenoms mais ouvrent la meme forme typographique.
    private static final Set<String> FAUX_PRENOMS = Set.of(
        "monsieur", "madame", "mademoiselle", "salarie", "salariee", "employe",
        "employee", "total", "montant", "periode", "emploi", "convention",
        "cotisation", "cotisations", "retenue", "retenues", "impot", "net",
        "brut", "cumul", "cumuls", "prime", "conge", "conges", "date", "mode",
        "taux", "base", "libelle", "adresse", "code", "numero", "matricule");

    public List<AnalysisResult.Check> analyze(String text) {
        if (text == null || text.isBlank()) return List.of();

        List<AnalysisResult.Check> checks = new ArrayList<>();
        AnalysisResult.Check mention = checkMentionDeSpecimen(text);
        if (mention != null) checks.add(mention);
        AnalysisResult.Check employeur = checkEmployeur(text);
        if (employeur != null) checks.add(employeur);
        AnalysisResult.Check salarie = checkIdentiteSalarie(text);
        if (salarie != null) checks.add(salarie);
        return checks;
    }

    // ── Mentions de specimen ─────────────────────────────────────────────────

    /**
     * Mentions par lesquelles un document se declare sans valeur.
     *
     * "Exemple de bulletin de paie" en fait partie : c'est la signature des
     * generateurs en ligne, et cinq des neuf bulletins reels servant de reference
     * la portent en pied de page. Un tel document n'est pas un justificatif de
     * revenus, quelle que soit la coherence de ses calculs — et c'est precisement
     * la piece qu'un candidat telecharge en quelques secondes.
     */
    private static final List<String> MENTIONS_EXPLICITES = List.of(
        "specimen", "document fictif", "donnees fictives", "identite fictive",
        "ne pas utiliser", "non valable", "ne constitue pas un justificatif",
        "exemple de bulletin", "exemple de fiche de paie", "modele de bulletin",
        "document de test", "environnement de test", "bulletin de test",
        "echantillon", "factice", "sans valeur juridique");

    /**
     * Termes trop courants pour conclure seuls : une societe peut s'appeler Test,
     * un libelle porter le mot demo. Deux d'entre eux au moins doivent se
     * rencontrer pour que le document devienne douteux.
     */
    private static final List<String> TERMES_FAIBLES = List.of(
        "test", "demo", "demonstration", "fictif", "fictive", "apercu",
        "preview", "simule", "brouillon", "a titre d'exemple");

    /** Nombre de termes faibles distincts a partir duquel le doute s'installe. */
    private static final int TERMES_FAIBLES_MIN = 2;

    private AnalysisResult.Check checkMentionDeSpecimen(String text) {
        String norm = normalize(text, true);

        List<String> explicites = MENTIONS_EXPLICITES.stream()
            .filter(norm::contains).toList();
        if (!explicites.isEmpty()) {
            return AnalysisResult.Check.builder()
                .category("Structure")
                .label("Mention de document sans valeur")
                .status("FAILED")
                .detail(String.format(
                    "Le document se déclare lui-même sans valeur : « %s » — ce n'est"
                        + " pas un justificatif de revenus, quelle que soit la"
                        + " cohérence de ses calculs",
                    String.join(" », « ", explicites)))
                .build();
        }

        List<String> faibles = TERMES_FAIBLES.stream()
            .filter(t -> Pattern.compile("\\b" + Pattern.quote(t) + "\\b",
                Pattern.UNICODE_CHARACTER_CLASS).matcher(norm).find())
            .toList();
        if (faibles.size() >= TERMES_FAIBLES_MIN) {
            return AnalysisResult.Check.builder()
                .category("Structure")
                .label("Mention de document sans valeur")
                .status("WARNING")
                .detail(String.format(
                    "Le document porte %d mentions évoquant un exemple ou un essai :"
                        + " %s — à vérifier, un bulletin authentique n'en comporte pas",
                    faibles.size(), String.join(", ", faibles)))
                .build();
        }
        return null;
    }

    // ── Employeur ────────────────────────────────────────────────────────────

    /**
     * Le document ne nomme qu'un seul employeur.
     *
     * Le constat reste un avertissement : un second employeur se rencontre
     * legitimement, en portage salarial ou en mise a disposition, ou l'entreprise
     * cliente figure aux cotes de la societe qui emploie. Les deux noms sont donc
     * restitues, un gestionnaire tranchant d'un coup d'oeil ce qu'aucune regle ne
     * tranchera a sa place.
     */
    private AnalysisResult.Check checkEmployeur(String text) {
        Map<String, String> parNom = new LinkedHashMap<>();
        for (String line : text.split("\\r?\\n")) {
            Matcher m = DENOMINATION.matcher(line);
            while (m.find()) {
                String nom = (m.group(1) != null ? m.group(1) : m.group(2))
                    .replaceAll("\\s+", " ").trim();
                parNom.putIfAbsent(normalize(nom), nom);
            }
        }
        if (parNom.size() < 2) return null;

        return AnalysisResult.Check.builder()
            .category(CATEGORY)
            .label("Cohérence de l'employeur")
            .status("WARNING")
            .detail(String.format(
                "Le document nomme %d employeurs différents : %s — un seul est"
                    + " attendu, sauf portage salarial ou mise à disposition",
                parNom.size(), String.join(" / ", parNom.values())))
            .build();
    }

    // ── Identite du salarie ──────────────────────────────────────────────────

    /**
     * Un meme prenom n'est suivi que d'un seul patronyme.
     *
     * C'est la trace que laisse la retouche d'une occurrence parmi plusieurs :
     * le bloc d'identification et le recapitulatif de paiement portent alors deux
     * noms pour la meme personne.
     *
     * Le constat retombe en avertissement quand le document mentionne un nom de
     * naissance ou un nom d'usage : un meme prenom precede alors legitimement
     * deux patronymes, apres un mariage.
     */
    private AnalysisResult.Check checkIdentiteSalarie(String text) {
        Map<String, Set<String>> patronymes = new LinkedHashMap<>();
        Matcher m = IDENTITE.matcher(text);
        while (m.find()) {
            String prenom = m.group(1);
            if (FAUX_PRENOMS.contains(normalize(prenom))) continue;
            patronymes.computeIfAbsent(normalize(prenom), k -> new LinkedHashSet<>())
                .add(m.group(2));
        }

        for (var e : patronymes.entrySet()) {
            if (e.getValue().size() < 2) continue;

            boolean nomDeNaissance = mentionneUnNomDeNaissance(text);
            return AnalysisResult.Check.builder()
                .category(CATEGORY)
                .label("Cohérence de l'identité")
                .status(nomDeNaissance ? "WARNING" : "FAILED")
                .detail(String.format(
                    "Le document porte deux noms pour la même personne : %s —"
                        + " %s",
                    String.join(" / ", e.getValue()),
                    nomDeNaissance
                        ? "cohérent avec le nom de naissance mentionné sur le"
                            + " bulletin, à confirmer"
                        : "une seule occurrence a probablement été retouchée"))
                .build();
        }
        return null;
    }

    private boolean mentionneUnNomDeNaissance(String text) {
        return MENTION_NOM_DE_NAISSANCE.matcher(normalize(text, true)).find();
    }

    private String normalize(String value) {
        return normalize(value, false);
    }

    /** @param garderEspaces conserve les espaces, pour chercher des expressions */
    private String normalize(String value, boolean garderEspaces) {
        String sansAccent = Normalizer.normalize(value.toLowerCase(), Normalizer.Form.NFD)
            .replaceAll("[\\p{InCombiningDiacriticalMarks}]", "");
        return garderEspaces
            ? sansAccent.replaceAll("[^a-z0-9' ]", " ").replaceAll(" +", " ")
            : sansAccent.replaceAll("[^a-z0-9]", "");
    }
}
