package com.stockportfolio.dto;

import com.stockportfolio.valuation.forecast.ForecastArchetype;
import com.stockportfolio.valuation.forecast.ForecastScenarioOverride;
import com.stockportfolio.valuation.explicit.DebtFinancingPolicy;
import com.stockportfolio.valuation.explicit.ExplicitOperatingForecastRequest;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record ForecastTemplateResponse(
        String symbol,
        String eligibility,
        ForecastArchetype suggestedArchetype,
        String confidence,
        List<String> reasons,
        List<ForecastArchetype> alternatives,
        String templateVersion,
        String nwcStatus,
        String sharesPolicy,
        BigDecimal sharesOutstanding,
        Map<ForecastArchetype, Template> templates,
        ForecastPreviewRequest savedSnapshot,
        String snapshotStatus,
        ForecastTemporalContext temporalContext
) {
    public record Template(
            ForecastArchetype archetype,
            String description,
            String revenueDriverLabel,
            Map<String, ForecastScenarioOverride> scenarios,
            DebtFinancingPolicy debtFinancingPolicy,
            ExplicitOperatingForecastRequest baseInputs,
            List<String> warnings
    ) { }
}
