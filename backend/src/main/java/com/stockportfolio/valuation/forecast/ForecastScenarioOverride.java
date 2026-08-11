package com.stockportfolio.valuation.forecast;

import com.stockportfolio.valuation.explicit.OperatingDriver;
import com.stockportfolio.valuation.explicit.TerminalOperatingDriver;

import java.util.List;

public record ForecastScenarioOverride(
        List<OperatingDriver> explicitOperatingDrivers,
        TerminalOperatingDriver terminalOperatingDriver
) { }
