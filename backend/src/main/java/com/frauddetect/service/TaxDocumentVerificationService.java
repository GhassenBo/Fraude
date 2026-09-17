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
     * Limite au revenu fiscal de reference, seule donnee dont la presence dans
     * les champs signes a ete verifiee sur un avis de production. Y ajouter le
     * nombre de parts produisait un ecart trompeur, son format dans le code
     * n'etant pas celui du texte : signaler une anomalie sur une correspondance
     * supposee serait pire que ne rien signaler. D'autres valeurs pourront etre
     * ajoutees a mesure que leur format est confirme.
     */
    private Map<String, String> comparableValues(AvisImposition avis) {
        Map<String, String> values = new LinkedHashMap<>();
        if (avis.getRevenuFiscalReference() != null) {
            values.put("revenu fiscal de référence",
                String.valueOf(avis.getRevenuFiscalReference().longValue()));
        }
        return values;
    }
}
