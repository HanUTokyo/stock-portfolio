package com.stockportfolio.service;

import com.stockportfolio.dto.ValuationAssumptions;
import com.stockportfolio.dto.ValuationEvaluationResponse;
import com.stockportfolio.dto.ValuationScenarioResponse;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

@Component
public class ValuationEngine {
    public static final MathContext MC = new MathContext(18, RoundingMode.HALF_UP);
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    public record Quarter(
            LocalDate periodEnd,
            LocalDate filingDate,
            Integer fiscalYear,
            String fiscalPeriod,
            BigDecimal dilutedEps,
            BigDecimal cfo,
            BigDecimal capex,
            BigDecimal interestExpense,
            BigDecimal netBorrowing,
            BigDecimal depreciationAmortization,
            BigDecimal changeInWorkingCapital,
            BigDecimal operatingIncome,
            BigDecimal taxProvision,
            BigDecimal pretaxIncome,
            BigDecimal netIncome,
            BigDecimal equity,
            BigDecimal debt,
            BigDecimal cash,
            BigDecimal shortTermInvestments,
            BigDecimal noncurrentMarketableSecurities,
            BigDecimal investedCapital,
            BigDecimal revenue,
            BigDecimal grossProfit,
            BigDecimal totalAssets,
            String currencyCode
    ) {
        public Quarter(LocalDate periodEnd, LocalDate filingDate, Integer fiscalYear, String fiscalPeriod,
                       BigDecimal dilutedEps, BigDecimal cfo, BigDecimal capex, BigDecimal interestExpense,
                       BigDecimal netBorrowing, BigDecimal depreciationAmortization, BigDecimal changeInWorkingCapital,
                       BigDecimal operatingIncome, BigDecimal taxProvision, BigDecimal pretaxIncome,
                       BigDecimal netIncome, BigDecimal equity, BigDecimal debt, BigDecimal cash,
                       BigDecimal investedCapital, BigDecimal revenue, BigDecimal grossProfit,
                       BigDecimal totalAssets, String currencyCode) {
            this(periodEnd, filingDate, fiscalYear, fiscalPeriod, dilutedEps, cfo, capex, interestExpense,
                    netBorrowing, depreciationAmortization, changeInWorkingCapital, operatingIncome,
                    taxProvision, pretaxIncome, netIncome, equity, debt, cash, null, null,
                    investedCapital, revenue, grossProfit, totalAssets, currencyCode);
        }
    }

    public record MarketInputs(
            BigDecimal currentPrice,
            BigDecimal sharesOutstanding,
            BigDecimal riskFreeRatePct,
            BigDecimal beta,
            BigDecimal equityRiskPremiumPct
    ) { }

    public record Selection(
            String model,
            BigDecimal latestTtmCashFlow,
            BigDecimal baseCashFlow,
            BigDecimal initialGrowthRatePct,
            BigDecimal automaticDiscountRatePct,
            BigDecimal taxRatePct,
            BigDecimal crossCheckDifferencePct,
            BigDecimal netDebt,
            BigDecimal debt,
            BigDecimal cash,
            BigDecimal shortTermInvestments,
            BigDecimal noncurrentMarketableSecurities,
            int growthSampleCount,
            List<BigDecimal> historicalGrowthComponentsPct,
            List<String> missingFields,
            List<String> warnings,
            List<Quarter> quarters,
            List<MethodSelection> methodSelections
    ) {
        public boolean available() {
            return model != null && missingFields != null && missingFields.isEmpty()
                    && baseCashFlow != null && baseCashFlow.signum() > 0
                    && automaticDiscountRatePct != null;
        }

        public MethodSelection methodSelection(String method) {
            if (methodSelections == null) return null;
            return methodSelections.stream()
                    .filter(candidate -> candidate.method().equalsIgnoreCase(method))
                    .findFirst()
                    .orElse(null);
        }

        public Selection forMethod(String method) {
            MethodSelection selected = methodSelection(method);
            if (selected == null) return this;
            return new Selection(selected.method(), selected.latestTtmCashFlow(), selected.normalizedBaseCashFlow(),
                    selected.initialGrowthRatePct(), selected.automaticDiscountRatePct(), taxRatePct,
                    selected.definitionCrossCheckDifferencePct(), netDebt, debt, cash, shortTermInvestments,
                    noncurrentMarketableSecurities, selected.growthSampleCount(),
                    selected.historicalGrowthComponentsPct(), selected.missingInputs(), selected.warnings(), quarters,
                    methodSelections);
        }
    }

    public record MethodSelection(
            String method,
            BigDecimal latestTtmCashFlow,
            BigDecimal crossCheckTtmCashFlow,
            BigDecimal normalizedBaseCashFlow,
            BigDecimal initialGrowthRatePct,
            BigDecimal automaticDiscountRatePct,
            BigDecimal definitionCrossCheckDifferencePct,
            int growthSampleCount,
            List<BigDecimal> historicalGrowthComponentsPct,
            List<String> missingInputs,
            List<String> warnings
    ) {
        public boolean available() {
            return missingInputs.isEmpty() && latestTtmCashFlow != null && latestTtmCashFlow.signum() > 0
                    && normalizedBaseCashFlow != null && normalizedBaseCashFlow.signum() > 0
                    && automaticDiscountRatePct != null;
        }
    }

    public record GrowthInputs(BigDecimal autoGrowthPct, BigDecimal historicalGrowthPct,
                               BigDecimal consensusGrowthPct) { }

    public Selection select(List<Quarter> source, MarketInputs market, BigDecimal taxOverridePct) {
        List<Quarter> rows = source.stream().sorted(Comparator.comparing(Quarter::periodEnd)).toList();
        if (rows.size() < 4) return unavailable(rows, List.of("fourConsecutiveActualQuarters"));
        List<Quarter> latest = rows.subList(rows.size() - 4, rows.size());
        if (!consecutive(latest)) return unavailable(rows, List.of("fourConsecutiveActualQuarters"));

        BigDecimal taxRatePct = resolveTaxRatePct(rows, taxOverridePct);
        BigDecimal tax = taxRatePct == null ? null : taxRatePct.divide(ONE_HUNDRED, MC);
        BigDecimal cfo = sumRequired(latest, Quarter::cfo);
        BigDecimal capex = sumRequired(latest, Quarter::capex);
        BigDecimal interest = sumRequired(latest, Quarter::interestExpense);
        BigDecimal netBorrowing = sumRequired(latest, Quarter::netBorrowing);
        BigDecimal operatingIncome = sumRequired(latest, Quarter::operatingIncome);
        BigDecimal depreciationAmortization = sumRequired(latest, Quarter::depreciationAmortization);
        BigDecimal deltaNwc = sumRequired(latest, Quarter::changeInWorkingCapital);
        BigDecimal debt = latestNonNull(latest, Quarter::debt);
        BigDecimal cash = latestNonNull(latest, Quarter::cash);
        BigDecimal shortTermInvestments = latestNonNull(latest, Quarter::shortTermInvestments);
        BigDecimal noncurrentMarketable = latestNonNull(latest, Quarter::noncurrentMarketableSecurities);
        BigDecimal netDebt = debt == null && cash == null && shortTermInvestments == null && noncurrentMarketable == null
                ? null : nvl(debt).subtract(nvl(cash), MC).subtract(nvl(shortTermInvestments), MC).subtract(nvl(noncurrentMarketable), MC);
        BigDecimal marketCap = multiply(market.currentPrice(), market.sharesOutstanding());
        boolean immaterialDebt = debt != null && marketCap != null && marketCap.signum() > 0
                && debt.divide(marketCap, MC).compareTo(new BigDecimal("0.01")) < 0;
        List<String> fcffWarnings = new ArrayList<>();
        if (interest == null && immaterialDebt) {
            interest = BigDecimal.ZERO;
            fcffWarnings.add("Interest expense treated as zero because debt is below 1% of market value.");
        }

        BigDecimal coePct = costOfEquityPct(market);
        // The reported-cash definition is primary. CFO already contains the complete indirect
        // cash-flow bridge (working capital plus non-cash adjustments); do not rebuild it from
        // a partial ΔNWC tag. Add back the after-tax financing cost to unlever the reported CFO.
        BigDecimal reportedCashFcff = cfo == null || capex == null || interest == null || tax == null
                ? null : cfo.add(interest.multiply(BigDecimal.ONE.subtract(tax), MC), MC).subtract(capex, MC);
        // This is an audit reconstruction only until every indirect-CFO adjustment and working-
        // capital component is sourced on a non-overlapping basis. Quarter.changeInWorkingCapital
        // is accounting ΔNWC (increase is positive), so the cash-flow formula subtracts it.
        BigDecimal operatingFcff = operatingIncome == null || tax == null || depreciationAmortization == null
                || capex == null || deltaNwc == null ? null
                : operatingIncome.multiply(BigDecimal.ONE.subtract(tax), MC)
                .add(depreciationAmortization, MC).subtract(capex, MC).subtract(deltaNwc, MC);
        BigDecimal crossDifference = reportedCashFcff == null || operatingFcff == null
                ? null : relativeDifferencePct(reportedCashFcff, operatingFcff);
        if (crossDifference == null) {
            fcffWarnings.add("NOPAT-based FCFF reconstruction is unavailable because a complete operating bridge is missing.");
        } else if (crossDifference.compareTo(BigDecimal.valueOf(25)) > 0) {
            fcffWarnings.add("Severe operating FCFF bridge gap above 25%; reported-cash FCFF remains primary pending a detailed indirect-CFO bridge.");
        } else if (crossDifference.compareTo(BigDecimal.TEN) > 0) {
            fcffWarnings.add("Operating FCFF bridge gap is above 10%; reported-cash FCFF remains primary pending a detailed indirect-CFO bridge.");
        }
        BigDecimal waccPct = waccPct(latest, marketCap, debt, tax, coePct, immaterialDebt);

        BigDecimal fcfe = cfo == null || capex == null || netBorrowing == null
                ? null : cfo.subtract(capex, MC).add(netBorrowing, MC);
        BigDecimal operatingFcfe = operatingFcff == null || interest == null || tax == null || netBorrowing == null
                ? null : operatingFcff.subtract(interest.multiply(BigDecimal.ONE.subtract(tax), MC), MC).add(netBorrowing, MC);
        BigDecimal fcfeCrossDifference = fcfe == null || operatingFcfe == null
                ? null : relativeDifferencePct(fcfe, operatingFcfe);
        List<String> fcfeWarnings = new ArrayList<>();
        fcfeWarnings.add("Debt policy uses reported net borrowing, including short- and long-term financing when supplied.");
        if (fcfeCrossDifference == null) {
            fcfeWarnings.add("Operating FCFE cross-check is unavailable because FCFF, interest, tax, or net borrowing is missing.");
        } else if (fcfeCrossDifference.compareTo(BigDecimal.valueOf(25)) > 0) {
            fcfeWarnings.add("Severe FCFE definition conflict above 25%.");
        } else if (fcfeCrossDifference.compareTo(BigDecimal.TEN) > 0) {
            fcfeWarnings.add("FCFE cross-check difference is above 10%.");
        }

        List<String> commonMissing = new ArrayList<>();
        if (market.sharesOutstanding() == null || market.sharesOutstanding().signum() <= 0) commonMissing.add("sharesOutstanding");

        List<String> fcffMissing = new ArrayList<>(commonMissing);
        if (market.currentPrice() == null || market.currentPrice().signum() <= 0) fcffMissing.add("currentPrice");
        if (cfo == null) fcffMissing.add("operatingCashFlow");
        if (capex == null) fcffMissing.add("capex");
        if (interest == null && !immaterialDebt) fcffMissing.add("interestExpense");
        if (tax == null) fcffMissing.add("taxRate");
        if (debt == null) fcffMissing.add("totalDebt");
        if (cash == null) fcffMissing.add("cashAndEquivalents");
        if (waccPct == null) fcffMissing.add("automaticWacc");
        // Economic FCFF is primary only when inputs are complete; cash FCFF remains the explicitly named reconciliation reference.
        MethodSelection fcffSelection = buildMethodSelection(rows, "FCFF", tax, operatingFcff, reportedCashFcff, waccPct,
                crossDifference, fcffMissing, fcffWarnings);

        List<String> fcfeMissing = new ArrayList<>(commonMissing);
        if (cfo == null) fcfeMissing.add("operatingCashFlow");
        if (capex == null) fcfeMissing.add("capex");
        if (netBorrowing == null) fcfeMissing.add("netBorrowing");
        if (coePct == null) fcfeMissing.add("costOfEquity");
        MethodSelection fcfeSelection = buildMethodSelection(rows, "FCFE", tax, fcfe, operatingFcfe, coePct,
                fcfeCrossDifference, fcfeMissing, fcfeWarnings);

        List<MethodSelection> methods = List.of(fcffSelection, fcfeSelection);
        MethodSelection selected = fcffSelection.available() ? fcffSelection : fcfeSelection.available() ? fcfeSelection : null;
        if (selected == null) {
            List<String> missing = methods.stream().flatMap(method -> method.missingInputs().stream()).distinct().toList();
            return new Selection(null, null, null, null, null, taxRatePct, null, netDebt, debt, cash,
                    shortTermInvestments, noncurrentMarketable, 0, List.of(), missing, List.of(), rows, methods);
        }
        return new Selection(selected.method(), selected.latestTtmCashFlow(), selected.normalizedBaseCashFlow(),
                selected.initialGrowthRatePct(), selected.automaticDiscountRatePct(), taxRatePct,
                selected.definitionCrossCheckDifferencePct(), netDebt, debt, cash, shortTermInvestments,
                noncurrentMarketable, selected.growthSampleCount(), selected.historicalGrowthComponentsPct(),
                List.of(), selected.warnings(), rows, methods);
    }

    private MethodSelection buildMethodSelection(List<Quarter> rows, String method, BigDecimal tax,
                                                 BigDecimal latestTtm, BigDecimal crossCheckTtm,
                                                 BigDecimal discountPct,
                                                 BigDecimal definitionCrossCheckDifferencePct,
                                                 List<String> rawMissing, List<String> warnings) {
        List<String> missing = new ArrayList<>(rawMissing);
        if (latestTtm == null || latestTtm.signum() <= 0) missing.add("positive" + method.substring(0, 1) + method.substring(1).toLowerCase());
        List<BigDecimal> annual = annualTtmCashFlows(rows, method, tax, false);
        BigDecimal base = annual.size() >= 3 ? median(annual.subList(annual.size() - 3, annual.size())) : latestTtm;
        if (base == null || base.signum() <= 0) base = latestTtm;
        if (base == null || base.signum() <= 0) missing.add("positiveNormalizedBaseCashFlow");
        List<BigDecimal> growthAnnual = annualTtmCashFlows(rows, method, tax, true);
        List<BigDecimal> growthComponents = historicalGrowthComponents(growthAnnual);
        BigDecimal growth = growthComponents.isEmpty() ? null : median(growthComponents)
                .max(BigDecimal.valueOf(-5)).min(BigDecimal.valueOf(15)).setScale(4, RoundingMode.HALF_UP);
        return new MethodSelection(method, latestTtm, crossCheckTtm, base, growth, discountPct,
                definitionCrossCheckDifferencePct, growthAnnual.size(), growthComponents,
                missing.stream().distinct().toList(), List.copyOf(warnings));
    }

    public ValuationAssumptions defaultAssumptions(Selection selection, String type) {
        BigDecimal base = selection.baseCashFlow();
        BigDecimal growth = selection.initialGrowthRatePct();
        BigDecimal discount = selection.automaticDiscountRatePct();
        BigDecimal terminal;
        BigDecimal margin;
        if ("BEAR".equals(type)) {
            growth = growth.subtract(BigDecimal.valueOf(4));
            discount = discount.add(new BigDecimal("1.5"));
            terminal = BigDecimal.valueOf(2);
            margin = BigDecimal.valueOf(30);
        } else if ("BULL".equals(type)) {
            growth = growth.add(BigDecimal.valueOf(4));
            discount = discount.subtract(BigDecimal.ONE);
            terminal = BigDecimal.valueOf(3);
            margin = BigDecimal.TEN;
        } else {
            terminal = new BigDecimal("2.5");
            margin = BigDecimal.valueOf(20);
        }
        BigDecimal minimumDiscount = terminal.add(BigDecimal.valueOf(2));
        if (discount.compareTo(minimumDiscount) < 0) discount = minimumDiscount;
        return new ValuationAssumptions(scaleMoney(base), scaleRate(growth), scaleRate(discount), terminal,
                10, margin, null);
    }

    public ValuationAssumptions defaultSettings(String type) {
        BigDecimal terminal = "BEAR".equals(type) ? BigDecimal.valueOf(2)
                : "BULL".equals(type) ? BigDecimal.valueOf(3) : new BigDecimal("2.5");
        BigDecimal margin = "BEAR".equals(type) ? BigDecimal.valueOf(30)
                : "BULL".equals(type) ? BigDecimal.TEN : BigDecimal.valueOf(20);
        return new ValuationAssumptions(null, null, null, terminal, 10, margin, null,
                "AUTO", "AUTO_BLEND", "AUTO", null, null, null, null, null);
    }

    public ValuationAssumptions normalizeLegacy(ValuationAssumptions input) {
        if (input == null) return null;
        if (input.baseCashFlowMode() != null || input.growthMode() != null || input.discountRateMode() != null) return input;
        return new ValuationAssumptions(input.baseCashFlow(), input.initialGrowthRatePct(), input.discountRatePct(),
                input.terminalGrowthRatePct(), input.projectionYears(), input.marginOfSafetyPct(), input.taxRateOverridePct(),
                "MANUAL", "CUSTOM_LINEAR", "MANUAL_RATE", input.annualGrowthRatesPct(),
                input.riskFreeRatePct(), input.beta(), input.equityRiskPremiumPct(), input.fcffWaccSelection(), input.fcffCashInterestReference());
    }

    public ValuationAssumptions resolve(String type, ValuationAssumptions raw, Selection selection,
                                        MarketInputs market, GrowthInputs growthInputs) {
        ValuationAssumptions settings = normalizeLegacy(raw);
        if (settings == null) settings = defaultSettings(type);
        String baseMode = mode(settings.baseCashFlowMode(), "AUTO");
        String growthMode = mode(settings.growthMode(), "AUTO_BLEND");
        String discountMode = mode(settings.discountRateMode(), "AUTO");

        BigDecimal base = settings.baseCashFlow();
        if ("AUTO".equals(baseMode)) {
            base = selection.baseCashFlow();
        }

        BigDecimal growth = settings.initialGrowthRatePct();
        if ("AUTO_BLEND".equals(growthMode)) growth = growthInputs == null ? null : growthInputs.autoGrowthPct();
        else if ("HISTORICAL".equals(growthMode)) growth = growthInputs == null ? null : growthInputs.historicalGrowthPct();
        else if ("CONSENSUS".equals(growthMode)) growth = growthInputs == null ? null : growthInputs.consensusGrowthPct();
        if (!growthMode.startsWith("CUSTOM") && growth != null) {
            if ("BEAR".equals(type)) growth = growth.subtract(BigDecimal.valueOf(4));
            else if ("BULL".equals(type)) growth = growth.add(BigDecimal.valueOf(4));
        }

        BigDecimal riskFree = choose(settings.riskFreeRatePct(), market.riskFreeRatePct());
        BigDecimal beta = choose(settings.beta(), market.beta());
        BigDecimal erp = choose(settings.equityRiskPremiumPct(), market.equityRiskPremiumPct());
        MarketInputs resolvedMarket = new MarketInputs(market.currentPrice(), market.sharesOutstanding(), riskFree, beta, erp);
        BigDecimal discount = settings.discountRatePct();
        if ("AUTO".equals(discountMode)) discount = selection.automaticDiscountRatePct();
        else if ("MANUAL_CAPM_COMPONENTS".equals(discountMode)) {
            BigDecimal coe = costOfEquityPct(resolvedMarket);
            BigDecimal tax = selection.taxRatePct() == null ? null : pct(selection.taxRatePct());
            discount = "FCFF".equals(selection.model())
                    ? waccPct(selection.quarters().subList(selection.quarters().size() - 4, selection.quarters().size()),
                    multiply(market.currentPrice(), market.sharesOutstanding()), selection.debt(), tax, coe, false)
                    : coe;
        }
        if ("AUTO".equals(discountMode) && discount != null) {
            if ("BEAR".equals(type)) discount = discount.add(new BigDecimal("1.5"));
            else if ("BULL".equals(type)) discount = discount.subtract(BigDecimal.ONE);
        }
        BigDecimal terminal = settings.terminalGrowthRatePct();
        if (discount != null && terminal != null && discount.subtract(terminal).compareTo(BigDecimal.valueOf(2)) < 0)
            discount = terminal.add(BigDecimal.valueOf(2));

        List<BigDecimal> path = settings.annualGrowthRatesPct() == null ? null : List.copyOf(settings.annualGrowthRatesPct());
        return new ValuationAssumptions(scaleMoney(base), scaleRate(growth), scaleRate(discount), terminal,
                settings.projectionYears(), settings.marginOfSafetyPct(), settings.taxRateOverridePct(),
                baseMode, growthMode, discountMode, path, riskFree, beta, erp, settings.fcffWaccSelection(), settings.fcffCashInterestReference());
    }

    public List<String> validate(ValuationAssumptions a) {
        List<String> errors = new ArrayList<>();
        if (a == null) return List.of("assumptions are required");
        range(errors, "baseCashFlow", a.baseCashFlow(), BigDecimal.ZERO, null, false);
        range(errors, "initialGrowthRatePct", a.initialGrowthRatePct(), BigDecimal.valueOf(-50), BigDecimal.valueOf(50), true);
        range(errors, "discountRatePct", a.discountRatePct(), BigDecimal.valueOf(2), BigDecimal.valueOf(30), true);
        range(errors, "terminalGrowthRatePct", a.terminalGrowthRatePct(), BigDecimal.valueOf(-5), BigDecimal.valueOf(5), true);
        range(errors, "marginOfSafetyPct", a.marginOfSafetyPct(), BigDecimal.ZERO, BigDecimal.valueOf(50), true);
        if (a.taxRateOverridePct() != null) range(errors, "taxRateOverridePct", a.taxRateOverridePct(), BigDecimal.ZERO, BigDecimal.valueOf(40), true);
        if (a.fcffCashInterestReference() != null) range(errors, "fcffCashInterestReference", a.fcffCashInterestReference(), BigDecimal.ZERO, null, true);
        if (a.projectionYears() == null || a.projectionYears() < 1 || a.projectionYears() > 20) errors.add("projectionYears must be between 1 and 20");
        if ("CUSTOM_PATH".equalsIgnoreCase(a.growthMode())) {
            if (a.annualGrowthRatesPct() == null || a.projectionYears() == null || a.annualGrowthRatesPct().size() != a.projectionYears())
                errors.add("annualGrowthRatesPct must contain exactly projectionYears values");
            else for (BigDecimal value : a.annualGrowthRatesPct())
                range(errors, "annualGrowthRatesPct", value, BigDecimal.valueOf(-50), BigDecimal.valueOf(50), true);
        }
        if (a.discountRatePct() != null && a.terminalGrowthRatePct() != null
                && a.discountRatePct().subtract(a.terminalGrowthRatePct()).compareTo(BigDecimal.valueOf(2)) < 0) {
            errors.add("discountRatePct must be at least 2 percentage points above terminalGrowthRatePct");
        }
        return errors;
    }

    public ValuationScenarioResponse evaluateSettings(String type, String origin, ValuationAssumptions settings,
                                                       Selection selection, MarketInputs market, GrowthInputs growthInputs,
                                                       java.time.OffsetDateTime updatedAt) {
        ValuationAssumptions normalized = normalizeLegacy(settings == null ? defaultSettings(type) : settings);
        ValuationAssumptions resolved = resolve(type, normalized, selection, market, growthInputs);
        ValuationScenarioResponse calculated = evaluate(type, origin, resolved, selection, market, updatedAt);
        Map<String, String> sources = assumptionSources(normalized);
        List<String> overrides = sources.entrySet().stream().filter(e -> "USER_OVERRIDE".equals(e.getValue()))
                .map(Map.Entry::getKey).toList();
        return new ValuationScenarioResponse(type, "AUTO", selection.model(), origin, normalized, calculated.valid(),
                calculated.intrinsicValuePerShare(), calculated.marginOfSafetyPrice(), calculated.enterpriseValue(),
                calculated.equityValue(), calculated.terminalValueWeightPct(), calculated.projection(),
                calculated.warnings(), updatedAt, resolved, sources, overrides);
    }

    public ValuationScenarioResponse evaluateSettingsForMethod(String method, String type, String origin,
                                                                ValuationAssumptions settings, Selection selection,
                                                                MarketInputs market, GrowthInputs growthInputs,
                                                                java.time.OffsetDateTime updatedAt) {
        return evaluateSettings(type, origin, settings, selection.forMethod(method), market, growthInputs, updatedAt);
    }

    public ValuationScenarioResponse evaluate(String type, String origin, ValuationAssumptions a,
                                               Selection selection, MarketInputs market, java.time.OffsetDateTime updatedAt) {
        List<String> errors = validate(a);
        if (!selection.available()) errors = concat(errors, selection.missingFields());
        if (!errors.isEmpty()) return invalid(type, origin, a, selection.model(), errors, updatedAt);
        BigDecimal discount = pct(a.discountRatePct());
        BigDecimal terminalGrowth = pct(a.terminalGrowthRatePct());
        BigDecimal cashFlow = a.baseCashFlow();
        List<ValuationScenarioResponse.ProjectionPoint> projection = new ArrayList<>();
        BigDecimal explicitPv = BigDecimal.ZERO;
        for (int year = 1; year <= a.projectionYears(); year++) {
            BigDecimal growthPct = projectionGrowth(a, year);
            cashFlow = cashFlow.multiply(BigDecimal.ONE.add(pct(growthPct)), MC);
            BigDecimal factor = BigDecimal.ONE.add(discount).pow(year, MC);
            BigDecimal pv = cashFlow.divide(factor, MC);
            explicitPv = explicitPv.add(pv, MC);
            projection.add(new ValuationScenarioResponse.ProjectionPoint(year, scaleRate(growthPct), scaleMoney(cashFlow),
                    factor.setScale(8, RoundingMode.HALF_UP), scaleMoney(pv)));
        }
        BigDecimal terminal = cashFlow.multiply(BigDecimal.ONE.add(terminalGrowth), MC)
                .divide(discount.subtract(terminalGrowth), MC);
        BigDecimal terminalPv = terminal.divide(BigDecimal.ONE.add(discount).pow(a.projectionYears(), MC), MC);
        BigDecimal enterpriseOrEquity = explicitPv.add(terminalPv, MC);
        BigDecimal enterprise = "FCFF".equals(selection.model()) ? enterpriseOrEquity : null;
        BigDecimal equity = "FCFF".equals(selection.model())
                ? enterpriseOrEquity.subtract(nvl(selection.netDebt()), MC) : enterpriseOrEquity;
        BigDecimal perShare = equity.divide(market.sharesOutstanding(), MC);
        BigDecimal safety = perShare.multiply(BigDecimal.ONE.subtract(pct(a.marginOfSafetyPct())), MC);
        BigDecimal terminalWeight = terminalPv.divide(enterpriseOrEquity, MC).multiply(ONE_HUNDRED);
        List<String> warnings = new ArrayList<>(selection.warnings());
        if (terminalWeight.compareTo(BigDecimal.valueOf(70)) >= 0) warnings.add("Valuation is highly dependent on terminal value.");
        return new ValuationScenarioResponse(type, "AUTO", selection.model(), origin, a, true,
                scaleMoney(perShare), scaleMoney(safety), scaleMoney(enterprise), scaleMoney(equity),
                scaleRate(terminalWeight), projection, warnings, updatedAt);
    }

    public ValuationEvaluationResponse.Sensitivity sensitivity(String type, ValuationAssumptions a,
                                                                Selection selection, MarketInputs market) {
        List<BigDecimal> discounts = offsets(a.discountRatePct(), List.of(-2, -1, 0, 1, 2));
        List<BigDecimal> terminals = offsets(a.terminalGrowthRatePct(), List.of(-1d, -.5d, 0d, .5d, 1d));
        List<List<BigDecimal>> values = new ArrayList<>();
        for (BigDecimal terminal : terminals) {
            List<BigDecimal> row = new ArrayList<>();
            for (BigDecimal discount : discounts) {
                ValuationAssumptions cell = copy(a, a.initialGrowthRatePct(), discount, terminal, a.annualGrowthRatesPct());
                ValuationScenarioResponse result = evaluate(type, "EVALUATED", cell, selection, market, null);
                row.add(result.valid() ? result.intrinsicValuePerShare() : null);
            }
            values.add(row);
        }
        return new ValuationEvaluationResponse.Sensitivity(discounts, terminals, values);
    }

    public ValuationEvaluationResponse.ReverseDcf reverse(String type, ValuationAssumptions a,
                                                           Selection selection, MarketInputs market) {
        if (market.currentPrice() == null || market.currentPrice().signum() <= 0)
            return new ValuationEvaluationResponse.ReverseDcf(null, null, "UNAVAILABLE");
        BigDecimal growth = solve(a, BigDecimal.valueOf(-50), BigDecimal.valueOf(50), market.currentPrice(),
                value -> withInitialGrowth(a, value), type, selection, market, true);
        BigDecimal lowDiscount = a.terminalGrowthRatePct().add(BigDecimal.valueOf(2));
        BigDecimal discount = solve(a, lowDiscount, BigDecimal.valueOf(30), market.currentPrice(),
                value -> copy(a, a.initialGrowthRatePct(), value, a.terminalGrowthRatePct(), a.annualGrowthRatesPct()),
                type, selection, market, false);
        String status = growth == null && discount == null ? "UNSOLVABLE"
                : growth == null || discount == null ? "PARTIAL" : "AVAILABLE";
        return new ValuationEvaluationResponse.ReverseDcf(growth, discount, status);
    }

    private BigDecimal solve(ValuationAssumptions base, BigDecimal low, BigDecimal high, BigDecimal target,
                             Function<BigDecimal, ValuationAssumptions> replace, String type,
                             Selection selection, MarketInputs market, boolean increasing) {
        BigDecimal left = low, right = high;
        BigDecimal leftValue = value(type, replace.apply(left), selection, market);
        BigDecimal rightValue = value(type, replace.apply(right), selection, market);
        if (leftValue == null || rightValue == null) return null;
        if ((leftValue.subtract(target).signum() * rightValue.subtract(target).signum()) > 0) return null;
        for (int i = 0; i < 80; i++) {
            BigDecimal mid = left.add(right).divide(BigDecimal.valueOf(2), MC);
            BigDecimal midValue = value(type, replace.apply(mid), selection, market);
            if (midValue == null) return null;
            if (midValue.subtract(target).abs().compareTo(new BigDecimal("0.000001")) < 0) return scaleRate(mid);
            boolean moveLeft = increasing ? midValue.compareTo(target) < 0 : midValue.compareTo(target) > 0;
            if (moveLeft) left = mid; else right = mid;
        }
        return scaleRate(left.add(right).divide(BigDecimal.valueOf(2), MC));
    }

    private BigDecimal value(String type, ValuationAssumptions a, Selection s, MarketInputs m) {
        ValuationScenarioResponse result = evaluate(type, "EVALUATED", a, s, m, null);
        return result.valid() ? result.intrinsicValuePerShare() : null;
    }

    private BigDecimal projectionGrowth(ValuationAssumptions a, int year) {
        if ("CUSTOM_PATH".equalsIgnoreCase(a.growthMode()) && a.annualGrowthRatesPct() != null
                && a.annualGrowthRatesPct().size() >= year) return a.annualGrowthRatesPct().get(year - 1);
        if (a.projectionYears() == 1) return a.initialGrowthRatePct();
        BigDecimal fraction = BigDecimal.valueOf(year - 1L).divide(BigDecimal.valueOf(a.projectionYears() - 1L), MC);
        return a.initialGrowthRatePct().add(a.terminalGrowthRatePct().subtract(a.initialGrowthRatePct()).multiply(fraction, MC), MC);
    }

    private ValuationAssumptions withInitialGrowth(ValuationAssumptions a, BigDecimal value) {
        List<BigDecimal> path = a.annualGrowthRatesPct();
        if ("CUSTOM_PATH".equalsIgnoreCase(a.growthMode()) && path != null && !path.isEmpty()) {
            BigDecimal delta = value.subtract(path.getFirst());
            path = path.stream().map(v -> v.add(delta)).toList();
        }
        return copy(a, value, a.discountRatePct(), a.terminalGrowthRatePct(), path);
    }

    private ValuationAssumptions copy(ValuationAssumptions a, BigDecimal initialGrowth, BigDecimal discount,
                                      BigDecimal terminal, List<BigDecimal> path) {
        return new ValuationAssumptions(a.baseCashFlow(), initialGrowth, discount, terminal,
                a.projectionYears(), a.marginOfSafetyPct(), a.taxRateOverridePct(), a.baseCashFlowMode(),
                a.growthMode(), a.discountRateMode(), path, a.riskFreeRatePct(), a.beta(), a.equityRiskPremiumPct(), a.fcffWaccSelection(), a.fcffCashInterestReference());
    }

    private List<BigDecimal> annualTtmCashFlows(List<Quarter> rows, String model, BigDecimal tax, boolean sustainableGrowth) {
        Map<Integer, BigDecimal> byYear = new LinkedHashMap<>();
        for (int i = 3; i < rows.size(); i++) {
            String fiscalPeriod = rows.get(i).fiscalPeriod();
            if (!("FY".equalsIgnoreCase(fiscalPeriod) || "Q4".equalsIgnoreCase(fiscalPeriod))) continue;
            List<Quarter> four = rows.subList(i - 3, i + 1);
            if (!consecutive(four)) continue;
            BigDecimal cfo = sumRequired(four, Quarter::cfo);
            BigDecimal capex = sumRequired(four, Quarter::capex);
            BigDecimal value = null;
            if ("FCFF".equals(model)) {
                BigDecimal interest = sumRequired(four, Quarter::interestExpense);
                if (cfo != null && capex != null && interest != null && tax != null)
                    value = cfo.add(interest.multiply(BigDecimal.ONE.subtract(tax), MC), MC).subtract(capex, MC);
            } else {
                if (sustainableGrowth) {
                    if (cfo != null && capex != null) value = cfo.subtract(capex, MC);
                } else {
                    BigDecimal borrowing = sumRequired(four, Quarter::netBorrowing);
                    if (cfo != null && capex != null && borrowing != null) value = cfo.subtract(capex, MC).add(borrowing, MC);
                }
            }
            if (value != null) byYear.put(rows.get(i).fiscalYear() == null ? rows.get(i).periodEnd().getYear() : rows.get(i).fiscalYear(), value);
        }
        return byYear.values().stream().toList();
    }

    private List<BigDecimal> historicalGrowthComponents(List<BigDecimal> annual) {
        List<BigDecimal> growths = new ArrayList<>();
        for (int i = 1; i < annual.size(); i++) {
            BigDecimal previous = annual.get(i - 1), current = annual.get(i);
            if (previous != null && previous.signum() > 0 && current != null)
                growths.add(current.divide(previous, MC).subtract(BigDecimal.ONE).multiply(ONE_HUNDRED));
        }
        List<BigDecimal> components = new ArrayList<>();
        if (!growths.isEmpty()) {
            List<BigDecimal> latest = growths.size() > 3 ? growths.subList(growths.size() - 3, growths.size()) : growths;
            components.add(median(latest));
        }
        BigDecimal cagr3 = cagr(annual, 3);
        BigDecimal cagr5 = cagr(annual, 5);
        if (cagr3 != null) components.add(cagr3);
        if (cagr5 != null) components.add(cagr5);
        return components.stream().map(v -> v.max(BigDecimal.valueOf(-20)).min(BigDecimal.valueOf(30))
                .setScale(4, RoundingMode.HALF_UP)).toList();
    }

    private BigDecimal cagr(List<BigDecimal> annual, int years) {
        if (annual.size() <= years) return null;
        BigDecimal start = annual.get(annual.size() - 1 - years), end = annual.get(annual.size() - 1);
        if (start == null || end == null || start.signum() <= 0 || end.signum() <= 0) return null;
        double value = (Math.pow(end.divide(start, MC).doubleValue(), 1d / years) - 1d) * 100d;
        return BigDecimal.valueOf(value);
    }

    private BigDecimal resolveTaxRatePct(List<Quarter> rows, BigDecimal override) {
        if (override != null && override.compareTo(BigDecimal.ZERO) >= 0 && override.compareTo(BigDecimal.valueOf(40)) <= 0) return override;
        List<Quarter> latest = rows.subList(rows.size() - 4, rows.size());
        BigDecimal tax = sumRequired(latest, Quarter::taxProvision);
        BigDecimal pretax = sumRequired(latest, Quarter::pretaxIncome);
        BigDecimal rate = effectiveTaxPct(tax, pretax);
        if (rate != null) return rate;
        Map<Integer, List<Quarter>> years = new LinkedHashMap<>();
        for (Quarter row : rows) years.computeIfAbsent(row.fiscalYear() == null ? row.periodEnd().getYear() : row.fiscalYear(), ignored -> new ArrayList<>()).add(row);
        List<BigDecimal> rates = new ArrayList<>();
        for (List<Quarter> year : years.values()) {
            if (year.size() < 4) continue;
            BigDecimal r = effectiveTaxPct(sumRequired(year, Quarter::taxProvision), sumRequired(year, Quarter::pretaxIncome));
            if (r != null) rates.add(r);
        }
        if (rates.size() > 3) rates = rates.subList(rates.size() - 3, rates.size());
        return rates.isEmpty() ? null : median(rates);
    }

    private BigDecimal effectiveTaxPct(BigDecimal tax, BigDecimal pretax) {
        if (tax == null || pretax == null || pretax.signum() <= 0) return null;
        BigDecimal value = tax.divide(pretax, MC).multiply(ONE_HUNDRED);
        return value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(BigDecimal.valueOf(40)) > 0 ? null : scaleRate(value);
    }

    private BigDecimal costOfEquityPct(MarketInputs m) {
        if (m.riskFreeRatePct() == null || m.beta() == null || m.equityRiskPremiumPct() == null) return null;
        return m.riskFreeRatePct().add(m.beta().multiply(m.equityRiskPremiumPct(), MC), MC);
    }

    private BigDecimal waccPct(List<Quarter> latest, BigDecimal marketCap, BigDecimal debt, BigDecimal tax,
                               BigDecimal coePct, boolean immaterialDebt) {
        if (coePct == null || marketCap == null || marketCap.signum() <= 0) return null;
        if (debt == null) return null;
        if (debt.signum() <= 0 || immaterialDebt) return coePct;
        BigDecimal interest = sumRequired(latest, Quarter::interestExpense);
        BigDecimal startDebt = latest.get(0).debt(), endDebt = latest.get(latest.size() - 1).debt();
        if (interest == null || startDebt == null || endDebt == null) return null;
        BigDecimal averageDebt = startDebt.add(endDebt).divide(BigDecimal.valueOf(2), MC);
        if (averageDebt.signum() <= 0) return null;
        BigDecimal costDebtPct = interest.abs().divide(averageDebt, MC).multiply(ONE_HUNDRED);
        if (costDebtPct.signum() <= 0 || costDebtPct.compareTo(BigDecimal.valueOf(15)) > 0) return null;
        BigDecimal total = marketCap.add(debt, MC);
        BigDecimal equityWeight = marketCap.divide(total, MC);
        BigDecimal debtWeight = debt.divide(total, MC);
        return coePct.multiply(equityWeight, MC)
                .add(costDebtPct.multiply(BigDecimal.ONE.subtract(tax), MC).multiply(debtWeight, MC), MC);
    }

    private boolean consecutive(List<Quarter> rows) {
        if (rows.size() != 4) return false;
        for (int i = 1; i < rows.size(); i++) {
            long days = ChronoUnit.DAYS.between(rows.get(i - 1).periodEnd(), rows.get(i).periodEnd());
            if (days < 70 || days > 110) return false;
        }
        return true;
    }

    private Selection unavailable(List<Quarter> rows, List<String> missing) {
        List<String> distinct = missing.stream().distinct().toList();
        List<MethodSelection> methods = List.of(
                new MethodSelection("FCFF", null, null, null, null, null, null, 0, List.of(), distinct, List.of()),
                new MethodSelection("FCFE", null, null, null, null, null, null, 0, List.of(), distinct, List.of())
        );
        return new Selection(null, null, null, null, null, null, null, null,
                null, null, null, null, 0, List.of(), distinct, List.of(), rows, methods);
    }

    private ValuationScenarioResponse invalid(String type, String origin, ValuationAssumptions a, String model,
                                               List<String> errors, java.time.OffsetDateTime updatedAt) {
        return new ValuationScenarioResponse(type, "AUTO", model, origin, a, false, null, null,
                null, null, null, List.of(), errors, updatedAt);
    }

    private void range(List<String> errors, String field, BigDecimal value, BigDecimal min, BigDecimal max, boolean inclusiveMin) {
        if (value == null) { errors.add(field + " is required"); return; }
        if ((inclusiveMin ? value.compareTo(min) < 0 : value.compareTo(min) <= 0) || (max != null && value.compareTo(max) > 0))
            errors.add(field + " is outside the allowed range");
    }

    private BigDecimal relativeDifferencePct(BigDecimal a, BigDecimal b) {
        BigDecimal denominator = a.abs().max(b.abs());
        return denominator.signum() == 0 ? BigDecimal.ZERO : a.subtract(b).abs().divide(denominator, MC).multiply(ONE_HUNDRED);
    }

    /** Cross-model variance is anchored to FCFF equity value, the primary enterprise-value method. */
    public BigDecimal crossModelDifferencePct(BigDecimal fcffEquityValue, BigDecimal fcfeEquityValue) {
        if (fcffEquityValue == null || fcfeEquityValue == null || fcffEquityValue.signum() == 0) return null;
        return fcffEquityValue.subtract(fcfeEquityValue).abs().divide(fcffEquityValue.abs(), MC)
                .multiply(ONE_HUNDRED).setScale(4, RoundingMode.HALF_UP);
    }

    public String crossModelReadiness(BigDecimal differencePct, BigDecimal fcffValue, BigDecimal fcfeValue) {
        if (fcffValue == null || fcfeValue == null || differencePct == null) return "UNAVAILABLE";
        if (differencePct.compareTo(BigDecimal.TEN) <= 0) return "READY";
        if (differencePct.compareTo(BigDecimal.valueOf(25)) <= 0) return "READY_WITH_CAVEATS";
        return "NOT_READY";
    }

    private BigDecimal sumRequired(List<Quarter> rows, Function<Quarter, BigDecimal> getter) {
        BigDecimal sum = BigDecimal.ZERO;
        for (Quarter row : rows) {
            BigDecimal value = getter.apply(row);
            if (value == null) return null;
            sum = sum.add(value, MC);
        }
        return sum;
    }

    private BigDecimal latestNonNull(List<Quarter> rows, Function<Quarter, BigDecimal> getter) {
        for (int i = rows.size() - 1; i >= 0; i--) if (getter.apply(rows.get(i)) != null) return getter.apply(rows.get(i));
        return null;
    }

    private BigDecimal median(List<BigDecimal> input) {
        List<BigDecimal> values = input.stream().sorted().toList();
        int mid = values.size() / 2;
        return values.size() % 2 == 1 ? values.get(mid) : values.get(mid - 1).add(values.get(mid)).divide(BigDecimal.valueOf(2), MC);
    }

    private List<BigDecimal> offsets(BigDecimal center, List<? extends Number> offsets) {
        return offsets.stream().map(value -> center.add(BigDecimal.valueOf(value.doubleValue())).setScale(2, RoundingMode.HALF_UP)).toList();
    }

    private List<String> concat(List<String> a, List<String> b) {
        List<String> result = new ArrayList<>(a); result.addAll(b); return result.stream().distinct().toList();
    }
    private BigDecimal pct(BigDecimal value) { return value.divide(ONE_HUNDRED, MC); }
    private BigDecimal nvl(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }
    private BigDecimal choose(BigDecimal primary, BigDecimal fallback) { return primary == null ? fallback : primary; }
    private String mode(String raw, String fallback) { return raw == null || raw.isBlank() ? fallback : raw.trim().toUpperCase(); }
    private Map<String, String> assumptionSources(ValuationAssumptions settings) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        result.put("baseCashFlow", "AUTO".equals(mode(settings.baseCashFlowMode(), "AUTO")) ? "SYSTEM_AUTO" : "USER_OVERRIDE");
        result.put("growth", mode(settings.growthMode(), "AUTO_BLEND").startsWith("CUSTOM") ? "USER_OVERRIDE" : "SYSTEM_" + mode(settings.growthMode(), "AUTO_BLEND"));
        result.put("discountRate", "AUTO".equals(mode(settings.discountRateMode(), "AUTO")) ? "SYSTEM_AUTO" : "USER_OVERRIDE");
        result.put("terminalGrowthRatePct", "USER_INPUT");
        result.put("projectionYears", "USER_INPUT");
        result.put("marginOfSafetyPct", "USER_INPUT");
        if (settings.taxRateOverridePct() != null) result.put("taxRate", "USER_OVERRIDE");
        if (settings.fcffWaccSelection() != null) result.put("fcffWaccSelection", "EXTERNAL_REFERENCE_SNAPSHOT");
        if (settings.fcffCashInterestReference() != null) result.put("fcffCashInterestReference", "USER_ASSUMPTION");
        return Map.copyOf(result);
    }
    private BigDecimal multiply(BigDecimal a, BigDecimal b) { return a == null || b == null ? null : a.multiply(b, MC); }
    private BigDecimal min(BigDecimal a, BigDecimal b) { return a == null ? b : b == null ? a : a.min(b); }
    private BigDecimal max(BigDecimal a, BigDecimal b) { return a == null ? b : b == null ? a : a.max(b); }
    private BigDecimal scaleMoney(BigDecimal value) { return value == null ? null : value.setScale(4, RoundingMode.HALF_UP); }
    private BigDecimal scaleRate(BigDecimal value) { return value == null ? null : value.setScale(4, RoundingMode.HALF_UP); }
}
