package com.stockportfolio.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record ValuationResponse(
        String symbol,
        String engineVersion,
        LocalDate calculationDate,
        LocalDate priceDate,
        LocalDate financialDate,
        LocalDate filingDate,
        LocalDate cpiDate,
        Applicability applicability,
        DataQuality dataQuality,
        String selectedModel,
        Overview overview,
        List<ValuationScenarioResponse> scenarios,
        List<GrowthReferenceResponse> growthReferences,
        CapeSummary cape,
        Map<String, Object> cashFlow,
        Map<String, Object> capitalEfficiency,
        Map<String, Object> grossMargin,
        List<Diagnostic> diagnostics,
        List<String> missingFields,
        Map<String, FieldSourceResponse> fieldSources,
        String calculationMode,
        String readiness,
        ValuationMethodsResponse valuationMethods,
        CrossModelReconciliationResponse crossModelReconciliation,
        DebtBreakdownResponse debtBreakdown,
        NetBorrowingBreakdownResponse netBorrowingBreakdown,
        DiscountRateBreakdownResponse discountRateBreakdown,
        List<ScenarioDriverBridgeResponse> scenarioDriverBridge,
        CashFlowBridgeResponse cashFlowBridge,
        FundamentalsFreshness fundamentalsFreshness
) {
    public ValuationResponse(String symbol, String engineVersion, LocalDate calculationDate, LocalDate priceDate,
                             LocalDate financialDate, LocalDate filingDate, LocalDate cpiDate,
                             Applicability applicability, DataQuality dataQuality, String selectedModel,
                             Overview overview, List<ValuationScenarioResponse> scenarios,
                             List<GrowthReferenceResponse> growthReferences, CapeSummary cape,
                             Map<String, Object> cashFlow, Map<String, Object> capitalEfficiency,
                             Map<String, Object> grossMargin, List<Diagnostic> diagnostics,
                             List<String> missingFields, Map<String, FieldSourceResponse> fieldSources) {
        this(symbol, engineVersion, calculationDate, priceDate, financialDate, filingDate, cpiDate,
                applicability, dataQuality, selectedModel, overview, scenarios, growthReferences, cape,
                cashFlow, capitalEfficiency, grossMargin, diagnostics, missingFields, fieldSources,
                "DUAL_TRACK", "UNAVAILABLE", null, null, null, null, null, List.of(), null, null);
    }
    public record Applicability(boolean applicable, String status, List<String> reasons) { }
    public record DataQuality(String grade, List<String> reasons) { }
    public record FundamentalsFreshness(String status, LocalDate financialDate, LocalDate filingDate,
                                        Long financialAgeDays, Long filingAgeDays, List<String> reasons) { }
    public record Overview(
            BigDecimal bearValue,
            BigDecimal baseValue,
            BigDecimal bullValue,
            BigDecimal rangeLow,
            BigDecimal rangeHigh,
            BigDecimal currentPrice
    ) { }
    public record CapeSummary(
            String status,
            BigDecimal realCape10y,
            BigDecimal realNormalizedPe3y,
            BigDecimal realNormalizedPe5y,
            BigDecimal ttmPe,
            BigDecimal percentile,
            int sampleCount,
            LocalDate rangeStart,
            LocalDate rangeEnd,
            List<CapePoint> history,
            List<String> missingFields
    ) { }
    public record CapePoint(LocalDate asOfDate, BigDecimal cape, int earningsQuarterCount) { }
    public record Diagnostic(String code, String severity, String message, String evidence) { }
}
