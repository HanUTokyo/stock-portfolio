package com.stockportfolio.dto;

import java.math.BigDecimal;
import java.util.List;

public record ValuationEvaluationResponse(
        String symbol,
        String engineVersion,
        ValuationScenarioResponse scenario,
        Sensitivity sensitivity,
        ReverseDcf reverseDcf,
        List<ValuationResponse.Diagnostic> diagnostics,
        String calculationMode,
        String readiness,
        ValuationMethodsResponse valuationMethods,
        CrossModelReconciliationResponse crossModelReconciliation,
        DebtBreakdownResponse debtBreakdown,
        NetBorrowingBreakdownResponse netBorrowingBreakdown,
        DiscountRateBreakdownResponse discountRateBreakdown,
        List<ScenarioDriverBridgeResponse> scenarioDriverBridge,
        CashFlowBridgeResponse cashFlowBridge,
        ValuationResponse.FundamentalsFreshness fundamentalsFreshness
) {
    public ValuationEvaluationResponse(String symbol, String engineVersion, ValuationScenarioResponse scenario,
                                       Sensitivity sensitivity, ReverseDcf reverseDcf,
                                       List<ValuationResponse.Diagnostic> diagnostics) {
        this(symbol, engineVersion, scenario, sensitivity, reverseDcf, diagnostics,
                "DUAL_TRACK", "UNAVAILABLE", null, null, null, null, null, List.of(), null, null);
    }
    public record Sensitivity(List<BigDecimal> discountRatesPct,
                              List<BigDecimal> terminalGrowthRatesPct,
                              List<List<BigDecimal>> intrinsicValues) { }
    public record ReverseDcf(BigDecimal impliedInitialGrowthRatePct,
                             BigDecimal impliedDiscountRatePct,
                             String status) { }
}
