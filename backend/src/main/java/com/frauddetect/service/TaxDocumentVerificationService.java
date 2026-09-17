package com.frauddetect.service;

import com.frauddetect.model.AnalysisResult;
import com.frauddetect.model.AvisImposition;
import com.frauddetect.twoddoc.TwoDDocData;
import com.frauddetect.twoddoc.TwoDDocVerificationService;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Orchestre les controles d'un document fiscal.
 *
 * Deux verifications independantes, volontairement separees :
 *
 *   TaxDocumentVerificationService
 *     +-- TwoDDocVerificationService : integrite des donnees signees
 *     +-- AvisImpositionService      : rapprochement avec les bulletins
 *
 * Une troisieme, l'interrogation du service de verification de la DGFiP,
 * viendra s'y ajouter sans modifier les deux premieres.
 */
@Service
public class TaxDocumentVerificationService {

    private final AvisImpositionService avisImpositionService;
    private final TwoDDocVerificationService twoDDocService;

    public TaxDocumentVerificationService(AvisImpositionService avisImpositionService,
                                          TwoDDocVerificationService twoDDocService) {
        this.avisImpositionService = avisImpositionService;
        this.twoDDocService = twoDDocService;
    }

    public record Result(
        AvisImposition avis,
        TwoDDocData twoDDoc,
        Map<String, TwoDDocVerificationService.Comparison> comparisons,
        List<AnalysisResult.Check> checks
    ) {
    }

    /**
     * @param pdfBytes contenu du document ; lu deux fois, pour le texte puis pour
     *                 le rendu graphique du DataMatrix
     * @param netImposableMensuel net imposable releve sur les bulletins, ou null
     */
    public Result verify(byte[] pdfBytes, Double netImposableMensuel) throws Exception {
        AvisImposition avis;
        try (ByteArrayInputStream in = new ByteArrayInputStream(pdfBytes)) {
            avis = avisImpositionService.extractFromPdf(in);
        }

        TwoDDocData twoDDoc;
        try (ByteArrayInputStream in = new ByteArrayInputStream(pdfBytes)) {
            twoDDoc = twoDDocService.analyze(in);
        }

        Map<String, TwoDDocVerificationService.Comparison> comparisons =
            twoDDocService.compare(twoDDoc, comparableValues(avis));

        List<AnalysisResult.Check> checks = new ArrayList<>();
        checks.addAll(avisImpositionService.verify(avis, netImposableMensuel));
        checks.addAll(twoDDocService.toChecks(twoDDoc, comparisons));

        return new Result(avis, twoDDoc, comparisons, checks);
    }

    /**
     * Valeurs soumises au rapprochement.
     *
     * Seules figurent ici les donnees a la fois lisibles dans le texte du PDF et
     * dont la presence dans les champs signes a ete verifiee sur un avis de
     * production. Une valeur comparee a tort produirait un ecart sur un document
     * authentique, ce qui est plus nuisible que de ne pas la comparer.
     *
     * Ecartees, et pourquoi :
     *
     *   numero fiscal      present dans le 2D-DOC, mais figurant sur le document
     *                      dans une zone graphique que l'extraction de texte ne
     *                      restitue pas : aucune source a comparer.
     *   reference de l'avis absente du texte extrait.
     *   nombre de parts    valeur trop courte pour etre discriminante : "3" se
     *                      retrouve dans presque tous les champs numeriques, ce
     *                      qui produit autant de faux rapprochements que de faux
     *                      ecarts.
     *   impot, annee       peu discriminants, et l'annee est deja couverte par le
     *                      champ portant l'identite.
     */
    private Map<String, String> comparableValues(AvisImposition avis) {
        Map<String, String> values = new LinkedHashMap<>();

        if (avis.getRevenuFiscalReference() != null) {
            values.put("revenu fiscal de référence",
                String.valueOf(avis.getRevenuFiscalReference().longValue()));
        }

        // Le champ signe porte nom et prenom accoles : chaque declarant est
        // compare separement, un avis de foyer en comptant plusieurs.
        List<String> declarants = avis.getNomsDeclarants();
        if (declarants != null) {
            for (int i = 0; i < declarants.size(); i++) {
                values.put("identité du déclarant " + (i + 1), declarants.get(i));
            }
        }
        return values;
    }
}
