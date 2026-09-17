package com.frauddetect.service;

import com.frauddetect.entity.User;
import com.frauddetect.model.AnalysisResult;
import com.frauddetect.model.AvisImposition;
import com.frauddetect.model.BatchAnalysisResult;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Verifie un dossier complet : les bulletins de salaire et, s'il est fourni,
 * l'avis d'imposition du meme candidat.
 *
 *   DossierVerificationService
 *     +-- FraudDetectionService           : analyse de chaque bulletin, recoupement des cumuls
 *     +-- TaxDocumentVerificationService  : avis (2D-DOC + rapprochement des revenus)
 *     +-- rapprochements propres au dossier : base de comparaison, identite
 *
 * L'apport par rapport aux deux verifications prises separement est double. Le
 * net imposable qui sert de base au rapprochement est deduit des bulletins au
 * lieu d'etre saisi, ce qui supprime une ressaisie et la faute de frappe qui va
 * avec. Et l'identite portee par les bulletins est confrontee aux declarants de
 * l'avis : sans ce controle, un candidat peut presenter l'avis d'un tiers dont
 * les revenus concordent, et la comparaison des montants ne verra rien.
 */
@Service
public class DossierVerificationService {

    private static final String CATEGORY = "Dossier";

    /**
     * Tolerance admise entre le cumul ramene au mois et le net imposable du mois.
     * Mêmes bornes que suggestionNetImposable() cote frontend, qui alimente le
     * rapprochement d'un avis seul : les deux doivent proposer la meme base.
     */
    private static final double RATIO_CUMUL_MIN = 0.85;
    private static final double RATIO_CUMUL_MAX = 1.25;

    /** Longueur minimale d'un mot retenu pour le rapprochement d'identite. */
    private static final int LONGUEUR_MOT_SIGNIFIANT = 3;

    /** Plafonds de score appliques quand un controle du dossier echoue. */
    private static final int PLAFOND_FALSIFICATION = 40;
    private static final int PLAFOND_ECART_REVENUS = 60;

    // Civilites et mentions d'etat civil : presentes d'un cote, absentes de
    // l'autre, elles concorderaient sans rien prouver.
    private static final Set<String> MOTS_IGNORES = Set.of(
        "monsieur", "madame", "mademoiselle", "mme", "mlle", "mgr",
        "epouse", "epouse:", "nee", "veuve", "divorcee");

    private final FraudDetectionService fraudDetectionService;
    private final TaxDocumentVerificationService taxDocumentVerificationService;

    public DossierVerificationService(
        FraudDetectionService fraudDetectionService,
        TaxDocumentVerificationService taxDocumentVerificationService) {
        this.fraudDetectionService = fraudDetectionService;
        this.taxDocumentVerificationService = taxDocumentVerificationService;
    }

    /** Net imposable mensuel deduit des bulletins, et provenance restituable. */
    public record BaseRapprochement(double netImposableMensuel, String origine) {
    }

    /**
     * @param avis   null quand le dossier ne comporte aucun avis d'imposition
     * @param base   null quand aucun bulletin ne porte de net imposable lisible
     */
    public record Result(
        BatchAnalysisResult bulletins,
        TaxDocumentVerificationService.Result avis,
        BaseRapprochement base,
        List<AnalysisResult.Check> dossierChecks
    ) {
    }

    /**
     * @param avisImposition avis du candidat, facultatif. Absent, le dossier se
     *                       reduit a l'analyse des bulletins.
     */
    public Result verify(List<MultipartFile> bulletins, MultipartFile avisImposition, User user)
        throws Exception {

        BatchAnalysisResult lot = fraudDetectionService.analyzeBatch(bulletins, user);

        if (avisImposition == null || avisImposition.isEmpty()) {
            return new Result(lot, null, null, List.of());
        }

        BaseRapprochement base = baseRapprochement(lot);
        TaxDocumentVerificationService.Result avis = taxDocumentVerificationService.verify(
            avisImposition.getBytes(), base != null ? base.netImposableMensuel() : null);

        List<AnalysisResult.Check> checks = new ArrayList<>();
        checks.add(identiteCheck(lot, avis.avis()));

        appliqueAuVerdict(lot, checks, avis.checks());

        return new Result(lot, avis, base, checks);
    }

    /**
     * Repercute les controles du dossier sur le verdict du lot, que le score des
     * bulletins ignore : sans cela, un dossier dont l'avis concerne un tiers
     * resterait affiche authentique.
     *
     * Une identite qui ne concorde pas, ou un cachet 2D-DOC en desaccord avec le
     * texte du document, designent une piece falsifiee ou etrangere au candidat :
     * le dossier tombe au plus bas, comme pour une progression de cumuls
     * impossible.
     *
     * Un ecart entre les revenus declares et les bulletins pese moins : le revenu
     * du candidat a pu changer depuis l'annee de l'avis, changement d'emploi ou
     * augmentation. Le dossier devient suspect, pas frauduleux.
     */
    void appliqueAuVerdict(BatchAnalysisResult lot,
                           List<AnalysisResult.Check> dossierChecks,
                           List<AnalysisResult.Check> avisChecks) {
        List<AnalysisResult.Check> tous = new ArrayList<>(dossierChecks);
        tous.addAll(avisChecks);

        int plafond = 100;
        for (AnalysisResult.Check c : tous) {
            if (!"FAILED".equals(c.getStatus())) continue;
            plafond = Math.min(plafond, switch (c.getCategory()) {
                case CATEGORY, "2D-DOC" -> PLAFOND_FALSIFICATION;
                default -> PLAFOND_ECART_REVENUS;
            });
        }
        if (plafond == 100) return;

        int score = Math.min(lot.getGlobalScore(), plafond);
        lot.setGlobalScore(score);
        lot.setGlobalVerdict(fraudDetectionService.computeVerdict(score));
        lot.setGlobalColor(fraudDetectionService.computeColor(score));
    }

    // ── Base du rapprochement des revenus ────────────────────────────────────

    /**
     * Net imposable mensuel deduit des bulletins.
     *
     * Le bulletin le plus avance dans l'annee est retenu : son cumul couvre le
     * plus de mois, donc lisse le mieux primes et treizieme mois. Ramene au mois,
     * ce cumul est prefere au montant du mois, que l'avis integre mais qu'un mois
     * isole ignore. Il n'est retenu que s'il couvre bien les mois ecoules : une
     * embauche en cours d'annee le rend inferieur, et le diviser par le numero du
     * mois sous-estimerait le revenu, ce qui creuserait un ecart avec l'avis sur
     * un dossier honnete.
     */
    BaseRapprochement baseRapprochement(BatchAnalysisResult lot) {
        AnalysisResult.DocumentInfo retenu = null;
        String source = null;

        List<AnalysisResult> results = lot.getResults();
        for (int i = 0; i < results.size(); i++) {
            AnalysisResult.DocumentInfo info = results.get(i).getDocumentInfo();
            if (info == null) continue;
            if (info.getNetImposable() == null && info.getCumulNetImposable() == null) continue;
            if (retenu == null || mois(info) > mois(retenu)) {
                retenu = info;
                source = nomFichier(lot, i);
            }
        }
        if (retenu == null) return null;

        Double cumul = retenu.getCumulNetImposable();
        Double mensuel = retenu.getNetImposable();
        Integer mois = retenu.getMoisPeriode();

        if (cumul != null && mois != null && mois > 0) {
            double moyenne = cumul / mois;
            boolean couvreLesMoisEcoules = mensuel == null
                || (moyenne >= mensuel * RATIO_CUMUL_MIN && moyenne <= mensuel * RATIO_CUMUL_MAX);
            if (couvreLesMoisEcoules) {
                return new BaseRapprochement(arrondiCentimes(moyenne), String.format(
                    "moyenne mensuelle du cumul depuis janvier (%.0f € sur %d mois%s)",
                    cumul, mois, source != null ? ", " + source : ""));
            }
        }

        if (mensuel != null) {
            return new BaseRapprochement(mensuel, String.format(
                "net imposable du bulletin%s%s",
                retenu.getPeriode() != null ? " de " + retenu.getPeriode() : "",
                source != null ? " (" + source + ")" : ""));
        }
        return null;
    }

    private int mois(AnalysisResult.DocumentInfo info) {
        return info.getMoisPeriode() != null ? info.getMoisPeriode() : 0;
    }

    private String nomFichier(BatchAnalysisResult lot, int index) {
        List<String> noms = lot.getFilenames();
        return noms != null && index < noms.size() ? noms.get(index) : null;
    }

    private double arrondiCentimes(double valeur) {
        return Math.round(valeur * 100.0) / 100.0;
    }

    // ── Rapprochement d'identite ─────────────────────────────────────────────

    /**
     * Confronte l'identite des bulletins aux declarants de l'avis.
     *
     * La comparaison porte sur les mots, sans tenir compte de l'ordre : le
     * bulletin ecrit indifferemment "BORGI Ghassen" ou "Ghassen BORGI", l'avis
     * "BORGI GHASSEN".
     *
     * Un seul mot commun ne vaut pas echec : l'avis porte le nom de naissance et
     * le bulletin souvent le nom d'usage, divergence legitime et frequente apres
     * un mariage. Le prenom, lui, subsiste, d'ou le seuil a un mot. Aucun mot
     * commun, en revanche, n'a pas d'explication de ce type — sous reserve que
     * les deux identites aient bien ete extraites, ce que le nombre de mots
     * signifiants verifie : une extraction partielle ne doit pas se solder par
     * une accusation.
     */
    AnalysisResult.Check identiteCheck(BatchAnalysisResult lot, AvisImposition avis) {
        List<String> declarants = avis.getNomsDeclarants();
        Set<String> nomsBulletins = nomsDesBulletins(lot);

        if (declarants == null || declarants.isEmpty()) {
            return check("Identité bulletins / avis", "WARNING",
                "Nom des déclarants introuvable sur l'avis — vérifiez à l'œil que"
                    + " l'avis concerne bien le candidat");
        }
        if (nomsBulletins.isEmpty()) {
            return check("Identité bulletins / avis", "WARNING",
                "Nom du salarié introuvable sur les bulletins — le rapprochement"
                    + " d'identité avec l'avis n'a pas pu être fait");
        }

        int meilleur = 0;
        String declarantLePlusProche = declarants.get(0);
        boolean identitesExploitables = false;

        for (String nomBulletin : nomsBulletins) {
            Set<String> motsBulletin = motsSignifiants(nomBulletin);
            for (String declarant : declarants) {
                Set<String> motsDeclarant = motsSignifiants(declarant);
                if (motsBulletin.size() >= 2 && motsDeclarant.size() >= 2) {
                    identitesExploitables = true;
                }
                Set<String> communs = new LinkedHashSet<>(motsBulletin);
                communs.retainAll(motsDeclarant);
                if (communs.size() > meilleur) {
                    meilleur = communs.size();
                    declarantLePlusProche = declarant;
                }
            }
        }

        if (meilleur >= 2) {
            return check("Identité bulletins / avis", "OK",
                String.format("L'identité des bulletins figure parmi les déclarants"
                    + " de l'avis (%s)", declarantLePlusProche));
        }
        if (meilleur == 1) {
            return check("Identité bulletins / avis", "WARNING",
                String.format("Concordance partielle avec le déclarant %s :"
                    + " cohérent avec un nom d'usage différent du nom de naissance,"
                    + " à confirmer", declarantLePlusProche));
        }
        if (!identitesExploitables) {
            return check("Identité bulletins / avis", "WARNING",
                "Identités trop incomplètes pour être rapprochées — vérifiez à l'œil"
                    + " que l'avis concerne bien le candidat");
        }
        return check("Identité bulletins / avis", "FAILED",
            String.format("Aucun déclarant de l'avis ne correspond au salarié des"
                + " bulletins (%s) — l'avis concerne probablement une autre personne",
                String.join(", ", nomsBulletins)));
    }

    private Set<String> nomsDesBulletins(BatchAnalysisResult lot) {
        Set<String> noms = new LinkedHashSet<>();
        for (AnalysisResult r : lot.getResults()) {
            AnalysisResult.DocumentInfo info = r.getDocumentInfo();
            if (info == null || info.getEmploye() == null || info.getEmploye().isBlank()) continue;
            noms.add(info.getEmploye().trim());
        }
        return noms;
    }

    /** Mots comparables : sans accent, sans civilite, sans particule courte. */
    private Set<String> motsSignifiants(String identite) {
        Set<String> mots = new LinkedHashSet<>();
        String normalise = Normalizer.normalize(identite.toLowerCase(), Normalizer.Form.NFD)
            .replaceAll("[\\p{InCombiningDiacriticalMarks}]", "")
            .replaceAll("[^a-z]+", " ");
        for (String mot : normalise.trim().split("\\s+")) {
            if (mot.length() < LONGUEUR_MOT_SIGNIFIANT) continue;
            if (MOTS_IGNORES.contains(mot)) continue;
            mots.add(mot);
        }
        return mots;
    }

    private AnalysisResult.Check check(String label, String status, String detail) {
        return AnalysisResult.Check.builder()
            .category(CATEGORY).label(label).status(status).detail(detail).build();
    }
}
