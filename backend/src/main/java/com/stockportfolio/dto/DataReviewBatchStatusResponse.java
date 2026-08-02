package com.stockportfolio.dto;

import java.util.List;

public record DataReviewBatchStatusResponse(
        String source,
        String reviewStatus,
        int updatedCount,
        List<DataReviewRowResponse> rows
) {
}
