package com.frauddetect.model;

import lombok.Builder;
import lombok.Data;
import java.util.List;

@Data
@Builder
public class BatchAnalysisResult {
    private int globalScore;
    private String globalVerdict;
    private String globalColor;
    private int documentsAnalyzed;
    private List<AnalysisResult> results;
    private List<String> filenames;
}
