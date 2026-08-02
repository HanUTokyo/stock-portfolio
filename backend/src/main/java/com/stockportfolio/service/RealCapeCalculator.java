package com.stockportfolio.service;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

@Component
public class RealCapeCalculator {
    public BigDecimal realPe(BigDecimal price, List<ValuationEngine.Quarter> quarters, int years,
                             BigDecimal asOfCpi, Map<YearMonth, BigDecimal> cpi) {
        return realPe(price, quarters, years, asOfCpi, cpi, ValuationEngine.Quarter::dilutedEps);
    }

    public BigDecimal realPe(BigDecimal price, List<ValuationEngine.Quarter> quarters, int years,
                             BigDecimal asOfCpi, Map<YearMonth, BigDecimal> cpi,
                             Function<ValuationEngine.Quarter, BigDecimal> epsResolver) {
        if (price == null || price.signum() <= 0 || asOfCpi == null || quarters.size() != years * 4) return null;
        BigDecimal sum = BigDecimal.ZERO;
        for (ValuationEngine.Quarter quarter : quarters) {
            BigDecimal eps = epsResolver.apply(quarter);
            BigDecimal periodCpi = cpi.get(YearMonth.from(quarter.periodEnd()));
            if (eps == null || periodCpi == null || periodCpi.signum() <= 0) return null;
            sum = sum.add(eps.multiply(asOfCpi, ValuationEngine.MC).divide(periodCpi, ValuationEngine.MC), ValuationEngine.MC);
        }
        BigDecimal realAnnualEarnings = sum.divide(BigDecimal.valueOf(years), ValuationEngine.MC);
        if (realAnnualEarnings.signum() <= 0) return null;
        return price.divide(realAnnualEarnings, 4, RoundingMode.HALF_UP);
    }

    public BigDecimal percentile(BigDecimal current, List<BigDecimal> priorValidSamples) {
        if (current == null || priorValidSamples == null || priorValidSamples.size() < 20) return null;
        long atOrBelow = priorValidSamples.stream().filter(value -> value != null && value.compareTo(current) <= 0).count();
        return BigDecimal.valueOf(atOrBelow)
                .divide(BigDecimal.valueOf(priorValidSamples.size()), 8, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);
    }

    public boolean isCpiStale(LocalDate observationDate, LocalDate calculationDate) {
        return observationDate == null || calculationDate == null || observationDate.isBefore(calculationDate.minusDays(75));
    }
}
