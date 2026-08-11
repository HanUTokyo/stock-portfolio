package com.stockportfolio.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * One independently calculated DCF method. FCFF and FCFE are never averaged;
 * callers can inspect either method even when it is not the compatibility
 * selectedModel.
 */
public record ValuationMethodResponse(
        String method,
        boolean available,
        String availability,
        String status,
        String cashFlowDefinition,
        String crossCheckCashFlowDefinition,
        String discountRateType,
        String forecastMode,
        String debtPolicy,
        BigDecimal latestTtmCashFlow,
        BigDecimal crossCheckTtmCashFlow,
        BigDecimal normalizedBaseCashFlow,
        BigDecimal automaticDiscountRatePct,
        BigDecimal definitionCrossCheckDifferencePct,
        DebtBreakdownResponse debtBreakdown,
        NetBorrowingBreakdownResponse netBorrowingBreakdown,
        DiscountRateBreakdownResponse discountRateBreakdown,
        List<ScenarioDriverBridgeResponse> scenarioDriverBridge,
        List<GrowthProvenanceResponse> growthProvenance,
        List<ValuationScenarioResponse> scenarios,
        ValuationEvaluationResponse.Sensitivity sensitivity,
        ValuationEvaluationResponse.ReverseDcf reverseDcf,
        List<String> missingInputs,
        List<String> warnings
) { }
