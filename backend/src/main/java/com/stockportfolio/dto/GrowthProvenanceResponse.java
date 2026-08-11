package com.stockportfolio.dto;

import java.math.BigDecimal;
import java.util.List;

/** Explains exactly why one method/scenario received its growth assumptions. */
public record GrowthProvenanceResponse(
        String forecastId,
        String comparabilityStatus,
        String scenarioType,
        String growthMode,
        BigDecimal initialGrowthRatePct,
        BigDecimal terminalGrowthRatePct,
        BigDecimal historicalGrowthPct,
        BigDecimal consensusGrowthPct,
        String source,
        String selectionReason,
        List<String> fallbacksApplied
) { }
