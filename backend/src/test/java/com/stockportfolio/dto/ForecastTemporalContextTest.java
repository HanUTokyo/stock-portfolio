package com.stockportfolio.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.stockportfolio.model.EarningsHistory;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ForecastTemporalContextTest {
    @Test
    void anchorsTtmToActualIssuerPeriodEndAndUsesFiscalLabelsForFutureYears() {
        ForecastTemporalContext context = ForecastTemporalContext.fromLatestQuarters(List.of(
                quarter("2024-12-28", 2025, "Q1"),
                quarter("2025-03-29", 2025, "Q2"),
                quarter("2025-06-28", 2025, "Q3"),
                quarter("2025-09-27", 2025, "FY")
        ));

        assertThat(context.availability()).isEqualTo("FISCAL_LABEL_AVAILABLE");
        assertThat(context.startingRevenueBasis().type()).isEqualTo("TTM");
        assertThat(context.startingRevenueBasis().throughPeriodEnd()).isEqualTo(LocalDate.parse("2025-09-27"));
        assertThat(context.startingRevenueBasis().throughFiscalYear()).isEqualTo(2025);
        assertThat(context.forecastPeriods()).hasSize(10);
        assertThat(context.forecastPeriods().getFirst()).extracting(
                ForecastTemporalContext.ForecastPeriod::ordinalYear,
                ForecastTemporalContext.ForecastPeriod::fiscalYear,
                ForecastTemporalContext.ForecastPeriod::fiscalPeriod,
                ForecastTemporalContext.ForecastPeriod::periodEnd
        ).containsExactly(1, 2026, "FY", null);
        assertThat(context.forecastPeriods().getLast().fiscalYear()).isEqualTo(2035);
    }

    @Test
    void preservesNonCalendarIssuerFiscalIdentityWithoutInventingFutureDates() {
        ForecastTemporalContext context = ForecastTemporalContext.fromLatestQuarters(List.of(
                quarter("2025-12-27", 2026, "Q1"),
                quarter("2026-03-28", 2026, "Q2"),
                quarter("2026-06-27", 2026, "Q3"),
                quarter("2026-09-26", 2026, "FY")
        ));

        assertThat(context.startingRevenueBasis().throughPeriodEnd()).isEqualTo(LocalDate.parse("2026-09-26"));
        assertThat(context.forecastPeriods().getFirst().fiscalYear()).isEqualTo(2027);
        assertThat(context.forecastPeriods()).allMatch(period -> period.periodStart() == null && period.periodEnd() == null);
        assertThat(context.warnings()).anyMatch(warning -> warning.contains("52/53-week"));
    }

    @Test
    void isExplicitlyUnavailableWhenIssuerFiscalMetadataIsMissing() {
        ForecastTemporalContext context = ForecastTemporalContext.fromLatestQuarters(List.of(
                quarter("2025-12-31", 2026, "Q1"),
                quarter("2026-03-31", 2026, "Q2"),
                quarter("2026-06-30", 2026, "Q3"),
                quarter("2026-09-30", null, null)
        ));

        assertThat(context.availability()).isEqualTo("UNAVAILABLE");
        assertThat(context.startingRevenueBasis()).isNull();
        assertThat(context.forecastPeriods()).isEmpty();
    }

    @Test
    void serializesTheApiContractWithTheActualAnchorDate() throws Exception {
        ForecastTemporalContext context = ForecastTemporalContext.fromLatestQuarters(List.of(
                quarter("2025-12-27", 2026, "Q1"), quarter("2026-03-28", 2026, "Q2"),
                quarter("2026-06-27", 2026, "Q3"), quarter("2026-09-26", 2026, "FY")
        ));

        String json = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .writeValueAsString(context);
        assertThat(json).contains("\"throughPeriodEnd\":\"2026-09-26\"");
        assertThat(json).contains("\"fiscalYear\":2027");
        assertThat(json).contains("\"periodEnd\":null");
    }

    private EarningsHistory quarter(String date, Integer fiscalYear, String fiscalPeriod) {
        EarningsHistory row = new EarningsHistory();
        row.setAsOfDate(LocalDate.parse(date));
        row.setFiscalYear(fiscalYear);
        row.setFiscalPeriod(fiscalPeriod);
        return row;
    }
}
