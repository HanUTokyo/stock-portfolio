package com.stockportfolio.dto;

import java.util.List;
import java.util.Map;

public record DataReviewBatchPreviewResponse(
        String source,
        String reviewStatus,
        String reasonCode,
        int affectedCount,
        Map<String, Long> riskCounts,
        List<DataReviewRowResponse> rows
) {
}
