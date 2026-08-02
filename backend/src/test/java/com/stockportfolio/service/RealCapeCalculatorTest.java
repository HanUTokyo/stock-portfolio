package com.stockportfolio.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RealCapeCalculatorTest {
    private final RealCapeCalculator calculator = new RealCapeCalculator();

    @Test
    void calculatesRealCapeFromExactlyFortyQuarterlyDilutedEpsPoints() {
        Fixture fixture = fixture(BigDecimal.ONE);
        BigDecimal result = calculator.realPe(bd("100"), fixture.quarters(), 10, bd("120"), fixture.cpi());
        assertThat(result).isPositive();
    }

    @Test
    void acceptsPointInTimeSplitAdjustedEps() {
        Fixture fixture = fixture(bd("4"));
        BigDecimal unadjusted = calculator.realPe(bd("100"), fixture.quarters(), 10, bd("120"), fixture.cpi());
        BigDecimal adjusted = calculator.realPe(bd("100"), fixture.quarters(), 10, bd("120"), fixture.cpi(),
                quarter -> quarter.dilutedEps().divide(bd("4")));
        assertThat(adjusted).isEqualByComparingTo(unadjusted.multiply(bd("4")));
    }

    @Test
    void refusesMissingCpiAndNonPositiveTenYearRealEarnings() {
        Fixture fixture = fixture(BigDecimal.ONE);
        Map<YearMonth, BigDecimal> missing = new LinkedHashMap<>(fixture.cpi());
        missing.remove(YearMonth.from(fixture.quarters().get(10).periodEnd()));
        assertThat(calculator.realPe(bd("100"), fixture.quarters(), 10, bd("120"), missing)).isNull();

        Fixture losses = fixture(bd("-1"));
        assertThat(calculator.realPe(bd("100"), losses.quarters(), 10, bd("120"), losses.cpi())).isNull();
    }

    @Test
    void requiresTwentyPriorSamplesForPercentile() {
        assertThat(calculator.percentile(bd("20"), List.of(bd("10"), bd("30")))).isNull();
        List<BigDecimal> samples = new ArrayList<>();
        for (int i = 1; i <= 20; i++) samples.add(BigDecimal.valueOf(i));
        assertThat(calculator.percentile(bd("10"), samples)).isEqualByComparingTo("50.00");
    }

    @Test
    void marksCpiOlderThanSeventyFiveDaysAsStale() {
        LocalDate today = LocalDate.of(2026, 7, 16);
        assertThat(calculator.isCpiStale(today.minusDays(76), today)).isTrue();
        assertThat(calculator.isCpiStale(today.minusDays(75), today)).isFalse();
    }

    private Fixture fixture(BigDecimal eps) {
        List<ValuationEngine.Quarter> quarters = new ArrayList<>();
        Map<YearMonth, BigDecimal> cpi = new LinkedHashMap<>();
        LocalDate date = LocalDate.of(2016, 3, 31);
        for (int i = 0; i < 40; i++) {
            LocalDate period = date.plusMonths(i * 3L);
            cpi.put(YearMonth.from(period), bd("100").add(BigDecimal.valueOf(i).divide(bd("2"))));
            quarters.add(new ValuationEngine.Quarter(period, period.plusDays(35), period.getYear(), "Q" + (i % 4 + 1),
                    eps, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "USD"));
        }
        return new Fixture(quarters, cpi);
    }

    private BigDecimal bd(String value) { return new BigDecimal(value); }
    private record Fixture(List<ValuationEngine.Quarter> quarters, Map<YearMonth, BigDecimal> cpi) { }
}
