package com.stockportfolio.service;

import com.stockportfolio.dto.ForecastPreviewRequest;
import com.stockportfolio.dto.ForecastPreviewResponse;
import com.stockportfolio.dto.ForecastTemplateResponse;
import com.stockportfolio.dto.ForecastTemporalContext;
import com.stockportfolio.model.EarningsHistory;
import com.stockportfolio.model.Position;
import com.stockportfolio.model.ForecastScenarioSnapshot;
import com.stockportfolio.repository.EarningsHistoryRepository;
import com.stockportfolio.repository.ForecastScenarioSnapshotRepository;
import com.stockportfolio.repository.PositionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockportfolio.valuation.explicit.DebtFinancingPolicy;
import com.stockportfolio.valuation.explicit.ExplicitOperatingForecastRequest;
import com.stockportfolio.valuation.explicit.ExplicitOperatingForecastResult;
import com.stockportfolio.valuation.explicit.ExplicitOperatingForecastService;
import com.stockportfolio.valuation.explicit.OperatingDriver;
import com.stockportfolio.valuation.explicit.TerminalOperatingDriver;
import com.stockportfolio.valuation.forecast.ForecastArchetype;
import com.stockportfolio.valuation.forecast.ForecastScenarioOverride;
import com.stockportfolio.valuation.forecast.ForecastScenarioEnvelope;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Creates versioned, company-anchored explicit-forecast templates. The classifier is
 * deliberately advisory: it never changes a saved valuation or chooses a model silently.
 */
@Service
public class ForecastArchitectureService {
    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);
    public static final String TEMPLATE_VERSION = "forecast-archetype-3.0.0";
    private static final List<String> SCENARIOS = List.of("BEAR", "BASE", "BULL");

    private final PositionRepository positions;
    private final EarningsHistoryRepository earnings;
    private final ExplicitOperatingForecastService explicitForecast;
    private final ForecastScenarioSnapshotRepository snapshots;
    private final ObjectMapper objectMapper;

    public ForecastArchitectureService(PositionRepository positions, EarningsHistoryRepository earnings,
                                       ExplicitOperatingForecastService explicitForecast,
                                       ForecastScenarioSnapshotRepository snapshots, ObjectMapper objectMapper) {
        this.positions = positions;
        this.earnings = earnings;
        this.explicitForecast = explicitForecast;
        this.snapshots = snapshots;
        this.objectMapper = objectMapper;
    }

    public ForecastTemplateResponse template(String rawSymbol) {
        Inputs inputs = inputs(rawSymbol);
        if (inputs.financial()) return new ForecastTemplateResponse(inputs.symbol(), "FINANCIALS_EXCLUDED", null,
                "UNAVAILABLE", List.of("Financial companies require a dedicated bank/insurance valuation model."),
                List.of(), TEMPLATE_VERSION, "NOT_APPLICABLE", "CURRENT_DILUTED_SHARES_NO_BUYBACK",
                inputs.shares(), Map.of(), null, "NONE", inputs.temporalContext());
        if (!inputs.complete()) return new ForecastTemplateResponse(inputs.symbol(), "UNAVAILABLE", null,
                "UNAVAILABLE", inputs.missing(), List.of(), TEMPLATE_VERSION, "ASSUMPTION_REQUIRED",
                "CURRENT_DILUTED_SHARES_NO_BUYBACK", inputs.shares(), Map.of(), null, "NONE", inputs.temporalContext());

        Recommendation recommendation = recommend(inputs);
        Map<ForecastArchetype, ForecastTemplateResponse.Template> templates = new LinkedHashMap<>();
        for (ForecastArchetype archetype : ForecastArchetype.values()) templates.put(archetype, buildTemplate(inputs, archetype));
        return new ForecastTemplateResponse(inputs.symbol(), "AVAILABLE", recommendation.archetype(), recommendation.confidence(),
                recommendation.reasons(), recommendation.alternatives(), TEMPLATE_VERSION, "ASSUMPTION_REQUIRED",
                "CURRENT_DILUTED_SHARES_NO_BUYBACK", inputs.shares(), templates, savedSnapshot(inputs.symbol()),
                snapshots.findBySymbol(inputs.symbol()).isPresent() ? "SAVED_SNAPSHOT" : "NONE", inputs.temporalContext());
    }

    public ForecastPreviewResponse preview(String rawSymbol, ForecastPreviewRequest request) {
        if (request == null || request.archetype() == null) throw new ResponseStatusException(BAD_REQUEST, "archetype is required");
        ForecastTemplateResponse response = template(rawSymbol);
        if (!"AVAILABLE".equals(response.eligibility())) throw new ResponseStatusException(BAD_REQUEST,
                String.join(" ", response.reasons()));
        ForecastTemplateResponse.Template template = response.templates().get(request.archetype());
        DebtFinancingPolicy policy = request.debtFinancingPolicy() == null ? template.debtFinancingPolicy() : request.debtFinancingPolicy();
        Map<String, ExplicitOperatingForecastResult> results = new LinkedHashMap<>();
        Map<String, ForecastScenarioOverride> overrides = request.scenarios() == null ? Map.of() : request.scenarios();
        for (String type : SCENARIOS) {
            ForecastScenarioOverride selected = overrides.get(type);
            ForecastScenarioOverride fallback = template.scenarios().get(type);
            ForecastScenarioOverride drivers = selected == null ? fallback : merge(selected, fallback);
            ExplicitOperatingForecastRequest base = template.baseInputs();
            ExplicitOperatingForecastRequest forecastRequest = new ExplicitOperatingForecastRequest(
                    base.startingRevenue(), base.openingGrossDebt(), base.currentNetDebt(), base.waccRate(),
                    base.costOfEquityRate(), base.pretaxCostOfDebtRate(), base.terminalGrowthRate(),
                    drivers.explicitOperatingDrivers(), drivers.terminalOperatingDriver(), policy, base.targetEquityValue());
            results.put(type, explicitForecast.forecast(forecastRequest));
        }
        return new ForecastPreviewResponse(response.symbol(), "EXPLICIT_OPERATING_FORECAST", request.archetype(),
                "READY_WITH_CAVEATS", List.of("changeInNetWorkingCapital is an explicit analyst assumption until the detailed indirect-CFO bridge is complete."),
                TEMPLATE_VERSION, response.sharesPolicy(), response.sharesOutstanding(), results, response.temporalContext());
    }

    public ForecastPreviewResponse saveSnapshot(String rawSymbol, ForecastPreviewRequest request) {
        if (request == null || request.archetype() == null) throw new ResponseStatusException(BAD_REQUEST, "archetype is required");
        ForecastTemplateResponse currentTemplate = template(rawSymbol);
        ForecastTemplateResponse.Template selectedTemplate = currentTemplate.templates().get(request.archetype());
        if (selectedTemplate == null) throw new ResponseStatusException(BAD_REQUEST, "Unknown forecast archetype");
        ForecastPreviewRequest resolved = new ForecastPreviewRequest(request.archetype(),
                request.scenarios() == null ? selectedTemplate.scenarios() : request.scenarios(),
                request.debtFinancingPolicy() == null ? selectedTemplate.debtFinancingPolicy() : request.debtFinancingPolicy());
        ForecastPreviewResponse preview = preview(rawSymbol, resolved);
        String symbol = preview.symbol();
        ForecastScenarioSnapshot row = snapshots.findBySymbol(symbol).orElseGet(ForecastScenarioSnapshot::new);
        row.setSymbol(symbol);
        row.setArchetype(resolved.archetype().name());
        row.setTemplateVersion(TEMPLATE_VERSION);
        try {
            row.setSnapshotJson(objectMapper.writeValueAsString(new ForecastScenarioEnvelope(
                    "EXPLICIT_OPERATING_FORECAST", resolved.archetype(), TEMPLATE_VERSION, "USER_CONFIRMED_TEMPLATE", resolved)));
        } catch (Exception e) { throw new ResponseStatusException(BAD_REQUEST, "Unable to save forecast snapshot", e); }
        snapshots.save(row);
        return preview;
    }

    public void resetSnapshot(String rawSymbol) {
        String symbol = rawSymbol == null ? "" : rawSymbol.trim().toUpperCase(Locale.ROOT);
        snapshots.findBySymbol(symbol).ifPresent(snapshots::delete);
    }

    /** A saved v3 envelope is immutable until the user explicitly resets or saves it again. */
    public Optional<ForecastPreviewResponse> savedPreview(String rawSymbol) {
        String symbol = rawSymbol == null ? "" : rawSymbol.trim().toUpperCase(Locale.ROOT);
        ForecastPreviewRequest request = savedSnapshot(symbol);
        return request == null ? Optional.empty() : Optional.of(preview(symbol, request));
    }

    private ForecastPreviewRequest savedSnapshot(String symbol) {
        return snapshots.findBySymbol(symbol).map(row -> {
            try { return objectMapper.readValue(row.getSnapshotJson(), ForecastScenarioEnvelope.class).request(); }
            catch (Exception ignored) { return null; }
        }).orElse(null);
    }

    private ForecastScenarioOverride merge(ForecastScenarioOverride requested, ForecastScenarioOverride fallback) {
        return new ForecastScenarioOverride(requested.explicitOperatingDrivers() == null ? fallback.explicitOperatingDrivers() : requested.explicitOperatingDrivers(),
                requested.terminalOperatingDriver() == null ? fallback.terminalOperatingDriver() : requested.terminalOperatingDriver());
    }

    private ForecastTemplateResponse.Template buildTemplate(Inputs i, ForecastArchetype archetype) {
        ArchetypeProfile p = profile(archetype);
        BigDecimal terminalGrowth = p.terminalGrowth();
        BigDecimal targetMargin = archetype == ForecastArchetype.CYCLICAL_CAPITAL_INTENSIVE
                ? i.midCycleEbitMargin() : clamp(i.ebitMargin().add(p.marginExpansion()), new BigDecimal("0.02"), new BigDecimal("0.55"));
        BigDecimal terminalCapex = archetype == ForecastArchetype.CYCLICAL_CAPITAL_INTENSIVE
                ? i.midCycleCapexRate() : clamp(i.capexRate().add(p.capexAdjustment()), new BigDecimal("0.01"), new BigDecimal("0.25"));
        TerminalOperatingDriver terminal = new TerminalOperatingDriver(targetMargin, i.taxRate(), i.daRate(), terminalCapex, BigDecimal.ZERO);
        Map<String, ForecastScenarioOverride> scenarios = new LinkedHashMap<>();
        scenarios.put("BEAR", drivers(i, p, terminal, new BigDecimal("-1")));
        scenarios.put("BASE", drivers(i, p, terminal, BigDecimal.ZERO));
        scenarios.put("BULL", drivers(i, p, terminal, BigDecimal.ONE));
        ExplicitOperatingForecastRequest base = new ExplicitOperatingForecastRequest(i.ttmRevenue(), i.debt(), i.netDebt(),
                i.wacc(), i.costOfEquity(), new BigDecimal("0.035"), terminalGrowth, scenarios.get("BASE").explicitOperatingDrivers(),
                terminal, DebtFinancingPolicy.targetDebtFinancingRatio(p.debtFinancingRatio()), i.targetEquityValue());
        String label = archetype == ForecastArchetype.CONSUMER_BRAND ? "Revenue growth (volume × price/mix proxy)" : "Revenue growth";
        return new ForecastTemplateResponse.Template(archetype, p.description(), label, scenarios,
                DebtFinancingPolicy.targetDebtFinancingRatio(p.debtFinancingRatio()), base,
                List.of("No buyback forecast: per-share value uses current diluted shares.",
                        "ΔNWC is neutral by default and must be reviewed against the detailed indirect-CFO bridge."));
    }

    private ForecastScenarioOverride drivers(Inputs i, ArchetypeProfile p, TerminalOperatingDriver terminal, BigDecimal direction) {
        boolean bear = direction.signum() < 0;
        BigDecimal growthShock = bear ? p.bearGrowthShock() : direction.signum() > 0 ? p.bullGrowthShock() : BigDecimal.ZERO;
        BigDecimal marginShock = bear ? p.bearMarginShock() : direction.signum() > 0 ? p.bullMarginShock() : BigDecimal.ZERO;
        BigDecimal reinvestmentShock = bear ? p.bearReinvestmentShock() : BigDecimal.ZERO;
        BigDecimal y1 = clamp(i.revenueGrowth().add(growthShock), new BigDecimal("-0.15"), new BigDecimal("0.45"));
        BigDecimal y5 = clamp(terminalGrowth(p).add(p.yearFiveGrowthPremium()).add(growthShock.multiply(new BigDecimal("0.35"), MC)),
                new BigDecimal("-0.05"), new BigDecimal("0.20"));
        BigDecimal startMargin = clamp(i.ebitMargin().add(marginShock), new BigDecimal("-0.10"), new BigDecimal("0.55"));
        BigDecimal endMargin = clamp(terminal.ebitMargin().add(marginShock.multiply(new BigDecimal("0.45"), MC)), new BigDecimal("-0.10"), new BigDecimal("0.55"));
        List<OperatingDriver> years = new ArrayList<>();
        for (int year = 1; year <= 5; year++) {
            BigDecimal weight = BigDecimal.valueOf(year - 1).divide(BigDecimal.valueOf(4), MC);
            years.add(new OperatingDriver(interpolate(y1, y5, weight), interpolate(startMargin, endMargin, weight),
                    i.taxRate(), i.daRate(), clamp(i.capexRate().add(reinvestmentShock), new BigDecimal("0.005"), new BigDecimal("0.30")), BigDecimal.ZERO));
        }
        TerminalOperatingDriver adjustedTerminal = new TerminalOperatingDriver(endMargin, terminal.taxRate(), terminal.depreciationAndAmortizationRate(),
                clamp(terminal.capexRate().add(reinvestmentShock.multiply(new BigDecimal("0.35"), MC)), new BigDecimal("0.005"), new BigDecimal("0.30")), BigDecimal.ZERO);
        return new ForecastScenarioOverride(List.copyOf(years), adjustedTerminal);
    }

    private BigDecimal terminalGrowth(ArchetypeProfile p) { return p.terminalGrowth(); }
    private BigDecimal interpolate(BigDecimal a, BigDecimal b, BigDecimal w) { return a.add(b.subtract(a, MC).multiply(w, MC), MC); }
    private BigDecimal clamp(BigDecimal value, BigDecimal low, BigDecimal high) { return value.max(low).min(high); }

    private Recommendation recommend(Inputs i) {
        List<String> reasons = new ArrayList<>();
        ForecastArchetype selected;
        if (i.revenueGrowth().compareTo(new BigDecimal("0.20")) >= 0) {
            selected = ForecastArchetype.HIGH_GROWTH; reasons.add("Revenue growth is at least 20%.");
        } else if ((i.revenueVolatility().compareTo(new BigDecimal("0.10")) >= 0 || i.marginVolatility().compareTo(new BigDecimal("0.08")) >= 0)
                && i.capexRate().compareTo(new BigDecimal("0.08")) >= 0) {
            selected = ForecastArchetype.CYCLICAL_CAPITAL_INTENSIVE; reasons.add("Revenue/margin volatility and capital intensity indicate a cycle-sensitive cash-flow profile.");
        } else if (i.revenueGrowth().compareTo(new BigDecimal("0.05")) <= 0 && i.revenueVolatility().compareTo(new BigDecimal("0.05")) <= 0
                && i.marginVolatility().compareTo(new BigDecimal("0.05")) <= 0) {
            selected = ForecastArchetype.STABLE_MATURE; reasons.add("Low growth and low operating volatility indicate a stable mature profile.");
        } else if (i.capexRate().compareTo(new BigDecimal("0.07")) <= 0 && i.grossMargin().compareTo(new BigDecimal("0.35")) >= 0) {
            selected = ForecastArchetype.MATURE_TECH_PLATFORM; reasons.add("Asset-light economics and high gross margin indicate a mature platform profile.");
        } else {
            selected = ForecastArchetype.CONSUMER_BRAND; reasons.add("Revenue and margin profile fit the consumer/brand template; revenue is a volume × price/mix proxy.");
        }
        String sector = i.sector().toLowerCase(Locale.ROOT);
        if (selected == ForecastArchetype.CONSUMER_BRAND && (sector.contains("technology") || sector.contains("communication"))) {
            selected = ForecastArchetype.MATURE_TECH_PLATFORM; reasons.add("Sector is used only as a tie-breaker for the otherwise similar mature profiles.");
        }
        ForecastArchetype recommendation = selected;
        List<ForecastArchetype> alternatives = Arrays.stream(ForecastArchetype.values()).filter(value -> value != recommendation).toList();
        return new Recommendation(selected, reasons.size() >= 2 ? "HIGH" : "MEDIUM", List.copyOf(reasons), alternatives);
    }

    private Inputs inputs(String rawSymbol) {
        String symbol = rawSymbol == null ? "" : rawSymbol.trim().toUpperCase(Locale.ROOT);
        Position position = positions.findBySymbolIgnoreCase(symbol).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Position not found"));
        List<EarningsHistory> rows = earnings.findBySymbolAndAsOfDateLessThanEqualOrderByAsOfDateAsc(symbol, LocalDate.now());
        List<String> missing = new ArrayList<>();
        if (position.getSharesOutstanding() == null || position.getSharesOutstanding().signum() <= 0) missing.add("sharesOutstanding");
        if (rows.size() < 8) missing.add("eightQuarterFundamentalHistory");
        ForecastTemporalContext temporalContext = ForecastTemporalContext.fromLatestQuarters(
                rows.size() < 4 ? List.of() : rows.subList(rows.size() - 4, rows.size())
        );
        if (!missing.isEmpty()) return Inputs.incomplete(symbol, position.getSector(), position.getSharesOutstanding(), missing, temporalContext);
        List<EarningsHistory> latest = rows.subList(rows.size() - 4, rows.size());
        List<EarningsHistory> prior = rows.subList(rows.size() - 8, rows.size() - 4);
        BigDecimal revenue = sum(latest, EarningsHistory::getRevenue);
        BigDecimal priorRevenue = sum(prior, EarningsHistory::getRevenue);
        BigDecimal operating = sum(latest, EarningsHistory::getOperatingIncome);
        BigDecimal gross = sum(latest, EarningsHistory::getGrossProfit);
        BigDecimal capex = sum(latest, EarningsHistory::getCapex);
        BigDecimal da = sum(latest, EarningsHistory::getDepreciationAmortization);
        if (revenue == null || priorRevenue == null || operating == null || gross == null || capex == null || da == null || revenue.signum() <= 0 || priorRevenue.signum() <= 0) {
            missing.add("revenue/operatingIncome/capex/depreciation history"); return Inputs.incomplete(symbol, position.getSector(), position.getSharesOutstanding(), missing, temporalContext);
        }
        EarningsHistory end = latest.getLast();
        BigDecimal debt = nvl(end.getTotalDebt());
        BigDecimal netDebt = debt.subtract(nvl(end.getCashAndEquivalents()), MC).subtract(nvl(end.getShortTermInvestments()), MC).subtract(nvl(end.getNoncurrentMarketableSecurities()), MC);
        BigDecimal tax = effectiveTax(latest);
        BigDecimal coe = new BigDecimal("0.0469").add(nvl(position.getBeta()).max(BigDecimal.ONE).multiply(new BigDecimal("0.05"), MC), MC);
        BigDecimal marketCap = nvl(position.getLatestPrice()).multiply(position.getSharesOutstanding(), MC);
        BigDecimal debtWeight = marketCap.signum() <= 0 ? new BigDecimal("0.02") : debt.divide(debt.add(marketCap, MC), MC).min(new BigDecimal("0.30"));
        BigDecimal wacc = coe.multiply(BigDecimal.ONE.subtract(debtWeight, MC), MC).add(new BigDecimal("0.035").multiply(BigDecimal.ONE.subtract(tax, MC), MC).multiply(debtWeight, MC), MC);
        return new Inputs(symbol, position.getSector() == null ? "" : position.getSector(), position.getSharesOutstanding(), true, List.of(),
                revenue, revenue.divide(priorRevenue, MC).subtract(BigDecimal.ONE), operating.divide(revenue, MC), gross.divide(revenue, MC),
                capex.divide(revenue, MC), da.divide(revenue, MC), tax, debt, netDebt, coe, wacc,
                marketCap, standardDeviation(revenueGrowthSeries(rows)), standardDeviation(marginSeries(rows)),
                median(marginSeries(rows)), median(capexRateSeries(rows)), temporalContext);
    }

    private BigDecimal effectiveTax(List<EarningsHistory> rows) {
        BigDecimal tax = sum(rows, EarningsHistory::getTaxProvision), pretax = sum(rows, EarningsHistory::getPretaxIncome);
        if (tax == null || pretax == null || pretax.signum() <= 0) return new BigDecimal("0.21");
        return clamp(tax.divide(pretax, MC), BigDecimal.ZERO, new BigDecimal("0.35"));
    }
    private List<BigDecimal> revenueGrowthSeries(List<EarningsHistory> rows) {
        List<BigDecimal> values = new ArrayList<>(); for (int i = 4; i < rows.size(); i++) if (rows.get(i).getRevenue() != null && rows.get(i - 4).getRevenue() != null && rows.get(i - 4).getRevenue().signum() > 0) values.add(rows.get(i).getRevenue().divide(rows.get(i - 4).getRevenue(), MC).subtract(BigDecimal.ONE)); return values;
    }
    private List<BigDecimal> marginSeries(List<EarningsHistory> rows) {
        List<BigDecimal> values = new ArrayList<>(); for (EarningsHistory row : rows) if (row.getOperatingIncome() != null && row.getRevenue() != null && row.getRevenue().signum() > 0) values.add(row.getOperatingIncome().divide(row.getRevenue(), MC)); return values;
    }
    private List<BigDecimal> capexRateSeries(List<EarningsHistory> rows) {
        List<BigDecimal> values = new ArrayList<>(); for (EarningsHistory row : rows) if (row.getCapex() != null && row.getRevenue() != null && row.getRevenue().signum() > 0) values.add(row.getCapex().divide(row.getRevenue(), MC)); return values;
    }
    private BigDecimal median(List<BigDecimal> values) { if (values.isEmpty()) return BigDecimal.ZERO; List<BigDecimal> sorted = values.stream().sorted().toList(); int middle = sorted.size() / 2; return sorted.size() % 2 == 1 ? sorted.get(middle) : sorted.get(middle - 1).add(sorted.get(middle), MC).divide(BigDecimal.valueOf(2), MC); }
    private BigDecimal standardDeviation(List<BigDecimal> values) { if (values.size() < 2) return BigDecimal.ZERO; BigDecimal mean = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal.valueOf(values.size()), MC); BigDecimal sum = values.stream().map(v -> v.subtract(mean, MC).pow(2, MC)).reduce(BigDecimal.ZERO, BigDecimal::add); return sqrt(sum.divide(BigDecimal.valueOf(values.size()), MC)); }
    private BigDecimal sqrt(BigDecimal value) { return BigDecimal.valueOf(Math.sqrt(value.doubleValue())); }
    private BigDecimal sum(List<EarningsHistory> rows, java.util.function.Function<EarningsHistory, BigDecimal> getter) { BigDecimal total = BigDecimal.ZERO; for (EarningsHistory row : rows) { BigDecimal value = getter.apply(row); if (value == null) return null; total = total.add(value, MC); } return total; }
    private BigDecimal nvl(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }

    private ArchetypeProfile profile(ForecastArchetype value) {
        return switch (value) {
            case HIGH_GROWTH -> new ArchetypeProfile("Revenue growth fades while operating margin expands and reinvestment remains elevated.", new BigDecimal("0.03"), new BigDecimal("0.05"), new BigDecimal("0.015"), new BigDecimal("0.25"), new BigDecimal("0.08"), new BigDecimal("-0.08"), new BigDecimal("-0.05"), new BigDecimal("0.03"), new BigDecimal("0.05"), new BigDecimal("0.03"));
            case MATURE_TECH_PLATFORM -> new ArchetypeProfile("Asset-light mature platform with moderate growth and stable cash conversion.", new BigDecimal("0.025"), new BigDecimal("0.02"), BigDecimal.ZERO, new BigDecimal("0.15"), new BigDecimal("0.04"), new BigDecimal("-0.04"), new BigDecimal("-0.02"), new BigDecimal("0.01"), new BigDecimal("0.02"), new BigDecimal("0.01"));
            case CONSUMER_BRAND -> new ArchetypeProfile("Revenue uses a volume × price/mix proxy with stable reinvestment.", new BigDecimal("0.025"), new BigDecimal("0.01"), new BigDecimal("0.005"), new BigDecimal("0.10"), new BigDecimal("0.03"), new BigDecimal("-0.03"), new BigDecimal("-0.02"), new BigDecimal("0.01"), new BigDecimal("0.02"), new BigDecimal("0.01"));
            case CYCLICAL_CAPITAL_INTENSIVE -> new ArchetypeProfile("Growth, margin and capital spending converge to a mid-cycle normal state.", new BigDecimal("0.025"), BigDecimal.ZERO, new BigDecimal("0.02"), new BigDecimal("0.20"), new BigDecimal("0.05"), new BigDecimal("-0.05"), new BigDecimal("-0.05"), new BigDecimal("0.02"), new BigDecimal("0.03"), new BigDecimal("0.01"));
            case STABLE_MATURE -> new ArchetypeProfile("Low-growth mature cash-flow profile with stable margin and terminal-value sensitivity.", new BigDecimal("0.02"), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("0.08"), new BigDecimal("0.015"), new BigDecimal("-0.015"), new BigDecimal("-0.01"), new BigDecimal("0.005"), new BigDecimal("0.01"), new BigDecimal("0.005"));
        };
    }

    private record ArchetypeProfile(String description, BigDecimal terminalGrowth, BigDecimal marginExpansion, BigDecimal capexAdjustment,
                                    BigDecimal debtFinancingRatio, BigDecimal yearFiveGrowthPremium, BigDecimal bearGrowthShock,
                                    BigDecimal bearMarginShock, BigDecimal bearReinvestmentShock, BigDecimal bullGrowthShock, BigDecimal bullMarginShock) { }
    private record Recommendation(ForecastArchetype archetype, String confidence, List<String> reasons, List<ForecastArchetype> alternatives) { }
    private record Inputs(String symbol, String sector, BigDecimal shares, boolean complete, List<String> missing, BigDecimal ttmRevenue,
                          BigDecimal revenueGrowth, BigDecimal ebitMargin, BigDecimal grossMargin, BigDecimal capexRate, BigDecimal daRate,
                          BigDecimal taxRate, BigDecimal debt, BigDecimal netDebt, BigDecimal costOfEquity, BigDecimal wacc,
                          BigDecimal targetEquityValue, BigDecimal revenueVolatility, BigDecimal marginVolatility,
                          BigDecimal midCycleEbitMargin, BigDecimal midCycleCapexRate,
                          ForecastTemporalContext temporalContext) {
        static Inputs incomplete(String symbol, String sector, BigDecimal shares, List<String> missing, ForecastTemporalContext temporalContext) { return new Inputs(symbol, sector == null ? "" : sector, shares, false, List.copyOf(missing), null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, temporalContext); }
        boolean financial() { String value = sector.toLowerCase(Locale.ROOT); return value.contains("financial") || value.contains("bank") || value.contains("insurance") || value.contains("capital markets") || value.contains("credit"); }
    }
}
