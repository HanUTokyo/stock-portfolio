package com.stockportfolio.dto;

import java.util.Map;

public record DataReviewPatchRequest(
        Map<String, Object> changes,
        String reviewStatus,
        String note,
        String reasonCode,
        String expectedRevision
) {
    public DataReviewPatchRequest(Map<String, Object> changes, String reviewStatus, String note) {
        this(changes, reviewStatus, note, null, null);
    }
}
