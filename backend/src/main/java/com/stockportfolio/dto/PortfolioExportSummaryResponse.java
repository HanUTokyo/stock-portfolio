package com.stockportfolio.dto;

import java.math.BigDecimal;

public record PortfolioExportSummaryResponse(
        BigDecimal totalAssets,
        BigDecimal totalMarketValue,
        BigDecimal totalCostBasis
) {
}
