package com.stockportfolio.dto;

import com.stockportfolio.valuation.forecast.ForecastArchetype;
import com.stockportfolio.valuation.explicit.ExplicitOperatingForecastResult;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record ForecastPreviewResponse(
        String symbol,
        String forecastMode,
        ForecastArchetype archetype,
        String readiness,
        List<String> missingInputs,
        String templateVersion,
        String sharesPolicy,
        BigDecimal sharesOutstanding,
        Map<String, ExplicitOperatingForecastResult> scenarios,
        ForecastTemporalContext temporalContext
) { }
