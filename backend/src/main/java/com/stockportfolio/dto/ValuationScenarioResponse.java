package com.stockportfolio.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public record ValuationScenarioResponse(
        String scenarioType,
        String modelMode,
        String selectedModel,
        String origin,
        ValuationAssumptions assumptions,
        boolean valid,
        BigDecimal intrinsicValuePerShare,
        BigDecimal marginOfSafetyPrice,
        BigDecimal enterpriseValue,
        BigDecimal equityValue,
        BigDecimal terminalValueWeightPct,
        List<ProjectionPoint> projection,
        List<String> warnings,
        OffsetDateTime updatedAt,
        ValuationAssumptions resolvedAssumptions,
        Map<String, String> assumptionSources,
        List<String> manualOverrides,
        String migrationStatus,
        String savedEngineVersion,
        String evaluatedEngineVersion
) {
    public ValuationScenarioResponse(String scenarioType, String modelMode, String selectedModel, String origin,
                                     ValuationAssumptions assumptions, boolean valid,
                                     BigDecimal intrinsicValuePerShare, BigDecimal marginOfSafetyPrice,
                                     BigDecimal enterpriseValue, BigDecimal equityValue,
                                     BigDecimal terminalValueWeightPct, List<ProjectionPoint> projection,
                                     List<String> warnings, OffsetDateTime updatedAt,
                                     ValuationAssumptions resolvedAssumptions,
                                     Map<String, String> assumptionSources,
                                     List<String> manualOverrides) {
        this(scenarioType, modelMode, selectedModel, origin, assumptions, valid, intrinsicValuePerShare,
                marginOfSafetyPrice, enterpriseValue, equityValue, terminalValueWeightPct, projection,
                warnings, updatedAt, resolvedAssumptions, assumptionSources, manualOverrides,
                "CURRENT", null, null);
    }
    public ValuationScenarioResponse(String scenarioType, String modelMode, String selectedModel, String origin,
                                     ValuationAssumptions assumptions, boolean valid,
                                     BigDecimal intrinsicValuePerShare, BigDecimal marginOfSafetyPrice,
                                     BigDecimal enterpriseValue, BigDecimal equityValue,
                                     BigDecimal terminalValueWeightPct, List<ProjectionPoint> projection,
                                     List<String> warnings, OffsetDateTime updatedAt) {
        this(scenarioType, modelMode, selectedModel, origin, assumptions, valid, intrinsicValuePerShare,
                marginOfSafetyPrice, enterpriseValue, equityValue, terminalValueWeightPct, projection,
                warnings, updatedAt, assumptions, Map.of(), List.of(), "CURRENT", null, null);
    }
    public record ProjectionPoint(
            int year,
            BigDecimal growthRatePct,
            BigDecimal cashFlow,
            BigDecimal discountFactor,
            BigDecimal presentValue
    ) { }
}
