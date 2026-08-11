package com.stockportfolio.valuation.explicit;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExplicitOperatingForecastServiceTest {
    private static final BigDecimal TOLERANCE = new BigDecimal("0.000000001");
    private final ExplicitOperatingForecastService service = new ExplicitOperatingForecastService();

    @Test
    void handCalculatedFcffAndFcfePathsUseTheirCorrectDiscountRates() {
        ExplicitOperatingForecastResult result = service.forecast(request(
                constantDrivers(),
                new TerminalOperatingDriver(bd("0.20"), bd("0.20"), bd("0.05"), bd("0.10"), bd("0.02")),
                DebtFinancingPolicy.customAnnualNetBorrowing(Collections.nCopies(10, BigDecimal.ZERO))
        ));

        // Revenue 100; EBIT 20; NOPAT 16; D&A 5; capex 10; delta NWC 2 => FCFF 9.
        assertClose(result.operatingSchedule().getFirst().fcff(), bd("9"));
        // Average debt 20 * 10% cost * (1 - 20% tax) = 1.6; FCFE = 9 - 1.6 = 7.4.
        assertClose(result.debtSchedule().getFirst().afterTaxInterestExpense(), bd("1.6"));
        assertClose(result.debtSchedule().getFirst().fcfe(), bd("7.4"));

        // With zero growth, the ten-year forecast plus terminal value is the same as a perpetuity.
        assertClose(result.fcff().enterpriseValue(), bd("90"));
        assertClose(result.fcff().equityValue(), bd("85")); // EV 90 - current net debt 5.
        assertClose(result.fcfe().equityValue(), bd("61.666666666666666667")); // 7.4 / 12%.
        assertThat(result.fcff().discountRateType()).isEqualTo("WACC");
        assertThat(result.fcfe().discountRateType()).isEqualTo("COST_OF_EQUITY");
        assertThat(result.fcfe().enterpriseValue()).isNull();
        assertThat(result.fcfe().netDebtBridge()).isNull();
        assertThat(result.fcffSensitivity().discountRates()).hasSize(5);
        assertThat(result.fcffSensitivity().equityValues()).hasSize(5);
        assertThat(result.fcfeSensitivity().equityValues()).allSatisfy(row -> assertThat(row).hasSize(5));
        assertThat(result.fcffReverseDcf().status()).isEqualTo("UNAVAILABLE_MISSING_TARGET_EQUITY_VALUE");
    }

    @Test
    void reverseDcfSolvesEachTrackAgainstTheRequestedEquityValue() {
        ExplicitOperatingForecastRequest request = new ExplicitOperatingForecastRequest(
                bd("100"), bd("20"), bd("5"), bd("0.10"), bd("0.12"), bd("0.10"), BigDecimal.ZERO,
                constantDrivers(),
                new TerminalOperatingDriver(bd("0.20"), bd("0.20"), bd("0.05"), bd("0.10"), bd("0.02")),
                DebtFinancingPolicy.customAnnualNetBorrowing(Collections.nCopies(10, BigDecimal.ZERO)),
                bd("85")
        );

        ExplicitOperatingForecastResult result = service.forecast(request);

        assertThat(result.fcffReverseDcf().status()).isEqualTo("AVAILABLE");
        assertClose(result.fcffReverseDcf().impliedDiscountRate(), bd("0.10"));
        assertThat(result.fcfeReverseDcf().status()).isEqualTo("AVAILABLE");
    }

    @Test
    void buildsOneSharedOperatingScheduleForBothCashFlowDefinitions() {
        ExplicitOperatingForecastResult result = service.forecast(request(
                constantDrivers(),
                new TerminalOperatingDriver(bd("0.20"), bd("0.20"), bd("0.05"), bd("0.10"), bd("0.02")),
                DebtFinancingPolicy.targetDebtFinancingRatio(bd("0.50"))
        ));

        assertThat(result.operatingSchedule()).hasSize(10);
        assertThat(result.debtSchedule()).hasSize(10);
        for (int index = 0; index < 10; index++) {
            BigDecimal reconstructedFcff = result.debtSchedule().get(index).fcfe()
                    .add(result.debtSchedule().get(index).afterTaxInterestExpense())
                    .subtract(result.debtSchedule().get(index).netBorrowing());
            assertClose(reconstructedFcff, result.operatingSchedule().get(index).fcff());
        }
    }

    @Test
    void targetDebtPolicyFinancesAnnualReinvestmentAndNeverUsesHistoricalBorrowing() {
        ExplicitOperatingForecastResult result = service.forecast(request(
                constantDrivers(),
                new TerminalOperatingDriver(bd("0.20"), bd("0.20"), bd("0.05"), bd("0.10"), bd("0.02")),
                DebtFinancingPolicy.targetDebtFinancingRatio(bd("0.50"))
        ));

        // Reinvestment = capex 10 - D&A 5 + delta NWC 2 = 7; debt finances 50%.
        assertClose(result.operatingSchedule().getFirst().reinvestment(), bd("7"));
        assertClose(result.debtSchedule().getFirst().netBorrowing(), bd("3.5"));
        assertClose(result.debtSchedule().getFirst().closingDebt(), bd("23.5"));
        assertClose(result.debtSchedule().getFirst().averageDebt(), bd("21.75"));
        assertClose(result.debtSchedule().getFirst().pretaxInterestExpense(), bd("2.175"));
        assertClose(result.debtSchedule().getFirst().fcfe(), bd("10.76"));

        // There is intentionally no historical-net-borrowing field on the request. Every
        // forecast year is recomputed from that year's reinvestment and the target ratio.
        assertThat(result.debtSchedule())
                .allSatisfy(year -> assertClose(year.netBorrowing(), bd("3.5")));
    }

    @Test
    void customPolicyUsesTheTenSuppliedAnnualBorrowingValuesAndRollsDebtForward() {
        List<BigDecimal> custom = List.of(
                bd("5"), bd("-2"), bd("1"), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO
        );
        ExplicitOperatingForecastResult result = service.forecast(request(
                constantDrivers(),
                new TerminalOperatingDriver(bd("0.20"), bd("0.20"), bd("0.05"), bd("0.10"), bd("0.02")),
                DebtFinancingPolicy.customAnnualNetBorrowing(custom)
        ));

        assertThat(result.debtSchedule().stream().map(ExplicitOperatingForecastResult.DebtForecastYear::netBorrowing).toList())
                .containsExactlyElementsOf(custom);
        assertClose(result.debtSchedule().get(0).closingDebt(), bd("25"));
        assertClose(result.debtSchedule().get(1).openingDebt(), bd("25"));
        assertClose(result.debtSchedule().get(1).closingDebt(), bd("23"));
        assertClose(result.debtSchedule().get(2).closingDebt(), bd("24"));
    }

    @Test
    void yearsSixThroughTenFadeLinearlyToTerminalOperatingState() {
        OperatingDriver yearFive = new OperatingDriver(
                bd("0.10"), bd("0.30"), bd("0.25"), bd("0.04"), bd("0.09"), bd("0.03"));
        List<OperatingDriver> drivers = List.of(yearFive, yearFive, yearFive, yearFive, yearFive);
        TerminalOperatingDriver terminal = new TerminalOperatingDriver(
                bd("0.20"), bd("0.20"), bd("0.05"), bd("0.07"), bd("0.01"));

        ExplicitOperatingForecastResult result = service.forecast(request(
                drivers, terminal,
                DebtFinancingPolicy.customAnnualNetBorrowing(Collections.nCopies(10, BigDecimal.ZERO))
        ));

        ExplicitOperatingForecastResult.OperatingForecastYear yearSix = result.operatingSchedule().get(5);
        ExplicitOperatingForecastResult.OperatingForecastYear yearTen = result.operatingSchedule().get(9);
        assertThat(yearSix.explicitPeriod()).isFalse();
        assertClose(yearSix.revenueGrowthRate(), bd("0.08")); // 20% of the way from 10% to 0%.
        assertClose(yearSix.ebitMargin(), bd("0.28"));
        assertClose(yearTen.revenueGrowthRate(), BigDecimal.ZERO);
        assertClose(yearTen.ebitMargin(), terminal.ebitMargin());
        assertClose(yearTen.taxRate(), terminal.taxRate());
        assertClose(yearTen.depreciationAndAmortization(), yearTen.revenue().multiply(terminal.depreciationAndAmortizationRate()));
    }

    @Test
    void rejectsIncompleteCustomPolicyAndTerminalRatesAboveDiscountRates() {
        assertThatThrownBy(() -> service.forecast(request(
                constantDrivers(),
                new TerminalOperatingDriver(bd("0.20"), bd("0.20"), bd("0.05"), bd("0.10"), bd("0.02")),
                DebtFinancingPolicy.customAnnualNetBorrowing(List.of(BigDecimal.ZERO))
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly ten");

        ExplicitOperatingForecastRequest invalidSpread = new ExplicitOperatingForecastRequest(
                bd("100"), bd("20"), bd("5"), bd("0.02"), bd("0.12"), bd("0.10"), bd("0.03"),
                constantDrivers(), new TerminalOperatingDriver(bd("0.20"), bd("0.20"), bd("0.05"), bd("0.10"), bd("0.02")),
                DebtFinancingPolicy.targetDebtFinancingRatio(bd("0.50"))
        );
        assertThatThrownBy(() -> service.forecast(invalidSpread))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("waccRate must be greater");
    }

    private ExplicitOperatingForecastRequest request(List<OperatingDriver> drivers,
                                                     TerminalOperatingDriver terminal,
                                                     DebtFinancingPolicy policy) {
        return new ExplicitOperatingForecastRequest(
                bd("100"),
                bd("20"),
                bd("5"),
                bd("0.10"),
                bd("0.12"),
                bd("0.10"),
                BigDecimal.ZERO,
                drivers,
                terminal,
                policy
        );
    }

    private List<OperatingDriver> constantDrivers() {
        OperatingDriver driver = new OperatingDriver(
                BigDecimal.ZERO, bd("0.20"), bd("0.20"), bd("0.05"), bd("0.10"), bd("0.02"));
        return List.of(driver, driver, driver, driver, driver);
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    private static void assertClose(BigDecimal actual, BigDecimal expected) {
        assertThat(actual.subtract(expected).abs()).isLessThanOrEqualTo(TOLERANCE);
    }
}
