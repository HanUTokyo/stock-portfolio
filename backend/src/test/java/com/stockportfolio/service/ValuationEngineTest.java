package com.stockportfolio.service;

import com.stockportfolio.dto.ValuationAssumptions;
import com.stockportfolio.dto.ValuationScenarioResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ValuationEngineTest {
    private final ValuationEngine engine = new ValuationEngine();
    private final ValuationEngine.MarketInputs market = new ValuationEngine.MarketInputs(
            bd("100"), bd("100"), bd("4"), bd("1"), bd("5"));

    @Test
    void selectsFcffAndBuildsThreeDefaults() {
        ValuationEngine.Selection selection = engine.select(quarters(false, false), market, null);

        assertThat(selection.model()).isEqualTo("FCFF");
        assertThat(selection.methodSelection("FCFF").available()).isTrue();
        assertThat(selection.methodSelection("FCFE").available()).isTrue();
        assertThat(selection.methodSelection("FCFF").latestTtmCashFlow()).isEqualByComparingTo("330");
        assertThat(selection.methodSelection("FCFF").crossCheckTtmCashFlow()).isEqualByComparingTo("323");
        assertThat(selection.methodSelection("FCFE").latestTtmCashFlow()).isEqualByComparingTo("340");
        assertThat(selection.methodSelection("FCFE").crossCheckTtmCashFlow()).isEqualByComparingTo("347");
        assertThat(selection.baseCashFlow()).isPositive();
        assertThat(selection.crossCheckDifferencePct()).isLessThanOrEqualTo(bd("10"));
        assertThat(selection.netDebt()).isEqualByComparingTo("50");
        assertThat(engine.defaultAssumptions(selection, "BEAR").marginOfSafetyPct()).isEqualByComparingTo("30");
        assertThat(engine.defaultAssumptions(selection, "BASE").projectionYears()).isEqualTo(10);
        assertThat(engine.defaultAssumptions(selection, "BULL").marginOfSafetyPct()).isEqualByComparingTo("10");
    }

    @Test
    void fallsBackToFcfeWhenMaterialDebtHasNoInterest() {
        ValuationEngine.Selection selection = engine.select(quarters(true, false), market, null);

        assertThat(selection.model()).isEqualTo("FCFE");
        assertThat(selection.missingFields()).isEmpty();
        assertThat(selection.methodSelection("FCFF").missingInputs()).contains("interestExpense", "automaticWacc");
        assertThat(selection.methodSelection("FCFE").available()).isTrue();
    }

    @Test
    void reportedCashFcffRequiresCfoRatherThanUsingAnIncompleteOperatingReconstruction() {
        ValuationEngine.Selection selection = engine.select(quarters(false, false, true), market, null);

        assertThat(selection.model()).isNull();
        assertThat(selection.methodSelection("FCFF").available()).isFalse();
        assertThat(selection.methodSelection("FCFF").missingInputs()).contains("operatingCashFlow");
        assertThat(selection.methodSelection("FCFE").available()).isFalse();
    }

    @Test
    void stopsWhenNeitherCashFlowModelIsLegal() {
        ValuationEngine.Selection selection = engine.select(quarters(true, true), market, null);

        assertThat(selection.available()).isFalse();
        assertThat(selection.missingFields()).contains("netBorrowing", "interestExpense");
    }

    @Test
    void validatesTerminalSpreadAndInputBounds() {
        ValuationAssumptions invalid = new ValuationAssumptions(bd("100"), bd("10"), bd("4"), bd("3"), 10, bd("20"), null);

        assertThat(engine.validate(invalid)).contains("discountRatePct must be at least 2 percentage points above terminalGrowthRatePct");
    }

    @Test
    void projectionFadesFromInitialGrowthToTerminalGrowth() {
        ValuationEngine.Selection selection = engine.select(quarters(false, false), market, null);
        ValuationAssumptions assumptions = new ValuationAssumptions(selection.baseCashFlow(), bd("12"), bd("10"), bd("2"), 10, bd("20"), null);

        ValuationScenarioResponse result = engine.evaluate("BASE", "EVALUATED", assumptions, selection, market, null);

        assertThat(result.valid()).isTrue();
        assertThat(result.projection().getFirst().growthRatePct()).isEqualByComparingTo("12.0000");
        assertThat(result.projection().getLast().growthRatePct()).isEqualByComparingTo("2.0000");
        assertThat(result.intrinsicValuePerShare()).isPositive();
    }

    @Test
    void calculatesFcffAndFcfeIndependentlyWithoutAveraging() {
        ValuationEngine.Selection selection = engine.select(quarters(false, false), market, null);
        ValuationEngine.GrowthInputs growth = new ValuationEngine.GrowthInputs(bd("8"), bd("8"), null);

        ValuationScenarioResponse fcff = engine.evaluateSettingsForMethod("FCFF", "BASE", "EVALUATED",
                engine.defaultSettings("BASE"), selection, market, growth, null);
        ValuationScenarioResponse fcfe = engine.evaluateSettingsForMethod("FCFE", "BASE", "EVALUATED",
                engine.defaultSettings("BASE"), selection, market, growth, null);

        assertThat(fcff.valid()).isTrue();
        assertThat(fcfe.valid()).isTrue();
        assertThat(fcff.enterpriseValue()).isNotNull();
        assertThat(fcfe.enterpriseValue()).isNull();
        assertThat(fcff.selectedModel()).isEqualTo("FCFF");
        assertThat(fcfe.selectedModel()).isEqualTo("FCFE");
        assertThat(fcff.intrinsicValuePerShare()).isNotEqualByComparingTo(fcfe.intrinsicValuePerShare());
    }

    @Test
    void autoScenariosShareTheSameNormalizedStartingCashFlow() {
        ValuationEngine.Selection selection = engine.select(quarters(false, false), market, null);
        ValuationEngine.GrowthInputs growth = new ValuationEngine.GrowthInputs(bd("8"), bd("8"), null);

        ValuationAssumptions bear = engine.resolve("BEAR", engine.defaultSettings("BEAR"), selection, market, growth);
        ValuationAssumptions base = engine.resolve("BASE", engine.defaultSettings("BASE"), selection, market, growth);
        ValuationAssumptions bull = engine.resolve("BULL", engine.defaultSettings("BULL"), selection, market, growth);

        assertThat(bear.baseCashFlow()).isEqualByComparingTo(selection.baseCashFlow());
        assertThat(base.baseCashFlow()).isEqualByComparingTo(selection.baseCashFlow());
        assertThat(bull.baseCashFlow()).isEqualByComparingTo(selection.baseCashFlow());
    }

    @Test
    void fcfeDoesNotRequireCurrentPrice() {
        ValuationEngine.MarketInputs noPrice = new ValuationEngine.MarketInputs(
                null, bd("100"), bd("4"), bd("1"), bd("5"));

        ValuationEngine.Selection selection = engine.select(quarters(false, false), noPrice, null);

        assertThat(selection.model()).isEqualTo("FCFE");
        assertThat(selection.methodSelection("FCFE").missingInputs()).doesNotContain("currentPrice");
        assertThat(selection.methodSelection("FCFE").available()).isTrue();
    }

    @Test
    void reconcilesAgainstFcffWithTenAndTwentyFivePercentReadinessThresholds() {
        assertThat(engine.crossModelDifferencePct(bd("100"), bd("110"))).isEqualByComparingTo("10.0000");
        assertThat(engine.crossModelReadiness(bd("10"), bd("100"), bd("110"))).isEqualTo("READY");
        assertThat(engine.crossModelReadiness(bd("10.0001"), bd("100"), bd("110.0001")))
                .isEqualTo("READY_WITH_CAVEATS");
        assertThat(engine.crossModelReadiness(bd("25"), bd("100"), bd("125")))
                .isEqualTo("READY_WITH_CAVEATS");
        assertThat(engine.crossModelReadiness(bd("25.0001"), bd("100"), bd("125.0001")))
                .isEqualTo("NOT_READY");
        assertThat(engine.crossModelReadiness(null, bd("100"), null)).isEqualTo("UNAVAILABLE");
    }

    @Test
    void resolvesCompanySpecificGrowthAndCustomAnnualPath() {
        ValuationEngine.Selection selection = engine.select(quarters(false, false), market, null);
        ValuationAssumptions auto = engine.resolve("BASE", engine.defaultSettings("BASE"), selection, market,
                new ValuationEngine.GrowthInputs(bd("11"), bd("4"), bd("18")));
        assertThat(auto.initialGrowthRatePct()).isEqualByComparingTo("11.0000");

        ValuationAssumptions custom = new ValuationAssumptions(bd("100"), bd("12"), bd("10"), bd("2"),
                4, bd("20"), null, "MANUAL", "CUSTOM_PATH", "MANUAL_RATE",
                List.of(bd("12"), bd("9"), bd("6"), bd("3")), null, null, null);
        ValuationScenarioResponse result = engine.evaluateSettings("BASE", "EVALUATED", custom, selection, market,
                new ValuationEngine.GrowthInputs(bd("8"), bd("6"), bd("10")), null);
        assertThat(result.projection()).extracting(ValuationScenarioResponse.ProjectionPoint::growthRatePct)
                .containsExactly(bd("12.0000"), bd("9.0000"), bd("6.0000"), bd("3.0000"));
    }

    @Test
    void reverseDcfUsesTheSameValidatedGrowthBounds() {
        ValuationEngine.Selection selection = engine.select(quarters(false, false), market, null);
        ValuationAssumptions assumptions = new ValuationAssumptions(selection.baseCashFlow(), bd("8"), bd("10"), bd("2"), 10, bd("20"), null);
        assertThat(engine.reverse("BASE", assumptions, selection, market).status()).isNotEqualTo("UNAVAILABLE");
    }

    private List<ValuationEngine.Quarter> quarters(boolean missingInterest, boolean missingBorrowing) {
        return quarters(missingInterest, missingBorrowing, false);
    }

    private List<ValuationEngine.Quarter> quarters(boolean missingInterest, boolean missingBorrowing,
                                                   boolean missingCfo) {
        List<ValuationEngine.Quarter> rows = new ArrayList<>();
        LocalDate date = LocalDate.of(2024, 3, 31);
        for (int i = 0; i < 8; i++) {
            rows.add(new ValuationEngine.Quarter(
                    date.plusMonths(i * 3L), date.plusMonths(i * 3L).plusDays(35),
                    2024 + i / 4, "Q" + (i % 4 + 1), bd("1.00"), missingCfo ? null : bd("100"), bd("20"),
                    missingInterest ? null : bd("1"), missingBorrowing ? null : bd("5"), bd("25"),
                    bd("5"), bd("110"), bd("25"), bd("100"), bd("75"), bd("500"),
                    bd("100"), bd("20"), bd("10"), bd("20"), bd("580"), bd("500"), bd("400"), bd("1000"), "USD"));
        }
        return rows;
    }

    private BigDecimal bd(String value) { return new BigDecimal(value); }
}
