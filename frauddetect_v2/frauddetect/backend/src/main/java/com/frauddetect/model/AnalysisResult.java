package com.frauddetect.model;

import lombok.Builder;
import lombok.Data;
import java.util.List;

@Data
@Builder
public class AnalysisResult {
    private int score;
    private String verdict;
    private String color;
    private List<Check> checks;
    private DocumentInfo documentInfo;
    private int remainingDocuments;
    private boolean isPro;

    @Data
    @Builder
    public static class Check {
        private String category;
        private String label;
        private String status;
        private String detail;
    }

    @Data
    @Builder
    public static class DocumentInfo {
        private String employeur;
        private String siret;
        private String employe;
        private String periode;
        private String salaireBrut;
        private String salaireNet;
        private String pdfCreatedWith;
        private String pdfCreationDate;
        private String pdfModifiedDate;
        private boolean pdfModified;
        private int pageCount;
    }
}
