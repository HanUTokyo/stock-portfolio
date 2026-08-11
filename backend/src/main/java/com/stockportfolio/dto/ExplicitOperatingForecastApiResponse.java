package com.stockportfolio.dto;

import com.stockportfolio.valuation.explicit.ExplicitOperatingForecastResult;

public record ExplicitOperatingForecastApiResponse(
        String symbol,
        String engineVersion,
        String calculationMode,
        ExplicitOperatingForecastResult result
) {
}
