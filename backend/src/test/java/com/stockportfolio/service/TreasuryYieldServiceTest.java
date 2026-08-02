package com.stockportfolio.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TreasuryYieldServiceTest {

    @Test
    void appendsYearParameterWhenDefaultUrlHasNoDateFilter() {
        TreasuryYieldService service = new TreasuryYieldService(
                "https://home.treasury.gov/resource-center/data-chart-center/interest-rates/pages/xml?data=daily_treasury_yield_curve"
        );

        assertThat(service.buildYieldCurveUrl(2026))
                .endsWith("data=daily_treasury_yield_curve&field_tdr_date_value=2026");
    }

    @Test
    void preservesConfiguredUrlWhenDateFilterIsAlreadyPresent() {
        TreasuryYieldService service = new TreasuryYieldService(
                "https://example.test/xml?data=daily_treasury_yield_curve&field_tdr_date_value=2025"
        );

        assertThat(service.buildYieldCurveUrl(2026))
                .isEqualTo("https://example.test/xml?data=daily_treasury_yield_curve&field_tdr_date_value=2025");
    }
}
