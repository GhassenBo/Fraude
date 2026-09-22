package com.frauddetect.service;

import com.frauddetect.model.AnalysisResult;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.*;
import java.text.Normalizer;

@Service
public class SalaryCalculationService {

    // French SMIC 2024
    private static final double SMIC_MENSUEL = 1766.92;
    private static final double SMIC_HORAIRE = 11.65;

    // Montants : "12 000,00" (separateur de milliers) ou "9000,00" (sans separateur).
    // La partie decimale est obligatoire pour ne pas capturer les annees et codes.
    private static final Pattern AMOUNT_PATTERN = Pattern.compile(
        "(?<![0-9.,])([0-9]{1,3}(?:[\\s\u00a0][0-9]{3})+|[0-9]+)[.,]([0-9]{2})(?![0-9])");

    // Approximate charge rates
    private static final double CHARGES_SALARIALES_MIN = 0.20;
    private static final double CHARGES_SALARIALES_MAX = 0.30;

    /**
     * Analyse sans net à payer fiable : les contrôles qui portent sur ce montant
     * sont alors écartés. À réserver aux appelants qui ne savent pas d'où vient
     * la valeur — le pipeline, lui, transmet toujours sa provenance.
     */
    public List<AnalysisResult.Check> analyzeCalculations(String text, AnalysisResult.DocumentInfo docInfo) {
        return analyzeCalculations(text, docInfo, false);
    }

    /**
     * @param netFiable le net à payer de docInfo provient de l'analyse visuelle.
     *                  Les contrôles qui le comparent — au net social, au brut,
     *                  à la somme des cotisations — ne s'exécutent qu'à cette
     *                  condition : l'extraction par expression régulière confond
     *                  ce montant avec le net social, le net imposable ou le net
     *                  avant prélèvement, et un écart calculé sur la mauvaise
     *                  valeur accuserait un bulletin honnête.
     */
    public List<AnalysisResult.Check> analyzeCalculations(String text, AnalysisResult.DocumentInfo docInfo,
                                                           boolean netFiable) {
        List<AnalysisResult.Check> checks = new ArrayList<>();
        String[] lines = text.split("\\r?\\n");

        // Salaire brut : Vision en priorité, regex en fallback.
        Double salaireBrut = parseDocInfoAmount(docInfo != null ? docInfo.getSalaireBrut() : null);
        if (salaireBrut == null) {
            salaireBrut = extractAmountPreferSameLine(lines,
                "salaire brut", "remuneration brute", "remuneration brut",
                "total remuneration brute", "total remuneration brut",
                "brut total", "total brut", "brut fiscal", "brut :");
        }

        // Net À PAYER, prélèvement à la source déjà déduit : c'est la grandeur que
        // l'analyse visuelle renvoie, et elle ne doit pas être confondue avec le
        // net avant PAS extrait plus bas. Les deux diffèrent du montant de
        // l'impôt, soit zéro à quarante-cinq pour cent du net.
        //
        // Extraction visuelle uniquement : la regex génère trop de faux positifs
        // sur ce montant (confusion avec net social, net imposable, net avant PAS).
        Double netAPayer = netFiable
            ? parseDocInfoAmount(docInfo != null ? docInfo.getSalaireNet() : null)
            : null;

        // Sur la ligne de total, la part salariale precede la part patronale :
        // on prend donc le premier montant, pas le plus grand.
        Double totalCotisations = extractTotalCotisationsSalariales(lines);

        // Net social extracted independently — used to cross-validate NET À PAYER
        Double netSocial = extractAmountNearLabel(lines,
            "montant net social", "net social");

        // Net avant prélèvement à la source, la grandeur sur laquelle portent les
        // identités comptables : brut − cotisations = net avant PAS, puis
        // net avant PAS − PAS = net à payer.
        //
        // Les variantes avec apostrophe sont indispensables : normalizeDiacritics
        // retire les accents mais pas l'apostrophe, et le libelle reglementaire
        // s'imprime "NET A PAYER AVANT L'IMPOT SUR LE REVENU" sur une partie des
        // bulletins.
        //
        // "Net imposable" n'y figure pas : c'est une troisieme grandeur, qui
        // inclut la CSG non deductible et les avantages en nature. L'employer
        // comme substitut faussait le controle du prelevement a la source de la
        // valeur de cet ecart, une centaine d'euros sur les bulletins reels.
        Double netAvantPas = extractAmountPreferSameLine(lines,
            "net a payer avant impot sur le revenu",
            "net a payer avant l'impot sur le revenu",
            "net a payer avant impot",
            "net a payer avant l'impot",
            "net avant impot sur le revenu",
            "net avant l'impot sur le revenu",
            "net avant impot",
            "net avant l'impot",
            "net avant prelevement a la source",
            "net avant prelevement");

        // Montant du prélèvement à la source — extraction sur la même ligne uniquement.
        // La fenêtre ±4 lignes de extractAmountNearLabel retournerait le net avant impôt
        // (valeur plus grande, adjacente) au lieu du PAS lui-même.
        Double montantPAS = extractMontantPas(lines);

        // Valeur reellement libellee sur le document, avant tout repli : elle
        // seule autorise les controles qui comparent deux grandeurs distinctes.
        Double netAvantPasLibelle = netAvantPas;

        // A defaut de libelle, le net a payer tient lieu de net avant prelevement
        // quand rien n'indique qu'un impot a ete preleve : les deux montants sont
        // alors le meme. Ce repli evite d'eteindre les identites comptables sur
        // les bulletins a taux nul, sans jamais inventer d'ecart — des qu'un
        // prelevement est effectivement annonce, l'absence de libelle rend les
        // controles muets plutot que faux.
        if (netAvantPas == null && netAPayer != null
                && (montantPAS == null || montantPAS < 0.01)) {
            netAvantPas = netAPayer;
        }

        // Check 0: Net social vs Net à payer cross-validation
        // Invariant comptable : Net à payer = Net social − Impôt ≤ Net social (toujours)
        // On évite d'extraire l'impôt (labels ambigus, confusions avec bases CSG)
        // et on vérifie simplement que Net à payer ne dépasse pas Net social.
        if (netAPayer != null && netSocial != null && netAPayer > netSocial + 50) {
            checks.add(AnalysisResult.Check.builder()
                .category("Calculs")
                .label("Cohérence Net social / Net à payer")
                .status("FAILED")
                .detail(String.format(
                    "Net à payer (%.2f€) supérieur au Net social (%.2f€) — impossible,"
                        + " le net à payer a probablement été falsifié",
                    netAPayer, netSocial))
                .build());
        }

        // Check 1: Brut vs Net ratio — nécessite la valeur Vision (pas de regex fallback)
        if (!netFiable) {
            checks.add(AnalysisResult.Check.builder()
                .category("Calculs")
                .label("Ratio Net/Brut")
                .status("WARNING")
                .detail("Net à payer non extrait de façon fiable — le ratio Net/Brut"
                    + " n'a pas pu être vérifié")
                .build());
        } else if (salaireBrut != null && netAPayer != null && salaireBrut > 0) {
            double ratio = netAPayer / salaireBrut;
            if (ratio > 1.0) {
                checks.add(AnalysisResult.Check.builder()
                    .category("Calculs")
                    .label("Ratio Net/Brut")
                    .status("FAILED")
                    .detail(String.format("Net (%.2f€) supérieur au Brut (%.2f€) — impossible", netAPayer, salaireBrut))
                    .build());
            } else if (ratio > 0.93) {
                checks.add(AnalysisResult.Check.builder()
                    .category("Calculs")
                    .label("Ratio Net/Brut")
                    .status("WARNING")
                    .detail(String.format("Ratio Net/Brut de %.0f%% — élevé, vérifier si apprenti, ZFU/ZRR ou exonérations spécifiques", ratio * 100))
                    .build());
            } else if (ratio < 0.60) {
                checks.add(AnalysisResult.Check.builder()
                    .category("Calculs")
                    .label("Ratio Net/Brut")
                    .status("WARNING")
                    .detail(String.format("Ratio Net/Brut de %.0f%% — inhabituel, vérifier les cotisations", ratio * 100))
                    .build());
            } else {
                checks.add(AnalysisResult.Check.builder()
                    .category("Calculs")
                    .label("Ratio Net/Brut")
                    .status("OK")
                    .detail(String.format("Ratio Net/Brut de %.0f%% — cohérent avec les cotisations françaises", ratio * 100))
                    .build());
            }
        } else {
            checks.add(AnalysisResult.Check.builder()
                .category("Calculs")
                .label("Ratio Net/Brut")
                .status("WARNING")
                .detail("Impossible d'extraire le salaire brut et/ou net via Vision")
                .build());
        }

        // Check 2: Cotisations coherence
        // Use net avant PAS (before income tax) to avoid counting PAS as a missing cotisation
        Double netForCotisCheck = netAvantPas;
        // L'equation brut - net = cotisations ne tient que si rien ne s'ajoute ni ne
        // se retire au net apres cotisations. Interessement, titres-restaurant ou
        // remboursements de frais la rendent fausse sans qu'il y ait anomalie.
        boolean netHorsBrut = containsElementsHorsBrut(text);
        boolean exonere = isContratExonere(text);
        if (salaireBrut != null && netForCotisCheck != null && totalCotisations != null
                && !netHorsBrut) {
            double expectedDiff = salaireBrut - netForCotisCheck;
            double tolerance = salaireBrut * 0.05;
            if (Math.abs(expectedDiff - totalCotisations) > tolerance) {
                checks.add(AnalysisResult.Check.builder()
                    .category("Calculs")
                    .label("Cohérence des cotisations")
                    .status("WARNING")
                    .detail(String.format("Écart entre Brut−Net avant PAS (%.2f€) et cotisations (%.2f€) — peut indiquer primes ou avantages non cotisés", expectedDiff, totalCotisations))
                    .build());
            } else {
                checks.add(AnalysisResult.Check.builder()
                    .category("Calculs")
                    .label("Cohérence des cotisations")
                    .status("OK")
                    .detail(String.format("Brut − Net avant PAS = %.2f€, cotisations = %.2f€ — cohérent", expectedDiff, totalCotisations))
                    .build());
            }
        }

        // Check 3: Salary vs SMIC
        if (salaireBrut != null) {
            if (salaireBrut < SMIC_MENSUEL) {
                checks.add(AnalysisResult.Check.builder()
                    .category("Calculs")
                    .label("Comparaison SMIC")
                    .status(exonere ? "OK" : "WARNING")
                    .detail(exonere
                        ? String.format("Salaire brut %.2f€ sous le SMIC (%.2f€) — normal en"
                            + " alternance, la rémunération est un pourcentage légal du SMIC",
                            salaireBrut, SMIC_MENSUEL)
                        : String.format("Salaire brut %.2f€ inférieur au SMIC mensuel (%.2f€)"
                            + " — vérifier si temps partiel, mois incomplet, apprenti ou stage",
                            salaireBrut, SMIC_MENSUEL))
                    .build());
            } else if (salaireBrut > 50000) {
                checks.add(AnalysisResult.Check.builder()
                    .category("Calculs")
                    .label("Comparaison SMIC")
                    .status("WARNING")
                    .detail(String.format("Salaire brut %.2f€ — très élevé, vérifier la cohérence avec le poste", salaireBrut))
                    .build());
            } else {
                checks.add(AnalysisResult.Check.builder()
                    .category("Calculs")
                    .label("Comparaison SMIC")
                    .status("OK")
                    .detail(String.format("Salaire brut %.2f€ — au-dessus du SMIC (%.2f€)", salaireBrut, SMIC_MENSUEL))
                    .build());
            }
        }

        // Check 4: Required fields present
        checks.addAll(checkRequiredFields(text));

        // Check 5: Ratio Net/Brut par CCN
        // La plage conventionnelle est calibree sur le net avant PAS. Y injecter
        // le net a payer, ampute de l'impot, ecartait le ratio de sa plage sans
        // qu'aucune anomalie n'existe.
        Double netForCcn = netAvantPas;
        AnalysisResult.Check ratioCcn = checkNetBrutRatio(salaireBrut, netForCcn, text, totalCotisations, exonere);
        if (ratioCcn != null) checks.add(ratioCcn);

        // Check 6: Somme individuelle des lignes de cotisations
        AnalysisResult.Check cotisSum = checkCotisationsSum(text, salaireBrut, netAvantPas);
        if (cotisSum != null) checks.add(cotisSum);

        // Check 7: Cohérence prélèvement à la source
        // Équation : net_avant_PAS − PAS = net_final_après_PAS.
        // Source net avant PAS : Vision en priorité (fiable), regex en fallback.
        // Source net final : regex sur les lignes "net à payer" sans "avant" (valeur après déduction).
        // Sans net avant prelevement libelle ni montant d'impot, il n'y a rien a
        // confronter : le repli comparerait le net a payer a lui-meme et
        // conclurait toujours a la coherence, ce qui rassurerait a tort.
        if (netAvantPasLibelle != null || montantPAS != null) {
            // La ligne "net a payer" du document est ciblee precisement, tandis
            // que l'analyse visuelle ne renvoie qu'un net parmi plusieurs et
            // peut rapporter celui d'avant prelevement. Le texte prime donc, et
            // la valeur visuelle ne sert qu'a defaut.
            Double netTexte = extractNetFinalApresPas(lines);
            Double netFinalApresPas = netTexte != null ? netTexte : netAPayer;
            AnalysisResult.Check pasCheck =
                checkPAS(netAvantPas, montantPAS, netFinalApresPas);
            if (pasCheck != null) checks.add(pasCheck);
        }

        // Check 8: Cohérence des cumuls annuels
        AnalysisResult.Check cumulCheck = checkCumuls(lines, salaireBrut, docInfo);
        if (cumulCheck != null) checks.add(cumulCheck);

        // Check 9: Assiette des cotisations déplafonnées = brut
        AnalysisResult.Check assiette = checkAssietteDeplafonnee(lines, text, salaireBrut);
        if (assiette != null) checks.add(assiette);

        // Check 10: le total affiché des cotisations = somme de ses lignes
        AnalysisResult.Check totalCoherent = checkTotalCotisations(text, totalCotisations);
        if (totalCoherent != null) checks.add(totalCoherent);

        // Check 11: la date de paiement existe au calendrier
        AnalysisResult.Check dateCheck = checkDateDePaiement(lines);
        if (dateCheck != null) checks.add(dateCheck);

        // Check 12: l'année des cumuls correspond à celle de la période
        AnalysisResult.Check anneeCheck = checkAnneeDesCumuls(
            lines, docInfo != null ? docInfo.getPeriode() : null);
        if (anneeCheck != null) checks.add(anneeCheck);

        // Grandeurs fiscales exposees au reste du pipeline. Le rapprochement avec
        // l'avis d'imposition porte sur le net imposable, pas sur le net a payer :
        // sans elles, ce montant doit etre saisi a la main.
        if (docInfo != null) {
            docInfo.setNetImposable(extractNetImposable(lines, netAvantPasLibelle));
            docInfo.setCumulNetImposable(extractCumulNetImposable(lines));
        }

        return checks;
    }

    // ── Total des cotisations, date, annee des cumuls ────────────────────────

    // Du libelle le plus explicite au plus court. "total cotisations" sans autre
    // mot figure sur des bulletins reels et manquait a cette liste, si bien que
    // le total n'y etait pas lu du tout.
    private static final List<String> LABELS_TOTAL_SALARIAL = List.of(
        "total cotisations et contributions salariales",
        "total des cotisations et contributions salariales",
        "total cotisations salariales", "total retenues salariales",
        "total des cotisations et contributions", "montant total des cotisations",
        "total des cotisations", "total cotisations",
        "total prelevements salariales", "total charges salariales");

    /**
     * Total des cotisations salariales affiche sur le document.
     *
     * Sur la ligne de total, la part salariale precede la part patronale : on
     * prend donc le premier montant. Les lignes de total patronal sont ecartees,
     * les libelles les plus courts de la liste pouvant s'y retrouver.
     */
    private Double extractTotalCotisationsSalariales(String[] lines) {
        for (String label : LABELS_TOTAL_SALARIAL) {
            for (String line : lines) {
                String norm = normalizeDiacritics(line);
                if (!norm.contains(label)) continue;
                if (norm.contains("patronal") || norm.contains("employeur")) continue;
                Double montant = extractFirstAmountOnSameLine(new String[]{line}, label);
                if (montant != null) return montant;
            }
        }
        return null;
    }

    /**
     * Le total affiche des cotisations salariales egale la somme de ses lignes.
     *
     * Seul l'ecart dans un sens est signale : un total inferieur a la somme des
     * lignes qu'il agrege est impossible. L'inverse arrive legitimement, des
     * retenues a montant fixe n'ayant aucun taux a partir duquel les confirmer —
     * la somme est alors incomplete, et accuser serait injustifie.
     *
     * Le controle ne s'exprime donc que sur les bulletins dont les taux portent
     * un signe pourcent, seuls a permettre la confirmation ligne par ligne. Il
     * reste muet sur les autres plutot que d'y deviner une colonne.
     */
    private AnalysisResult.Check checkTotalCotisations(String text, Double totalAffiche) {
        if (totalAffiche == null || totalAffiche <= 0) return null;

        double somme = 0;
        int count = 0;
        for (String line : text.split("\\r?\\n")) {
            Double montant = montantSalarialConfirme(line);
            if (montant != null && montant > 0) {
                somme += montant;
                count++;
            }
        }
        if (count < 3) return null;

        double tolerance = Math.max(1.0, totalAffiche * 0.005);
        if (somme - totalAffiche > tolerance) {
            return AnalysisResult.Check.builder()
                .category("Calculs")
                .label("Total des cotisations")
                .status("FAILED")
                .detail(String.format(
                    "Total des cotisations salariales affiché (%.2f€) inférieur de %.2f€"
                        + " à la somme de ses propres lignes (%.2f€, %d lignes) —"
                        + " impossible, le total a probablement été minoré pour"
                        + " justifier un net plus élevé",
                    totalAffiche, somme - totalAffiche, somme, count))
                .build();
        }

        return AnalysisResult.Check.builder()
            .category("Calculs")
            .label("Total des cotisations")
            .status("OK")
            .detail(String.format(
                "Total des cotisations salariales (%.2f€) conforme à la somme de ses"
                    + " lignes (%.2f€, %d lignes)",
                totalAffiche, somme, count))
            .build();
    }

    // "Date de paiement 31/02/2026", "Salaire versé le 28/02/26".
    private static final Pattern DATE_DE_PAIEMENT = Pattern.compile(
        "(?i)(?:date de (?:paiement|versement)|(?:salaire\\s+)?vers[eé] le|pay[eé] le)"
            + "[^0-9]{0,12}([0-9]{2})/([0-9]{2})/([0-9]{2,4})");

    /**
     * La date de paiement existe au calendrier.
     *
     * Un 31 fevrier trahit une saisie fabriquee : aucun logiciel de paie ne
     * produit une date inexistante, alors qu'un faussaire qui retouche un
     * bulletin ne verifie pas le calendrier. Le controle ne juge que l'existence
     * de la date, pas sa vraisemblance : un versement anticipe ou tardif se
     * rencontre couramment.
     */
    private AnalysisResult.Check checkDateDePaiement(String[] lines) {
        for (String line : lines) {
            Matcher m = DATE_DE_PAIEMENT.matcher(line);
            if (!m.find()) continue;

            int jour = Integer.parseInt(m.group(1));
            int mois = Integer.parseInt(m.group(2));
            int annee = Integer.parseInt(m.group(3));
            if (annee < 100) annee += 2000;

            try {
                java.time.LocalDate.of(annee, mois, jour);
            } catch (java.time.DateTimeException e) {
                return AnalysisResult.Check.builder()
                    .category("Calculs")
                    .label("Date de paiement")
                    .status("FAILED")
                    .detail(String.format(
                        "Date de paiement inexistante : %02d/%02d/%d — aucun logiciel de"
                            + " paie ne produit une telle date",
                        jour, mois, annee))
                    .build();
            }

            return AnalysisResult.Check.builder()
                .category("Calculs")
                .label("Date de paiement")
                .status("OK")
                .detail(String.format("Date de paiement valide : %02d/%02d/%d",
                    jour, mois, annee))
                .build();
        }
        return null;
    }

    // "Cumul brut 2026 33 750,00" : l'annee suit immediatement le libelle.
    private static final Pattern ANNEE_DU_CUMUL = Pattern.compile(
        "(?i)cumul[^0-9\\n]{0,30}(20[0-9]{2})");

    private static final Pattern ANNEE = Pattern.compile("(20[0-9]{2})");

    /**
     * L'annee portee par les cumuls est celle de la periode du bulletin.
     *
     * Les cumuls repartent de zero chaque janvier : un bulletin de septembre 2026
     * ne peut pas afficher des cumuls 2025. Le controle ne s'exprime que sur les
     * lignes ne citant qu'une seule annee : la periode de reference des conges
     * payes en cite deux — "du 01/06/2025 au 31/05/2026" — et n'est pas un cumul
     * annuel.
     */
    private AnalysisResult.Check checkAnneeDesCumuls(String[] lines, String periode) {
        if (periode == null) return null;
        Matcher periodeAnnee = ANNEE.matcher(periode);
        if (!periodeAnnee.find()) return null;
        int anneeAttendue = Integer.parseInt(periodeAnnee.group(1));

        for (String line : lines) {
            Matcher m = ANNEE_DU_CUMUL.matcher(line);
            if (!m.find()) continue;

            // Plusieurs annees sur la ligne : periode de reference, pas un cumul.
            Matcher toutes = ANNEE.matcher(line);
            Set<String> annees = new LinkedHashSet<>();
            while (toutes.find()) annees.add(toutes.group(1));
            if (annees.size() != 1) continue;

            int anneeCumul = Integer.parseInt(m.group(1));
            if (anneeCumul != anneeAttendue) {
                return AnalysisResult.Check.builder()
                    .category("Calculs")
                    .label("Année des cumuls")
                    .status("FAILED")
                    .detail(String.format(
                        "Cumuls annoncés pour %d alors que la période du bulletin est %s"
                            + " — les cumuls repartent de zéro chaque janvier",
                        anneeCumul, periode.trim()))
                    .build();
            }
            return AnalysisResult.Check.builder()
                .category("Calculs")
                .label("Année des cumuls")
                .status("OK")
                .detail(String.format("Cumuls de l'année %d, cohérents avec la période",
                    anneeCumul))
                .build();
        }
        return null;
    }

    // ── Check 9 : assiette des cotisations deplafonnees ──────────────────────

    /**
     * L'assiette des cotisations deplafonnees egale la remuneration brute.
     *
     * C'est une identite, pas une tendance : les cotisations dites deplafonnees
     * portent sur la totalite du salaire. Verifie sur neuf bulletins reels de
     * cinq editeurs differents, l'egalite est exacte au centime dans les neuf
     * cas.
     *
     * Elle repere la falsification la plus courante, et la seule que les autres
     * controles laissent passer : gonfler le brut en ajoutant le meme montant au
     * net. L'equation brut moins cotisations egale net reste alors vraie, le taux
     * de charge reste plausible, mais l'assiette, elle, n'est pas retouchee — un
     * faussaire modifie les deux ou trois montants qu'il veut voir, pas les
     * colonnes de bases du tableau de cotisations.
     *
     * Seul l'ecart dans le sens de la fraude est signale, une assiette
     * inferieure au brut. L'inverse existe sur des regularisations et ne sert
     * aucune fraude.
     */
    private AnalysisResult.Check checkAssietteDeplafonnee(String[] lines, String text,
                                                         Double brut) {
        if (brut == null || brut <= 0) return null;

        Double assiette = extractFirstAmountOnSameLine(lines,
            "cotisations sur la totalite du salaire", "totalite du salaire",
            "securite sociale deplafonnee", "retraite deplafonnee",
            "maladie deplafonnee", "deplafonnee", "deplafonne");
        if (assiette == null) return null;

        double ecart = brut - assiette;
        // Tolerance d'arrondi seulement : l'egalite est exacte sur les bulletins
        // reels, un euro suffit donc a absorber les centimes.
        if (ecart <= Math.max(1.0, brut * 0.002)) {
            return AnalysisResult.Check.builder()
                .category("Calculs")
                .label("Assiette des cotisations")
                .status("OK")
                .detail(String.format(
                    "Assiette des cotisations déplafonnées (%.2f €) conforme au brut (%.2f €)",
                    assiette, brut))
                .build();
        }

        // Une deduction forfaitaire specifique reduit legitimement l'assiette,
        // de dix a trente pour cent : batiment, journalistes, VRP. La signaler
        // comme une falsification accuserait a tort ces salaries.
        if (aUneDeductionForfaitaire(text)) {
            return AnalysisResult.Check.builder()
                .category("Calculs")
                .label("Assiette des cotisations")
                .status("WARNING")
                .detail(String.format(
                    "Assiette des cotisations déplafonnées (%.2f €) inférieure au brut"
                        + " (%.2f €) de %.2f € — cohérent avec la déduction forfaitaire"
                        + " spécifique mentionnée sur le bulletin, à vérifier",
                    assiette, brut, ecart))
                .build();
        }

        return AnalysisResult.Check.builder()
            .category("Calculs")
            .label("Assiette des cotisations")
            .status("FAILED")
            .detail(String.format(
                "Assiette des cotisations déplafonnées (%.2f €) inférieure de %.2f € au"
                    + " brut déclaré (%.2f €) — les cotisations portent sur la totalité"
                    + " du salaire : le brut a probablement été majoré sans recalcul"
                    + " du tableau de cotisations",
                assiette, ecart, brut))
            .build();
    }

    // Mentions d'un abattement d'assiette. Leur presence rend un ecart legitime.
    private static final List<String> DEDUCTIONS_FORFAITAIRES = List.of(
        "deduction forfaitaire specifique", "deduction forfaitaire",
        "abattement d'assiette", "abattement assiette", "abattement de 10",
        "abattement de 20", "abattement de 30", "dfs");

    private boolean aUneDeductionForfaitaire(String text) {
        String norm = normalizeDiacritics(text);
        return DEDUCTIONS_FORFAITAIRES.stream().anyMatch(norm::contains);
    }

    // ── Grandeurs fiscales : rapprochement avec l'avis d'imposition ───────────

    /**
     * Net imposable du mois.
     *
     * Extraction sur la meme ligne uniquement : "Net imposable" sert aussi
     * d'en-tete de colonne sur certains bulletins, et la fenetre de +-4 lignes y
     * capterait le cout patronal ou le brut. Une valeur fausse produirait un ecart
     * signale a tort au rapprochement, ce qui est plus nuisible que l'absence de
     * valeur : un montant introuvable laisse la saisie manuelle disponible.
     *
     * @param netAvantImpot valeur de repli. Le net avant PAS n'inclut ni la CSG
     *                      non deductible ni les avantages en nature, soit un
     *                      ecart de quelques euros, negligeable devant la
     *                      tolerance du rapprochement annuel.
     */
    private Double extractNetImposable(String[] lines, Double netAvantImpot) {
        Double direct = extractAmountOnSameLine(lines,
            "net imposable", "net fiscal", "net imposable mensuel");
        return direct != null ? direct : netAvantImpot;
    }

    /**
     * Cumul du net imposable depuis janvier.
     *
     * C'est le rapprochement le plus solide avec l'avis : il se compare aux
     * salaires declares sans extrapoler un mois sur douze, donc sans que primes
     * ou treizieme mois creusent un ecart artificiel.
     */
    private Double extractCumulNetImposable(String[] lines) {
        Double sameLine = extractFirstAmountOnSameLine(lines,
            "cumul net imposable", "net imposable cumul", "cumul du net imposable",
            "net imposable annuel", "cumul imposable");
        if (sameLine != null) return sameLine;
        return extractCumulImposablePhrase(lines);
    }

    // Rendu en phrase par certains editeurs, au pluriel :
    // "Depuis le 1er janvier 2026 : Bruts 52 800,00, ... et Nets imposables 43 318,11."
    // Le pluriel interdit de reutiliser les libelles au singulier ci-dessus.
    private static final Pattern CUMUL_IMPOSABLE_PHRASE = Pattern.compile(
        "(?i)nets? imposables?\\s*:?\\s*"
            + "((?:[0-9]{1,3}(?:[\\s ][0-9]{3})+|[0-9]+)[.,][0-9]{2})");

    /** Nombre de lignes remontees pour retrouver l'intitule du bloc de cumuls. */
    private static final int PORTEE_BLOC_CUMULS = 3;

    /**
     * "Nets imposables" seul ne suffit pas : c'est aussi le libelle du mois. Le
     * montant n'est retenu que sous une marque de cumul, portee soit par la ligne
     * meme ("Depuis le 1er janvier 2026 : ... Nets imposables 43 318,11"), soit
     * par l'intitule du bloc quelques lignes plus haut ("Cumuls depuis janv.
     * 2026"), les deux mises en page existant sur des bulletins reels. La phrase
     * est parfois coupee entre deux lignes, d'ou la remontee.
     */
    private Double extractCumulImposablePhrase(String[] lines) {
        for (int i = 0; i < lines.length; i++) {
            Matcher m = CUMUL_IMPOSABLE_PHRASE.matcher(lines[i]);
            if (!m.find()) continue;
            if (!dansUnBlocDeCumuls(lines, i)) continue;
            try {
                return Double.parseDouble(
                    m.group(1).replaceAll("[\\s ]", "").replace(",", "."));
            } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    private boolean dansUnBlocDeCumuls(String[] lines, int index) {
        for (int j = Math.max(0, index - PORTEE_BLOC_CUMULS); j <= index; j++) {
            String norm = normalizeDiacritics(lines[j]);
            if (norm.contains("cumul") || norm.contains("depuis le")) return true;
        }
        return false;
    }

    // ── Check 8 : Cohérence des cumuls annuels ────────────────────────────────

    /**
     * Un faussaire qui gonfle le brut d'un mois oublie presque toujours de
     * recalculer les cumuls. On ne teste que les relations mathematiquement
     * certaines : le cumul inclut le mois courant, donc cumul >= brut du mois.
     * Toute borne superieure serait un faux positif (primes, 13e mois), et toute
     * borne inferieure liee au numero de mois aussi (embauche en cours d'annee).
     */
    private AnalysisResult.Check checkCumuls(String[] lines, Double brut,
                                             AnalysisResult.DocumentInfo docInfo) {
        String periode = docInfo != null ? docInfo.getPeriode() : null;
        Double cumulBrut = extractCumulBrut(lines);
        Integer moisPeriode = moisDePeriode(periode);

        if (docInfo != null) {
            docInfo.setCumulBrut(cumulBrut);
            docInfo.setMoisPeriode(moisPeriode);
        }

        if (cumulBrut == null || brut == null || brut <= 0) return null;

        String category = "Calculs";
        String label = "Cumuls annuels";

        if (cumulBrut < brut - 1.0) {
            return AnalysisResult.Check.builder()
                .category(category).label(label).status("FAILED")
                .detail(String.format(
                    "Cumul brut annuel (%.2f €) inférieur au brut du mois (%.2f €)"
                        + " — impossible, le cumul inclut le mois courant",
                    cumulBrut, brut))
                .build();
        }

        if (moisPeriode != null && moisPeriode > 1 && Math.abs(cumulBrut - brut) < 1.0) {
            return AnalysisResult.Check.builder()
                .category(category).label(label).status("WARNING")
                .detail(String.format(
                    "Cumul brut annuel identique au brut du mois (%.2f €) alors que la période"
                        + " est le mois %d — cohérent seulement en cas d'embauche ce mois-ci",
                    cumulBrut, moisPeriode))
                .build();
        }

        return AnalysisResult.Check.builder()
            .category(category).label(label).status("OK")
            .detail(String.format("Cumul brut annuel cohérent : %.2f € pour un brut mensuel de %.2f €",
                cumulBrut, brut))
            .build();
    }

    private Double extractCumulBrut(String[] lines) {
        Double sameLine = extractFirstAmountOnSameLine(lines,
            "cumul brut", "brut cumul", "cumule brut", "brut cumule",
            "total brut cumul", "cumul du brut", "brut annuel");
        if (sameLine != null) return sameLine;
        return extractCumulPhrase(lines);
    }

    // Certains editeurs rendent les cumuls en phrase :
    // "Depuis le 1er janvier 2026 : Bruts 5 560,25, Heures travaillees 760..."
    private static final Pattern CUMUL_PHRASE = Pattern.compile(
        "(?i)bruts?\\s*:?\\s*((?:[0-9]{1,3}(?:[\\s\u00a0][0-9]{3})+|[0-9]+)[.,][0-9]{2})");

    private Double extractCumulPhrase(String[] lines) {
        for (String line : lines) {
            if (!normalizeDiacritics(line).contains("depuis le")) continue;
            Matcher m = CUMUL_PHRASE.matcher(line);
            if (m.find()) {
                try {
                    return Double.parseDouble(
                        m.group(1).replaceAll("[\\s\u00a0]", "").replace(",", "."));
                } catch (NumberFormatException ignored) {}
            }
        }
        return null;
    }

    private static final String[] MOIS_NOMS = {
        "janvier", "fevrier", "mars", "avril", "mai", "juin",
        "juillet", "aout", "septembre", "octobre", "novembre", "decembre"
    };

    Integer moisDePeriode(String periode) {
        if (periode == null || periode.isBlank()) return null;
        String norm = normalizeDiacritics(periode);

        for (int i = 0; i < MOIS_NOMS.length; i++) {
            if (norm.contains(MOIS_NOMS[i])) return i + 1;
        }

        // Formats numeriques : 03/2026, 2026-03, 01/03/2026
        Matcher m = Pattern.compile("\\b(\\d{1,2})\\s*[/-]\\s*(\\d{4})\\b").matcher(norm);
        if (m.find()) {
            int val = Integer.parseInt(m.group(1));
            if (val >= 1 && val <= 12) return val;
        }
        m = Pattern.compile("\\b(\\d{4})\\s*[/-]\\s*(\\d{1,2})\\b").matcher(norm);
        if (m.find()) {
            int val = Integer.parseInt(m.group(2));
            if (val >= 1 && val <= 12) return val;
        }
        return null;
    }

    /** Parses a DocumentInfo amount string like "4249.60 €" or "4 249,60 €" to a Double. */
    private Double parseDocInfoAmount(String amount) {
        if (amount == null || amount.isBlank()) return null;
        try {
            double d = Double.parseDouble(
                amount.replaceAll("[\\s\u00a0€]", "").replace(",", "."));
            return d > 0 ? d : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Strip diacritical marks so "NET A PAYER" matches the accented key "net à payer". */
    private String normalizeDiacritics(String text) {
        return Normalizer.normalize(text.toLowerCase(), Normalizer.Form.NFD)
            .replaceAll("[\\p{InCombiningDiacriticalMarks}]", "");
    }

    private List<AnalysisResult.Check> checkRequiredFields(String text) {
        List<AnalysisResult.Check> checks = new ArrayList<>();
        // Normalize once — handles PDFs that strip accents (e.g. "NET A PAYER" vs "net à payer")
        String normalized = normalizeDiacritics(text);

        // Required fields on a French pay slip — keys are already diacritic-free
        Map<String, String> requiredFields = new LinkedHashMap<>();
        requiredFields.put("siret", "Numéro SIRET employeur");
        requiredFields.put("net a payer", "Net à payer");
        requiredFields.put("cotisation", "Lignes de cotisations");
        // congés payés: optional (cadres au forfait, nouveaux embauchés, certains logiciels n'affichent pas CP)
        boolean hasCP = normalized.contains("conges payes") || normalized.contains(" cp ")
            || normalized.contains("conge annuel") || normalized.contains("repos compensateur")
            || normalized.contains("rtt") || normalized.contains("conge paye");

        int missing = 0;
        List<String> missingList = new ArrayList<>();
        // Convention collective — vérification élargie (labels abrégés, IDCC, noms de CCN courants)
        if (!hasConventionCollective(normalized)) {
            missing++;
            missingList.add("Convention collective");
        }
        for (Map.Entry<String, String> entry : requiredFields.entrySet()) {
            if (!normalized.contains(entry.getKey())) {
                missing++;
                missingList.add(entry.getValue());
            }
        }
        if (!hasCP) {
            missingList.add("Congés payés (optionnel — normal pour cadres au forfait)");
            // CP absence counts as half a missing field — only tips the balance if other fields also missing
        }

        if (missing == 0) {
            checks.add(AnalysisResult.Check.builder()
                .category("Structure")
                .label("Champs obligatoires")
                .status("OK")
                .detail("Tous les champs obligatoires d'un bulletin français sont présents")
                .build());
        } else if (missing <= 2) {
            checks.add(AnalysisResult.Check.builder()
                .category("Structure")
                .label("Champs obligatoires")
                .status("WARNING")
                .detail("Champs manquants : " + String.join(", ", missingList))
                .build());
        } else {
            checks.add(AnalysisResult.Check.builder()
                .category("Structure")
                .label("Champs obligatoires")
                .status("FAILED")
                .detail("Nombreux champs obligatoires absents : " + String.join(", ", missingList))
                .build());
        }

        return checks;
    }

    /**
     * Détecte la mention de convention collective sous ses multiples formes :
     * libellé complet, abréviation (CCN, IDCC), ou nom d'une CCN courante.
     * Le texte passé doit être déjà normalisé (minuscules + sans diacritiques).
     */
    private boolean hasConventionCollective(String normalized) {
        String[] indicators = {
            "convention collective", "conv. collective", "conv.collective",
            "convention coll", " ccn ", "ccn:", "ccn\t", "idcc",
            "syntec", "metallurgie", "batiment", "commerce de detail",
            "transports routiers", "bureaux d etudes", "bureaux detudes", "etudes techniques"
        };
        for (String indicator : indicators) {
            if (normalized.contains(indicator)) return true;
        }
        return false;
    }

    /**
     * Searches for a label (diacritic-insensitive), then returns the LARGEST monetary amount
     * found within 1 line before + same line + up to 4 following lines.
     * Scanning 1 line before handles PDFs where the value is extracted before the label
     * due to right-column-first text ordering.
     * "Largest" avoids small incidental numbers (hours, %) and correctly identifies
     * multi-row labels like "NET A PAYER AVANT IMPOT SUR\nLE REVENU\n1563,67".
     */
    private Double extractAmountNearLabel(String[] lines, String... labels) {
        Double best = null;
        for (int i = 0; i < lines.length; i++) {
            String norm = normalizeDiacritics(lines[i]);
            boolean found = false;
            for (String label : labels) {
                if (norm.contains(label)) { found = true; break; }
            }
            if (!found) continue;
            for (int j = Math.max(0, i - 1); j <= Math.min(i + 4, lines.length - 1); j++) {
                Double val = largestAmountInLine(lines[j]);
                if (val != null && (best == null || val > best)) best = val;
            }
        }
        return best;
    }

    // ── Check 5 : Ratio Net/Brut par CCN ─────────────────────────────────────

    /**
     * Vérifie que le ratio Net/Brut est dans la plage attendue pour la CCN détectée.
     * Le net passé est soit la valeur Vision (net avant PAS, prioritaire), soit le
     * regex netAvantImpot. Le PAS personnel ne doit pas fausser le ratio CCN.
     * Plages basées sur les charges salariales françaises 2024 (régime général).
     */
    private AnalysisResult.Check checkNetBrutRatio(Double brut, Double net, String text,
                                                   Double totalCotisations, boolean exonere) {
        if (brut == null || brut <= 0) return null;

        if (exonere) {
            return AnalysisResult.Check.builder()
                .category("Calculs")
                .label("Ratio Net/Brut CCN")
                .status("OK")
                .detail("Contrat en alternance : l'exonération de cotisations salariales"
                    + " rend le ratio Net/Brut non comparable à une plage conventionnelle")
                .build();
        }

        // Le net inclut des elements hors brut (interessement, participation, frais,
        // IJSS) et le ratio net/brut depasse alors la plage conventionnelle sans
        // qu'il y ait anomalie. Le taux de charge, lui, ne depend que du brut et des
        // cotisations : c'est le seul indicateur comparable a une plage CCN.
        boolean useCharge = totalCotisations != null && totalCotisations > 0;
        Double base = useCharge ? Double.valueOf(brut - totalCotisations) : net;
        if (base == null) return null;

        String netLabel = useCharge ? "Brut−cotisations" : "Net avant PAS";

        String normalized = normalizeDiacritics(text);
        double min, max;
        String ccn;
        if (normalized.contains("syntec")) {
            min = 0.74; max = 0.82; ccn = "Syntec";
        } else if (normalized.contains("metallurgie")) {
            min = 0.72; max = 0.80; ccn = "Métallurgie";
        } else {
            min = 0.70; max = 0.83; ccn = "standard";
        }

        double ratio = base / brut;
        if (ratio < min || ratio > max) {
            return AnalysisResult.Check.builder()
                .category("Calculs")
                .label("Ratio Net/Brut CCN")
                .status(useCharge ? "FAILED" : "WARNING")
                .detail(String.format(
                    "%s/Brut de %.1f%% hors plage attendue [%.0f%%–%.0f%%] pour la CCN %s",
                    netLabel, ratio * 100, min * 100, max * 100, ccn))
                .build();
        }
        return AnalysisResult.Check.builder()
            .category("Calculs")
            .label("Ratio Net/Brut CCN")
            .status("OK")
            .detail(String.format(
                "%s/Brut de %.1f%% dans la plage [%.0f%%–%.0f%%] pour la CCN %s",
                netLabel, ratio * 100, min * 100, max * 100, ccn))
            .build();
    }

    // ── Check 7 : Cohérence prélèvement à la source ───────────────────────────

    /**
     * Vérifie l'équation : Net avant PAS − PAS = Net à payer final (tolérance 2€).
     * Un écart > 2€ indique que le net à payer a pu être falsifié après génération.
     * Ignoré silencieusement si net avant impôt ou net final ne sont pas disponibles.
     */
    // Le participe est la forme la plus repandue sur les bulletins reels :
    // "Impot sur le revenu preleve a la source".
    private static final List<String> LABELS_PAS = List.of(
        "prelevement a la source", "preleve a la source",
        "prelevee a la source", "retenue a la source");

    /**
     * Montant du prelevement a la source.
     *
     * La ligne presente trois colonnes : base, taux, montant. Le montant est donc
     * le dernier. Une ligne qui n'en porte qu'un seul n'expose que sa base, l'impot
     * etant nul ou marque d'un tiret : retourner cette base la ferait passer pour
     * un impot de plusieurs milliers d'euros. Mieux vaut alors ne rien affirmer et
     * deduire l'impot de l'ecart entre les deux nets.
     */
    private Double extractMontantPas(String[] lines) {
        for (String line : lines) {
            String norm = normalizeDiacritics(line);
            if (LABELS_PAS.stream().noneMatch(norm::contains)) continue;

            List<Double> montants = new ArrayList<>();
            Matcher m = AMOUNT_PATTERN.matcher(line);
            while (m.find()) {
                try {
                    montants.add(Double.parseDouble(
                        m.group().replaceAll("[\\s\u00a0]", "").replace(",", ".")));
                } catch (NumberFormatException ignored) {}
            }
            if (montants.isEmpty()) continue;

            // La ligne expose au plus trois colonnes : base, taux, montant. Un
            // taux accompagnant un montant unique signale que seule la base est
            // lisible, l'impot etant marque d'un tiret : la retourner le ferait
            // passer pour un impot de plusieurs milliers d'euros.
            boolean porteUnTaux = TAUX_COTISATION.matcher(line).find()
                || TAUX_SANS_POURCENT.matcher(line).find();
            if (porteUnTaux && montants.size() < 2) continue;

            return montants.get(montants.size() - 1);
        }
        return null;
    }

    // Taux imprime sans signe pourcent, reconnaissable a ses decimales :
    // "0,0000", "7,9000".
    private static final Pattern TAUX_SANS_POURCENT = Pattern.compile(
        "(?<![0-9.,])[0-9]{1,2}[.,][0-9]{3,4}(?![0-9])");

    /** Taux de prelevement a la source au-dela duquel le montant devient suspect. */
    private static final double TAUX_PAS_MAX = 0.45;

    /**
     * Controle du prelevement quand son montant n'est pas lisible : l'impot est
     * alors deduit de l'ecart entre le net avant prelevement et le net a payer.
     *
     * Deux verdicts possibles. Un net a payer superieur au net avant prelevement
     * est impossible, l'impot ne pouvant qu'abaisser le montant verse. Un taux
     * implicite au-dela du taux maximal du bareme est douteux sans etre
     * impossible : le prelevement peut etre majore sur demande du salarie.
     */
    private AnalysisResult.Check checkPasImplicite(double netAvantPas, double netFinal) {
        double impotImplicite = netAvantPas - netFinal;

        if (impotImplicite < -2) {
            return AnalysisResult.Check.builder()
                .category("Calculs")
                .label("Prélèvement à la source")
                .status("FAILED")
                .detail(String.format(
                    "Net à payer (%.2f€) supérieur au net avant prélèvement à la source"
                        + " (%.2f€) — impossible, l'impôt ne peut que diminuer le montant"
                        + " versé : écart de %.2f€",
                    netFinal, netAvantPas, -impotImplicite))
                .build();
        }

        double taux = netAvantPas > 0 ? impotImplicite / netAvantPas : 0;
        if (taux > TAUX_PAS_MAX) {
            return AnalysisResult.Check.builder()
                .category("Calculs")
                .label("Prélèvement à la source")
                .status("WARNING")
                .detail(String.format(
                    "Écart de %.2f€ entre le net avant prélèvement (%.2f€) et le net à"
                        + " payer (%.2f€), soit un taux implicite de %.1f%% — au-delà du"
                        + " barème, à moins d'un taux majoré sur demande",
                    impotImplicite, netAvantPas, netFinal, taux * 100))
                .build();
        }

        return AnalysisResult.Check.builder()
            .category("Calculs")
            .label("Prélèvement à la source")
            .status("OK")
            .detail(String.format(
                "Net avant prélèvement (%.2f€) − net à payer (%.2f€) = %.2f€,"
                    + " soit un taux implicite de %.1f%% — cohérent",
                netAvantPas, netFinal, impotImplicite, taux * 100))
            .build();
    }

    private AnalysisResult.Check checkPAS(Double netAvantImpot, Double pas, Double netFinal) {
        if (netAvantImpot == null || netFinal == null) return null;

        // Montant du prelevement introuvable, ou superieur au net dont il se
        // deduit, donc mal lu : il reste deductible de l'ecart entre les deux
        // nets. Se plaindre d'un libelle n'apprendrait rien a l'utilisateur,
        // alors que le taux implicite, lui, est verifiable.
        if (pas == null || pas >= netAvantImpot) {
            return checkPasImplicite(netAvantImpot, netFinal);
        }

        // Un net a payer superieur au net avant prelevement est impossible, quel
        // que soit le montant de l'impot : celui-ci ne peut que le diminuer.
        if (netFinal > netAvantImpot + 2) {
            return AnalysisResult.Check.builder()
                .category("Calculs")
                .label("Prélèvement à la source")
                .status("FAILED")
                .detail(String.format(
                    "Net à payer (%.2f€) supérieur au net avant prélèvement (%.2f€)"
                        + " alors que le bulletin annonce %.2f€ d'impôt — le net à payer"
                        + " a probablement été majoré",
                    netFinal, netAvantImpot, pas))
                .build();
        }

        double calculatedNet = netAvantImpot - pas;
        double ecart = Math.abs(calculatedNet - netFinal);

        if (ecart > 2) {
            return AnalysisResult.Check.builder()
                .category("Calculs")
                .label("Prélèvement à la source")
                // Au-dela de cinquante euros, l'ecart ne s'explique plus par un
                // arrondi ni par une ligne annexe.
                .status(ecart > 50 ? "FAILED" : "WARNING")
                .detail(String.format(
                    "Net avant PAS (%.2f€) − PAS (%.2f€) = %.2f€ ≠ Net à payer (%.2f€)"
                        + " — écart de %.2f€, incohérence prélèvement à la source",
                    netAvantImpot, pas, calculatedNet, netFinal, ecart))
                .build();
        }
        return AnalysisResult.Check.builder()
            .category("Calculs")
            .label("Prélèvement à la source")
            .status("OK")
            .detail(String.format(
                "Net avant PAS (%.2f€) − PAS (%.2f€) = %.2f€ ≈ Net à payer (%.2f€) — cohérent",
                netAvantImpot, pas, calculatedNet, netFinal))
            .build();
    }

    // ── Check 6 : Somme des cotisations salarié ───────────────────────────────

    // Taux d'une ligne de cotisation : "6,900 %", "0,40 %".
    private static final Pattern TAUX_COTISATION = Pattern.compile(
        "([0-9]{1,2}[.,][0-9]{1,4})\\s*%");

    // Lignes de synthese : elles portent des montants sans etre des cotisations.
    private static final Pattern LIGNE_DE_SYNTHESE = Pattern.compile(
        "(?i)^\\s*(?:total|cumul|net\\b|brut\\b|salaire\\b|remun|base\\b|libelle"
            + "|periode|conge|prime\\b|assiette)");

    /**
     * Montant salarial d'une ligne de cotisation, confirme par l'arithmetique.
     *
     * L'ordre des colonnes varie d'un editeur a l'autre : le taux precede la base
     * chez certains, la suit chez d'autres. Plutot que de supposer une mise en
     * page, on retient le montant que le calcul confirme, celui qui vaut la base
     * multipliee par le taux.
     *
     * La version precedente supposait l'ordre "taux, base, montant" et, sur la
     * mise en page inverse, sommait la colonne patronale : 1 117,82 EUR au lieu de
     * 808,15 EUR sur le jeu de tests, soit un ecart inexistant impute a un
     * bulletin sain. Un montant que le calcul ne confirme pas est desormais
     * ignore, et le controle s'efface s'il n'en confirme pas assez.
     */
    private Double montantSalarialConfirme(String line) {
        if (LIGNE_DE_SYNTHESE.matcher(line).find()) return null;

        List<Double> montants = new ArrayList<>();
        Matcher m = AMOUNT_PATTERN.matcher(line);
        while (m.find()) {
            try {
                montants.add(Double.parseDouble(
                    m.group().replaceAll("[\\s\u00a0]", "").replace(",", ".")));
            } catch (NumberFormatException ignored) {}
        }

        Matcher taux = TAUX_COTISATION.matcher(line);
        while (taux.find()) {
            double t;
            try {
                t = Double.parseDouble(taux.group(1).replace(",", ".")) / 100.0;
            } catch (NumberFormatException e) {
                continue;
            }
            if (t <= 0) continue;

            for (Double base : montants) {
                for (Double montant : montants) {
                    if (base <= montant) continue; // une base depasse son montant
                    // Tolerance d'arrondi : le taux imprime est lui-meme arrondi.
                    if (Math.abs(base * t - montant) <= Math.max(0.05, montant * 0.01)) {
                        return montant;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Verifie que le brut moins la somme des lignes de cotisation salariale donne
     * le net avant prelevement a la source.
     *
     * Le net a payer ne convient pas : l'impot y est deja deduit, et l'ecart
     * vaudrait mecaniquement le montant de ce prelevement sur n'importe quel
     * bulletin sain — c'etait la source d'un echec systematique.
     */
    private AnalysisResult.Check checkCotisationsSum(String text, Double brut, Double net) {
        if (brut == null || net == null || brut <= 0) return null;

        double sum = 0;
        int count = 0;
        for (String line : text.split("\\r?\\n")) {
            Double montant = montantSalarialConfirme(line);
            if (montant != null && montant > 0 && montant < brut) {
                sum += montant;
                count++;
            }
        }

        if (count < 3) return null; // pas assez de lignes confirmées pour être fiable

        double expectedNet = brut - sum;
        double ecart = Math.abs(expectedNet - net);

        if (ecart > 50) {
            return AnalysisResult.Check.builder()
                .category("Calculs")
                .label("Somme des cotisations")
                .status("FAILED")
                .detail(String.format(
                    "Brut (%.2f€) − cotisations salarié (%.2f€, %d lignes confirmées) = %.2f€"
                        + " ≠ Net avant PAS (%.2f€) — écart de %.2f€",
                    brut, sum, count, expectedNet, net, ecart))
                .build();
        } else if (ecart > 20) {
            return AnalysisResult.Check.builder()
                .category("Calculs")
                .label("Somme des cotisations")
                .status("WARNING")
                .detail(String.format(
                    "Brut − cotisations salarié (%.2f€, %d lignes confirmées) = %.2f€,"
                        + " Net avant PAS = %.2f€ — écart %.2f€ (possible prime ou arrondi)",
                    sum, count, expectedNet, net, ecart))
                .build();
        }
        return AnalysisResult.Check.builder()
            .category("Calculs")
            .label("Somme des cotisations")
            .status("OK")
            .detail(String.format(
                "Brut (%.2f€) − cotisations salarié (%.2f€, %d lignes confirmées) ≈ Net avant PAS"
                + " (%.2f€) — cohérent (écart %.2f€)",
                brut, sum, count, net, ecart))
            .build();
    }

    /**
     * Extrait le net final APRÈS prélèvement à la source.
     * Cible les lignes contenant "net a payer" mais PAS "avant"
     * (pour exclure "net a payer avant impôt sur le revenu").
     */
    private Double extractNetFinalApresPas(String[] lines) {
        for (String line : lines) {
            String norm = normalizeDiacritics(line);
            if (norm.contains("net a payer") && !norm.contains("avant")) {
                Double val = largestAmountInLine(line);
                if (val != null) return val;
            }
        }
        return null;
    }

    /**
     * Like extractAmountNearLabel but scans the SAME LINE ONLY (no window expansion).
     * Returns the LAST amount on the line (PAS is always the last column in standard payslip format).
     */
    /**
     * Cherche le montant sur la ligne du libelle, et n'elargit a la fenetre
     * voisine qu'en dernier recours. extractAmountNearLabel seul retient le plus
     * grand montant des 6 lignes autour, ce qui capture la base du prelevement a
     * la source (superieure au net) quand elle suit la ligne "net avant impot".
     */
    private Double extractAmountPreferSameLine(String[] lines, String... labels) {
        Double sameLine = extractAmountOnSameLine(lines, labels);
        if (sameLine != null) return sameLine;
        return extractAmountNearLabel(lines, labels);
    }

    // Apprentis et contrats de professionnalisation sont exoneres de cotisations
    // salariales : net egal au brut et remuneration en pourcentage du SMIC sont
    // alors normaux, et non des anomalies.
    private static final List<String> CONTRATS_EXONERES = List.of(
        "contrat d apprentissage", "contrat d'apprentissage", "apprenti",
        "contrat de professionnalisation", "professionnalisation");

    private boolean isContratExonere(String text) {
        String norm = normalizeDiacritics(text);
        return CONTRATS_EXONERES.stream().anyMatch(norm::contains);
    }

    private static final List<String> ELEMENTS_HORS_BRUT = List.of(
        "avantage en nature", "avantages en nature",
        "interessement", "participation", "titres-restaurant", "titre restaurant",
        "ticket restaurant", "remboursement de frais", "frais professionnels",
        "note de frais", "acompte", "avance sur salaire", "saisie sur salaire",
        "indemnite kilometrique", "ijss", "transport domicile");

    private boolean containsElementsHorsBrut(String text) {
        String norm = normalizeDiacritics(text);
        return ELEMENTS_HORS_BRUT.stream().anyMatch(norm::contains);
    }

    private Double extractFirstAmountOnSameLine(String[] lines, String... labels) {
        for (String line : lines) {
            String norm = normalizeDiacritics(line);
            for (String label : labels) {
                if (norm.contains(label)) {
                    Matcher m = AMOUNT_PATTERN.matcher(line);
                    while (m.find()) {
                        try {
                            double val = Double.parseDouble(
                                m.group().replaceAll("[\\s ]", "").replace(",", "."));
                            if (val >= 10 && val <= 200000) return val;
                        } catch (NumberFormatException ignored) {}
                    }
                }
            }
        }
        return null;
    }

    private Double extractAmountOnSameLine(String[] lines, String... labels) {
        for (String line : lines) {
            String norm = normalizeDiacritics(line);
            for (String label : labels) {
                if (norm.contains(label)) {
                    Double val = lastAmountInLine(line);
                    if (val != null) return val;
                }
            }
        }
        return null;
    }

    /** Returns the LAST plausible salary amount (10–50000) found in a single line. */
    private Double lastAmountInLine(String line) {
        Matcher m = AMOUNT_PATTERN.matcher(line);
        Double last = null;
        while (m.find()) {
            String raw = m.group().replaceAll("[\\s ]", "").replace(",", ".");
            try {
                double val = Double.parseDouble(raw);
                if (val >= 10 && val <= 50000) last = val;
            } catch (NumberFormatException ignored) {}
        }
        return last;
    }

    /** Returns the LARGEST plausible salary amount (100–200 000) found in a single line. */
    /** Returns the LARGEST plausible salary amount (100\u2013200 000) found in a single line. Requires decimal part to avoid capturing years/codes. */
    private Double largestAmountInLine(String line) {
        Matcher m = AMOUNT_PATTERN.matcher(line);
        Double best = null;
        while (m.find()) {
            String raw = m.group().replaceAll("[\\s\u00a0]", "").replace(",", ".");
            try {
                double val = Double.parseDouble(raw);
                if (val >= 100 && val <= 200000 && (best == null || val > best)) best = val;
            } catch (NumberFormatException ignored) {}
        }
        return best;
    }
}
