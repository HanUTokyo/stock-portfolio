package com.stockportfolio.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockportfolio.model.FundamentalFactObservation;
import com.stockportfolio.repository.FundamentalFactObservationRepository;
import com.stockportfolio.repository.SecDebtEvidenceRepository;
import com.stockportfolio.model.SecDebtEvidence;
import com.stockportfolio.repository.SecShareCountEvidenceRepository;
import com.stockportfolio.repository.StockSplitRepository;
import com.stockportfolio.model.SecShareCountEvidence;
import com.stockportfolio.model.StockSplit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.regex.Pattern;

@Service
public class SecCompanyFactsService {
    private static final Pattern QUARTER_FRAME = Pattern.compile("CY\\d{4}Q[1-4]");
    private static final Pattern INSTANT_QUARTER_FRAME = Pattern.compile("CY\\d{4}Q[1-4]I");

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String userAgent;

    private volatile Map<String, String> cikByTicker;
    private FundamentalFactObservationRepository factObservationRepository;
    private SecFilingXbrlGraphService filingXbrlGraphService;
    private SecDebtEvidenceRepository debtEvidenceRepository;
    private SecShareCountEvidenceRepository shareCountEvidenceRepository;
    private StockSplitRepository stockSplitRepository;

    public SecCompanyFactsService(ObjectMapper objectMapper,
                                  @Value("${app.sec.user-agent:stock-portfolio kaihan@example.com}") String userAgent) {
        this.objectMapper = objectMapper;
        this.userAgent = userAgent;
        this.httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    @Autowired(required = false)
    void setFactObservationRepository(FundamentalFactObservationRepository repository) {
        this.factObservationRepository = repository;
    }

    @Autowired(required = false)
    void setFilingXbrlGraphService(SecFilingXbrlGraphService service) { this.filingXbrlGraphService = service; }
    @Autowired(required = false) void setDebtEvidenceRepository(SecDebtEvidenceRepository repository) { this.debtEvidenceRepository = repository; }
    @Autowired(required = false) void setShareCountEvidenceRepository(SecShareCountEvidenceRepository repository) { this.shareCountEvidenceRepository = repository; }
    @Autowired(required = false) void setStockSplitRepository(StockSplitRepository repository) { this.stockSplitRepository = repository; }

    public List<YahooFinancePriceService.QuarterlyFundamentalPoint> fetchQuarterlyFundamentalsHistory(
            String symbol,
            LocalDate from,
            LocalDate to
    ) throws IOException, InterruptedException {
        Optional<String> cikOpt = resolveCik(symbol);
        if (cikOpt.isEmpty()) {
            return List.of();
        }

        JsonNode root = readJson("https://data.sec.gov/api/xbrl/companyfacts/CIK" + cikOpt.get() + ".json");
        JsonNode usGaap = root.path("facts").path("us-gaap");
        if (usGaap.isMissingNode() || usGaap.isNull()) {
            return List.of();
        }

        persistDilutedEpsVintages(symbol, usGaap, from, to);
        persistSharesOutstandingObservations(symbol, root.path("facts").path("dei"), from, to);
        persistCashFlowStatementGraphs(symbol, cikOpt.get(), usGaap, from, to);

        Map<LocalDate, SecQuarter> byDate = new LinkedHashMap<>();
        addStandaloneDurationMetric(byDate, usGaap, from, to, "USD/shares", SecQuarter::getBasicEps, SecQuarter::setBasicEps,
                "EarningsPerShareBasic", "EarningsPerShareBasicAndDiluted");
        addFirstMetric(byDate, usGaap, from, to, false, "USD/shares", SecQuarter::getBasicEps, SecQuarter::setBasicEps,
                "EarningsPerShareBasic", "EarningsPerShareBasicAndDiluted");
        addYtdMetric(byDate, usGaap, from, to, "USD/shares", SecQuarter::getBasicEps, SecQuarter::setBasicEps,
                "EarningsPerShareBasic", "EarningsPerShareBasicAndDiluted");
        addStandaloneDurationMetric(byDate, usGaap, from, to, "USD/shares", SecQuarter::getDilutedEps, SecQuarter::setDilutedEps,
                "EarningsPerShareDiluted", "EarningsPerShareBasicAndDiluted");
        addFirstMetric(byDate, usGaap, from, to, false, "USD/shares", SecQuarter::getDilutedEps, SecQuarter::setDilutedEps,
                "EarningsPerShareDiluted", "EarningsPerShareBasicAndDiluted");
        addStandaloneDurationMetric(byDate, usGaap, from, to, "shares", SecQuarter::getDilutedWeightedAverageShares, SecQuarter::setDilutedWeightedAverageShares,
                "WeightedAverageNumberOfDilutedSharesOutstanding", "WeightedAverageNumberOfShareOutstandingBasicAndDiluted");
        addFirstMetric(byDate, usGaap, from, to, false, "shares", SecQuarter::getDilutedWeightedAverageShares, SecQuarter::setDilutedWeightedAverageShares,
                "WeightedAverageNumberOfDilutedSharesOutstanding", "WeightedAverageNumberOfShareOutstandingBasicAndDiluted");
        addFlowMetric(byDate, usGaap, from, to, "USD", SecQuarter::getRevenue, SecQuarter::setRevenue,
                "RevenueFromContractWithCustomerExcludingAssessedTax", "Revenues", "SalesRevenueNet");
        addFlowMetric(byDate, usGaap, from, to, "USD", SecQuarter::getGrossProfit, SecQuarter::setGrossProfit,
                "GrossProfit");
        addFlowMetric(byDate, usGaap, from, to, "USD", SecQuarter::getOperatingIncome, SecQuarter::setOperatingIncome,
                "OperatingIncomeLoss", "IncomeLossFromContinuingOperationsBeforeIncomeTaxesExtraordinaryItemsNoncontrollingInterest");
        addFlowMetric(byDate, usGaap, from, to, "USD", SecQuarter::getNetIncome, SecQuarter::setNetIncome,
                "NetIncomeLoss", "ProfitLoss");
        addCashFlowMetric(byDate, usGaap, from, to, "USD", SecQuarter::getCashFlow, SecQuarter::setCashFlow,
                "NetCashProvidedByUsedInOperatingActivities", "NetCashProvidedByUsedInOperatingActivitiesContinuingOperations");
        addFlowMetric(byDate, usGaap, from, to, "USD", SecQuarter::getInterestExpense, SecQuarter::setInterestExpense,
                "InterestExpense", "InterestExpenseNonOperating", "InterestAndDebtExpense");
        // Fallback for indicative WACC only: cash interest is not accrued interest and must
        // never be presented as a verified economic-FCFF unlevering adjustment. It is used
        // only when no income-statement interest expense is available.
        addCashFlowMetric(byDate, usGaap, from, to, "USD", SecQuarter::getInterestExpense, SecQuarter::setInterestExpense,
                "InterestPaidNet", "InterestPaid");
        addFlowMetric(byDate, usGaap, from, to, "USD", SecQuarter::getTaxProvision, SecQuarter::setTaxProvision,
                "IncomeTaxExpenseBenefit");
        addFlowMetric(byDate, usGaap, from, to, "USD", SecQuarter::getPretaxIncome, SecQuarter::setPretaxIncome,
                "IncomeLossFromContinuingOperationsBeforeIncomeTaxesExtraordinaryItemsNoncontrollingInterest",
                "IncomeLossFromContinuingOperationsBeforeIncomeTaxes",
                "IncomeLossFromContinuingOperationsBeforeIncomeTaxesMinorityInterestAndIncomeLossFromEquityMethodInvestments");
        addFirstMetric(byDate, usGaap, from, to, true, "USD", SecQuarter::getStockholdersEquity, SecQuarter::setStockholdersEquity,
                "StockholdersEquity", "StockholdersEquityIncludingPortionAttributableToNoncontrollingInterest");
        addFirstMetric(byDate, usGaap, from, to, true, "USD", SecQuarter::getCashAndEquivalents, SecQuarter::setCashAndEquivalents,
                "CashAndCashEquivalentsAtCarryingValue",
                "CashCashEquivalentsRestrictedCashAndRestrictedCashEquivalents");
        addFirstMetric(byDate, usGaap, from, to, true, "USD", SecQuarter::getShortTermInvestments, SecQuarter::setShortTermInvestments,
                "ShortTermInvestments", "MarketableSecuritiesCurrent");
        addFirstMetric(byDate, usGaap, from, to, true, "USD", SecQuarter::getNoncurrentMarketableSecurities, SecQuarter::setNoncurrentMarketableSecurities,
                "MarketableSecuritiesNoncurrent");
        addFirstMetric(byDate, usGaap, from, to, true, "USD", SecQuarter::getInvestedCapital, SecQuarter::setInvestedCapital,
                "InvestedCapital");
        addResolvedDebtMetrics(symbol, byDate, usGaap, from, to);
        addCashFlowMetric(byDate, usGaap, from, to, "USD", SecQuarter::getCapex, SecQuarter::setCapex,
                "PaymentsToAcquirePropertyPlantAndEquipment", "PaymentsToAcquireProductiveAssets");
        addCashFlowMetric(byDate, usGaap, from, to, "USD", SecQuarter::getDepreciationAmortization, SecQuarter::setDepreciationAmortization,
                "DepreciationDepletionAndAmortization", "DepreciationDepletionAndAmortizationPropertyPlantAndEquipment");
        addCashFlowMetric(byDate, usGaap, from, to, "USD", SecQuarter::getChangeInWorkingCapital, SecQuarter::setChangeInWorkingCapital,
                "IncreaseDecreaseInOperatingCapital");
        addCashFlowMetric(byDate, usGaap, from, to, "USD", SecQuarter::getShareRepurchases, SecQuarter::setShareRepurchases,
                "PaymentsForRepurchaseOfCommonStock", "PaymentsForRepurchaseOfEquity", "PaymentsForRepurchaseOfStock");
        addFirstMetric(byDate, usGaap, from, to, true, "USD", SecQuarter::getTotalAssets, SecQuarter::setTotalAssets,
                "Assets");

        return byDate.values().stream()
                .map(SecQuarter::completeDerivedFields)
                .filter(SecQuarter::hasAnyFundamental)
                .map(SecQuarter::toPoint)
                .sorted(java.util.Comparator.comparing(YahooFinancePriceService.QuarterlyFundamentalPoint::asOfDate))
                .toList();
    }

    /** Re-ingests filing-scoped XBRL evidence without changing earnings-history values. */
    public void rebuildFilingCashFlowGraph(String symbol, LocalDate from, LocalDate to) throws IOException, InterruptedException {
        fetchQuarterlyFundamentalsHistory(symbol, from, to);
    }

    /** Rebuilds only filing-scoped SEC share-count evidence; never touches reviewed overlays or earnings history. */
    public void rebuildShareCountBridge(String symbol, LocalDate from, LocalDate to) throws IOException, InterruptedException {
        if (shareCountEvidenceRepository == null || filingXbrlGraphService == null) return;
        Optional<String> cik = resolveCik(symbol); if (cik.isEmpty()) return;
        JsonNode root = readJson("https://data.sec.gov/api/xbrl/companyfacts/CIK" + cik.get() + ".json");
        JsonNode values = root.path("facts").path("dei").path("EntityCommonStockSharesOutstanding").path("units").path("shares");
        Map<String, FilingMeta> filings = new LinkedHashMap<>();
        if (values.isArray()) for (JsonNode item : values) {
            String accession = item.path("accn").asText(""), filedRaw = item.path("filed").asText(""), form = item.path("form").asText("");
            try {
                LocalDate end = LocalDate.parse(item.path("end").asText("")), filed = LocalDate.parse(filedRaw);
                if (!accession.isBlank() && isQuarterlyOrAnnualForm(form) && !end.isBefore(from) && !end.isAfter(to))
                    filings.putIfAbsent(accession, new FilingMeta(filed, form));
            } catch (RuntimeException ignored) { }
        }
        List<SecShareCountEvidence> rebuilt = new ArrayList<>();
        List<StockSplit> splits = stockSplitRepository == null ? List.of() : stockSplitRepository.findBySymbolAndSplitDateBetweenOrderBySplitDateAsc(symbol, from, LocalDate.now());
        for (Map.Entry<String, FilingMeta> filing : filings.entrySet()) {
            SecFilingXbrlGraphService.FilingGraph graph = filingXbrlGraphService.load(cik.get(), filing.getKey());
            if ("AVAILABLE".equals(graph.status())) rebuilt.addAll(shareBridgeEvidence(symbol, graph, filing.getValue(), splits, from, to));
            Thread.sleep(110L); // SEC-friendly on-demand historical rebuild pacing
        }
        rebuilt = canonicalShareEvidence(rebuilt);
        shareCountEvidenceRepository.deleteBySymbolAndPeriodEndBetween(symbol, from, to);
        shareCountEvidenceRepository.flush();
        if (!rebuilt.isEmpty()) shareCountEvidenceRepository.saveAll(rebuilt);
    }

    /** Comparative columns recur in later filings; retain the original filing deterministically for each statement span. */
    private List<SecShareCountEvidence> canonicalShareEvidence(List<SecShareCountEvidence> evidence) {
        Map<String, List<SecShareCountEvidence>> byFilingSpan = evidence.stream().collect(java.util.stream.Collectors.groupingBy(
                row -> row.getPeriodStart() + "|" + row.getPeriodEnd() + "|" + row.getAccessionNumber(), LinkedHashMap::new, java.util.stream.Collectors.toList()));
        Map<String, List<SecShareCountEvidence>> selected = new LinkedHashMap<>();
        for (List<SecShareCountEvidence> candidate : byFilingSpan.values()) {
            SecShareCountEvidence first = candidate.getFirst();
            String span = first.getPeriodStart() + "|" + first.getPeriodEnd();
            List<SecShareCountEvidence> existing = selected.get(span);
            if (existing == null || compareFiling(candidate.getFirst(), existing.getFirst()) < 0) selected.put(span, candidate);
        }
        return selected.values().stream().flatMap(List::stream).toList();
    }

    private int compareFiling(SecShareCountEvidence left, SecShareCountEvidence right) {
        int filed = java.util.Comparator.nullsLast(LocalDate::compareTo).compare(left.getFiledDate(), right.getFiledDate());
        return filed != 0 ? filed : java.util.Comparator.nullsLast(String::compareTo).compare(left.getAccessionNumber(), right.getAccessionNumber());
    }

    private List<SecShareCountEvidence> shareBridgeEvidence(String symbol, SecFilingXbrlGraphService.FilingGraph graph,
                                                              FilingMeta filing, List<StockSplit> splits, LocalDate from, LocalDate to) {
        Map<ShareSpan, List<SecFilingXbrlGraphService.EquityFact>> bySpan = new LinkedHashMap<>();
        for (SecFilingXbrlGraphService.EquityFact fact : graph.nonOverlappingEquityLeafFacts()) {
            if (fact.start() != null && !"COMMON_SHARES_OUTSTANDING".equals(fact.bucket()))
                bySpan.computeIfAbsent(new ShareSpan(fact.start(), fact.end()), ignored -> new ArrayList<>()).add(fact);
        }
        Map<LocalDate, ShareSpan> preferred = new LinkedHashMap<>();
        for (ShareSpan span : bySpan.keySet()) preferred.merge(span.end(), span, (a, b) -> a.start().isBefore(b.start()) ? a : b);
        List<SecShareCountEvidence> result = new ArrayList<>();
        for (ShareSpan span : preferred.values()) {
            if (span.end().isBefore(from) || span.end().isAfter(to)) continue;
            List<SecFilingXbrlGraphService.EquityFact> components = bySpan.get(span);
            SecFilingXbrlGraphService.EquityFact beginning = outstandingAt(graph, span.start());
            // XBRL duration starts are exclusive for a balance carried from the prior day.
            if (beginning == null) beginning = outstandingAt(graph, span.start().minusDays(1));
            SecFilingXbrlGraphService.EquityFact ending = outstandingAt(graph, span.end());
            BigDecimal factor = splitFactor(span.end(), splits);
            BigDecimal begin = adjusted(beginning, factor), end = adjusted(ending, factor);
            Map<String, List<SecFilingXbrlGraphService.EquityFact>> byBucket = components.stream().collect(java.util.stream.Collectors.groupingBy(
                    SecFilingXbrlGraphService.EquityFact::bucket, LinkedHashMap::new, java.util.stream.Collectors.toList()));
            BigDecimal componentSum = byBucket.values().stream()
                    .map(facts -> facts.stream().map(this::signed).reduce(BigDecimal.ZERO, BigDecimal::add).multiply(factor))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal net = begin == null || end == null ? null : end.subtract(begin);
            BigDecimal residual = net == null ? null : net.subtract(componentSum);
            String coverage = begin != null && end != null && residual != null && residual.abs().compareTo(BigDecimal.valueOf(1_000)) <= 0 ? "COMPLETE" : "INCOMPLETE";
            String alignment = "STATEMENT_PERIOD_NOT_DIRECTLY_COMPARABLE_TO_COVER_PAGE";
            if (beginning != null) result.add(shareEvidence(symbol, span, "BEGINNING_SHARES", begin, coverage, beginning, filing, factor, alignment));
            if (ending != null) result.add(shareEvidence(symbol, span, "ENDING_SHARES", end, coverage, ending, filing, factor, alignment));
            for (Map.Entry<String, List<SecFilingXbrlGraphService.EquityFact>> entry : byBucket.entrySet()) {
                List<SecFilingXbrlGraphService.EquityFact> facts = entry.getValue();
                SecFilingXbrlGraphService.EquityFact representative = facts.getFirst();
                BigDecimal amount = facts.stream().map(this::signed).reduce(BigDecimal.ZERO, BigDecimal::add).multiply(factor);
                result.add(shareEvidence(symbol, span, entry.getKey(), amount, coverage, representative, filing, factor, alignment));
            }
            result.add(shareEvidence(symbol, span, "RESIDUAL", residual, coverage, ending != null ? ending : beginning, filing, factor, alignment));
        }
        return result;
    }

    private SecFilingXbrlGraphService.EquityFact outstandingAt(SecFilingXbrlGraphService.FilingGraph graph, LocalDate date) {
        return graph.nonOverlappingEquityLeafFacts().stream().filter(f -> "COMMON_SHARES_OUTSTANDING".equals(f.bucket()) && date.equals(f.end()))
                .sorted(java.util.Comparator.comparing(SecFilingXbrlGraphService.EquityFact::concept)).findFirst().orElse(null);
    }
    private BigDecimal signed(SecFilingXbrlGraphService.EquityFact fact) {
        BigDecimal value = new BigDecimal(fact.value()); return "TREASURY_STOCK_PURCHASES".equals(fact.bucket()) ? value.negate() : value;
    }
    private BigDecimal adjusted(SecFilingXbrlGraphService.EquityFact fact, BigDecimal factor) { return fact == null ? null : new BigDecimal(fact.value()).multiply(factor); }
    private BigDecimal splitFactor(LocalDate date, List<StockSplit> splits) {
        BigDecimal result = BigDecimal.ONE; for (StockSplit split : splits) if (split.getSplitDate().isAfter(date) && split.getDenominator().signum() > 0)
            result = result.multiply(split.getNumerator()).divide(split.getDenominator(), 12, java.math.RoundingMode.HALF_UP); return result;
    }
    private SecShareCountEvidence shareEvidence(String symbol, ShareSpan span, String type, BigDecimal amount, String coverage,
                                                 SecFilingXbrlGraphService.EquityFact fact, FilingMeta filing, BigDecimal factor, String alignment) {
        SecShareCountEvidence row = new SecShareCountEvidence(); row.setSymbol(symbol); row.setPeriodStart(span.start()); row.setPeriodEnd(span.end()); row.setComponentType(type); row.setAmount(amount);
        row.setCoverageStatus(coverage); row.setStatementRole(fact == null ? null : fact.statementRole()); row.setSourceConcepts(fact == null ? null : fact.concept()); row.setAccessionNumber(fact == null ? null : fact.accession());
        row.setForm(filing.form()); row.setFiledDate(filing.filed()); row.setSplitAdjustmentFactor(factor); row.setAlignmentStatus(alignment); return row;
    }
    private record ShareSpan(LocalDate start, LocalDate end) { }
    private record FilingMeta(LocalDate filed, String form) { }

    private Optional<String> resolveCik(String symbol) throws IOException, InterruptedException {
        if (symbol == null || symbol.isBlank()) {
            return Optional.empty();
        }
        Map<String, String> map = cikByTicker;
        if (map == null) {
            map = loadTickerMap();
            cikByTicker = map;
        }
        return Optional.ofNullable(map.get(symbol.trim().toUpperCase()));
    }

    private void persistDilutedEpsVintages(String symbol, JsonNode usGaap, LocalDate from, LocalDate to) {
        if (factObservationRepository == null) return;
        for (String concept : List.of("EarningsPerShareDiluted", "EarningsPerShareBasicAndDiluted")) {
            JsonNode values = usGaap.path(concept).path("units").path("USD/shares");
            if (!values.isArray()) continue;
            for (JsonNode item : values) {
                String endRaw = item.path("end").asText("");
                String filedRaw = item.path("filed").asText("");
                String form = item.path("form").asText("");
                if (endRaw.isBlank() || filedRaw.isBlank() || !("10-Q".equals(form) || "10-K".equals(form) || "10-K/A".equals(form))) continue;
                LocalDate end;
                LocalDate filed;
                try { end = LocalDate.parse(endRaw); filed = LocalDate.parse(filedRaw); } catch (RuntimeException e) { continue; }
                if (end.isBefore(from) || end.isAfter(to) || item.path("val").isMissingNode() || item.path("val").isNull()) continue;
                String accession = item.path("accn").asText("");
                if (factObservationRepository.existsBySymbolAndPeriodEndAndFieldNameAndSourceDateAndAccessionNumberAndUnit(
                        symbol, end, "dilutedEps", filed, accession, "USD/shares")) continue;
                FundamentalFactObservation observation = new FundamentalFactObservation();
                observation.setSymbol(symbol.trim().toUpperCase());
                observation.setPeriodEnd(end);
                observation.setFiscalYear(item.path("fy").isInt() ? item.path("fy").asInt() : null);
                observation.setFiscalPeriod(item.path("fp").asText(null));
                observation.setFieldName("dilutedEps");
                observation.setValue(item.path("val").decimalValue());
                observation.setUnit("USD/shares");
                observation.setCurrencyCode("USD");
                observation.setSourceCode("SEC");
                observation.setSourceDate(filed);
                observation.setAccessionNumber(accession);
                observation.setForm(form);
                factObservationRepository.save(observation);
            }
        }
    }

    private void persistSharesOutstandingObservations(String symbol, JsonNode dei, LocalDate from, LocalDate to) {
        if (factObservationRepository == null) return;
        JsonNode values = dei.path("EntityCommonStockSharesOutstanding").path("units").path("shares");
        if (!values.isArray()) return;
        for (JsonNode item : values) {
            String endRaw = item.path("end").asText("");
            String filedRaw = item.path("filed").asText("");
            String form = item.path("form").asText("");
            if (endRaw.isBlank() || filedRaw.isBlank() || !isQuarterlyOrAnnualForm(form)
                    || item.path("val").isMissingNode() || item.path("val").isNull()) continue;
            LocalDate end;
            LocalDate filed;
            try { end = LocalDate.parse(endRaw); filed = LocalDate.parse(filedRaw); } catch (RuntimeException e) { continue; }
            if (end.isBefore(from) || end.isAfter(to)) continue;
            String accession = item.path("accn").asText("");
            if (factObservationRepository.existsBySymbolAndPeriodEndAndFieldNameAndSourceDateAndAccessionNumberAndUnit(
                    symbol, end, "sharesOutstanding", filed, accession, "shares")) continue;
            FundamentalFactObservation observation = new FundamentalFactObservation();
            observation.setSymbol(symbol.trim().toUpperCase());
            observation.setPeriodEnd(end);
            observation.setFiscalYear(item.path("fy").isInt() ? item.path("fy").asInt() : null);
            observation.setFiscalPeriod(item.path("fp").asText(null));
            observation.setFieldName("sharesOutstanding");
            observation.setValue(item.path("val").decimalValue());
            observation.setUnit("shares");
            observation.setSourceCode("SEC");
            observation.setSourceDate(filed);
            observation.setAccessionNumber(accession);
            observation.setForm(form);
            factObservationRepository.save(observation);
        }
    }

    /**
     * Store filing-scoped, presentation-selected cash-flow leaves separately
     * from ordinary Company Facts. These observations are evidence for the
     * FCFF bridge and are never merged into a generic ΔNWC field.
     */
    private void persistCashFlowStatementGraphs(String symbol, String cik, JsonNode usGaap, LocalDate from, LocalDate to) {
        if (factObservationRepository == null || filingXbrlGraphService == null) return;
        JsonNode values = usGaap.path("NetCashProvidedByUsedInOperatingActivities").path("units").path("USD");
        if (!values.isArray()) return;
        Map<String, LocalDate> filings = new LinkedHashMap<>();
        for (JsonNode item : values) {
            String accn = item.path("accn").asText(""); String filedRaw = item.path("filed").asText(""); String form = item.path("form").asText("");
            if (accn.isBlank() || !isQuarterlyOrAnnualForm(form)) continue;
            try { LocalDate filed = LocalDate.parse(filedRaw); if (!filed.isBefore(from) && !filed.isAfter(to)) filings.putIfAbsent(accn, filed); }
            catch (RuntimeException ignored) { }
        }
        // Bound the sync cost; older filings are ingested on their historical sync runs.
        List<Map.Entry<String, LocalDate>> latest = filings.entrySet().stream()
                .sorted(Map.Entry.<String, LocalDate>comparingByValue().reversed()).limit(12).toList();
        for (Map.Entry<String, LocalDate> filing : latest) {
            SecFilingXbrlGraphService.FilingGraph graph = filingXbrlGraphService.load(cik, filing.getKey());
            if (!"AVAILABLE".equals(graph.status())) continue;
            for (SecFilingXbrlGraphService.Fact fact : graph.nonOverlappingLeafFacts()) {
                if (fact.end().isBefore(from) || fact.end().isAfter(to) || fact.unit() == null || !fact.unit().toUpperCase().contains("USD")) continue;
                String field = filingLeafFieldName(fact.concept());
                if (factObservationRepository.existsBySymbolAndPeriodEndAndFieldNameAndSourceDateAndAccessionNumberAndUnit(
                        symbol, fact.end(), field, filing.getValue(), filing.getKey(), fact.unit())) continue;
                try {
                    FundamentalFactObservation observation = new FundamentalFactObservation();
                    observation.setSymbol(symbol.trim().toUpperCase()); observation.setPeriodEnd(fact.end()); observation.setFieldName(field);
                    observation.setPeriodStart(fact.start()); observation.setXbrlBucket(fact.bucket()); observation.setCalculationWeight(fact.calculationWeight());
                    observation.setValue(new BigDecimal(fact.value())); observation.setUnit(fact.unit()); observation.setCurrencyCode("USD");
                    observation.setSourceCode("SEC_FILING_XBRL"); observation.setSourceDate(filing.getValue()); observation.setAccessionNumber(filing.getKey()); observation.setForm("XBRL");
                    factObservationRepository.save(observation);
                } catch (RuntimeException ignored) { /* malformed fact cannot make a filing bridge complete */ }
            }
        }
    }

    private String filingLeafFieldName(String concept) {
        String prefix = "xbrlCashFlowLeaf.";
        if (concept == null) return prefix + "unknown";
        if (prefix.length() + concept.length() <= 80) return prefix + concept;
        // The raw SEC concept remains in the bridge ledger. This key only needs
        // deterministic lookup semantics within a bounded legacy column.
        return prefix + concept.substring(0, 42) + "_" + Integer.toUnsignedString(concept.hashCode(), 36);
    }

    private boolean isQuarterlyOrAnnualForm(String form) {
        return "10-Q".equals(form) || "10-K".equals(form) || "10-K/A".equals(form) || "10-Q/A".equals(form);
    }

    private Map<String, String> loadTickerMap() throws IOException, InterruptedException {
        JsonNode root = readJson("https://www.sec.gov/files/company_tickers.json");
        Map<String, String> map = new HashMap<>();
        root.fields().forEachRemaining(entry -> {
            JsonNode item = entry.getValue();
            String ticker = item.path("ticker").asText("").trim().toUpperCase();
            if (!ticker.isBlank()) {
                map.put(ticker, String.format("%010d", item.path("cik_str").asLong()));
            }
        });
        return map;
    }

    private void addFirstMetric(Map<LocalDate, SecQuarter> byDate,
                                JsonNode usGaap,
                                LocalDate from,
                                LocalDate to,
                                boolean instant,
                                String unit,
                                Function<SecQuarter, BigDecimal> getter,
                                BiConsumer<SecQuarter, BigDecimal> setter,
                                String... concepts) {
        for (String concept : concepts) {
            List<MetricPoint> points = readConcept(usGaap, concept, unit, instant, from, to);
            for (MetricPoint point : points) {
                SecQuarter quarter = byDate.computeIfAbsent(point.asOfDate(), SecQuarter::new);
                quarter.observe(point);
                if (getter.apply(quarter) == null) {
                    setter.accept(quarter, point.value());
                }
            }
        }
    }

    private void addResolvedDebtMetrics(String symbol, Map<LocalDate, SecQuarter> byDate,
                                        JsonNode usGaap,
                                        LocalDate from,
                                        LocalDate to) {
        SecDebtResolver.Resolution resolution = SecDebtResolver.resolve(usGaap, from, to);
        persistDebtEvidence(symbol, resolution);
        for (SecDebtResolver.Metric metric : resolution.totalDebt()) {
            if (metric.coverage() != SecDebtResolver.Coverage.COMPLETE) continue;
            SecQuarter quarter = byDate.computeIfAbsent(metric.asOfDate(), SecQuarter::new);
            quarter.observe(toMetricPoint(metric));
            quarter.setTotalDebt(metric.value());
        }
        for (SecDebtResolver.Metric metric : resolution.netBorrowing()) {
            if (metric.coverage() != SecDebtResolver.Coverage.COMPLETE) continue;
            SecQuarter quarter = byDate.computeIfAbsent(metric.asOfDate(), SecQuarter::new);
            quarter.observe(toMetricPoint(metric));
            quarter.setNetBorrowing(metric.value());
        }
    }

    private void persistDebtEvidence(String symbol, SecDebtResolver.Resolution resolution) {
        if (debtEvidenceRepository == null) return;
        List<SecDebtEvidence> rows = new ArrayList<>();
        persistEvidenceRows(rows, symbol, "BALANCE", resolution.totalDebt());
        persistEvidenceRows(rows, symbol, "NET_BORROWING", resolution.netBorrowing());
        if (rows.isEmpty()) return;
        LocalDate from = rows.stream().map(SecDebtEvidence::getPeriodEnd).min(LocalDate::compareTo).orElseThrow();
        LocalDate to = rows.stream().map(SecDebtEvidence::getPeriodEnd).max(LocalDate::compareTo).orElseThrow();
        debtEvidenceRepository.deleteBySymbolAndPeriodEndBetween(symbol, from, to);
        debtEvidenceRepository.flush();
        debtEvidenceRepository.saveAll(rows);
    }
    private void persistEvidenceRows(List<SecDebtEvidence> rows, String symbol, String metricType, List<SecDebtResolver.Metric> metrics) {
        for (SecDebtResolver.Metric metric : metrics) for (SecDebtResolver.Evidence source : metric.evidence()) {
            SecDebtEvidence row = new SecDebtEvidence(); row.setSymbol(symbol); row.setPeriodEnd(metric.asOfDate()); row.setMetricType(metricType);
            row.setComponentType(source.componentType()); row.setAmount(source.amount()); row.setCoverageStatus(metric.coverage().name()); row.setSelectedRoute(metric.route().name());
            row.setSourceConcepts(String.join(",", source.concepts())); row.setAccessionNumbers(source.accessions().isBlank() ? metric.accessionNumber() : source.accessions()); row.setForm(metric.form()); row.setFiledDate(metric.filed());
            row.setSourceStart(source.sourceStart()); row.setSourceEnd(source.sourceEnd()); row.setQuarterizationMethod(metric.quarterizationMethod()); rows.add(row);
        }
    }

    private MetricPoint toMetricPoint(SecDebtResolver.Metric metric) {
        return new MetricPoint(metric.asOfDate(), metric.value(),
                metric.filed() == null ? LocalDate.MIN : metric.filed(), metric.fiscalYear(),
                metric.fiscalPeriod(), metric.accessionNumber(), metric.form());
    }

    private void addStandaloneDurationMetric(Map<LocalDate, SecQuarter> byDate,
                                             JsonNode usGaap,
                                             LocalDate from,
                                             LocalDate to,
                                             String unit,
                                             Function<SecQuarter, BigDecimal> getter,
                                             BiConsumer<SecQuarter, BigDecimal> setter,
                                             String... concepts) {
        for (String concept : concepts) {
            for (MetricPoint point : readStandaloneDurationConcept(usGaap, concept, unit, from, to)) {
                SecQuarter quarter = byDate.computeIfAbsent(point.asOfDate(), SecQuarter::new);
                quarter.observe(point);
                if (getter.apply(quarter) == null) {
                    setter.accept(quarter, point.value());
                }
            }
        }
    }

    private void addCashFlowMetric(Map<LocalDate, SecQuarter> byDate,
                                   JsonNode usGaap,
                                   LocalDate from,
                                   LocalDate to,
                                   String unit,
                                   Function<SecQuarter, BigDecimal> getter,
                                   BiConsumer<SecQuarter, BigDecimal> setter,
                                   String... concepts) {
        for (String concept : concepts) {
            for (MetricPoint point : readConcept(usGaap, concept, unit, false, from, to)) {
                SecQuarter quarter = byDate.computeIfAbsent(point.asOfDate(), SecQuarter::new);
                quarter.observe(point);
                if (getter.apply(quarter) == null) {
                    setter.accept(quarter, point.value());
                }
            }
            for (MetricPoint point : readYtdCashFlowConcept(usGaap, concept, unit, from, to)) {
                SecQuarter quarter = byDate.computeIfAbsent(point.asOfDate(), SecQuarter::new);
                quarter.observe(point);
                if (getter.apply(quarter) == null) {
                    setter.accept(quarter, point.value());
                }
            }
        }
    }

    private void addFlowMetric(Map<LocalDate, SecQuarter> byDate,
                               JsonNode usGaap,
                               LocalDate from,
                               LocalDate to,
                               String unit,
                               Function<SecQuarter, BigDecimal> getter,
                               BiConsumer<SecQuarter, BigDecimal> setter,
                               String... concepts) {
        for (String concept : concepts) {
            for (MetricPoint point : readStandaloneDurationConcept(usGaap, concept, unit, from, to)) {
                SecQuarter quarter = byDate.computeIfAbsent(point.asOfDate(), SecQuarter::new);
                quarter.observe(point);
                if (getter.apply(quarter) == null) {
                    setter.accept(quarter, point.value());
                }
            }
            for (MetricPoint point : readConcept(usGaap, concept, unit, false, from, to)) {
                SecQuarter quarter = byDate.computeIfAbsent(point.asOfDate(), SecQuarter::new);
                quarter.observe(point);
                if (getter.apply(quarter) == null) {
                    setter.accept(quarter, point.value());
                }
            }
            for (MetricPoint point : readYtdConcept(usGaap, concept, unit, from, to)) {
                SecQuarter quarter = byDate.computeIfAbsent(point.asOfDate(), SecQuarter::new);
                quarter.observe(point);
                if (getter.apply(quarter) == null) {
                    setter.accept(quarter, point.value());
                }
            }
        }
    }

    private void addYtdMetric(Map<LocalDate, SecQuarter> byDate,
                              JsonNode usGaap,
                              LocalDate from,
                              LocalDate to,
                              String unit,
                              Function<SecQuarter, BigDecimal> getter,
                              BiConsumer<SecQuarter, BigDecimal> setter,
                              String... concepts) {
        for (String concept : concepts) {
            for (MetricPoint point : readYtdConcept(usGaap, concept, unit, from, to)) {
                SecQuarter quarter = byDate.computeIfAbsent(point.asOfDate(), SecQuarter::new);
                quarter.observe(point);
                if (getter.apply(quarter) == null) {
                    setter.accept(quarter, point.value());
                }
            }
        }
    }

    private List<MetricPoint> readConcept(JsonNode usGaap,
                                          String concept,
                                          String unit,
                                          boolean instant,
                                          LocalDate from,
                                          LocalDate to) {
        JsonNode values = usGaap.path(concept).path("units").path(unit);
        if (!values.isArray()) {
            return List.of();
        }

        Pattern framePattern = instant ? INSTANT_QUARTER_FRAME : QUARTER_FRAME;
        Map<LocalDate, MetricPoint> firstPublishedByEndDate = new HashMap<>();
        for (JsonNode item : values) {
            String form = item.path("form").asText("");
            if (!isQuarterlyOrAnnualForm(form)) {
                continue;
            }
            String frame = item.path("frame").asText("");
            if (!framePattern.matcher(frame).matches()) {
                continue;
            }
            JsonNode valueNode = item.path("val");
            String endRaw = item.path("end").asText("");
            if (endRaw.isBlank() || valueNode.isMissingNode() || valueNode.isNull()) {
                continue;
            }
            LocalDate asOfDate = LocalDate.parse(endRaw);
            if (asOfDate.isBefore(from) || asOfDate.isAfter(to)) {
                continue;
            }
            MetricPoint point = new MetricPoint(asOfDate, valueNode.decimalValue(), parseDateOrMin(item.path("filed").asText("")),
                    item.path("fy").isInt() ? item.path("fy").asInt() : null,
                    item.path("fp").asText(null), item.path("accn").asText(null), form);
            firstPublishedByEndDate.merge(asOfDate, point, this::preferredMetricPoint);
        }
        return firstPublishedByEndDate.values().stream()
                .sorted(java.util.Comparator.comparing(MetricPoint::asOfDate))
                .toList();
    }

    private List<MetricPoint> readStandaloneDurationConcept(JsonNode usGaap,
                                                            String concept,
                                                            String unit,
                                                            LocalDate from,
                                                            LocalDate to) {
        JsonNode values = usGaap.path(concept).path("units").path(unit);
        if (!values.isArray()) {
            return List.of();
        }

        return readStandaloneDurationPoints(values, from, to).stream()
                .map(point -> new MetricPoint(point.endDate(), point.value(), point.filed(), point.fiscalYear(),
                        point.fiscalPeriod(), point.accessionNumber(), point.form()))
                .sorted(java.util.Comparator.comparing(MetricPoint::asOfDate))
                .toList();
    }

    private List<ReportedFlowPoint> readStandaloneDurationPoints(JsonNode values, LocalDate from, LocalDate to) {
        Map<LocalDate, ReportedFlowPoint> firstPublishedByEndDate = new HashMap<>();
        for (JsonNode item : values) {
            String form = item.path("form").asText("");
            if (!isQuarterlyOrAnnualForm(form)) {
                continue;
            }
            JsonNode valueNode = item.path("val");
            String startRaw = item.path("start").asText("");
            String endRaw = item.path("end").asText("");
            if (startRaw.isBlank() || endRaw.isBlank() || valueNode.isMissingNode() || valueNode.isNull()) {
                continue;
            }
            LocalDate startDate = LocalDate.parse(startRaw);
            LocalDate endDate = LocalDate.parse(endRaw);
            long durationDays = ChronoUnit.DAYS.between(startDate, endDate) + 1;
            if (durationDays < 70 || durationDays > 110 || endDate.isBefore(from) || endDate.isAfter(to)) {
                continue;
            }
            LocalDate filed = parseDateOrMin(item.path("filed").asText(""));
            ReportedFlowPoint point = new ReportedFlowPoint(startDate, endDate, valueNode.decimalValue(), filed,
                    item.path("fy").isInt() ? item.path("fy").asInt() : null,
                    item.path("fp").asText(null), item.path("accn").asText(null), form);
            firstPublishedByEndDate.merge(endDate, point, this::preferredReportedFlowPoint);
        }

        return firstPublishedByEndDate.values().stream()
                .sorted(java.util.Comparator.comparing(ReportedFlowPoint::endDate))
                .toList();
    }

    private List<MetricPoint> readYtdCashFlowConcept(JsonNode usGaap,
                                                     String concept,
                                                     String unit,
                                                     LocalDate from,
                                                     LocalDate to) {
        return readYtdConcept(usGaap, concept, unit, from, to);
    }

    private List<MetricPoint> readYtdConcept(JsonNode usGaap,
                                             String concept,
                                             String unit,
                                             LocalDate from,
                                             LocalDate to) {
        JsonNode values = usGaap.path(concept).path("units").path(unit);
        if (!values.isArray()) {
            return List.of();
        }

        Map<LocalDate, Map<String, ReportedFlowPoint>> byFiscalYearStart = new HashMap<>();
        Map<LocalDate, ReportedFlowPoint> standaloneByEndDate = readStandaloneDurationPoints(values, LocalDate.MIN, LocalDate.MAX)
                .stream()
                .collect(java.util.stream.Collectors.toMap(
                        ReportedFlowPoint::endDate,
                        Function.identity(),
                        (first, second) -> second
                ));
        for (JsonNode item : values) {
            String fp = item.path("fp").asText("");
            if (!("Q1".equals(fp) || "Q2".equals(fp) || "Q3".equals(fp) || "FY".equals(fp))) {
                continue;
            }
            String form = item.path("form").asText("");
            if (!isQuarterlyOrAnnualForm(form)) {
                continue;
            }
            JsonNode valueNode = item.path("val");
            String endRaw = item.path("end").asText("");
            if (endRaw.isBlank() || valueNode.isMissingNode() || valueNode.isNull()) {
                continue;
            }
            String startRaw = item.path("start").asText("");
            if (startRaw.isBlank()) {
                continue;
            }
            LocalDate startDate = LocalDate.parse(startRaw);
            LocalDate endDate = LocalDate.parse(endRaw);
            LocalDate filed = parseDateOrMin(item.path("filed").asText(""));
            ReportedFlowPoint point = new ReportedFlowPoint(startDate, endDate, valueNode.decimalValue(), filed,
                    item.path("fy").isInt() ? item.path("fy").asInt() : null, fp,
                    item.path("accn").asText(null), form);
            Map<String, ReportedFlowPoint> byPeriod = byFiscalYearStart.computeIfAbsent(startDate, ignored -> new HashMap<>());
            byPeriod.merge(fp, point, this::preferredReportedFlowPoint);
        }

        List<MetricPoint> points = new ArrayList<>();
        for (Map<String, ReportedFlowPoint> byPeriod : byFiscalYearStart.values()) {
            BigDecimal previousYtd = null;
            String previousPeriod = null;
            for (String fp : List.of("Q1", "Q2", "Q3", "FY")) {
                ReportedFlowPoint current = byPeriod.get(fp);
                if (current == null) {
                    continue;
                }
                BigDecimal quarterValue = "Q1".equals(fp)
                        ? current.value()
                        : isImmediateFiscalPredecessor(previousPeriod, fp) && previousYtd != null
                        ? current.value().subtract(previousYtd)
                        : null;
                if (quarterValue == null) {
                    ReportedFlowPoint standalone = standaloneByEndDate.get(current.endDate());
                    if (standalone != null && standalone.startDate().isAfter(current.startDate())) {
                        if ("Q2".equals(fp)) {
                            BigDecimal inferredQ1 = current.value().subtract(standalone.value());
                            LocalDate inferredQ1End = standalone.startDate().minusDays(1);
                            if (!inferredQ1End.isBefore(from) && !inferredQ1End.isAfter(to)) {
                                points.add(new MetricPoint(inferredQ1End, inferredQ1, current.filed(), current.fiscalYear(),
                                        "Q1", current.accessionNumber(), current.form()));
                            }
                        }
                        quarterValue = standalone.value();
                    }
                }
                previousYtd = current.value();
                previousPeriod = fp;
                if (quarterValue == null || current.endDate().isBefore(from) || current.endDate().isAfter(to)) {
                    continue;
                }
                points.add(new MetricPoint(current.endDate(), quarterValue, current.filed(), current.fiscalYear(),
                        fp, current.accessionNumber(), current.form()));
            }
        }
        return points;
    }

    private boolean isImmediateFiscalPredecessor(String previous, String current) {
        return ("Q1".equals(previous) && "Q2".equals(current))
                || ("Q2".equals(previous) && "Q3".equals(current))
                || ("Q3".equals(previous) && "FY".equals(current));
    }

    private MetricPoint preferredMetricPoint(MetricPoint first, MetricPoint second) {
        int comparison = comparePublication(first.filed(), first.accessionNumber(), second.filed(), second.accessionNumber());
        return comparison <= 0 ? first : second;
    }

    private ReportedFlowPoint preferredReportedFlowPoint(ReportedFlowPoint first, ReportedFlowPoint second) {
        int comparison = comparePublication(first.filed(), first.accessionNumber(), second.filed(), second.accessionNumber());
        return comparison <= 0 ? first : second;
    }

    private int comparePublication(LocalDate firstFiled, String firstAccession,
                                   LocalDate secondFiled, String secondAccession) {
        LocalDate firstDate = firstFiled == null || LocalDate.MIN.equals(firstFiled) ? LocalDate.MAX : firstFiled;
        LocalDate secondDate = secondFiled == null || LocalDate.MIN.equals(secondFiled) ? LocalDate.MAX : secondFiled;
        int comparison = firstDate.compareTo(secondDate);
        if (comparison != 0) return comparison;
        String first = firstAccession == null ? "" : firstAccession;
        String second = secondAccession == null ? "" : secondAccession;
        return first.compareTo(second);
    }

    private LocalDate parseDateOrMin(String raw) {
        if (raw == null || raw.isBlank()) {
            return LocalDate.MIN;
        }
        return LocalDate.parse(raw);
    }

    private JsonNode readJson(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", userAgent)
                .header("Accept-Encoding", "identity")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new IOException("SEC request failed with status " + response.statusCode());
        }
        return objectMapper.readTree(response.body());
    }

    private record MetricPoint(LocalDate asOfDate, BigDecimal value, LocalDate filed,
                               Integer fiscalYear, String fiscalPeriod, String accessionNumber, String form) {
        private MetricPoint(LocalDate asOfDate, BigDecimal value) {
            this(asOfDate, value, LocalDate.MIN, null, null, null, null);
        }
    }

    private record ReportedFlowPoint(LocalDate startDate, LocalDate endDate, BigDecimal value, LocalDate filed,
                                     Integer fiscalYear, String fiscalPeriod, String accessionNumber, String form) {
    }

    private static class SecQuarter {
        private final LocalDate asOfDate;
        private BigDecimal basicEps;
        private BigDecimal cashFlow;
        private BigDecimal fcf;
        private BigDecimal adjustedFcf;
        private BigDecimal revenue;
        private BigDecimal grossProfit;
        private BigDecimal operatingIncome;
        private BigDecimal interestExpense;
        private BigDecimal netIncome;
        private BigDecimal stockholdersEquity;
        private BigDecimal totalDebt;
        private BigDecimal cashAndEquivalents;
        private BigDecimal shortTermInvestments;
        private BigDecimal noncurrentMarketableSecurities;
        private BigDecimal taxProvision;
        private BigDecimal pretaxIncome;
        private BigDecimal investedCapital;
        private BigDecimal capex;
        private BigDecimal dilutedEps;
        private BigDecimal dilutedWeightedAverageShares;
        private BigDecimal depreciationAmortization;
        private BigDecimal changeInWorkingCapital;
        private BigDecimal netBorrowing;
        private BigDecimal shareRepurchases;
        private BigDecimal totalAssets;
        private LocalDate filingDate;
        private Integer fiscalYear;
        private String fiscalPeriod;

        private SecQuarter(LocalDate asOfDate) {
            this.asOfDate = asOfDate;
        }

        private boolean hasAnyFundamental() {
            return basicEps != null || cashFlow != null || fcf != null || revenue != null || grossProfit != null
                    || operatingIncome != null || netIncome != null || stockholdersEquity != null;
        }

        private SecQuarter completeDerivedFields() {
            if (dilutedWeightedAverageShares != null && dilutedWeightedAverageShares.signum() <= 0) {
                dilutedWeightedAverageShares = null;
            }
            if (fcf == null && cashFlow != null && capex != null) {
                fcf = cashFlow.subtract(capex);
            }
            if (adjustedFcf == null && cashFlow != null && capex != null) {
                adjustedFcf = cashFlow.subtract(capex);
            }
            // Keep invested capital on the system's DCF bridge definition. The SEC
            // InvestedCapital concept is issuer-specific and can represent operating
            // capital rather than debt + equity less non-operating liquidity.
            if (totalDebt != null && stockholdersEquity != null) {
                investedCapital = totalDebt.add(stockholdersEquity);
                if (cashAndEquivalents != null) {
                    investedCapital = investedCapital.subtract(cashAndEquivalents);
                }
                if (shortTermInvestments != null) investedCapital = investedCapital.subtract(shortTermInvestments);
                if (noncurrentMarketableSecurities != null) investedCapital = investedCapital.subtract(noncurrentMarketableSecurities);
            }
            return this;
        }

        private void observe(MetricPoint point) {
            if (point.filed() != null && !LocalDate.MIN.equals(point.filed())
                    && (filingDate == null || point.filed().isBefore(filingDate))) {
                filingDate = point.filed();
                if (point.fiscalYear() != null) fiscalYear = point.fiscalYear();
                if (point.fiscalPeriod() != null && !point.fiscalPeriod().isBlank()) fiscalPeriod = point.fiscalPeriod();
            } else if (filingDate == null) {
                if (point.fiscalYear() != null) fiscalYear = point.fiscalYear();
                if (point.fiscalPeriod() != null && !point.fiscalPeriod().isBlank()) fiscalPeriod = point.fiscalPeriod();
            }
        }

        private YahooFinancePriceService.QuarterlyFundamentalPoint toPoint() {
            return new YahooFinancePriceService.QuarterlyFundamentalPoint(
                    asOfDate,
                    basicEps,
                    "USD",
                    null,
                    null,
                    cashFlow,
                    fcf,
                    capex,
                    adjustedFcf,
                    null,
                    null,
                    null,
                    revenue,
                    grossProfit,
                    operatingIncome,
                    interestExpense,
                    netIncome,
                    stockholdersEquity,
                    totalDebt,
                    cashAndEquivalents,
                    shortTermInvestments,
                    noncurrentMarketableSecurities,
                    taxProvision,
                    pretaxIncome,
                    investedCapital,
                    dilutedEps,
                    dilutedWeightedAverageShares,
                    depreciationAmortization,
                    changeInWorkingCapital,
                    netBorrowing,
                    shareRepurchases,
                    totalAssets,
                    fiscalYear == null ? asOfDate.getYear() : fiscalYear,
                    fiscalPeriod,
                    filingDate
            );
        }

        private BigDecimal getBasicEps() { return basicEps; }
        private void setBasicEps(BigDecimal basicEps) { this.basicEps = basicEps; }
        private BigDecimal getCashFlow() { return cashFlow; }
        private void setCashFlow(BigDecimal cashFlow) { this.cashFlow = cashFlow; }
        private BigDecimal getFcf() { return fcf; }
        private BigDecimal getRevenue() { return revenue; }
        private void setRevenue(BigDecimal revenue) { this.revenue = revenue; }
        private BigDecimal getGrossProfit() { return grossProfit; }
        private void setGrossProfit(BigDecimal grossProfit) { this.grossProfit = grossProfit; }
        private BigDecimal getOperatingIncome() { return operatingIncome; }
        private void setOperatingIncome(BigDecimal operatingIncome) { this.operatingIncome = operatingIncome; }
        private BigDecimal getInterestExpense() { return interestExpense; }
        private void setInterestExpense(BigDecimal interestExpense) { this.interestExpense = interestExpense == null ? null : interestExpense.abs(); }
        private BigDecimal getNetIncome() { return netIncome; }
        private void setNetIncome(BigDecimal netIncome) { this.netIncome = netIncome; }
        private BigDecimal getStockholdersEquity() { return stockholdersEquity; }
        private void setStockholdersEquity(BigDecimal stockholdersEquity) { this.stockholdersEquity = stockholdersEquity; }
        private BigDecimal getTotalDebt() { return totalDebt; }
        private void setTotalDebt(BigDecimal totalDebt) { this.totalDebt = totalDebt; }
        private BigDecimal getCashAndEquivalents() { return cashAndEquivalents; }
        private void setCashAndEquivalents(BigDecimal cashAndEquivalents) { this.cashAndEquivalents = cashAndEquivalents; }
        private BigDecimal getShortTermInvestments() { return shortTermInvestments; }
        private void setShortTermInvestments(BigDecimal value) { this.shortTermInvestments = value; }
        private BigDecimal getNoncurrentMarketableSecurities() { return noncurrentMarketableSecurities; }
        private void setNoncurrentMarketableSecurities(BigDecimal value) { this.noncurrentMarketableSecurities = value; }
        private BigDecimal getTaxProvision() { return taxProvision; }
        private void setTaxProvision(BigDecimal taxProvision) { this.taxProvision = taxProvision; }
        private BigDecimal getPretaxIncome() { return pretaxIncome; }
        private void setPretaxIncome(BigDecimal pretaxIncome) { this.pretaxIncome = pretaxIncome; }
        private BigDecimal getInvestedCapital() { return investedCapital; }
        private void setInvestedCapital(BigDecimal investedCapital) { this.investedCapital = investedCapital; }
        private BigDecimal getCapex() { return capex; }
        private void setCapex(BigDecimal capex) { this.capex = capex == null ? null : capex.abs(); }
        private BigDecimal getDilutedEps() { return dilutedEps; }
        private void setDilutedEps(BigDecimal value) { dilutedEps = value; }
        private BigDecimal getDilutedWeightedAverageShares() { return dilutedWeightedAverageShares; }
        private void setDilutedWeightedAverageShares(BigDecimal value) { dilutedWeightedAverageShares = value; }
        private BigDecimal getDepreciationAmortization() { return depreciationAmortization; }
        private void setDepreciationAmortization(BigDecimal value) { depreciationAmortization = value == null ? null : value.abs(); }
        private BigDecimal getChangeInWorkingCapital() { return changeInWorkingCapital; }
        private void setChangeInWorkingCapital(BigDecimal value) { changeInWorkingCapital = value; }
        private void setNetBorrowing(BigDecimal value) { netBorrowing = value; }
        private BigDecimal getShareRepurchases() { return shareRepurchases; }
        private void setShareRepurchases(BigDecimal value) { shareRepurchases = value == null ? null : value.abs(); }
        private BigDecimal getTotalAssets() { return totalAssets; }
        private void setTotalAssets(BigDecimal value) { totalAssets = value; }
    }
}
