package com.stockportfolio.valuation.explicit;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import static com.stockportfolio.valuation.explicit.ExplicitOperatingForecastResult.DebtForecastYear;
import static com.stockportfolio.valuation.explicit.ExplicitOperatingForecastResult.DiscountedCashFlowYear;
import static com.stockportfolio.valuation.explicit.ExplicitOperatingForecastResult.OperatingForecastYear;
import static com.stockportfolio.valuation.explicit.ExplicitOperatingForecastResult.ReverseDcf;
import static com.stockportfolio.valuation.explicit.ExplicitOperatingForecastResult.SensitivityGrid;
import static com.stockportfolio.valuation.explicit.ExplicitOperatingForecastResult.ValuationTrack;

/**
 * Builds one shared operating forecast and independently values FCFF and FCFE.
 * The service is stateless and deterministic: all economic assumptions are
 * supplied in the request and no historical financing value is inferred.
 */
@Service
public class ExplicitOperatingForecastService {
    static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);
    private static final int EXPLICIT_YEARS = 5;
    private static final int TOTAL_YEARS = 10;

    public ExplicitOperatingForecastResult forecast(ExplicitOperatingForecastRequest request) {
        validate(request);

        List<OperatingForecastYear> operatingSchedule = buildOperatingSchedule(request);
        List<DebtForecastYear> debtSchedule = buildDebtSchedule(request, operatingSchedule);

        List<BigDecimal> fcff = operatingSchedule.stream().map(OperatingForecastYear::fcff).toList();
        List<BigDecimal> fcfe = debtSchedule.stream().map(DebtForecastYear::fcfe).toList();
        ValuationTrack fcffValuation = valueTrack(
                fcff,
                request.waccRate(),
                request.terminalGrowthRate(),
                "NOPAT + D&A - Capex - Delta NWC",
                "WACC",
                request.currentNetDebt(),
                true
        );
        ValuationTrack fcfeValuation = valueTrack(
                fcfe,
                request.costOfEquityRate(),
                request.terminalGrowthRate(),
                "FCFF - after-tax interest + net borrowing",
                "COST_OF_EQUITY",
                null,
                false
        );

        return new ExplicitOperatingForecastResult(
                "EXPLICIT_OPERATING_FORECAST",
                operatingSchedule,
                debtSchedule,
                fcffValuation,
                fcfeValuation,
                sensitivity(fcff, request.waccRate(), request.terminalGrowthRate(), request.currentNetDebt()),
                sensitivity(fcfe, request.costOfEquityRate(), request.terminalGrowthRate(), null),
                reverse(fcff, request.terminalGrowthRate(), request.currentNetDebt(), request.targetEquityValue()),
                reverse(fcfe, request.terminalGrowthRate(), null, request.targetEquityValue())
        );
    }

    private SensitivityGrid sensitivity(List<BigDecimal> cashFlows, BigDecimal centerDiscount,
                                        BigDecimal centerGrowth, BigDecimal netDebt) {
        List<BigDecimal> discounts = List.of(new BigDecimal("-0.02"), new BigDecimal("-0.01"),
                        BigDecimal.ZERO, new BigDecimal("0.01"), new BigDecimal("0.02"))
                .stream().map(centerDiscount::add).toList();
        List<BigDecimal> growthRates = List.of(new BigDecimal("-0.01"), new BigDecimal("-0.005"),
                        BigDecimal.ZERO, new BigDecimal("0.005"), new BigDecimal("0.01"))
                .stream().map(centerGrowth::add).toList();
        List<List<BigDecimal>> values = new ArrayList<>();
        for (BigDecimal growth : growthRates) {
            List<BigDecimal> row = new ArrayList<>();
            for (BigDecimal discount : discounts) {
                row.add(discount.signum() <= 0 || discount.compareTo(growth) <= 0
                        ? null : equityValue(cashFlows, discount, growth, netDebt));
            }
            values.add(row);
        }
        return new SensitivityGrid(discounts, growthRates, values);
    }

    private ReverseDcf reverse(List<BigDecimal> cashFlows, BigDecimal terminalGrowth,
                               BigDecimal netDebt, BigDecimal targetEquityValue) {
        if (targetEquityValue == null || targetEquityValue.signum() <= 0) {
            return new ReverseDcf("UNAVAILABLE_MISSING_TARGET_EQUITY_VALUE", targetEquityValue, null);
        }
        BigDecimal low = terminalGrowth.add(new BigDecimal("0.0001"), MC).max(new BigDecimal("0.0001"));
        BigDecimal high = new BigDecimal("0.50");
        BigDecimal lowValue = equityValue(cashFlows, low, terminalGrowth, netDebt);
        BigDecimal highValue = equityValue(cashFlows, high, terminalGrowth, netDebt);
        if (targetEquityValue.compareTo(lowValue) > 0 || targetEquityValue.compareTo(highValue) < 0) {
            return new ReverseDcf("OUT_OF_RANGE", targetEquityValue, null);
        }
        for (int i = 0; i < 100; i++) {
            BigDecimal mid = low.add(high, MC).divide(BigDecimal.valueOf(2), MC);
            BigDecimal value = equityValue(cashFlows, mid, terminalGrowth, netDebt);
            if (value.compareTo(targetEquityValue) > 0) low = mid;
            else high = mid;
        }
        return new ReverseDcf("AVAILABLE", targetEquityValue,
                low.add(high, MC).divide(BigDecimal.valueOf(2), MC));
    }

    private BigDecimal equityValue(List<BigDecimal> cashFlows, BigDecimal discountRate,
                                   BigDecimal terminalGrowth, BigDecimal netDebt) {
        BigDecimal onePlusDiscount = BigDecimal.ONE.add(discountRate, MC);
        BigDecimal value = BigDecimal.ZERO;
        for (int index = 0; index < cashFlows.size(); index++) {
            value = value.add(cashFlows.get(index).divide(onePlusDiscount.pow(index + 1, MC), MC), MC);
        }
        BigDecimal terminalCashFlow = cashFlows.get(TOTAL_YEARS - 1)
                .multiply(BigDecimal.ONE.add(terminalGrowth, MC), MC);
        BigDecimal terminalValue = terminalCashFlow.divide(discountRate.subtract(terminalGrowth, MC), MC)
                .divide(onePlusDiscount.pow(TOTAL_YEARS, MC), MC);
        BigDecimal total = value.add(terminalValue, MC);
        return netDebt == null ? total : total.subtract(netDebt, MC);
    }

    private List<OperatingForecastYear> buildOperatingSchedule(ExplicitOperatingForecastRequest request) {
        List<OperatingForecastYear> schedule = new ArrayList<>(TOTAL_YEARS);
        BigDecimal priorRevenue = request.startingRevenue();
        OperatingDriver yearFive = request.explicitOperatingDrivers().get(EXPLICIT_YEARS - 1);

        for (int year = 1; year <= TOTAL_YEARS; year++) {
            boolean explicit = year <= EXPLICIT_YEARS;
            OperatingDriver driver = explicit
                    ? request.explicitOperatingDrivers().get(year - 1)
                    : fadedDriver(yearFive, request.terminalOperatingDriver(), request.terminalGrowthRate(), year - EXPLICIT_YEARS);

            BigDecimal revenue = priorRevenue.multiply(BigDecimal.ONE.add(driver.revenueGrowthRate(), MC), MC);
            BigDecimal ebit = revenue.multiply(driver.ebitMargin(), MC);
            BigDecimal nopat = ebit.multiply(BigDecimal.ONE.subtract(driver.taxRate(), MC), MC);
            BigDecimal da = revenue.multiply(driver.depreciationAndAmortizationRate(), MC);
            BigDecimal capex = revenue.multiply(driver.capexRate(), MC);
            BigDecimal deltaNwc = revenue.multiply(driver.changeInNetWorkingCapitalRate(), MC);
            BigDecimal reinvestment = capex.subtract(da, MC).add(deltaNwc, MC);
            BigDecimal fcff = nopat.add(da, MC).subtract(capex, MC).subtract(deltaNwc, MC);

            schedule.add(new OperatingForecastYear(
                    year, explicit, driver.revenueGrowthRate(), revenue, driver.ebitMargin(), ebit,
                    driver.taxRate(), nopat, da, capex, deltaNwc, reinvestment, fcff
            ));
            priorRevenue = revenue;
        }
        return List.copyOf(schedule);
    }

    private OperatingDriver fadedDriver(OperatingDriver yearFive,
                                         TerminalOperatingDriver terminal,
                                         BigDecimal terminalGrowth,
                                         int fadeStep) {
        BigDecimal weight = BigDecimal.valueOf(fadeStep).divide(BigDecimal.valueOf(TOTAL_YEARS - EXPLICIT_YEARS), MC);
        return new OperatingDriver(
                interpolate(yearFive.revenueGrowthRate(), terminalGrowth, weight),
                interpolate(yearFive.ebitMargin(), terminal.ebitMargin(), weight),
                interpolate(yearFive.taxRate(), terminal.taxRate(), weight),
                interpolate(yearFive.depreciationAndAmortizationRate(), terminal.depreciationAndAmortizationRate(), weight),
                interpolate(yearFive.capexRate(), terminal.capexRate(), weight),
                interpolate(yearFive.changeInNetWorkingCapitalRate(), terminal.changeInNetWorkingCapitalRate(), weight)
        );
    }

    private BigDecimal interpolate(BigDecimal start, BigDecimal end, BigDecimal weight) {
        return start.add(end.subtract(start, MC).multiply(weight, MC), MC);
    }

    private List<DebtForecastYear> buildDebtSchedule(ExplicitOperatingForecastRequest request,
                                                     List<OperatingForecastYear> operatingSchedule) {
        List<DebtForecastYear> schedule = new ArrayList<>(TOTAL_YEARS);
        BigDecimal openingDebt = request.openingGrossDebt();
        DebtFinancingPolicy policy = request.debtFinancingPolicy();

        for (int index = 0; index < TOTAL_YEARS; index++) {
            OperatingForecastYear operating = operatingSchedule.get(index);
            BigDecimal netBorrowing = switch (policy.type()) {
                case TARGET_DEBT_FINANCING_RATIO -> operating.reinvestment()
                        .multiply(policy.targetDebtFinancingRatio(), MC);
                case CUSTOM_ANNUAL_NET_BORROWING -> policy.customAnnualNetBorrowing().get(index);
            };
            BigDecimal closingDebt = openingDebt.add(netBorrowing, MC);
            if (closingDebt.signum() < 0) {
                throw new IllegalArgumentException("Debt financing policy produces negative debt in year " + (index + 1));
            }
            BigDecimal averageDebt = openingDebt.add(closingDebt, MC).divide(BigDecimal.valueOf(2), MC);
            BigDecimal pretaxInterest = averageDebt.multiply(request.pretaxCostOfDebtRate(), MC);
            BigDecimal afterTaxInterest = pretaxInterest.multiply(BigDecimal.ONE.subtract(operating.taxRate(), MC), MC);
            BigDecimal fcfe = operating.fcff().subtract(afterTaxInterest, MC).add(netBorrowing, MC);

            schedule.add(new DebtForecastYear(
                    index + 1, policy.type(), openingDebt, netBorrowing, closingDebt, averageDebt,
                    pretaxInterest, afterTaxInterest, fcfe
            ));
            openingDebt = closingDebt;
        }
        return List.copyOf(schedule);
    }

    private ValuationTrack valueTrack(List<BigDecimal> cashFlows,
                                      BigDecimal discountRate,
                                      BigDecimal terminalGrowth,
                                      String definition,
                                      String discountRateType,
                                      BigDecimal netDebt,
                                      boolean enterpriseValueTrack) {
        List<DiscountedCashFlowYear> discounted = new ArrayList<>(TOTAL_YEARS);
        BigDecimal pvExplicit = BigDecimal.ZERO;
        BigDecimal onePlusDiscount = BigDecimal.ONE.add(discountRate, MC);
        for (int index = 0; index < cashFlows.size(); index++) {
            BigDecimal discountFactor = BigDecimal.ONE.divide(onePlusDiscount.pow(index + 1, MC), MC);
            BigDecimal presentValue = cashFlows.get(index).multiply(discountFactor, MC);
            discounted.add(new DiscountedCashFlowYear(index + 1, cashFlows.get(index), discountFactor, presentValue));
            pvExplicit = pvExplicit.add(presentValue, MC);
        }

        BigDecimal terminalCashFlow = cashFlows.get(TOTAL_YEARS - 1)
                .multiply(BigDecimal.ONE.add(terminalGrowth, MC), MC);
        BigDecimal terminalValue = terminalCashFlow.divide(discountRate.subtract(terminalGrowth, MC), MC);
        BigDecimal pvTerminal = terminalValue.divide(onePlusDiscount.pow(TOTAL_YEARS, MC), MC);
        BigDecimal totalValue = pvExplicit.add(pvTerminal, MC);
        BigDecimal enterpriseValue = enterpriseValueTrack ? totalValue : null;
        BigDecimal equityValue = enterpriseValueTrack ? totalValue.subtract(netDebt, MC) : totalValue;

        return new ValuationTrack(
                definition, discountRateType, discountRate, discounted, terminalCashFlow, terminalValue,
                pvExplicit, pvTerminal, enterpriseValue, netDebt, equityValue
        );
    }

    private void validate(ExplicitOperatingForecastRequest request) {
        require(request, "request");
        requirePositive(request.startingRevenue(), "startingRevenue");
        requireNonNegative(request.openingGrossDebt(), "openingGrossDebt");
        require(request.currentNetDebt(), "currentNetDebt");
        requireRate(request.waccRate(), "waccRate", false);
        requireRate(request.costOfEquityRate(), "costOfEquityRate", false);
        requireRate(request.pretaxCostOfDebtRate(), "pretaxCostOfDebtRate", true);
        requireRate(request.terminalGrowthRate(), "terminalGrowthRate", true);
        if (request.waccRate().compareTo(request.terminalGrowthRate()) <= 0) {
            throw new IllegalArgumentException("waccRate must be greater than terminalGrowthRate");
        }
        if (request.costOfEquityRate().compareTo(request.terminalGrowthRate()) <= 0) {
            throw new IllegalArgumentException("costOfEquityRate must be greater than terminalGrowthRate");
        }
        if (request.explicitOperatingDrivers().size() != EXPLICIT_YEARS) {
            throw new IllegalArgumentException("explicitOperatingDrivers must contain exactly five years");
        }
        for (int index = 0; index < request.explicitOperatingDrivers().size(); index++) {
            validateDriver(request.explicitOperatingDrivers().get(index), "explicitOperatingDrivers[" + index + "]");
        }
        TerminalOperatingDriver terminal = require(request.terminalOperatingDriver(), "terminalOperatingDriver");
        validateOperatingRates(terminal.ebitMargin(), terminal.taxRate(), terminal.depreciationAndAmortizationRate(),
                terminal.capexRate(), terminal.changeInNetWorkingCapitalRate(), "terminalOperatingDriver");

        DebtFinancingPolicy policy = require(request.debtFinancingPolicy(), "debtFinancingPolicy");
        require(policy.type(), "debtFinancingPolicy.type");
        if (policy.type() == DebtFinancingPolicy.Type.TARGET_DEBT_FINANCING_RATIO) {
            BigDecimal ratio = require(policy.targetDebtFinancingRatio(), "targetDebtFinancingRatio");
            if (ratio.signum() < 0 || ratio.compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalArgumentException("targetDebtFinancingRatio must be between zero and one");
            }
        } else {
            if (policy.customAnnualNetBorrowing().size() != TOTAL_YEARS
                    || policy.customAnnualNetBorrowing().stream().anyMatch(value -> value == null)) {
                throw new IllegalArgumentException("customAnnualNetBorrowing must contain exactly ten non-null values");
            }
        }
    }

    private void validateDriver(OperatingDriver driver, String name) {
        require(driver, name);
        requireRate(driver.revenueGrowthRate(), name + ".revenueGrowthRate", true);
        validateOperatingRates(driver.ebitMargin(), driver.taxRate(), driver.depreciationAndAmortizationRate(),
                driver.capexRate(), driver.changeInNetWorkingCapitalRate(), name);
    }

    private void validateOperatingRates(BigDecimal ebitMargin,
                                        BigDecimal taxRate,
                                        BigDecimal daRate,
                                        BigDecimal capexRate,
                                        BigDecimal deltaNwcRate,
                                        String name) {
        requireRate(ebitMargin, name + ".ebitMargin", true);
        requireRate(taxRate, name + ".taxRate", true);
        requireRate(daRate, name + ".depreciationAndAmortizationRate", true);
        requireRate(capexRate, name + ".capexRate", true);
        requireRate(deltaNwcRate, name + ".changeInNetWorkingCapitalRate", true);
        if (taxRate.signum() < 0 || taxRate.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException(name + ".taxRate must be between zero and one");
        }
    }

    private void requireRate(BigDecimal value, String name, boolean allowZero) {
        require(value, name);
        if (value.compareTo(BigDecimal.ONE.negate()) <= 0 || value.compareTo(BigDecimal.ONE) >= 0
                || (!allowZero && value.signum() <= 0)) {
            throw new IllegalArgumentException(name + " must be a decimal rate between -1 and 1");
        }
    }

    private void requirePositive(BigDecimal value, String name) {
        require(value, name);
        if (value.signum() <= 0) throw new IllegalArgumentException(name + " must be positive");
    }

    private void requireNonNegative(BigDecimal value, String name) {
        require(value, name);
        if (value.signum() < 0) throw new IllegalArgumentException(name + " must not be negative");
    }

    private <T> T require(T value, String name) {
        if (value == null) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
