package com.stockportfolio.valuation.forecast;

import com.stockportfolio.dto.ForecastPreviewRequest;

/** Persisted immutable-shape snapshot; it is never auto-regenerated on data refresh. */
public record ForecastScenarioEnvelope(
        String forecastMode,
        ForecastArchetype archetype,
        String templateVersion,
        String initializationSource,
        ForecastPreviewRequest request
) { }
