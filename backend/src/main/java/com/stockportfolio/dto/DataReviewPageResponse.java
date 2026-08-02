package com.stockportfolio.dto;

import java.util.List;

public record DataReviewPageResponse(
        String source,
        int page,
        int size,
        long totalElements,
        int totalPages,
        List<DataReviewRowResponse> rows
) {
}
