package com.stockportfolio.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockportfolio.dto.*;
import com.stockportfolio.model.EarningsHistory;
import com.stockportfolio.model.EconomicObservation;
import com.stockportfolio.model.Position;
import com.stockportfolio.model.PriceHistory;
import com.stockportfolio.model.ValuationScenario;
import com.stockportfolio.model.StockSplit;
import com.stockportfolio.repository.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.function.Function;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
@Transactional
public class ValuationService {
    private static final List<String> SCENARIO_TYPES = List.of("BEAR", "BASE", "BULL");

    private final PositionRepository positionRepository;
    private final EarningsHistoryRepository earningsRepository;
    private final PriceHistoryRepository priceRepository;
    private final ValuationScenarioRepository scenarioRepository;
    private final EarningsEstimateRepository earningsEstimateRepository;
    private final StockSplitRepository stockSplitRepository;
    private final EconomicDataService economicDataService;
    private final MarketAssumptionsService marketAssumptionsService;
    private final ReviewedDataResolver reviewedDataResolver;
    private final ValuationEngine engine;
    private final RealCapeCalculator realCapeCalculator;
    private final YahooFinancePriceService yahooFinancePriceService;
    private final ObjectMapper objectMapper;
    private final String engineVersion;
    private final BigDecimal equityRiskPremiumPct;
    private final ZoneId marketZone;

    public ValuationService(PositionRepository positionRepository,
                            EarningsHistoryRepository earningsRepository,
                            PriceHistoryRepository priceRepository,
                            ValuationScenarioRepository scenarioRepository,
                            EarningsEstimateRepository earningsEstimateRepository,
                            StockSplitRepository stockSplitRepository,
                            EconomicDataService economicDataService,
                            MarketAssumptionsService marketAssumptionsService,
                            ReviewedDataResolver reviewedDataResolver,
                            ValuationEngine engine,
                            RealCapeCalculator realCapeCalculator,
                            YahooFinancePriceService yahooFinancePriceService,
                            ObjectMapper objectMapper,
                            @Value("${app.valuation.engine-version:valuation-java-1.0.0}") String engineVersion,
                            @Value("${app.valuation.equity-risk-premium:5.0}") BigDecimal equityRiskPremiumPct,
                            @Value("${app.pricing.timezone:America/New_York}") String marketTimezone) {
        this.positionRepository = positionRepository;
        this.earningsRepository = earningsRepository;
        this.priceRepository = priceRepository;
        this.scenarioRepository = scenarioRepository;
        this.earningsEstimateRepository = earningsEstimateRepository;
        this.stockSplitRepository = stockSplitRepository;
        this.economicDataService = economicDataService;
        this.marketAssumptionsService = marketAssumptionsService;
        this.reviewedDataResolver = reviewedDataResolver;
        this.engine = engine;
        this.realCapeCalculator = realCapeCalculator;
        this.yahooFinancePriceService = yahooFinancePriceService;
        this.objectMapper = objectMapper;
        this.engineVersion = engineVersion;
        this.equityRiskPremiumPct = equityRiskPremiumPct;
        this.marketZone = ZoneId.of(marketTimezone);
    }

    @Transactional
    public ValuationResponse get(String rawSymbol) {
        Context context = context(rawSymbol, null);
        List<ValuationScenarioResponse> scenarios = SCENARIO_TYPES.stream()
                .map(type -> scenario(type, context, null, false))
                .toList();
        return response(context, scenarios);
    }

    @Transactional
    public ValuationEvaluationResponse evaluate(String rawSymbol, ValuationEvaluateRequest request) {
        String type = scenarioType(request.scenarioType());
        ValuationAssumptions settings = request.assumptions() == null ? engine.defaultSettings(type) : engine.normalizeLegacy(request.assumptions());
        Context context = context(rawSymbol, settings.taxRateOverridePct());
        ValuationScenarioResponse result = engine.evaluateSettings(type, "EVALUATED", settings,
                context.selection(), context.market(), context.growth().inputs(), null);
        if (!result.valid()) throw badRequest(result.warnings());
        return new ValuationEvaluationResponse(context.symbol(), engineVersion, result,
                engine.sensitivity(type, result.resolvedAssumptions(), context.selection(), context.market()),
                engine.reverse(type, result.resolvedAssumptions(), context.selection(), context.market()),
                diagnostics(context, result));
    }

    public ValuationScenarioResponse save(String rawSymbol, String rawType, ValuationSaveRequest request) {
        String symbol = normalize(rawSymbol);
        String type = scenarioType(rawType);
        if (request.modelMode() != null && !"AUTO".equalsIgnoreCase(request.modelMode()))
            throw new ResponseStatusException(BAD_REQUEST, "modelMode must be AUTO");
        ValuationAssumptions settings = request.assumptions() == null ? engine.defaultSettings(type) : engine.normalizeLegacy(request.assumptions());
        Context context = context(symbol, settings.taxRateOverridePct());
        ValuationScenarioResponse evaluated = engine.evaluateSettings(type, "SAVED", settings,
                context.selection(), context.market(), context.growth().inputs(), null);
        if (!evaluated.valid()) throw badRequest(evaluated.warnings());
        ValuationScenario entity = scenarioRepository.findBySymbolAndScenarioType(symbol, type).orElseGet(ValuationScenario::new);
        entity.setSymbol(symbol);
        entity.setScenarioType(type);
        entity.setModelMode("AUTO");
        entity.setEngineVersion(engineVersion);
        try { entity.setAssumptionsJson(objectMapper.writeValueAsString(settings)); }
        catch (JsonProcessingException e) { throw new ResponseStatusException(BAD_REQUEST, "Invalid assumptions", e); }
        ValuationScenario saved = scenarioRepository.save(entity);
        return engine.evaluateSettings(type, "SAVED", settings, context.selection(), context.market(),
                context.growth().inputs(), saved.getUpdatedAt());
    }

    public ValuationScenarioResponse reset(String rawSymbol, String rawType) {
        String symbol = normalize(rawSymbol);
        String type = scenarioType(rawType);
        if (positionRepository.findBySymbolIgnoreCase(symbol).isEmpty()) throw new ResponseStatusException(NOT_FOUND, "Position not found");
        scenarioRepository.deleteBySymbolAndScenarioType(symbol, type);
        scenarioRepository.flush();
        Context context = context(symbol, null);
        return scenario(type, context, null, true);
    }

    private ValuationResponse response(Context c, List<ValuationScenarioResponse> scenarios) {
        Map<String, ValuationScenarioResponse> byType = new HashMap<>();
        scenarios.forEach(s -> byType.put(s.scenarioType(), s));
        BigDecimal bear = value(byType.get("BEAR"));
        BigDecimal base = value(byType.get("BASE"));
        BigDecimal bull = value(byType.get("BULL"));
        BigDecimal low = bear == null || bull == null ? null : bear.min(bull);
        BigDecimal high = bear == null || bull == null ? null : bear.max(bull);
        ValuationResponse.DataQuality quality = quality(c, scenarios);
        List<ValuationResponse.Diagnostic> diagnostics = diagnostics(c, byType.get("BASE"));
        return new ValuationResponse(c.symbol(), engineVersion, LocalDate.now(marketZone), c.priceDate(),
                c.financialDate(), c.filingDate(), c.cape().cpiDate(), c.applicability(), quality,
                c.selection().model(), new ValuationResponse.Overview(bear, base, bull, low, high, c.market().currentPrice()),
                scenarios, c.growth().references(), c.cape().summary(), cashFlow(c), capitalEfficiency(c), grossMargin(c), diagnostics,
                c.selection().missingFields(), c.fieldSources());
    }

    private Context context(String rawSymbol, BigDecimal taxOverridePct) {
        String symbol = normalize(rawSymbol);
        Position position = positionRepository.findBySymbolIgnoreCase(symbol)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Position not found"));
        LocalDate today = LocalDate.now(marketZone);
        List<EarningsHistory> raw = earningsRepository.findBySymbolAndAsOfDateBetweenOrderByAsOfDateAsc(symbol, today.minusYears(16), today);
        Map<String, Map<String, Object>> corrections = reviewedDataResolver.correctedValues("fundamentals", raw.stream().map(EarningsHistory::getId).toList());
        Set<String> rejected = reviewedDataResolver.rejectedRecordIds("fundamentals", raw.stream().map(EarningsHistory::getId).toList());
        List<EarningsHistory> acceptedRows = raw.stream()
                .filter(row -> !rejected.contains(String.valueOf(row.getId())))
                .toList();
        List<ValuationEngine.Quarter> quarters = acceptedRows.stream()
                .map(row -> quarter(row, corrections.getOrDefault(String.valueOf(row.getId()), Map.of())))
                .toList();
        List<PriceHistory> prices = priceRepository.findBySymbolAndTradeDateBetweenOrderByTradeDateAsc(symbol, today.minusYears(16), today);
        PriceHistory latestPrice = prices.isEmpty() ? null : prices.get(prices.size() - 1);
        BigDecimal currentPrice = latestPrice == null ? position.getLatestPrice() : latestPrice.getClosePrice();
        LocalDate priceDate = latestPrice == null ? (position.getPriceUpdatedAt() == null ? null : position.getPriceUpdatedAt().toLocalDate()) : latestPrice.getTradeDate();

        MarketAssumptionsResponse assumptions;
        try { assumptions = marketAssumptionsService.getMarketAssumptions(symbol); }
        catch (RuntimeException e) { assumptions = new MarketAssumptionsResponse(symbol, null, "10Y", null, "U.S. Treasury", position.getBeta(), position.getBetaSource(), List.of(e.getMessage())); }
        BigDecimal beta = position.getBeta() == null ? assumptions.beta() : position.getBeta();
        ValuationEngine.MarketInputs market = new ValuationEngine.MarketInputs(currentPrice,
                position.getEffectiveSharesOutstanding(), assumptions.riskFreeRate(), beta, equityRiskPremiumPct);
        ValuationEngine.Selection selection = engine.select(quarters, market, taxOverridePct);
        GrowthBundle growth = growth(symbol, selection);
        ValuationResponse.Applicability applicability = applicability(position, quarters, selection);
        List<StockSplit> splits = stockSplits(symbol, today.minusYears(20), today);
        CapeComputation cape = cape(position, quarters, prices, currentPrice, priceDate, isOperatingCompany(position), splits);
        LocalDate financialDate = quarters.isEmpty() ? null : quarters.get(quarters.size() - 1).periodEnd();
        LocalDate filingDate = quarters.stream().map(ValuationEngine.Quarter::filingDate).filter(Objects::nonNull).max(LocalDate::compareTo).orElse(null);
        EarningsHistory latestAccepted = acceptedRows.isEmpty() ? null : acceptedRows.get(acceptedRows.size() - 1);
        Map<String, Object> latestOverrides = latestAccepted == null ? Map.of()
                : corrections.getOrDefault(String.valueOf(latestAccepted.getId()), Map.of());
        return new Context(symbol, position, quarters, prices, market, selection, growth, applicability, cape, priceDate,
                financialDate, filingDate, valuationFieldSources(latestAccepted, latestOverrides));
    }

    private ValuationScenarioResponse scenario(String type, Context c, ValuationAssumptions direct, boolean forceDefault) {
        ValuationScenario persisted = forceDefault ? null : scenarioRepository.findBySymbolAndScenarioType(c.symbol(), type).orElse(null);
        ValuationAssumptions assumptions = direct;
        String origin = "DEFAULT";
        if (assumptions == null && persisted != null) {
            try { assumptions = objectMapper.readValue(persisted.getAssumptionsJson(), ValuationAssumptions.class); origin = "SAVED"; }
            catch (JsonProcessingException ignored) { }
        }
        if (assumptions == null) assumptions = engine.defaultSettings(type);
        return engine.evaluateSettings(type, origin, assumptions, c.selection(), c.market(), c.growth().inputs(),
                persisted == null ? null : persisted.getUpdatedAt());
    }

    private ValuationResponse.Applicability applicability(Position p, List<ValuationEngine.Quarter> quarters, ValuationEngine.Selection selection) {
        List<String> reasons = new ArrayList<>();
        if (!"EQUITY".equalsIgnoreCase(p.getAssetClass())) reasons.add("Asset class is not equity.");
        if (!"COMMON_STOCK".equalsIgnoreCase(p.getInstrumentType())) reasons.add("Instrument is not common stock.");
        if (p.getSector() == null || p.getSector().isBlank()) reasons.add("Sector classification is missing.");
        else if (p.getSector().toLowerCase(Locale.ROOT).contains("financial")) reasons.add("Current cash-flow DCF is not applicable to financial companies.");
        String financialCurrency = quarters.stream().map(ValuationEngine.Quarter::currencyCode).filter(Objects::nonNull).reduce((a, b) -> b).orElse(null);
        if (p.getQuoteCurrency() != null && financialCurrency != null && !p.getQuoteCurrency().equalsIgnoreCase(financialCurrency))
            reasons.add("Quote and financial-statement currencies differ.");
        if (!selection.available()) reasons.add("No legal FCFF or FCFE model can be selected.");
        return new ValuationResponse.Applicability(reasons.isEmpty(), reasons.isEmpty() ? "AVAILABLE" : "UNAVAILABLE", reasons);
    }

    private boolean isOperatingCompany(Position p) {
        return "EQUITY".equalsIgnoreCase(p.getAssetClass())
                && "COMMON_STOCK".equalsIgnoreCase(p.getInstrumentType())
                && p.getSector() != null
                && !p.getSector().isBlank()
                && !p.getSector().toLowerCase(Locale.ROOT).contains("financial");
    }

    private Map<String, FieldSourceResponse> valuationFieldSources(EarningsHistory row, Map<String, Object> overrides) {
        if (row == null) return Map.of();
        LinkedHashMap<String, FieldSourceResponse> result = new LinkedHashMap<>();
        putSource(result, "dilutedEps", row.getDilutedEps(), row, overrides, false);
        putSource(result, "cashFlow", row.getCashFlow(), row, overrides, false);
        putSource(result, "capex", row.getCapex(), row, overrides, false);
        putSource(result, "interestExpense", row.getInterestExpense(), row, overrides, false);
        putSource(result, "netBorrowing", row.getNetBorrowing(), row, overrides, false);
        putSource(result, "depreciationAmortization", row.getDepreciationAmortization(), row, overrides, false);
        putSource(result, "changeInWorkingCapital", row.getChangeInWorkingCapital(), row, overrides, false);
        putSource(result, "operatingIncome", row.getOperatingIncome(), row, overrides, false);
        putSource(result, "taxProvision", row.getTaxProvision(), row, overrides, false);
        putSource(result, "pretaxIncome", row.getPretaxIncome(), row, overrides, false);
        putSource(result, "stockholdersEquity", row.getStockholdersEquity(), row, overrides, false);
        putSource(result, "totalDebt", row.getTotalDebt(), row, overrides, false);
        putSource(result, "cashAndEquivalents", row.getCashAndEquivalents(), row, overrides, false);
        putSource(result, "shortTermInvestments", row.getShortTermInvestments(), row, overrides, false);
        putSource(result, "noncurrentMarketableSecurities", row.getNoncurrentMarketableSecurities(), row, overrides, false);
        putSource(result, "investedCapital", row.getInvestedCapital(), row, overrides, false);
        putSource(result, "roe", row.getRoe(), row, overrides, true);
        putSource(result, "roic", row.getRoic(), row, overrides, true);
        putSource(result, "grossMargin", row.getGrossMargin(), row, overrides, true);
        return Collections.unmodifiableMap(result);
    }

    private void putSource(Map<String, FieldSourceResponse> target, String field, Object value,
                           EarningsHistory row, Map<String, Object> overrides, boolean derived) {
        if (value == null && !overrides.containsKey(field)) return;
        if (overrides.containsKey(field)) {
            target.put(field, new FieldSourceResponse("REVIEWED", "Manual reviewed correction", row.getAsOfDate(), null));
        } else if (derived) {
            target.put(field, new FieldSourceResponse("SYSTEM_DERIVED", "Java valuation engine", row.getAsOfDate(), null));
        } else if (row.getFilingDate() != null) {
            target.put(field, new FieldSourceResponse("SEC_COMPANY_FACTS", "SEC Company Facts", row.getFilingDate(), null));
        } else {
            target.put(field, new FieldSourceResponse("YAHOO_FALLBACK", "Yahoo Finance fallback", row.getAsOfDate(), null));
        }
    }

    private ValuationResponse.DataQuality quality(Context c, List<ValuationScenarioResponse> scenarios) {
        if (!c.applicability().applicable()) return new ValuationResponse.DataQuality("Unavailable", c.applicability().reasons());
        List<String> reasons = new ArrayList<>();
        BigDecimal diff = c.selection().crossCheckDifferencePct();
        String grade = "High";
        if (diff == null) { grade = "Medium"; reasons.add("FCFF cross-check is incomplete."); }
        else if (diff.compareTo(BigDecimal.valueOf(25)) > 0) { grade = "Low"; reasons.add("FCFF definitions differ by more than 25%."); }
        else if (diff.compareTo(BigDecimal.TEN) > 0) { grade = "Medium"; reasons.add("FCFF definitions differ by 10%–25%."); }
        if (c.selection().warnings().stream().anyMatch(w -> w.contains("Severe"))) grade = "Low";
        if (scenarios.stream().anyMatch(s -> s.manualOverrides() != null && !s.manualOverrides().isEmpty())) {
            grade = "Low";
            reasons.add("One or more scenarios use key user overrides.");
        }
        return new ValuationResponse.DataQuality(grade, reasons);
    }

    private CapeComputation cape(Position p, List<ValuationEngine.Quarter> quarters, List<PriceHistory> prices,
                                 BigDecimal currentPrice, LocalDate priceDate, boolean operatingCompany,
                                 List<StockSplit> splits) {
        if (!operatingCompany) return CapeComputation.unavailable("NOT_APPLICABLE", null, List.of("operatingCompany"));
        String currency = quarters.stream().map(ValuationEngine.Quarter::currencyCode).filter(Objects::nonNull).reduce((a, b) -> b).orElse(null);
        if (!"USD".equalsIgnoreCase(currency)) return CapeComputation.unavailable("NON_USD", null, List.of("USD financial currency"));
        economicDataService.refreshIfEmpty();
        Optional<EconomicObservation> latestOpt = economicDataService.latestCpi();
        if (latestOpt.isEmpty()) return CapeComputation.unavailable("MISSING_CPI", null, List.of("CPIAUCSL"));
        EconomicObservation latestCpi = latestOpt.get();
        if (realCapeCalculator.isCpiStale(latestCpi.getObservationDate(), LocalDate.now(marketZone)))
            return CapeComputation.unavailable("STALE_CPI", latestCpi.getObservationDate(), List.of("fresh CPIAUCSL"));
        if (quarters.size() < 40 || priceDate == null || currentPrice == null)
            return CapeComputation.unavailable("MISSING_DATA", latestCpi.getObservationDate(), List.of("40 diluted EPS quarters"));
        LocalDate cpiFrom = quarters.get(Math.max(0, quarters.size() - 64)).periodEnd().withDayOfMonth(1);
        Map<YearMonth, BigDecimal> cpi = new HashMap<>();
        economicDataService.cpiBetween(cpiFrom, latestCpi.getObservationDate()).forEach(row -> cpi.put(YearMonth.from(row.getObservationDate()), row.getValue()));
        List<ValuationResponse.CapePoint> history = new ArrayList<>();
        LocalDate firstSample = quarters.get(0).periodEnd().plusYears(10).with(TemporalAdjusters.lastDayOfMonth());
        LocalDate sample = quarterEnd(firstSample);
        LocalDate lastSample = completedQuarterEnd(priceDate);
        while (!sample.isAfter(lastSample)) {
            LocalDate asOf = sample;
            List<ValuationEngine.Quarter> published = quarters.stream()
                    .filter(q -> q.filingDate() != null && !q.filingDate().isAfter(asOf))
                    .sorted(Comparator.comparing(ValuationEngine.Quarter::periodEnd))
                    .toList();
            if (published.size() >= 40) {
                List<ValuationEngine.Quarter> window = published.subList(published.size() - 40, published.size());
                BigDecimal sampleCpi = cpiAtOrBefore(cpi, YearMonth.from(asOf));
                BigDecimal cape = continuousQuarterly(window)
                        ? realCapeCalculator.realPe(priceAtOrBefore(prices, asOf), window, 10, sampleCpi, cpi,
                        q -> splitAdjustedEps(q, asOf, splits))
                        : null;
                if (cape != null) history.add(new ValuationResponse.CapePoint(asOf, cape, 40));
            }
            sample = quarterEnd(sample.plusMonths(3));
        }
        List<ValuationEngine.Quarter> last40 = quarters.subList(quarters.size() - 40, quarters.size());
        boolean continuous = continuousQuarterly(last40);
        LocalDate valuationDate = priceDate == null ? LocalDate.now(marketZone) : priceDate;
        BigDecimal real10 = continuous ? realCapeCalculator.realPe(currentPrice, last40, 10, latestCpi.getValue(), cpi,
                q -> splitAdjustedEps(q, valuationDate, splits)) : null;
        BigDecimal real5 = continuous ? realCapeCalculator.realPe(currentPrice, last40.subList(20, 40), 5, latestCpi.getValue(), cpi,
                q -> splitAdjustedEps(q, valuationDate, splits)) : null;
        BigDecimal real3 = continuous ? realCapeCalculator.realPe(currentPrice, last40.subList(28, 40), 3, latestCpi.getValue(), cpi,
                q -> splitAdjustedEps(q, valuationDate, splits)) : null;
        List<ValuationEngine.Quarter> latestFour = quarters.subList(quarters.size() - 4, quarters.size());
        BigDecimal ttm = continuousQuarterly(latestFour) ? nominalTtmPe(currentPrice, latestFour, valuationDate, splits) : null;
        List<BigDecimal> prior = history.stream().map(ValuationResponse.CapePoint::cape).filter(Objects::nonNull).toList();
        BigDecimal percentile = realCapeCalculator.percentile(real10, prior);
        String status = real10 == null ? "NOT_MEANINGFUL" : "AVAILABLE";
        ValuationResponse.CapeSummary summary = new ValuationResponse.CapeSummary(status, real10, real3, real5, ttm,
                percentile, prior.size(), history.isEmpty() ? null : history.get(0).asOfDate(),
                history.isEmpty() ? null : history.get(history.size() - 1).asOfDate(), history,
                real10 == null ? List.of("continuous diluted EPS or CPI match") : List.of());
        return new CapeComputation(summary, latestCpi.getObservationDate());
    }

    private BigDecimal nominalTtmPe(BigDecimal price, List<ValuationEngine.Quarter> rows, LocalDate asOf, List<StockSplit> splits) {
        BigDecimal sum = BigDecimal.ZERO;
        for (ValuationEngine.Quarter row : rows) { BigDecimal eps = splitAdjustedEps(row, asOf, splits); if (eps == null) return null; sum = sum.add(eps); }
        return sum.signum() <= 0 ? null : price.divide(sum, 4, RoundingMode.HALF_UP);
    }

    private Map<String, Object> cashFlow(Context c) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("model", c.selection().model());
        result.put("latestTtmCashFlow", c.selection().latestTtmCashFlow());
        result.put("baseCashFlow", c.selection().baseCashFlow());
        result.put("crossCheckDifferencePct", c.selection().crossCheckDifferencePct());
        result.put("netDebt", c.selection().netDebt());
        result.put("debt", c.selection().debt());
        result.put("cash", c.selection().cash());
        result.put("shortTermInvestments", c.selection().shortTermInvestments());
        result.put("noncurrentMarketableSecurities", c.selection().noncurrentMarketableSecurities());
        return result;
    }

    private GrowthBundle growth(String symbol, ValuationEngine.Selection selection) {
        BigDecimal historical = selection.initialGrowthRatePct();
        List<GrowthReferenceResponse.Component> historicalComponents = new ArrayList<>();
        for (int i = 0; i < selection.historicalGrowthComponentsPct().size(); i++) {
            historicalComponents.add(new GrowthReferenceResponse.Component("historicalComponent" + (i + 1),
                    selection.historicalGrowthComponentsPct().get(i), selection.growthSampleCount(), "fiscal-year TTM"));
        }
        GrowthReferenceResponse historicalRef = new GrowthReferenceResponse("HISTORICAL", historical,
                historical == null ? "UNAVAILABLE" : "AVAILABLE", selection.growthSampleCount() >= 5 ? "High" : "Medium",
                "SEC_COMPANY_FACTS", "SEC Company Facts / reviewed fundamentals", null,
                selection.growthSampleCount(), historicalComponents);

        List<com.stockportfolio.model.EarningsEstimate> annual = earningsEstimateRepository
                .findBySymbolAndPeriodTypeOrderByPeriodEndDateAsc(symbol, "ANNUAL");
        com.stockportfolio.model.EarningsEstimate current = annual.stream().filter(e -> "0y".equals(e.getPeriodCode())).findFirst().orElse(null);
        com.stockportfolio.model.EarningsEstimate next = annual.stream().filter(e -> "+1y".equals(e.getPeriodCode())).findFirst().orElse(null);
        BigDecimal epsGrowth = estimateGrowth(current == null ? null : current.getEpsAvg(), next == null ? null : next.getEpsAvg());
        BigDecimal revenueGrowth = estimateGrowth(current == null ? null : current.getRevenueAvg(), next == null ? null : next.getRevenueAvg());
        int epsWeight = Math.min(20, next == null || next.getNumberOfAnalysts() == null ? 0 : next.getNumberOfAnalysts());
        int revenueWeight = Math.min(20, next == null || next.getRevenueAnalysts() == null ? 0 : next.getRevenueAnalysts());
        BigDecimal consensusRaw = weightedGrowth(epsGrowth, epsWeight, revenueGrowth, revenueWeight);
        OffsetDateTime fetchedAt = annual.stream().map(com.stockportfolio.model.EarningsEstimate::getFetchedAt)
                .filter(Objects::nonNull).max(OffsetDateTime::compareTo).orElse(null);
        boolean fresh = fetchedAt != null && !fetchedAt.isBefore(OffsetDateTime.now().minusDays(45));
        BigDecimal consensus = fresh ? consensusRaw : null;
        List<GrowthReferenceResponse.Component> consensusComponents = new ArrayList<>();
        if (epsGrowth != null) consensusComponents.add(new GrowthReferenceResponse.Component("EPS", epsGrowth, epsWeight, "next fiscal year"));
        if (revenueGrowth != null) consensusComponents.add(new GrowthReferenceResponse.Component("Revenue", revenueGrowth, revenueWeight, "next fiscal year"));
        GrowthReferenceResponse consensusRef = new GrowthReferenceResponse("CONSENSUS", consensusRaw,
                consensusRaw == null ? "UNAVAILABLE" : fresh ? "AVAILABLE" : "STALE",
                epsWeight + revenueWeight >= 20 ? "High" : "Medium", "YAHOO_ESTIMATES", "Yahoo Finance earningsTrend",
                fetchedAt, epsWeight + revenueWeight, consensusComponents);

        BigDecimal auto = historical == null ? consensus : consensus == null ? historical
                : historical.add(consensus).divide(BigDecimal.valueOf(2), ValuationEngine.MC);
        if (auto != null) auto = auto.max(BigDecimal.valueOf(-5)).min(BigDecimal.valueOf(15)).setScale(4, RoundingMode.HALF_UP);
        GrowthReferenceResponse blended = new GrowthReferenceResponse("AUTO_BLEND", auto,
                auto == null ? "UNAVAILABLE" : "AVAILABLE", historical != null && consensus != null ? "High" : "Medium",
                "SYSTEM_DERIVED", "50% historical + 50% fresh consensus", fetchedAt,
                selection.growthSampleCount() + epsWeight + revenueWeight,
                List.of(new GrowthReferenceResponse.Component("Historical", historical, selection.growthSampleCount(), "fiscal years"),
                        new GrowthReferenceResponse.Component("Consensus", consensus, epsWeight + revenueWeight, "next fiscal year")));
        return new GrowthBundle(new ValuationEngine.GrowthInputs(auto, historical, consensus),
                List.of(blended, historicalRef, consensusRef));
    }

    private BigDecimal estimateGrowth(BigDecimal current, BigDecimal next) {
        if (current == null || next == null || current.signum() <= 0) return null;
        return next.divide(current, ValuationEngine.MC).subtract(BigDecimal.ONE).multiply(BigDecimal.valueOf(100))
                .setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal weightedGrowth(BigDecimal first, int firstWeight, BigDecimal second, int secondWeight) {
        if (first == null || firstWeight <= 0) { first = null; firstWeight = 0; }
        if (second == null || secondWeight <= 0) { second = null; secondWeight = 0; }
        int total = firstWeight + secondWeight;
        if (total == 0) return null;
        BigDecimal sum = (first == null ? BigDecimal.ZERO : first.multiply(BigDecimal.valueOf(firstWeight)))
                .add(second == null ? BigDecimal.ZERO : second.multiply(BigDecimal.valueOf(secondWeight)));
        return sum.divide(BigDecimal.valueOf(total), ValuationEngine.MC).setScale(4, RoundingMode.HALF_UP);
    }

    private List<StockSplit> stockSplits(String symbol, LocalDate from, LocalDate to) {
        List<StockSplit> cached = stockSplitRepository.findBySymbolAndSplitDateBetweenOrderBySplitDateAsc(symbol, from, to);
        if (!cached.isEmpty()) return cached;
        try {
            for (YahooFinancePriceService.YahooStockSplitPoint point : yahooFinancePriceService.fetchStockSplits(symbol, from, to)) {
                StockSplit row = stockSplitRepository.findBySymbolAndSplitDate(symbol, point.splitDate()).orElseGet(StockSplit::new);
                row.setSymbol(symbol); row.setSplitDate(point.splitDate()); row.setNumerator(point.numerator());
                row.setDenominator(point.denominator()); row.setSourceCode("YAHOO"); row.setSourceDate(point.splitDate());
                stockSplitRepository.save(row);
            }
            return stockSplitRepository.findBySymbolAndSplitDateBetweenOrderBySplitDateAsc(symbol, from, to);
        } catch (Exception ignored) { return cached; }
    }

    private BigDecimal splitAdjustedEps(ValuationEngine.Quarter quarter, LocalDate asOf, List<StockSplit> splits) {
        if (quarter.dilutedEps() == null) return null;
        BigDecimal result = quarter.dilutedEps();
        for (StockSplit split : splits) {
            if (split.getSplitDate().isAfter(quarter.periodEnd()) && !split.getSplitDate().isAfter(asOf))
                result = result.multiply(split.getDenominator(), ValuationEngine.MC).divide(split.getNumerator(), ValuationEngine.MC);
        }
        return result;
    }

    private Map<String, Object> capitalEfficiency(Context c) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        List<ValuationEngine.Quarter> q = c.quarters();
        if (q.size() < 5) return result;
        List<ValuationEngine.Quarter> latest = q.subList(q.size() - 4, q.size());
        BigDecimal netIncome = sum(latest, ValuationEngine.Quarter::netIncome);
        BigDecimal operating = sum(latest, ValuationEngine.Quarter::operatingIncome);
        BigDecimal startEquity = q.get(q.size() - 5).equity(), endEquity = q.get(q.size() - 1).equity();
        BigDecimal startCapital = invested(q.get(q.size() - 5)), endCapital = invested(q.get(q.size() - 1));
        BigDecimal roe = returnPct(netIncome, average(startEquity, endEquity));
        BigDecimal tax = c.selection().taxRatePct() == null ? null : c.selection().taxRatePct().divide(BigDecimal.valueOf(100), ValuationEngine.MC);
        BigDecimal nopat = operating == null || tax == null ? null : operating.multiply(BigDecimal.ONE.subtract(tax), ValuationEngine.MC);
        BigDecimal roic = returnPct(nopat, average(startCapital, endCapital));
        result.put("ttmRoePct", roe);
        result.put("ttmRoicPct", roic);
        BigDecimal marketCap = c.market().currentPrice() == null || c.market().sharesOutstanding() == null ? null : c.market().currentPrice().multiply(c.market().sharesOutstanding());
        boolean equityDeclining = startEquity != null && endEquity != null && endEquity.compareTo(startEquity) < 0;
        boolean buybackDistortion = roe != null && roe.compareTo(BigDecimal.valueOf(100)) > 0 && equityDeclining;
        if (!buybackDistortion && endEquity != null && marketCap != null && marketCap.signum() > 0)
            buybackDistortion = endEquity.divide(marketCap, ValuationEngine.MC).compareTo(new BigDecimal("0.10")) < 0;
        result.put("buybackDistortion", buybackDistortion);
        return result;
    }

    private Map<String, Object> grossMargin(Context c) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        List<ValuationEngine.Quarter> q = c.quarters();
        if (q.size() < 4) return result;
        List<ValuationEngine.Quarter> latest = q.subList(q.size() - 4, q.size());
        BigDecimal revenue = sum(latest, ValuationEngine.Quarter::revenue);
        BigDecimal grossProfit = sum(latest, ValuationEngine.Quarter::grossProfit);
        result.put("ttmGrossMarginPct", returnPct(grossProfit, revenue));
        int currentYear = latest.get(latest.size() - 1).fiscalYear() == null ? latest.get(latest.size() - 1).periodEnd().getYear() : latest.get(latest.size() - 1).fiscalYear();
        List<ValuationEngine.Quarter> year = q.stream().filter(row -> (row.fiscalYear() == null ? row.periodEnd().getYear() : row.fiscalYear()) == currentYear).toList();
        result.put("annualPeriodLabel", year.size() >= 4 ? "ANNUAL" : "YTD");
        result.put("annualQuarterCount", Math.min(year.size(), 4));
        return result;
    }

    private List<ValuationResponse.Diagnostic> diagnostics(Context c, ValuationScenarioResponse base) {
        List<ValuationResponse.Diagnostic> result = new ArrayList<>();
        for (String reason : c.applicability().reasons()) result.add(new ValuationResponse.Diagnostic("NOT_APPLICABLE", "critical", reason, c.symbol()));
        if (c.selection().crossCheckDifferencePct() != null && c.selection().crossCheckDifferencePct().compareTo(BigDecimal.TEN) > 0)
            result.add(new ValuationResponse.Diagnostic("FCFF_DEFINITION_CONFLICT",
                    c.selection().crossCheckDifferencePct().compareTo(BigDecimal.valueOf(25)) > 0 ? "critical" : "warning",
                    "FCFF definitions do not reconcile within 10%.", c.selection().crossCheckDifferencePct() + "%"));
        if (base != null && base.terminalValueWeightPct() != null && base.terminalValueWeightPct().compareTo(BigDecimal.valueOf(70)) >= 0)
            result.add(new ValuationResponse.Diagnostic("HIGH_TERMINAL_VALUE_WEIGHT", "warning",
                    "Valuation is highly dependent on terminal value.", base.terminalValueWeightPct() + "%"));
        Map<String, Object> capital = capitalEfficiency(c);
        if (Boolean.TRUE.equals(capital.get("buybackDistortion"))) result.add(new ValuationResponse.Diagnostic(
                "ROE_BUYBACK_DISTORTION", "warning", "High ROE may be distorted by buybacks or a small equity base.", "TTM ROE=" + capital.get("ttmRoePct")));
        if (c.cape().summary().sampleCount() < 20) result.add(new ValuationResponse.Diagnostic("CAPE_SAMPLE_TOO_SMALL", "info",
                "CAPE percentile requires at least 20 prior valid quarterly samples.", "sampleCount=" + c.cape().summary().sampleCount()));
        return result;
    }

    private ValuationEngine.Quarter quarter(EarningsHistory h, Map<String, Object> o) {
        return new ValuationEngine.Quarter(h.getAsOfDate(), h.getFilingDate(), h.getFiscalYear(), h.getFiscalPeriod(),
                reviewedDataResolver.decimal(o, "dilutedEps", h.getDilutedEps()), reviewedDataResolver.decimal(o, "cashFlow", h.getCashFlow()),
                reviewedDataResolver.decimal(o, "capex", h.getCapex()), reviewedDataResolver.decimal(o, "interestExpense", h.getInterestExpense()),
                reviewedDataResolver.decimal(o, "netBorrowing", h.getNetBorrowing()), reviewedDataResolver.decimal(o, "depreciationAmortization", h.getDepreciationAmortization()),
                reviewedDataResolver.decimal(o, "changeInWorkingCapital", h.getChangeInWorkingCapital()), reviewedDataResolver.decimal(o, "operatingIncome", h.getOperatingIncome()),
                reviewedDataResolver.decimal(o, "taxProvision", h.getTaxProvision()), reviewedDataResolver.decimal(o, "pretaxIncome", h.getPretaxIncome()),
                reviewedDataResolver.decimal(o, "netIncome", h.getNetIncome()), reviewedDataResolver.decimal(o, "stockholdersEquity", h.getStockholdersEquity()),
                reviewedDataResolver.decimal(o, "totalDebt", h.getTotalDebt()), reviewedDataResolver.decimal(o, "cashAndEquivalents", h.getCashAndEquivalents()),
                reviewedDataResolver.decimal(o, "shortTermInvestments", h.getShortTermInvestments()),
                reviewedDataResolver.decimal(o, "noncurrentMarketableSecurities", h.getNoncurrentMarketableSecurities()),
                reviewedDataResolver.decimal(o, "investedCapital", h.getInvestedCapital()), reviewedDataResolver.decimal(o, "revenue", h.getRevenue()),
                reviewedDataResolver.decimal(o, "grossProfit", h.getGrossProfit()), reviewedDataResolver.decimal(o, "totalAssets", h.getTotalAssets()),
                reviewedDataResolver.text(o, "currencyCode", h.getCurrencyCode()));
    }

    private BigDecimal priceAtOrBefore(List<PriceHistory> prices, LocalDate date) {
        for (int i = prices.size() - 1; i >= 0; i--) if (!prices.get(i).getTradeDate().isAfter(date))
            return prices.get(i).getClosePrice();
        return null;
    }
    private LocalDate quarterEnd(LocalDate date) {
        int endMonth = ((date.getMonthValue() - 1) / 3 + 1) * 3;
        return YearMonth.of(date.getYear(), endMonth).atEndOfMonth();
    }
    private LocalDate completedQuarterEnd(LocalDate date) {
        LocalDate candidate = quarterEnd(date);
        return candidate.isAfter(date) ? quarterEnd(date.minusMonths(3)) : candidate;
    }
    private BigDecimal cpiAtOrBefore(Map<YearMonth, BigDecimal> cpi, YearMonth month) {
        for (int i = 0; i < 4; i++) {
            BigDecimal value = cpi.get(month.minusMonths(i));
            if (value != null) return value;
        }
        return null;
    }
    private boolean continuousQuarterly(List<ValuationEngine.Quarter> rows) {
        if (rows.isEmpty()) return false;
        for (int i = 1; i < rows.size(); i++) {
            long days = java.time.temporal.ChronoUnit.DAYS.between(rows.get(i - 1).periodEnd(), rows.get(i).periodEnd());
            if (days < 70 || days > 110) return false;
        }
        return true;
    }
    private BigDecimal value(ValuationScenarioResponse r) { return r != null && r.valid() ? r.intrinsicValuePerShare() : null; }
    private BigDecimal sum(List<ValuationEngine.Quarter> rows, Function<ValuationEngine.Quarter, BigDecimal> getter) {
        BigDecimal total = BigDecimal.ZERO; for (ValuationEngine.Quarter row : rows) { BigDecimal v = getter.apply(row); if (v == null) return null; total = total.add(v); } return total;
    }
    private BigDecimal average(BigDecimal a, BigDecimal b) { return a == null || b == null ? null : a.add(b).divide(BigDecimal.valueOf(2), ValuationEngine.MC); }
    private BigDecimal returnPct(BigDecimal n, BigDecimal d) { return n == null || d == null || d.signum() <= 0 ? null : n.divide(d, ValuationEngine.MC).multiply(BigDecimal.valueOf(100)).setScale(4, RoundingMode.HALF_UP); }
    private BigDecimal invested(ValuationEngine.Quarter q) { return q.investedCapital() != null ? q.investedCapital() : q.debt() == null || q.equity() == null ? null : q.debt().add(q.equity())
            .subtract(q.cash() == null ? BigDecimal.ZERO : q.cash())
            .subtract(q.shortTermInvestments() == null ? BigDecimal.ZERO : q.shortTermInvestments())
            .subtract(q.noncurrentMarketableSecurities() == null ? BigDecimal.ZERO : q.noncurrentMarketableSecurities()); }
    private String scenarioType(String raw) { String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT); if (!SCENARIO_TYPES.contains(value)) throw new ResponseStatusException(BAD_REQUEST, "scenarioType must be BEAR, BASE, or BULL"); return value; }
    private String normalize(String raw) { if (raw == null || raw.isBlank()) throw new ResponseStatusException(BAD_REQUEST, "symbol is required"); return raw.trim().toUpperCase(Locale.ROOT); }
    private ResponseStatusException badRequest(List<String> errors) { return new ResponseStatusException(BAD_REQUEST, String.join("; ", errors)); }

    private record Context(String symbol, Position position, List<ValuationEngine.Quarter> quarters,
                           List<PriceHistory> prices, ValuationEngine.MarketInputs market,
                           ValuationEngine.Selection selection, GrowthBundle growth, ValuationResponse.Applicability applicability,
                           CapeComputation cape, LocalDate priceDate, LocalDate financialDate, LocalDate filingDate,
                           Map<String, FieldSourceResponse> fieldSources) { }
    private record CapeComputation(ValuationResponse.CapeSummary summary, LocalDate cpiDate) {
        static CapeComputation unavailable(String status, LocalDate cpiDate, List<String> missing) {
            return new CapeComputation(new ValuationResponse.CapeSummary(status, null, null, null, null, null,
                    0, null, null, List.of(), missing), cpiDate);
        }
    }
    private record GrowthBundle(ValuationEngine.GrowthInputs inputs, List<GrowthReferenceResponse> references) { }
}
