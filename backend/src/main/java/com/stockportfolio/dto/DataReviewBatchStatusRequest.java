package com.stockportfolio.dto;

import java.util.List;
import java.util.Map;

public record DataReviewBatchStatusRequest(
        List<String> recordIds,
        String reviewStatus,
        String note,
        String reasonCode,
        Map<String, String> expectedRevisions
) {
    public DataReviewBatchStatusRequest(List<String> recordIds, String reviewStatus, String note) {
        this(recordIds, reviewStatus, note, null, Map.of());
    }
}
