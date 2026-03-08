package com.stockportfolio.dto;

import java.math.BigDecimal;

public record PortfolioSummaryResponse(
        int totalPositions,
        BigDecimal totalCostBasis,
        BigDecimal totalUnits
) {
}
