package com.stockportfolio.dto;

import java.math.BigDecimal;
import java.util.List;

public record ValuationEvaluationResponse(
        String symbol,
        String engineVersion,
        ValuationScenarioResponse scenario,
        Sensitivity sensitivity,
        ReverseDcf reverseDcf,
        List<ValuationResponse.Diagnostic> diagnostics
) {
    public record Sensitivity(List<BigDecimal> discountRatesPct,
                              List<BigDecimal> terminalGrowthRatesPct,
                              List<List<BigDecimal>> intrinsicValues) { }
    public record ReverseDcf(BigDecimal impliedInitialGrowthRatePct,
                             BigDecimal impliedDiscountRatePct,
                             String status) { }
}
