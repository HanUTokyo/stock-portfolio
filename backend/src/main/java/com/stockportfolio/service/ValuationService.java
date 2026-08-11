package com.stockportfolio.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockportfolio.dto.*;
import com.stockportfolio.model.EarningsHistory;
import com.stockportfolio.model.EconomicObservation;
import com.stockportfolio.model.Position;
import com.stockportfolio.model.PriceHistory;
import com.stockportfolio.model.ValuationScenario;
import com.stockportfolio.model.SecDebtEvidence;
import com.stockportfolio.model.StockSplit;
import com.stockportfolio.repository.*;
import com.stockportfolio.valuation.explicit.ExplicitOperatingForecastResult;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.time.temporal.ChronoUnit;
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
    private SecCashFlowBridgeResolver cashFlowBridgeResolver = new SecCashFlowBridgeResolver();
    private SecDebtEvidenceRepository debtEvidenceRepository;
    private ExternalWaccReferenceService externalWaccReferenceService;
    private ForecastArchitectureService forecastArchitectureService;

    @Autowired(required = false)
    void setCashFlowBridgeResolver(SecCashFlowBridgeResolver resolver) { this.cashFlowBridgeResolver = resolver; }
    @Autowired(required = false) void setDebtEvidenceRepository(SecDebtEvidenceRepository repository) { this.debtEvidenceRepository = repository; }
    @Autowired(required = false) void setExternalWaccReferenceService(ExternalWaccReferenceService service) { this.externalWaccReferenceService = service; }
    @Autowired(required = false) void setForecastArchitectureService(ForecastArchitectureService service) { this.forecastArchitectureService = service; }
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
        ForecastPreviewResponse explicit = forecastArchitectureService == null ? null
                : forecastArchitectureService.savedPreview(context.symbol()).orElse(null);
        return response(context, scenarios, explicit);
    }

    @Transactional(readOnly = true)
    public WaccReferencesResponse waccReferences(String rawSymbol) {
        Context context = context(rawSymbol, null);
        return externalReferences(context);
    }

    public WaccReferencesResponse refreshWaccReferences(String rawSymbol) {
        Context context = context(rawSymbol, null);
        return externalWaccReferenceService == null ? externalReferences(context)
                : externalWaccReferenceService.refresh(context.symbol(), systemWacc(context));
    }

    @Transactional
    public ValuationEvaluationResponse evaluate(String rawSymbol, ValuationEvaluateRequest request) {
        String type = scenarioType(request.scenarioType());
        ValuationAssumptions settings = request.assumptions() == null ? engine.defaultSettings(type) : engine.normalizeLegacy(request.assumptions());
        Context context = context(rawSymbol, settings.taxRateOverridePct());
        ValuationScenarioResponse result = engine.evaluateSettings(type, "EVALUATED", compatibilitySettings(settings, context),
                context.selection(), context.market(), context.growth().inputs(), null);
        if (!result.valid()) throw badRequest(result.warnings());
        DualTrackBundle dualTrack = dualTrack(context, List.of(result));
        ValuationMethodResponse compatibilityMethod = selectedMethodResponse(context.selection().model(), dualTrack.methods());
        return new ValuationEvaluationResponse(context.symbol(), engineVersion, result,
                engine.sensitivity(type, result.resolvedAssumptions(), context.selection(), context.market()),
                engine.reverse(type, result.resolvedAssumptions(), context.selection(), context.market()),
                diagnostics(context, result, dualTrack.reconciliation()), "DUAL_TRACK", dualTrack.readiness(),
                dualTrack.methods(), dualTrack.reconciliation(), compatibilityMethod.debtBreakdown(),
                compatibilityMethod.netBorrowingBreakdown(), compatibilityMethod.discountRateBreakdown(),
                compatibilityMethod.scenarioDriverBridge(), context.cashFlowBridge(), context.fundamentalsFreshness());
    }

    public ValuationScenarioResponse save(String rawSymbol, String rawType, ValuationSaveRequest request) {
        String symbol = normalize(rawSymbol);
        String type = scenarioType(rawType);
        if (request.modelMode() != null && !"AUTO".equalsIgnoreCase(request.modelMode()))
            throw new ResponseStatusException(BAD_REQUEST, "modelMode must be AUTO");
        ValuationAssumptions settings = request.assumptions() == null ? engine.defaultSettings(type) : engine.normalizeLegacy(request.assumptions());
        Context context = context(symbol, settings.taxRateOverridePct());
        ValuationScenarioResponse evaluated = engine.evaluateSettings(type, "SAVED", compatibilitySettings(settings, context),
                context.selection(), context.market(), context.growth().inputs(), null);
        if (!evaluated.valid()) throw badRequest(evaluated.warnings());
        ValuationScenario entity = scenarioRepository.findBySymbolAndScenarioType(symbol, type).orElseGet(ValuationScenario::new);
        entity.setSymbol(symbol);
        entity.setScenarioType(type);
        entity.setModelMode("AUTO");
        entity.setEngineVersion(engineVersion);
        entity.setAssumptionsSchemaVersion(settings.fcffWaccSelection() == null ? 2 : 3);
        entity.setCashFlowBasisAtSave(context.selection().model());
        entity.setMigrationStatus("CURRENT");
        try { entity.setAssumptionsJson(objectMapper.writeValueAsString(settings)); }
        catch (JsonProcessingException e) { throw new ResponseStatusException(BAD_REQUEST, "Invalid assumptions", e); }
        ValuationScenario saved = scenarioRepository.save(entity);
        ValuationScenarioResponse response = engine.evaluateSettings(type, "SAVED", compatibilitySettings(settings, context), context.selection(), context.market(),
                context.growth().inputs(), saved.getUpdatedAt());
        return withMigrationMetadata(response, saved, "CURRENT");
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
        return response(c, scenarios, null);
    }

    private ValuationResponse response(Context c, List<ValuationScenarioResponse> scenarios, ForecastPreviewResponse explicit) {
        if (explicit != null) return explicitResponse(c, explicit);
        Map<String, ValuationScenarioResponse> byType = new HashMap<>();
        scenarios.forEach(s -> byType.put(s.scenarioType(), s));
        BigDecimal bear = value(byType.get("BEAR"));
        BigDecimal base = value(byType.get("BASE"));
        BigDecimal bull = value(byType.get("BULL"));
        BigDecimal low = bear == null || bull == null ? null : bear.min(bull);
        BigDecimal high = bear == null || bull == null ? null : bear.max(bull);
        DualTrackBundle dualTrack = dualTrack(c, scenarios);
        String compatibilityModel = dualTrack.methods().fcff().available() ? "FCFF"
                : dualTrack.methods().fcfe().available() ? "FCFE" : "UNAVAILABLE";
        ValuationMethodResponse compatibilityMethod = selectedMethodResponse(compatibilityModel, dualTrack.methods());
        ValuationResponse.DataQuality quality = quality(c, scenarios, dualTrack.reconciliation());
        List<ValuationResponse.Diagnostic> diagnostics = diagnostics(c, byType.get("BASE"), dualTrack.reconciliation());
        return new ValuationResponse(c.symbol(), engineVersion, LocalDate.now(marketZone), c.priceDate(),
                c.financialDate(), c.filingDate(), c.cape().cpiDate(), c.applicability(), quality,
                compatibilityModel, new ValuationResponse.Overview(bear, base, bull, low, high, c.market().currentPrice()),
                scenarios, c.growth().references(), c.cape().summary(), cashFlow(c, compatibilityModel), capitalEfficiency(c), grossMargin(c), diagnostics,
                c.selection().missingFields(), c.fieldSources(), "DUAL_TRACK", dualTrack.readiness(),
                dualTrack.methods(), dualTrack.reconciliation(), compatibilityMethod.debtBreakdown(),
                compatibilityMethod.netBorrowingBreakdown(), compatibilityMethod.discountRateBreakdown(),
                compatibilityMethod.scenarioDriverBridge(), c.cashFlowBridge(), c.fundamentalsFreshness());
    }

    private ValuationMethodResponse selectedMethodResponse(String selectedModel, ValuationMethodsResponse methods) {
        return "FCFE".equalsIgnoreCase(selectedModel) ? methods.fcfe() : methods.fcff();
    }

    /** Turns a user-confirmed Forecast 3.0 snapshot into the canonical valuation response. */
    private ValuationResponse explicitResponse(Context c, ForecastPreviewResponse preview) {
        List<ValuationScenarioResponse> fcff = explicitScenarios("FCFF", preview);
        List<ValuationScenarioResponse> fcfe = explicitScenarios("FCFE", preview);
        CrossModelReconciliationResponse reconciliation = reconcile("FCFF", fcff, fcfe, null);
        List<String> explicitWarnings = new ArrayList<>(preview.missingInputs());
        explicitWarnings.add("EXPLICIT_OPERATING_FORECAST: user-confirmed Forecast 3.0 snapshot is the active valuation basis.");
        ValuationMethodResponse fcffMethod = explicitMethod("FCFF", c, fcff, preview, explicitWarnings);
        ValuationMethodResponse fcfeMethod = explicitMethod("FCFE", c, fcfe, preview, explicitWarnings);
        ValuationMethodsResponse methods = new ValuationMethodsResponse(fcffMethod, fcfeMethod);
        Map<String, ValuationScenarioResponse> byType = byScenarioType(fcff);
        BigDecimal bear = value(byType.get("BEAR")), base = value(byType.get("BASE")), bull = value(byType.get("BULL"));
        BigDecimal low = bear == null || bull == null ? null : bear.min(bull);
        BigDecimal high = bear == null || bull == null ? null : bear.max(bull);
        List<ValuationResponse.Diagnostic> diagnostics = new ArrayList<>(diagnostics(c, byType.get("BASE"), reconciliation));
        diagnostics.add(new ValuationResponse.Diagnostic("FORECAST_3_ACTIVE", "info",
                "A saved explicit operating forecast is the active valuation basis.", preview.templateVersion()));
        return new ValuationResponse(c.symbol(), engineVersion, LocalDate.now(marketZone), c.priceDate(), c.financialDate(), c.filingDate(),
                c.cape().cpiDate(), c.applicability(), quality(c, fcff, reconciliation), "FCFF",
                new ValuationResponse.Overview(bear, base, bull, low, high, c.market().currentPrice()), fcff,
                c.growth().references(), c.cape().summary(), cashFlow(c, preview), capitalEfficiency(c), grossMargin(c), diagnostics,
                c.selection().missingFields(), c.fieldSources(), "DUAL_TRACK_EXPLICIT_OPERATING_FORECAST", reconciliation.readiness(),
                methods, reconciliation, fcffMethod.debtBreakdown(), fcffMethod.netBorrowingBreakdown(), fcffMethod.discountRateBreakdown(),
                List.of(), c.cashFlowBridge(), c.fundamentalsFreshness());
    }

    private List<ValuationScenarioResponse> explicitScenarios(String method, ForecastPreviewResponse preview) {
        return SCENARIO_TYPES.stream().map(type -> {
            ExplicitOperatingForecastResult result = preview.scenarios().get(type);
            ExplicitOperatingForecastResult.ValuationTrack track = "FCFF".equals(method) ? result.fcff() : result.fcfe();
            BigDecimal perShare = track.equityValue().divide(preview.sharesOutstanding(), ValuationEngine.MC).setScale(4, RoundingMode.HALF_UP);
            List<ValuationScenarioResponse.ProjectionPoint> projection = track.discountedCashFlows().stream()
                    .map(point -> new ValuationScenarioResponse.ProjectionPoint(point.year(), null, point.cashFlow(), point.discountFactor(), point.presentValue())).toList();
            return new ValuationScenarioResponse(type, "AUTO", method, "FORECAST_3_SAVED", null, true, perShare, null,
                    track.enterpriseValue(), track.equityValue(), null, projection,
                    List.of("EXPLICIT_OPERATING_FORECAST: saved user-confirmed snapshot."), null, null,
                    Map.of("forecastMode", "SAVED_SNAPSHOT"), List.of(), "CURRENT", engineVersion, engineVersion);
        }).toList();
    }

    private ValuationMethodResponse explicitMethod(String method, Context c, List<ValuationScenarioResponse> scenarios,
                                                   ForecastPreviewResponse preview, List<String> warnings) {
        ExplicitOperatingForecastResult base = preview.scenarios().get("BASE");
        ExplicitOperatingForecastResult.ValuationTrack track = "FCFF".equals(method) ? base.fcff() : base.fcfe();
        return new ValuationMethodResponse(method, true, "PARTIAL", "PARTIAL",
                track.cashFlowDefinition(), null, track.discountRateType(), "EXPLICIT_OPERATING_FORECAST",
                "FCFF".equals(method) ? "SAVED_EXPLICIT_NET_DEBT_BRIDGE" : "SAVED_DEBT_FINANCING_POLICY",
                null, null,
                null, track.discountRate().multiply(BigDecimal.valueOf(100)), null,
                debtBreakdown(c), netBorrowingBreakdown(c), discountRateBreakdown(method, c), List.of(), List.of(), scenarios,
                null, null, preview.missingInputs(), warnings);
    }

    private DualTrackBundle dualTrack(Context c, List<ValuationScenarioResponse> compatibilityScenarios) {
        List<ValuationScenarioResponse> fcffScenarios = scenariosForMethod("FCFF", c, compatibilityScenarios);
        boolean completeNetBorrowingEvidence = hasCompleteNetBorrowingEvidence(c);
        List<ValuationScenarioResponse> fcfeScenarios = completeNetBorrowingEvidence
                ? scenariosForMethod("FCFE", c, compatibilityScenarios) : List.of();
        ValuationMethodResponse fcff = methodResponse("FCFF", c, fcffScenarios, true);
        ValuationMethodResponse fcfe = methodResponse("FCFE", c, fcfeScenarios, completeNetBorrowingEvidence);
        ValuationMethodsResponse methods = new ValuationMethodsResponse(fcff, fcfe);
        CrossModelReconciliationResponse reconciliation = reconcile(c.selection().model(),
                "INCOMPLETE".equals(c.cashFlowBridge().coverageStatus()) ? List.of() : fcffScenarios, fcfeScenarios,
                fcff.definitionCrossCheckDifferencePct());
        String readiness = "STALE_FUNDAMENTALS".equals(c.fundamentalsFreshness().status()) ? "NOT_READY" : reconciliation.readiness();
        if (!readiness.equals(reconciliation.readiness())) {
            List<String> warnings = new ArrayList<>(reconciliation.warnings());
            warnings.add("STALE_FUNDAMENTALS: valuation cash-flow inputs are beyond the permitted reporting age.");
            reconciliation = new CrossModelReconciliationResponse(readiness, reconciliation.comparabilityStatus(), reconciliation.baseDifferencePct(), reconciliation.scenarios(), warnings);
        }
        return new DualTrackBundle(methods, reconciliation, readiness);
    }

    private List<ValuationScenarioResponse> scenariosForMethod(String method, Context c,
                                                               List<ValuationScenarioResponse> compatibilityScenarios) {
        return compatibilityScenarios.stream().map(source -> {
            if (method.equalsIgnoreCase(source.selectedModel())) return source;
            ValuationAssumptions settings = source.assumptions() == null
                    ? engine.defaultSettings(source.scenarioType()) : source.assumptions();
            if ("FCFF".equals(method)) settings = applyExternalFcffWacc(settings);
            ValuationEngine.Selection selection = selectionForMethod(method, c, settings);
            return engine.evaluateSettings(source.scenarioType(), source.origin(), settings, selection,
                    c.market(), c.growth().inputs(), source.updatedAt());
        }).toList();
    }

    private WaccReferencesResponse externalReferences(Context context) {
        if (externalWaccReferenceService == null)
            return new WaccReferencesResponse(context.symbol(), systemWacc(context), List.of());
        return externalWaccReferenceService.references(context.symbol(), systemWacc(context));
    }

    private BigDecimal systemWacc(Context context) {
        ValuationEngine.MethodSelection fcff = context.selection().methodSelection("FCFF");
        return fcff == null ? null : fcff.automaticDiscountRatePct();
    }

    /** A selection is a serialized snapshot; never re-read the mutable external cache while evaluating a saved scenario. */
    private ValuationAssumptions applyExternalFcffWacc(ValuationAssumptions settings) {
        WaccReferenceSelection selected = settings == null ? null : settings.fcffWaccSelection();
        if (selected == null || selected.ratePct() == null || "SYSTEM_ESTIMATE".equals(selected.provider())
                || selected.ratePct().compareTo(BigDecimal.valueOf(2)) < 0 || selected.ratePct().compareTo(BigDecimal.valueOf(30)) > 0)
            return settings;
        return new ValuationAssumptions(settings.baseCashFlow(), settings.initialGrowthRatePct(), selected.ratePct(),
                settings.terminalGrowthRatePct(), settings.projectionYears(), settings.marginOfSafetyPct(), settings.taxRateOverridePct(),
                settings.baseCashFlowMode(), settings.growthMode(), "MANUAL_RATE", settings.annualGrowthRatesPct(),
                settings.riskFreeRatePct(), settings.beta(), settings.equityRiskPremiumPct(), selected, settings.fcffCashInterestReference());
    }

    private ValuationAssumptions compatibilitySettings(ValuationAssumptions settings, Context context) {
        return "FCFF".equals(context.selection().model()) ? applyExternalFcffWacc(settings) : settings;
    }

    /**
     * A selected public WACC may be used with the explicitly labelled cash-FCFF reference.
     * This is intentionally an INDICATIVE view: it never makes the economic NOPAT bridge COMPLETE.
     */
    private ValuationEngine.Selection selectionForMethod(String method, Context context, ValuationAssumptions settings) {
        BigDecimal cashReference = cashFcffReference(context, settings);
        if (!"FCFF".equals(method) || !hasExternalWacc(settings) || cashReference == null || cashReference.signum() <= 0) {
            return context.selection().forMethod(method);
        }
        BigDecimal rate = settings.fcffWaccSelection().ratePct();
        return new ValuationEngine.Selection("FCFF", cashReference, cashReference, context.growth().inputs().autoGrowthPct(), rate,
                context.selection().taxRatePct(), context.cashFlowBridge().reconciliationDifferencePct(),
                context.selection().netDebt(), context.selection().debt(), context.selection().cash(),
                context.selection().shortTermInvestments(), context.selection().noncurrentMarketableSecurities(),
                context.selection().growthSampleCount(), context.selection().historicalGrowthComponentsPct(), List.of(),
                List.of("INDICATIVE_FCFF: external WACC selected with CASH_FCFF_REFERENCE_ONLY; economic FCFF bridge remains incomplete."),
                context.selection().quarters(), context.selection().methodSelections());
    }

    private boolean hasExternalWacc(ValuationAssumptions settings) {
        WaccReferenceSelection selection = settings == null ? null : settings.fcffWaccSelection();
        return selection != null && selection.ratePct() != null && !"SYSTEM_ESTIMATE".equals(selection.provider())
                && selection.ratePct().compareTo(BigDecimal.valueOf(2)) >= 0 && selection.ratePct().compareTo(BigDecimal.valueOf(30)) <= 0;
    }

    private BigDecimal cashFcffReference(Context context, ValuationAssumptions settings) {
        if (settings == null || settings.fcffCashInterestReference() == null
                || settings.fcffCashInterestReference().signum() < 0 || context.selection().taxRatePct() == null) return null;
        BigDecimal cfo = latestFourSum(context.selection().quarters(), ValuationEngine.Quarter::cfo);
        BigDecimal capex = latestFourSum(context.selection().quarters(), ValuationEngine.Quarter::capex);
        if (cfo == null || capex == null) return null;
        BigDecimal afterTaxInterest = settings.fcffCashInterestReference().multiply(
                BigDecimal.ONE.subtract(context.selection().taxRatePct().divide(BigDecimal.valueOf(100), ValuationEngine.MC)), ValuationEngine.MC);
        return cfo.subtract(capex, ValuationEngine.MC).add(afterTaxInterest, ValuationEngine.MC);
    }

    private ValuationMethodResponse methodResponse(String method, Context c,
                                                   List<ValuationScenarioResponse> scenarios,
                                                   boolean completeFinancingEvidence) {
        ValuationEngine.MethodSelection selected = c.selection().methodSelection(method);
        List<String> missing = selected == null ? List.of("methodSelection") : selected.missingInputs();
        boolean bridgeIncomplete = "FCFF".equals(method) && c.cashFlowBridge() != null
                && !"COMPLETE".equals(c.cashFlowBridge().coverageStatus());
        boolean indicativeCashReference = "FCFF".equals(method) && bridgeIncomplete && !scenarios.isEmpty()
                && scenarios.stream().anyMatch(ValuationScenarioResponse::valid);
        boolean financingEvidenceIncomplete = "FCFE".equals(method) && !completeFinancingEvidence;
        if (financingEvidenceIncomplete) missing = concatStrings(missing, List.of("completeNetBorrowingEvidence"));
        boolean allValid = !bridgeIncomplete && !financingEvidenceIncomplete && selected != null && selected.available() && !scenarios.isEmpty()
                && scenarios.stream().allMatch(ValuationScenarioResponse::valid);
        boolean someValid = !bridgeIncomplete && !financingEvidenceIncomplete && selected != null && selected.available()
                && scenarios.stream().anyMatch(ValuationScenarioResponse::valid);
        boolean operatingBridgeGap = "FCFF".equals(method) && selected != null
                && selected.definitionCrossCheckDifferencePct() != null
                && selected.definitionCrossCheckDifferencePct().compareTo(BigDecimal.TEN) > 0;
        if (bridgeIncomplete) missing = concatStrings(missing, c.cashFlowBridge().missingInputs());
        String availability = indicativeCashReference || (bridgeIncomplete && "FCFF".equals(method) && selected != null && selected.available())
                ? "INDICATIVE" : bridgeIncomplete || financingEvidenceIncomplete ? "UNAVAILABLE" : allValid ? (operatingBridgeGap ? "PARTIAL" : "AVAILABLE")
                : someValid ? "PARTIAL" : "UNAVAILABLE";
        List<String> warnings = new ArrayList<>();
        if (selected != null) warnings.addAll(selected.warnings());
        if (bridgeIncomplete) warnings.addAll(c.cashFlowBridge().warnings());
        if (indicativeCashReference) {
            warnings.add("INDICATIVE_FCFF: external WACC is applied to CASH_FCFF_REFERENCE_ONLY; this result is excluded from cross-model readiness.");
        } else if (bridgeIncomplete && "FCFF".equals(method) && selected != null && selected.available()) {
            warnings.add("INDICATIVE_FCFF: WACC uses cash-interest fallback where accrued interest is unavailable; this result is excluded from cross-model readiness.");
        }
        if (financingEvidenceIncomplete) warnings.add("FCFE is unavailable because the latest four quarters do not each have COMPLETE SEC net-borrowing evidence.");
        scenarios.stream().flatMap(s -> s.warnings().stream()).forEach(warnings::add);
        ValuationScenarioResponse baseScenario = scenarios.stream()
                .filter(scenario -> "BASE".equals(scenario.scenarioType()) && scenario.valid())
                .findFirst().orElse(null);
        ValuationEvaluationResponse.Sensitivity sensitivity = bridgeIncomplete || financingEvidenceIncomplete || baseScenario == null ? null
                : engine.sensitivity("BASE", baseScenario.resolvedAssumptions(), c.selection().forMethod(method), c.market());
        ValuationEvaluationResponse.ReverseDcf reverseDcf = bridgeIncomplete || financingEvidenceIncomplete || baseScenario == null ? null
                : engine.reverse("BASE", baseScenario.resolvedAssumptions(), c.selection().forMethod(method), c.market());
        String fcff = "FCFF";
        return new ValuationMethodResponse(method, allValid || indicativeCashReference, availability, availability,
                fcff.equals(method) ? "EBIT × (1 - cash operating tax rate) + D&A - capex - delta operating NWC"
                        : "CFO - capex + reported total net borrowing",
                fcff.equals(method) ? "CFO + after-tax interest - capex (cash reference only)"
                        : "FCFF - after-tax interest + reported total net borrowing",
                fcff.equals(method) ? "WACC" : "COST_OF_EQUITY",
                "LEGACY_CASH_FLOW_FADE",
                fcff.equals(method) ? "REPORTED_NET_DEBT_WITH_MARKETABLE_SECURITIES"
                        : "REPORTED_TOTAL_NET_BORROWING",
                indicativeCashReference ? baseScenario.resolvedAssumptions().baseCashFlow() : bridgeIncomplete && fcff.equals(method) ? null : selected == null ? null : selected.latestTtmCashFlow(),
                selected == null ? null : selected.crossCheckTtmCashFlow(),
                indicativeCashReference ? baseScenario.resolvedAssumptions().baseCashFlow() : bridgeIncomplete && fcff.equals(method) ? null : selected == null ? null : selected.normalizedBaseCashFlow(),
                indicativeCashReference ? baseScenario == null || baseScenario.resolvedAssumptions() == null ? null : baseScenario.resolvedAssumptions().discountRatePct() : selected == null ? null : selected.automaticDiscountRatePct(),
                selected == null ? null : selected.definitionCrossCheckDifferencePct(),
                debtBreakdown(c), netBorrowingBreakdown(c), discountRateBreakdown(method, c),
                scenarioDriverBridge(selected, scenarios), growthProvenance(scenarios, c), scenarios, sensitivity, reverseDcf, missing,
                warnings.stream().filter(Objects::nonNull).distinct().toList());
    }

    private List<GrowthProvenanceResponse> growthProvenance(List<ValuationScenarioResponse> scenarios, Context c) {
        return scenarios.stream().map(scenario -> {
            ValuationAssumptions assumptions = scenario.resolvedAssumptions();
            String mode = assumptions == null || assumptions.growthMode() == null ? "AUTO_BLEND" : assumptions.growthMode();
            String source = switch (mode) {
                case "AUTO_BLEND" -> "SHARED_AUTO_BLEND";
                case "HISTORICAL" -> "SHARED_HISTORICAL";
                case "CONSENSUS" -> "SHARED_CONSENSUS";
                default -> scenario.assumptionSources() != null && "USER_OVERRIDE".equals(scenario.assumptionSources().get("initialGrowthRatePct"))
                        ? "USER_OVERRIDE" : mode;
            };
            List<String> fallbacks = new ArrayList<>();
            if ("AUTO_BLEND".equals(mode) && c.growth().inputs().historicalGrowthPct() == null) fallbacks.add("historicalGrowthUnavailable");
            if ("AUTO_BLEND".equals(mode) && c.growth().inputs().consensusGrowthPct() == null) fallbacks.add("consensusGrowthUnavailable");
            String reason = "Shared operating forecast used by FCFF and FCFE; method-specific historical cash-flow growth is excluded.";
            if (!fallbacks.isEmpty()) reason += " AUTO_BLEND falls back to the available shared reference.";
            return new GrowthProvenanceResponse("legacy-shared-forecast-" + scenario.scenarioType().toLowerCase(Locale.ROOT),
                    "COMPARABLE_SHARED_FORECAST", scenario.scenarioType(), mode,
                    assumptions == null ? null : assumptions.initialGrowthRatePct(),
                    assumptions == null ? null : assumptions.terminalGrowthRatePct(),
                    c.growth().inputs().historicalGrowthPct(), c.growth().inputs().consensusGrowthPct(), source,
                    reason, List.copyOf(fallbacks));
        }).toList();
    }

    private DebtBreakdownResponse debtBreakdown(Context c) {
        List<SecDebtEvidence> evidence = debtEvidence("BALANCE", c);
        if (!evidence.isEmpty()) {
            LocalDate end = evidence.get(evidence.size()-1).getPeriodEnd(); List<SecDebtEvidence> latest = evidence.stream().filter(e -> end.equals(e.getPeriodEnd())).toList();
            return new DebtBreakdownResponse("COMPLETE".equals(latest.getFirst().getCoverageStatus()) ? "AVAILABLE" : "INCOMPLETE", c.selection().debt(), amount(latest,"COMMERCIAL_PAPER"), amount(latest,"CURRENT_TERM_DEBT"), amount(latest,"NONCURRENT_TERM_DEBT"), amount(latest,"OTHER_SHORT_TERM"), c.selection().cash(),c.selection().shortTermInvestments(),c.selection().noncurrentMarketableSecurities(),c.selection().netDebt(),List.of(),latest.getFirst().getCoverageStatus(),latest.getFirst().getSelectedRoute(),end,latest.getFirst().getFiledDate(),components(latest),tokens(latest,true),tokens(latest,false),List.of());
        }
        List<String> missing = new ArrayList<>();
        if (c.selection().debt() == null) missing.add("totalDebt");
        if (c.selection().cash() == null) missing.add("cashAndEquivalents");
        missing.addAll(List.of("commercialPaper", "currentTermDebt", "noncurrentTermDebt", "otherDebtLikeItems"));
        String status = c.selection().debt() == null || c.selection().cash() == null ? "UNAVAILABLE" : "INCOMPLETE";
        return new DebtBreakdownResponse(status, c.selection().debt(), null, null, null, null,
                c.selection().cash(), c.selection().shortTermInvestments(),
                c.selection().noncurrentMarketableSecurities(), c.selection().netDebt(), missing);
    }

    private NetBorrowingBreakdownResponse netBorrowingBreakdown(Context c) {
        List<SecDebtEvidence> evidence = debtEvidence("NET_BORROWING", c);
        if (!evidence.isEmpty()) { LocalDate end=evidence.get(evidence.size()-1).getPeriodEnd(); List<SecDebtEvidence> latest=evidence.stream().filter(e->end.equals(e.getPeriodEnd())).toList(); return new NetBorrowingBreakdownResponse("COMPLETE".equals(latest.getFirst().getCoverageStatus())?"AVAILABLE":"INCOMPLETE",latestFourSum(c.selection().quarters(),ValuationEngine.Quarter::netBorrowing),amount(latest,"COMMERCIAL_PAPER"),amount(latest,"OTHER_SHORT_TERM"),amount(latest,"LONG_TERM"),List.of(),latest.getFirst().getCoverageStatus(),latest.getFirst().getSelectedRoute(),end,components(latest),tokens(latest,true),tokens(latest,false),latest.getFirst().getQuarterizationMethod(),List.of()); }
        BigDecimal total = latestFourSum(c.selection().quarters(), ValuationEngine.Quarter::netBorrowing);
        List<String> missing = new ArrayList<>(List.of("commercialPaperNetBorrowing",
                "otherShortTermNetBorrowing", "longTermNetBorrowing"));
        if (total == null) missing.add("totalNetBorrowing");
        return new NetBorrowingBreakdownResponse(total == null ? "UNAVAILABLE" : "INCOMPLETE",
                total, null, null, null, missing);
    }

    private List<SecDebtEvidence> debtEvidence(String metric, Context c) { if (debtEvidenceRepository==null || c.selection().quarters().isEmpty()) return List.of(); LocalDate to=c.selection().quarters().getLast().periodEnd(); return debtEvidenceRepository.findBySymbolAndMetricTypeAndPeriodEndBetweenOrderByPeriodEndAsc(c.symbol(),metric,to.minusYears(2),to); }
    private boolean hasCompleteNetBorrowingEvidence(Context c) {
        List<ValuationEngine.Quarter> quarters = c.selection().quarters();
        if (quarters.size() < 4) return false;
        List<LocalDate> latestFour = quarters.subList(quarters.size() - 4, quarters.size()).stream()
                .map(ValuationEngine.Quarter::periodEnd).toList();
        Map<LocalDate, List<SecDebtEvidence>> byPeriod = debtEvidence("NET_BORROWING", c).stream()
                .collect(java.util.stream.Collectors.groupingBy(SecDebtEvidence::getPeriodEnd));
        return latestFour.stream().allMatch(period -> {
            List<SecDebtEvidence> rows = byPeriod.get(period);
            return rows != null && !rows.isEmpty()
                    && rows.stream().allMatch(row -> "COMPLETE".equals(row.getCoverageStatus()));
        });
    }
    private BigDecimal amount(List<SecDebtEvidence> rows,String type){ return rows.stream().filter(e->type.equals(e.getComponentType())).map(SecDebtEvidence::getAmount).reduce(BigDecimal.ZERO,BigDecimal::add); }
    private List<DebtComponentResponse> components(List<SecDebtEvidence> rows){ return rows.stream().map(e->new DebtComponentResponse(e.getComponentType(),e.getAmount(),split(e.getSourceConcepts()),split(e.getAccessionNumbers()))).toList(); }
    private List<String> tokens(List<SecDebtEvidence> rows,boolean concepts){ return rows.stream().flatMap(e->(concepts?split(e.getSourceConcepts()):split(e.getAccessionNumbers())).stream()).distinct().toList(); }
    private List<String> split(String value){ return value==null||value.isBlank()?List.of():List.of(value.split(",")); }

    private DiscountRateBreakdownResponse discountRateBreakdown(String method, Context c) {
        BigDecimal riskFree = c.market().riskFreeRatePct();
        BigDecimal beta = c.market().beta();
        BigDecimal erp = c.market().equityRiskPremiumPct();
        BigDecimal coe = riskFree == null || beta == null || erp == null
                ? null : riskFree.add(beta.multiply(erp, ValuationEngine.MC), ValuationEngine.MC);
        ValuationEngine.MethodSelection selected = c.selection().methodSelection(method);
        BigDecimal discount = selected == null ? null : selected.automaticDiscountRatePct();
        List<String> missing = new ArrayList<>();
        if (riskFree == null) missing.add("riskFreeRatePct");
        if (beta == null) missing.add("beta");
        if (erp == null) missing.add("equityRiskPremiumPct");
        if ("FCFE".equals(method)) {
            if (discount == null) missing.add("costOfEquityPct");
            return new DiscountRateBreakdownResponse(missing.isEmpty() ? "AVAILABLE" : "UNAVAILABLE",
                    "COST_OF_EQUITY", rate(riskFree), rate(beta), rate(erp), rate(coe),
                    null, null, null, null, rate(discount), missing);
        }

        BigDecimal debt = c.selection().debt();
        BigDecimal marketCap = c.market().currentPrice() == null || c.market().sharesOutstanding() == null
                ? null : c.market().currentPrice().multiply(c.market().sharesOutstanding(), ValuationEngine.MC);
        BigDecimal preTaxDebt = null;
        BigDecimal afterTaxDebt = null;
        BigDecimal equityWeight = null;
        BigDecimal debtWeight = null;
        if (debt == null) missing.add("totalDebt");
        if (marketCap == null || marketCap.signum() <= 0) missing.add("marketCapitalization");
        if (debt != null && marketCap != null && marketCap.signum() > 0) {
            boolean immaterial = debt.signum() <= 0
                    || debt.divide(marketCap, ValuationEngine.MC).compareTo(new BigDecimal("0.01")) < 0;
            BigDecimal totalCapital = marketCap.add(debt.max(BigDecimal.ZERO), ValuationEngine.MC);
            equityWeight = marketCap.divide(totalCapital, ValuationEngine.MC).multiply(BigDecimal.valueOf(100));
            debtWeight = debt.max(BigDecimal.ZERO).divide(totalCapital, ValuationEngine.MC).multiply(BigDecimal.valueOf(100));
            if (immaterial) {
                preTaxDebt = BigDecimal.ZERO;
                afterTaxDebt = BigDecimal.ZERO;
            } else {
                BigDecimal interest = latestFourSum(c.selection().quarters(), ValuationEngine.Quarter::interestExpense);
                BigDecimal averageDebt = averageLatestFourDebt(c.selection().quarters());
                if (interest == null) missing.add("interestExpense");
                if (averageDebt == null || averageDebt.signum() <= 0) missing.add("averageDebt");
                if (interest != null && averageDebt != null && averageDebt.signum() > 0) {
                    preTaxDebt = interest.abs().divide(averageDebt, ValuationEngine.MC)
                            .multiply(BigDecimal.valueOf(100));
                    BigDecimal tax = c.selection().taxRatePct() == null ? null
                            : c.selection().taxRatePct().divide(BigDecimal.valueOf(100), ValuationEngine.MC);
                    if (tax == null) missing.add("taxRate");
                    else afterTaxDebt = preTaxDebt.multiply(BigDecimal.ONE.subtract(tax), ValuationEngine.MC);
                }
            }
        }
        if (discount == null) missing.add("waccPct");
        return new DiscountRateBreakdownResponse(missing.isEmpty() ? "AVAILABLE" : "INCOMPLETE", "WACC",
                rate(riskFree), rate(beta), rate(erp), rate(coe), rate(preTaxDebt), rate(afterTaxDebt),
                rate(equityWeight), rate(debtWeight), rate(discount), missing.stream().distinct().toList());
    }

    private List<ScenarioDriverBridgeResponse> scenarioDriverBridge(ValuationEngine.MethodSelection method,
                                                                    List<ValuationScenarioResponse> scenarios) {
        BigDecimal normalized = method == null ? null : method.normalizedBaseCashFlow();
        return scenarios.stream().map(scenario -> {
            ValuationAssumptions resolved = scenario.resolvedAssumptions();
            BigDecimal start = resolved == null ? null : resolved.baseCashFlow();
            BigDecimal adjustment = normalized == null || normalized.signum() == 0 || start == null ? null
                    : start.subtract(normalized, ValuationEngine.MC).divide(normalized.abs(), ValuationEngine.MC)
                    .multiply(BigDecimal.valueOf(100)).setScale(4, RoundingMode.HALF_UP);
            List<String> warnings = adjustment != null && adjustment.signum() != 0
                    ? List.of("Scenario starting cash flow differs from the normalized method base due to an explicit override.")
                    : List.of();
            return new ScenarioDriverBridgeResponse(scenario.scenarioType(), "LEGACY_CASH_FLOW_FADE",
                    normalized, start, adjustment, resolved == null ? null : resolved.initialGrowthRatePct(),
                    resolved == null ? null : resolved.discountRatePct(),
                    resolved == null ? null : resolved.terminalGrowthRatePct(),
                    resolved == null ? null : resolved.projectionYears(), warnings);
        }).toList();
    }

    private CrossModelReconciliationResponse reconcile(String selectedModel,
                                                        List<ValuationScenarioResponse> fcff,
                                                        List<ValuationScenarioResponse> fcfe,
                                                        BigDecimal fcffOperatingBridgeGapPct) {
        Map<String, ValuationScenarioResponse> fcffByType = byScenarioType(fcff);
        Map<String, ValuationScenarioResponse> fcfeByType = byScenarioType(fcfe);
        List<String> types = new ArrayList<>();
        SCENARIO_TYPES.forEach(type -> {
            if (fcffByType.containsKey(type) || fcfeByType.containsKey(type)) types.add(type);
        });
        List<CrossModelReconciliationResponse.Scenario> scenarios = types.stream().map(type -> {
            ValuationScenarioResponse fcffScenario = fcffByType.get(type);
            ValuationScenarioResponse fcfeScenario = fcfeByType.get(type);
            BigDecimal fcffValue = value(fcffScenario);
            BigDecimal fcfeValue = value(fcfeScenario);
            BigDecimal difference = engine.crossModelDifferencePct(fcffValue, fcfeValue);
            String readiness = engine.crossModelReadiness(difference, fcffValue, fcfeValue);
            String primary = "FCFE".equalsIgnoreCase(selectedModel) ? "FCFE" : "FCFF";
            BigDecimal primaryValue = "FCFF".equals(primary) ? fcffValue : fcfeValue;
            BigDecimal crossValue = "FCFF".equals(primary) ? fcfeValue : fcffValue;
            List<String> warnings = "UNAVAILABLE".equals(readiness)
                    ? List.of("Both FCFF and FCFE must be available for cross-model reconciliation.")
                    : "NOT_READY".equals(readiness)
                    ? List.of("FCFF and FCFE differ by more than 25%.")
                    : "READY_WITH_CAVEATS".equals(readiness)
                    ? List.of("FCFF and FCFE differ by more than 10%.") : List.of();
            return new CrossModelReconciliationResponse.Scenario(type, primary, primaryValue,
                    "FCFF".equals(primary) ? "FCFE" : "FCFF", crossValue, difference, readiness, warnings);
        }).toList();
        CrossModelReconciliationResponse.Scenario base = scenarios.stream()
                .filter(s -> "BASE".equals(s.scenarioType())).findFirst()
                .orElse(scenarios.isEmpty() ? null : scenarios.getFirst());
        ValuationScenarioResponse baseFcff = base == null ? null : fcffByType.get(base.scenarioType());
        ValuationScenarioResponse baseFcfe = base == null ? null : fcfeByType.get(base.scenarioType());
        boolean fcffAvailable = value(baseFcff) != null;
        boolean fcfeAvailable = value(baseFcfe) != null;
        String readiness = fcffAvailable && fcfeAvailable ? base.readiness()
                : fcffAvailable || fcfeAvailable ? "READY_WITH_CAVEATS" : "NOT_READY";
        List<String> topWarnings = new ArrayList<>(scenarios.stream()
                .flatMap(s -> s.warnings().stream()).distinct().toList());
        if (fcffAvailable ^ fcfeAvailable) {
            topWarnings.add("Only one DCF method is available; valuation can be used only with caveats.");
        } else if (!fcffAvailable && !fcfeAvailable) {
            topWarnings.add("Neither FCFF nor FCFE is available; valuation is not ready.");
        }
        if (fcffOperatingBridgeGapPct != null && fcffOperatingBridgeGapPct.compareTo(BigDecimal.TEN) > 0) {
            if ("READY".equals(readiness)) readiness = "READY_WITH_CAVEATS";
            topWarnings.add("FCFF operating reconstruction exceeds the 10% bridge tolerance; do not treat the dual-track result as fully ready.");
        }
        return new CrossModelReconciliationResponse(readiness, "COMPARABLE_SHARED_FORECAST", base == null ? null : base.differencePct(),
                scenarios, topWarnings.stream().distinct().toList());
    }

    private Map<String, ValuationScenarioResponse> byScenarioType(List<ValuationScenarioResponse> scenarios) {
        LinkedHashMap<String, ValuationScenarioResponse> result = new LinkedHashMap<>();
        scenarios.forEach(scenario -> result.put(scenario.scenarioType(), scenario));
        return result;
    }

    private List<String> concatStrings(List<String> left, List<String> right) {
        ArrayList<String> values = new ArrayList<>(left == null ? List.of() : left);
        if (right != null) values.addAll(right);
        return values.stream().filter(Objects::nonNull).distinct().toList();
    }

    private Context context(String rawSymbol, BigDecimal taxOverridePct) {
        String symbol = normalize(rawSymbol);
        Position position = positionRepository.findBySymbolIgnoreCase(symbol)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Position not found"));
        LocalDate today = LocalDate.now(marketZone);
        List<EarningsHistory> raw = earningsRepository.findBySymbolAndAsOfDateBetweenOrderByAsOfDateAsc(symbol, today.minusYears(16), today);
        Map<String, Map<String, Object>> corrections = reviewedDataResolver.correctedValues("fundamentals", raw.stream().map(EarningsHistory::getId).toList());
        Set<String> rejected = reviewedDataResolver.rejectedRecordIds("fundamentals", raw.stream().map(EarningsHistory::getId).toList());
        List<EarningsHistory> acceptedRows = canonicalQuarterRows(raw.stream()
                .filter(row -> !rejected.contains(String.valueOf(row.getId())))
                .toList());
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
        CashFlowBridgeResponse cashFlowBridge = cashFlowBridgeResolver.resolve(symbol, quarters, selection.taxRatePct());
        GrowthBundle growth = growth(symbol, selection);
        ValuationResponse.Applicability applicability = applicability(position, quarters, selection);
        List<StockSplit> splits = stockSplits(symbol, today.minusYears(20), today);
        CapeComputation cape = cape(position, quarters, prices, currentPrice, priceDate, isOperatingCompany(position), splits);
        LocalDate financialDate = quarters.isEmpty() ? null : quarters.get(quarters.size() - 1).periodEnd();
        LocalDate filingDate = quarters.stream().map(ValuationEngine.Quarter::filingDate).filter(Objects::nonNull).max(LocalDate::compareTo).orElse(null);
        ValuationResponse.FundamentalsFreshness fundamentalsFreshness = fundamentalsFreshness(today, financialDate, filingDate);
        EarningsHistory latestAccepted = acceptedRows.isEmpty() ? null : acceptedRows.get(acceptedRows.size() - 1);
        Map<String, Object> latestOverrides = latestAccepted == null ? Map.of()
                : corrections.getOrDefault(String.valueOf(latestAccepted.getId()), Map.of());
        return new Context(symbol, position, quarters, prices, market, selection, growth, applicability, cape, cashFlowBridge, fundamentalsFreshness, priceDate,
                financialDate, filingDate, valuationFieldSources(latestAccepted, latestOverrides));
    }

    private List<EarningsHistory> canonicalQuarterRows(List<EarningsHistory> rows) {
        Map<LocalDate, EarningsHistory> canonical = new LinkedHashMap<>();
        for (EarningsHistory row : rows) {
            if (row.getAsOfDate() == null) continue;
            EarningsHistory current = canonical.get(row.getAsOfDate());
            if (current == null || comparePublication(row, current) > 0) canonical.put(row.getAsOfDate(), row);
        }
        return canonical.values().stream().sorted(Comparator.comparing(EarningsHistory::getAsOfDate)).toList();
    }

    private int comparePublication(EarningsHistory left, EarningsHistory right) {
        LocalDate leftFiled = left.getFilingDate() == null ? LocalDate.MIN : left.getFilingDate();
        LocalDate rightFiled = right.getFilingDate() == null ? LocalDate.MIN : right.getFilingDate();
        int filed = leftFiled.compareTo(rightFiled);
        if (filed != 0) return filed;
        return Long.compare(left.getId() == null ? Long.MIN_VALUE : left.getId(), right.getId() == null ? Long.MIN_VALUE : right.getId());
    }

    private ValuationResponse.FundamentalsFreshness fundamentalsFreshness(LocalDate asOf, LocalDate financialDate, LocalDate filingDate) {
        List<String> reasons = new ArrayList<>();
        Long financialAge = financialDate == null ? null : ChronoUnit.DAYS.between(financialDate, asOf);
        Long filingAge = filingDate == null ? null : ChronoUnit.DAYS.between(filingDate, asOf);
        if (financialDate == null) reasons.add("No canonical financial period is available.");
        else if (financialDate.isAfter(asOf)) reasons.add("Latest financial period is after the valuation date.");
        else if (financialAge > 135) reasons.add("Latest financial period is " + financialAge + " days old; limit is 135 days.");
        if (filingDate == null) reasons.add("No SEC filing date is available for the selected fundamentals.");
        else if (filingDate.isAfter(asOf)) reasons.add("Latest filing date is after the valuation date.");
        else if (filingAge > 120) reasons.add("Latest filing is " + filingAge + " days old; limit is 120 days.");
        String status = reasons.isEmpty() ? "CURRENT" : "STALE_FUNDAMENTALS";
        return new ValuationResponse.FundamentalsFreshness(status, financialDate, filingDate, financialAge, filingAge, List.copyOf(reasons));
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
        ValuationScenarioResponse evaluated = engine.evaluateSettings(type, origin, compatibilitySettings(assumptions, c), c.selection(), c.market(), c.growth().inputs(),
                persisted == null ? null : persisted.getUpdatedAt());
        if (persisted == null) return evaluated;
        boolean versionChanged = persisted.getEngineVersion() != null && !engineVersion.equals(persisted.getEngineVersion());
        boolean basisChanged = persisted.getCashFlowBasisAtSave() == null
                || !Objects.equals(persisted.getCashFlowBasisAtSave(), c.selection().model());
        boolean manual = evaluated.manualOverrides() != null && !evaluated.manualOverrides().isEmpty();
        String migrationStatus = versionChanged && basisChanged && manual ? "REVIEW_REQUIRED" : "CURRENT";
        if ("REVIEW_REQUIRED".equals(migrationStatus)) {
            List<String> warnings = new ArrayList<>(evaluated.warnings());
            warnings.add("Saved manual assumptions were created on a different cash-flow basis and require review.");
            evaluated = new ValuationScenarioResponse(evaluated.scenarioType(), evaluated.modelMode(),
                    evaluated.selectedModel(), "REVIEW_REQUIRED", evaluated.assumptions(), false,
                    evaluated.intrinsicValuePerShare(), evaluated.marginOfSafetyPrice(), evaluated.enterpriseValue(),
                    evaluated.equityValue(), evaluated.terminalValueWeightPct(), evaluated.projection(), warnings,
                    evaluated.updatedAt(), evaluated.resolvedAssumptions(), evaluated.assumptionSources(),
                    evaluated.manualOverrides());
        }
        return withMigrationMetadata(evaluated, persisted, migrationStatus);
    }

    private ValuationScenarioResponse withMigrationMetadata(ValuationScenarioResponse response,
                                                             ValuationScenario persisted,
                                                             String migrationStatus) {
        return new ValuationScenarioResponse(response.scenarioType(), response.modelMode(), response.selectedModel(),
                response.origin(), response.assumptions(), response.valid(), response.intrinsicValuePerShare(),
                response.marginOfSafetyPrice(), response.enterpriseValue(), response.equityValue(),
                response.terminalValueWeightPct(), response.projection(), response.warnings(), response.updatedAt(),
                response.resolvedAssumptions(), response.assumptionSources(), response.manualOverrides(),
                migrationStatus, persisted.getEngineVersion(), engineVersion);
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

    private ValuationResponse.DataQuality quality(Context c, List<ValuationScenarioResponse> scenarios,
                                                  CrossModelReconciliationResponse reconciliation) {
        if (!c.applicability().applicable()) return new ValuationResponse.DataQuality("Unavailable", c.applicability().reasons());
        List<String> reasons = new ArrayList<>();
        BigDecimal diff = c.selection().crossCheckDifferencePct();
        String grade = "High";
        if (diff == null) { grade = "Medium"; reasons.add("FCFF cross-check is incomplete."); }
        else if (diff.compareTo(BigDecimal.valueOf(25)) > 0) { grade = "Low"; reasons.add("FCFF definitions differ by more than 25%."); }
        else if (diff.compareTo(BigDecimal.TEN) > 0) { grade = "Medium"; reasons.add("FCFF definitions differ by 10%–25%."); }
        if (c.selection().warnings().stream().anyMatch(w -> w.contains("Severe"))) grade = "Low";
        if (reconciliation != null && "NOT_READY".equals(reconciliation.readiness())) {
            grade = "Low";
            reasons.add(reconciliation.baseDifferencePct() == null
                    ? "Neither FCFF nor FCFE BASE valuation is available."
                    : "FCFF and FCFE BASE values differ by more than 25%.");
        } else if (reconciliation != null && "READY_WITH_CAVEATS".equals(reconciliation.readiness())) {
            if ("High".equals(grade)) grade = "Medium";
            reasons.add(reconciliation.baseDifferencePct() == null
                    ? "Only one DCF method is available for the BASE scenario."
                    : "FCFF and FCFE BASE values differ by 10%–25%.");
        } else if (reconciliation != null && "UNAVAILABLE".equals(reconciliation.readiness())) {
            if ("High".equals(grade)) grade = "Medium";
            reasons.add("Cross-model reconciliation is unavailable.");
        }
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

    private Map<String, Object> cashFlow(Context c) { return cashFlow(c, c.selection().model()); }

    private Map<String, Object> cashFlow(Context c, String model) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("model", model);
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

    /** Explicit forecasts have Year 1 forecast cash flow, not a historical TTM or legacy base cash flow. */
    private Map<String, Object> cashFlow(Context c, ForecastPreviewResponse preview) {
        ExplicitOperatingForecastResult base = preview.scenarios().get("BASE");
        ExplicitOperatingForecastResult.ValuationTrack fcff = base.fcff();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("model", "EXPLICIT_OPERATING_FORECAST");
        result.put("forecastMode", "EXPLICIT_OPERATING_FORECAST");
        result.put("cashFlowDefinition", fcff.cashFlowDefinition());
        result.put("latestTtmCashFlow", null);
        result.put("baseCashFlow", null);
        result.put("baseYear1ForecastCashFlow", fcff.discountedCashFlows().isEmpty() ? null : fcff.discountedCashFlows().getFirst().cashFlow());
        result.put("terminalYearForecastCashFlow", fcff.discountedCashFlows().isEmpty() ? null : fcff.discountedCashFlows().getLast().cashFlow());
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

    private List<ValuationResponse.Diagnostic> diagnostics(Context c, ValuationScenarioResponse base,
                                                            CrossModelReconciliationResponse reconciliation) {
        List<ValuationResponse.Diagnostic> result = new ArrayList<>();
        for (String reason : c.applicability().reasons()) result.add(new ValuationResponse.Diagnostic("NOT_APPLICABLE", "critical", reason, c.symbol()));
        if ("STALE_FUNDAMENTALS".equals(c.fundamentalsFreshness().status())) {
            result.add(new ValuationResponse.Diagnostic("STALE_FUNDAMENTALS", "critical",
                    "Valuation uses fundamentals outside the permitted reporting-age window.",
                    String.join(" ", c.fundamentalsFreshness().reasons())));
        }
        if (c.cashFlowBridge() != null && !"COMPLETE".equals(c.cashFlowBridge().coverageStatus())) {
            result.add(new ValuationResponse.Diagnostic("FCFF_ECONOMIC_BRIDGE_INCOMPLETE", "critical",
                    "Economic FCFF is not available until the indirect-CFO bridge is sourced from non-overlapping SEC filing statement relationships.",
                    "coverage=" + c.cashFlowBridge().coverageStatus() + "; residual=" + c.cashFlowBridge().residual()));
        }
        if (c.selection().crossCheckDifferencePct() != null && c.selection().crossCheckDifferencePct().compareTo(BigDecimal.TEN) > 0)
            result.add(new ValuationResponse.Diagnostic("FCFF_OPERATING_BRIDGE_GAP",
                    c.selection().crossCheckDifferencePct().compareTo(BigDecimal.valueOf(25)) > 0 ? "critical" : "warning",
                    "Reported-cash FCFF and the incomplete NOPAT operating reconstruction do not reconcile within 10%.",
                    c.selection().crossCheckDifferencePct() + "%"));
        if (base != null && base.terminalValueWeightPct() != null && base.terminalValueWeightPct().compareTo(BigDecimal.valueOf(70)) >= 0)
            result.add(new ValuationResponse.Diagnostic("HIGH_TERMINAL_VALUE_WEIGHT", "warning",
                    "Valuation is highly dependent on terminal value.", base.terminalValueWeightPct() + "%"));
        if (reconciliation != null && reconciliation.baseDifferencePct() != null
                && ("READY_WITH_CAVEATS".equals(reconciliation.readiness())
                || "NOT_READY".equals(reconciliation.readiness()))) {
            result.add(new ValuationResponse.Diagnostic("CROSS_MODEL_RECONCILIATION",
                    "NOT_READY".equals(reconciliation.readiness()) ? "critical" : "warning",
                    "FCFF and FCFE BASE values do not reconcile within 10%.",
                    reconciliation.baseDifferencePct() + "%"));
        } else if (reconciliation != null && reconciliation.baseDifferencePct() == null) {
            result.add(new ValuationResponse.Diagnostic("CROSS_MODEL_RECONCILIATION_UNAVAILABLE", "info",
                    "Both FCFF and FCFE are required for cross-model reconciliation.", c.symbol()));
        }
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
                accountingDeltaNwc(reviewedDataResolver.decimal(o, "changeInWorkingCapital", h.getChangeInWorkingCapital())),
                reviewedDataResolver.decimal(o, "operatingIncome", h.getOperatingIncome()),
                reviewedDataResolver.decimal(o, "taxProvision", h.getTaxProvision()), reviewedDataResolver.decimal(o, "pretaxIncome", h.getPretaxIncome()),
                reviewedDataResolver.decimal(o, "netIncome", h.getNetIncome()), reviewedDataResolver.decimal(o, "stockholdersEquity", h.getStockholdersEquity()),
                reviewedDataResolver.decimal(o, "totalDebt", h.getTotalDebt()), reviewedDataResolver.decimal(o, "cashAndEquivalents", h.getCashAndEquivalents()),
                reviewedDataResolver.decimal(o, "shortTermInvestments", h.getShortTermInvestments()),
                reviewedDataResolver.decimal(o, "noncurrentMarketableSecurities", h.getNoncurrentMarketableSecurities()),
                reviewedDataResolver.decimal(o, "investedCapital", h.getInvestedCapital()), reviewedDataResolver.decimal(o, "revenue", h.getRevenue()),
                reviewedDataResolver.decimal(o, "grossProfit", h.getGrossProfit()), reviewedDataResolver.decimal(o, "totalAssets", h.getTotalAssets()),
                reviewedDataResolver.text(o, "currencyCode", h.getCurrencyCode()));
    }

    /** SEC/Yahoo stores the cash-flow-statement working-capital effect; DCF uses accounting ΔNWC. */
    private BigDecimal accountingDeltaNwc(BigDecimal cashFlowStatementEffect) {
        return cashFlowStatementEffect == null ? null : cashFlowStatementEffect.negate();
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
    private BigDecimal latestFourSum(List<ValuationEngine.Quarter> rows,
                                     Function<ValuationEngine.Quarter, BigDecimal> getter) {
        if (rows == null || rows.size() < 4) return null;
        return sum(rows.subList(rows.size() - 4, rows.size()), getter);
    }
    private BigDecimal averageLatestFourDebt(List<ValuationEngine.Quarter> rows) {
        if (rows == null || rows.size() < 4) return null;
        BigDecimal start = rows.get(rows.size() - 4).debt();
        BigDecimal end = rows.get(rows.size() - 1).debt();
        return average(start, end);
    }
    private BigDecimal rate(BigDecimal value) {
        return value == null ? null : value.setScale(4, RoundingMode.HALF_UP);
    }
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
                           CapeComputation cape, CashFlowBridgeResponse cashFlowBridge, ValuationResponse.FundamentalsFreshness fundamentalsFreshness, LocalDate priceDate, LocalDate financialDate, LocalDate filingDate,
                           Map<String, FieldSourceResponse> fieldSources) { }
    private record CapeComputation(ValuationResponse.CapeSummary summary, LocalDate cpiDate) {
        static CapeComputation unavailable(String status, LocalDate cpiDate, List<String> missing) {
            return new CapeComputation(new ValuationResponse.CapeSummary(status, null, null, null, null, null,
                    0, null, null, List.of(), missing), cpiDate);
        }
    }
    private record GrowthBundle(ValuationEngine.GrowthInputs inputs, List<GrowthReferenceResponse> references) { }
    private record DualTrackBundle(ValuationMethodsResponse methods,
                                   CrossModelReconciliationResponse reconciliation,
                                   String readiness) { }
}
