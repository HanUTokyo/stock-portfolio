package com.stockportfolio.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public record DataReviewRowResponse(
        String source,
        String recordId,
        Map<String, Object> rawValues,
        Map<String, Object> reviewedValues,
        Map<String, Object> effectiveValues,
        String reviewStatus,
        String note,
        String reviewer,
        OffsetDateTime updatedAt,
        String reasonCode,
        String riskLevel,
        int anomalyCount,
        List<String> anomalyFlags,
        List<DataReviewAnomalyResponse> anomalies,
        String revision
) {
    public DataReviewRowResponse(
            String source, String recordId, Map<String, Object> rawValues, Map<String, Object> reviewedValues,
            Map<String, Object> effectiveValues, String reviewStatus, String note, String reviewer,
            OffsetDateTime updatedAt, List<String> anomalyFlags, List<DataReviewAnomalyResponse> anomalies, String revision
    ) {
        this(source, recordId, rawValues, reviewedValues, effectiveValues, reviewStatus, note, reviewer, updatedAt,
                null, "normal", anomalyFlags == null ? 0 : anomalyFlags.size(), anomalyFlags, anomalies, revision);
    }
}
