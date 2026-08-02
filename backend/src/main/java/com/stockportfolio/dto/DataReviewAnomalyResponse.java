package com.stockportfolio.dto;

public record DataReviewAnomalyResponse(
        String code,
        String severity,
        String message
) {
}
