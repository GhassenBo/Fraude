package com.frauddetect.service;

import com.frauddetect.model.AnalysisResult;
import com.frauddetect.model.AvisImposition;
import com.frauddetect.model.BatchAnalysisResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class DossierVerificationServiceTest {

    @Mock private com.frauddetect.util.PdfAnalyzer pdfAnalyzer;
    @Mock private SiretVerificationService siretService;
    @Mock private SalaryCalculationService salaryService;
    @Mock private AiAnalysisService aiAnalysisService;
    @Mock private ClaudeVisionService claudeVisionService;
    @Mock private com.frauddetect.repository.UserRepository userRepository;
    @Mock private com.frauddetect.repository.AnalysisRepository analysisRepository;
    @Mock private TaxDocumentVerificationService taxService;

    private DossierVerificationService service;

    @BeforeEach
    void setUp() {
        // FraudDetectionService reel : seuls computeVerdict et computeColor sont
        // sollicites ici, et ils ne touchent aucune dependance.
        FraudDetectionService fraudDetectionService = new FraudDetectionService(
            pdfAnalyzer, siretService, salaryService, aiAnalysisService,
            claudeVisionService, new DocumentCoherenceService(),
            new EmailService(null), userRepository, analysisRepository);
        service = new DossierVerificationService(fraudDetectionService, taxService);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private AnalysisResult bulletin(AnalysisResult.DocumentInfo info) {
        return AnalysisResult.builder().score(90).documentInfo(info).build();
    }

    private AnalysisResult.DocumentInfo info(String employe, Double netImposable,
                                             Double cumul, Integer mois) {
        return AnalysisResult.DocumentInfo.builder()
            .employe(employe).netImposable(netImposable)
            .cumulNetImposable(cumul).moisPeriode(mois).build();
    }

    private BatchAnalysisResult lot(List<AnalysisResult.DocumentInfo> infos, String... noms) {
        List<AnalysisResult> results = new ArrayList<>();
        infos.forEach(i -> results.add(bulletin(i)));
        return BatchAnalysisResult.builder()
            .globalScore(90).globalVerdict("AUTHENTIQUE").globalColor("green")
            .documentsAnalyzed(results.size())
            .results(results).filenames(Arrays.asList(noms))
            .crossChecks(List.of()).build();
    }

    private AvisImposition avis(String... declarants) {
        return AvisImposition.builder()
            .anneeRevenus(2025).salairesDeclares(List.of(31560.0))
            .nomsDeclarants(List.of(declarants)).build();
    }

    private AnalysisResult.Check check(String category, String status) {
        return AnalysisResult.Check.builder()
            .category(category).label("l").status(status).detail("d").build();
    }

    // ── Base du rapprochement ────────────────────────────────────────────────

    @Test
    void leCumulRameneAuMoisEstPrefereAuMontantDuMois() {
        // 43 318,11 sur 11 mois = 3 938,01/mois, exactement le montant du mois :
        // primes lissees, base retenue.
        DossierVerificationService.BaseRapprochement base = service.baseRapprochement(
            lot(List.of(info("FAURE AMANDINE", 3938.01, 43318.11, 11)), "bulletin.pdf"));

        assertThat(base.netImposableMensuel()).isEqualTo(3938.01);
        assertThat(base.origine()).contains("cumul depuis janvier").contains("bulletin.pdf");
    }

    @Test
    void leCumulQuiNeCouvrePasLesMoisEcoules_estEcarte() {
        // Embauche en juillet : 9 356,52 depuis janvier, mais la periode est le
        // mois 9. Le diviser par 9 donnerait 1 040 contre 2 339 reellement percus.
        DossierVerificationService.BaseRapprochement base = service.baseRapprochement(
            lot(List.of(info("BERNARD ANTOINE", 2339.13, 9356.52, 9)), "bulletin.pdf"));

        assertThat(base.netImposableMensuel()).isEqualTo(2339.13);
        assertThat(base.origine()).contains("net imposable du bulletin");
    }

    @Test
    void sansMoisDePeriode_leMontantDuMoisSert() {
        // Le cumul existe mais rien ne dit sur combien de mois il porte.
        DossierVerificationService.BaseRapprochement base = service.baseRapprochement(
            lot(List.of(info("LEROY CAMILLE", 2619.04, 20952.32, null)), "bulletin.pdf"));

        assertThat(base.netImposableMensuel()).isEqualTo(2619.04);
    }

    @Test
    void dansUnLot_leBulletinLePlusAvanceDansLAnneeEstRetenu() {
        // Son cumul couvre le plus de mois, donc lisse le mieux.
        DossierVerificationService.BaseRapprochement base = service.baseRapprochement(lot(
            List.of(info("X", 2000.0, 6000.0, 3), info("X", 2100.0, 16800.0, 8)),
            "mars.pdf", "aout.pdf"));

        assertThat(base.netImposableMensuel()).isEqualTo(2100.0);
        assertThat(base.origine()).contains("aout.pdf");
    }

    @Test
    void aucuneGrandeurFiscale_aucuneBase() {
        assertThat(service.baseRapprochement(
            lot(List.of(info("X", null, null, 5)), "bulletin.pdf"))).isNull();
    }

    // ── Identite ─────────────────────────────────────────────────────────────

    @Test
    void identiteConcordante_estOK() {
        AnalysisResult.Check c = service.identiteCheck(
            lot(List.of(info("BORGI Ghassen", 2000.0, null, 5)), "b.pdf"),
            avis("BORGI GHASSEN"));

        assertThat(c.getStatus()).isEqualTo("OK");
    }

    @Test
    void ordreInverseDesMots_concordeQuandMeme() {
        // Le bulletin ecrit indifferemment "NOM Prenom" ou "Prenom NOM".
        AnalysisResult.Check c = service.identiteCheck(
            lot(List.of(info("Ghassen BORGI", 2000.0, null, 5)), "b.pdf"),
            avis("BORGI GHASSEN"));

        assertThat(c.getStatus()).isEqualTo("OK");
    }

    @Test
    void civiliteEtAccents_neGenentPas() {
        AnalysisResult.Check c = service.identiteCheck(
            lot(List.of(info("Madame HÉLÈNE DUPRÉ", 2000.0, null, 5)), "b.pdf"),
            avis("DUPRE HELENE"));

        assertThat(c.getStatus()).isEqualTo("OK");
    }

    @Test
    void candidatDeclarantDeuxDUnFoyer_estAccepte() {
        AnalysisResult.Check c = service.identiteCheck(
            lot(List.of(info("DUPONT MARIE", 2000.0, null, 5)), "b.pdf"),
            avis("MARTIN JEAN", "DUPONT MARIE"));

        assertThat(c.getStatus()).isEqualTo("OK");
        assertThat(c.getDetail()).contains("DUPONT MARIE");
    }

    @Test
    void nomDUsageDifferentDuNomDeNaissance_resteUnAvertissement() {
        // L'avis porte le nom de naissance, le bulletin le nom d'epouse : le
        // prenom subsiste. Signaler un echec accuserait a tort.
        AnalysisResult.Check c = service.identiteCheck(
            lot(List.of(info("MARTIN SOPHIE", 2000.0, null, 5)), "b.pdf"),
            avis("BERNARD SOPHIE"));

        assertThat(c.getStatus()).isEqualTo("WARNING");
        assertThat(c.getDetail()).contains("nom d'usage");
    }

    @Test
    void avisDUnTiers_estDetecte() {
        // Le cas que la comparaison des revenus seule ne voit pas : un avis dont
        // les montants concordent, mais qui concerne quelqu'un d'autre.
        AnalysisResult.Check c = service.identiteCheck(
            lot(List.of(info("BORGI Ghassen", 2000.0, null, 5)), "b.pdf"),
            avis("LEFEVRE PATRICIA"));

        assertThat(c.getStatus()).isEqualTo("FAILED");
        assertThat(c.getDetail()).contains("BORGI Ghassen");
    }

    @Test
    void identitePartiellementExtraite_neConclutPas() {
        // Un seul mot d'un cote : l'extraction a echoue, pas le candidat.
        AnalysisResult.Check c = service.identiteCheck(
            lot(List.of(info("Ghassen", 2000.0, null, 5)), "b.pdf"),
            avis("LEFEVRE PATRICIA"));

        assertThat(c.getStatus()).isEqualTo("WARNING");
        assertThat(c.getDetail()).contains("incomplètes");
    }

    @Test
    void avisSansDeclarant_pasDeRapprochement() {
        AnalysisResult.Check c = service.identiteCheck(
            lot(List.of(info("BORGI Ghassen", 2000.0, null, 5)), "b.pdf"),
            AvisImposition.builder().nomsDeclarants(List.of()).build());

        assertThat(c.getStatus()).isEqualTo("WARNING");
        assertThat(c.getDetail()).contains("déclarants introuvable");
    }

    @Test
    void bulletinSansNom_pasDeRapprochement() {
        AnalysisResult.Check c = service.identiteCheck(
            lot(List.of(info(null, 2000.0, null, 5)), "b.pdf"),
            avis("BORGI GHASSEN"));

        assertThat(c.getStatus()).isEqualTo("WARNING");
        assertThat(c.getDetail()).contains("salarié introuvable");
    }

    // ── Repercussion sur le verdict ──────────────────────────────────────────

    @Test
    void identiteEnEchec_faitTomberLeVerdict() {
        BatchAnalysisResult lot = lot(List.of(info("X", 2000.0, null, 5)), "b.pdf");

        service.appliqueAuVerdict(lot, List.of(check("Dossier", "FAILED")), List.of());

        assertThat(lot.getGlobalScore()).isEqualTo(40);
        assertThat(lot.getGlobalVerdict()).isEqualTo("FRAUDULEUX");
        assertThat(lot.getGlobalColor()).isEqualTo("red");
    }

    @Test
    void cachet2DDocEnEchec_faitTomberLeVerdict() {
        BatchAnalysisResult lot = lot(List.of(info("X", 2000.0, null, 5)), "b.pdf");

        service.appliqueAuVerdict(lot, List.of(), List.of(check("2D-DOC", "FAILED")));

        assertThat(lot.getGlobalVerdict()).isEqualTo("FRAUDULEUX");
    }

    @Test
    void ecartDeRevenus_rendSuspectSansConclureALaFraude() {
        // Le revenu a pu changer depuis l'annee de l'avis.
        BatchAnalysisResult lot = lot(List.of(info("X", 2000.0, null, 5)), "b.pdf");

        service.appliqueAuVerdict(lot, List.of(),
            List.of(check("Avis d'imposition", "FAILED")));

        assertThat(lot.getGlobalScore()).isEqualTo(60);
        assertThat(lot.getGlobalVerdict()).isEqualTo("SUSPECT");
    }

    @Test
    void aucunEchec_laisseLeVerdictIntact() {
        BatchAnalysisResult lot = lot(List.of(info("X", 2000.0, null, 5)), "b.pdf");

        service.appliqueAuVerdict(lot, List.of(check("Dossier", "OK")),
            List.of(check("Avis d'imposition", "WARNING")));

        assertThat(lot.getGlobalScore()).isEqualTo(90);
        assertThat(lot.getGlobalVerdict()).isEqualTo("AUTHENTIQUE");
    }

    @Test
    void unScoreDejaBas_nEstPasRemonteParUnPlafond() {
        BatchAnalysisResult lot = lot(List.of(info("X", 2000.0, null, 5)), "b.pdf");
        lot.setGlobalScore(20);

        service.appliqueAuVerdict(lot, List.of(),
            List.of(check("Avis d'imposition", "FAILED")));

        assertThat(lot.getGlobalScore()).isEqualTo(20);
    }
}
