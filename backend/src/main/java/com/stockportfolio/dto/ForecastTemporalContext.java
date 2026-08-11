package com.stockportfolio.dto;

import com.stockportfolio.model.EarningsHistory;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Temporal meaning of an explicit forecast.  Future calendar dates are not
 * inferred because issuer fiscal calendars can contain 52- or 53-week years.
 */
public record ForecastTemporalContext(
        String availability,
        StartingRevenueBasis startingRevenueBasis,
        List<ForecastPeriod> forecastPeriods,
        List<String> warnings
) {
    public record StartingRevenueBasis(
            String type,
            LocalDate throughPeriodEnd,
            Integer throughFiscalYear,
            String throughFiscalPeriod
    ) { }

    public record ForecastPeriod(
            int ordinalYear,
            Integer fiscalYear,
            String fiscalPeriod,
            LocalDate periodStart,
            LocalDate periodEnd
    ) { }

    public static ForecastTemporalContext fromLatestQuarters(List<EarningsHistory> latest) {
        if (latest.size() != 4) return unavailable("TTM requires exactly four canonical quarters.");
        EarningsHistory anchor = latest.getLast();
        if (anchor.getAsOfDate() == null || anchor.getFiscalYear() == null || blank(anchor.getFiscalPeriod())) {
            return unavailable("Latest TTM quarter lacks issuer fiscal-year, fiscal-period, or period-end metadata.");
        }
        List<ForecastPeriod> periods = new ArrayList<>();
        for (int ordinal = 1; ordinal <= 10; ordinal++) {
            periods.add(new ForecastPeriod(
                    ordinal,
                    anchor.getFiscalYear() + ordinal,
                    "FY",
                    null,
                    null
            ));
        }
        return new ForecastTemporalContext(
                "FISCAL_LABEL_AVAILABLE",
                new StartingRevenueBasis("TTM", anchor.getAsOfDate(), anchor.getFiscalYear(), anchor.getFiscalPeriod()),
                List.copyOf(periods),
                List.of("Forecast fiscal-year labels are issuer FY labels. Future calendar period dates are unavailable because the platform does not infer 52/53-week fiscal calendars.")
        );
    }

    public static ForecastTemporalContext unavailable(String reason) {
        return new ForecastTemporalContext("UNAVAILABLE", null, List.of(), List.of(reason));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
