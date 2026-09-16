package com.frauddetect.model;

import lombok.Builder;
import lombok.Data;

/**
 * Donnees relevees sur un avis d'imposition.
 *
 * Le numero fiscal et la reference de l'avis sont des identifiants personnels :
 * ils servent a preparer la verification aupres de l'administration et ne doivent
 * jamais etre journalises.
 */
@Data
@Builder
public class AvisImposition {
    private String numeroFiscal;
    private String referenceAvis;
    private Integer anneeRevenus;
    private Double traitementsSalaires;
    private Double revenuFiscalReference;
}
