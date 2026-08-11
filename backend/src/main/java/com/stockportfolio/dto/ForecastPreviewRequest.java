package com.stockportfolio.dto;

import com.stockportfolio.valuation.explicit.DebtFinancingPolicy;
import com.stockportfolio.valuation.forecast.ForecastArchetype;
import com.stockportfolio.valuation.forecast.ForecastScenarioOverride;

import java.util.Map;

public record ForecastPreviewRequest(
        ForecastArchetype archetype,
        Map<String, ForecastScenarioOverride> scenarios,
        DebtFinancingPolicy debtFinancingPolicy
) { }
