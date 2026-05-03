package com.stockportfolio.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record PortfolioExportResponse(
        OffsetDateTime generatedAt,
        String baseCurrency,
        PortfolioExportSummaryResponse portfolioSummary,
        List<PortfolioExportHoldingResponse> holdings
) {
}
