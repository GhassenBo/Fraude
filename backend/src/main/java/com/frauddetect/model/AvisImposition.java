package com.frauddetect.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Donnees relevees sur un avis d'imposition.
 *
 * L'avis est etabli pour un foyer fiscal : la ligne des salaires porte une
 * colonne par declarant, puis un total. Les montants sont donc conserves tels
 * quels, le rapprochement identifiant ensuite celle qui concerne le candidat.
 */
@Data
@Builder
public class AvisImposition {
    private Integer anneeRevenus;
    private List<Double> salairesDeclares;
    private Double revenuFiscalReference;
}
