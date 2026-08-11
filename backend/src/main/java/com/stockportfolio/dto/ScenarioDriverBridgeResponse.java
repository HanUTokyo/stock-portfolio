package com.stockportfolio.dto;

import java.math.BigDecimal;
import java.util.List;

public record ScenarioDriverBridgeResponse(
        String scenarioType,
        String forecastMode,
        BigDecimal normalizedBaseCashFlow,
        BigDecimal scenarioStartingCashFlow,
        BigDecimal startingCashFlowAdjustmentPct,
        BigDecimal initialGrowthRatePct,
        BigDecimal discountRatePct,
        BigDecimal terminalGrowthRatePct,
        Integer projectionYears,
        List<String> warnings
) { }
