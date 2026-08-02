package com.stockportfolio.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record PositionResponse(
        Long id,
        String symbol,
        BigDecimal latestPrice,
        BigDecimal latestPe,
        BigDecimal sharesOutstanding,
        BigDecimal sharesOutstandingOverride,
        BigDecimal effectiveSharesOutstanding,
        String sharesOutstandingSource,
        OffsetDateTime sharesOutstandingUpdatedAt,
        OffsetDateTime priceUpdatedAt,
        String assetClass,
        String instrumentType,
        String underlying,
        String sector,
        String region,
        OffsetDateTime metadataUpdatedAt,
        OffsetDateTime updatedAt,
        String profileReviewStatus,
        Long version,
        String quoteCurrency,
        BigDecimal beta,
        String betaSource,
        OffsetDateTime betaUpdatedAt
) {
}
