package com.stockportfolio.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockportfolio.model.FundamentalFactObservation;
import com.stockportfolio.repository.FundamentalFactObservationRepository;
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
        addSummedMetric(byDate, usGaap, from, to, true, "USD", SecQuarter::getTotalDebt, SecQuarter::setTotalDebt,
                "ShortTermBorrowings",
                "DebtCurrent",
                "LongTermDebtCurrent",
                "LongTermDebtAndFinanceLeaseObligationsCurrent",
                "LongTermDebtNoncurrent",
                "LongTermDebtAndFinanceLeaseObligationsNoncurrent");
        addCashFlowMetric(byDate, usGaap, from, to, "USD", SecQuarter::getCapex, SecQuarter::setCapex,
                "PaymentsToAcquirePropertyPlantAndEquipment", "PaymentsToAcquireProductiveAssets");
        addCashFlowMetric(byDate, usGaap, from, to, "USD", SecQuarter::getDepreciationAmortization, SecQuarter::setDepreciationAmortization,
                "DepreciationDepletionAndAmortization", "DepreciationDepletionAndAmortizationPropertyPlantAndEquipment");
        addCashFlowMetric(byDate, usGaap, from, to, "USD", SecQuarter::getChangeInWorkingCapital, SecQuarter::setChangeInWorkingCapital,
                "IncreaseDecreaseInOperatingCapital");
        addCashFlowMetric(byDate, usGaap, from, to, "USD", SecQuarter::getDebtIssuance, SecQuarter::setDebtIssuance,
                "ProceedsFromIssuanceOfLongTermDebt", "ProceedsFromIssuanceOfDebt");
        addCashFlowMetric(byDate, usGaap, from, to, "USD", SecQuarter::getDebtRepayment, SecQuarter::setDebtRepayment,
                "RepaymentsOfLongTermDebt", "RepaymentsOfDebt");
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

    private void addSummedMetric(Map<LocalDate, SecQuarter> byDate,
                                 JsonNode usGaap,
                                 LocalDate from,
                                 LocalDate to,
                                 boolean instant,
                                 String unit,
                                 Function<SecQuarter, BigDecimal> getter,
                                 BiConsumer<SecQuarter, BigDecimal> setter,
                                 String... concepts) {
        for (String concept : concepts) {
            for (MetricPoint point : readConcept(usGaap, concept, unit, instant, from, to)) {
                SecQuarter quarter = byDate.computeIfAbsent(point.asOfDate(), SecQuarter::new);
                quarter.observe(point);
                BigDecimal current = getter.apply(quarter);
                setter.accept(quarter, current == null ? point.value() : current.add(point.value()));
            }
        }
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
        List<MetricPoint> points = new ArrayList<>();
        for (JsonNode item : values) {
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
            points.add(new MetricPoint(asOfDate, valueNode.decimalValue(), parseDateOrMin(item.path("filed").asText("")),
                    item.path("fy").isInt() ? item.path("fy").asInt() : null,
                    item.path("fp").asText(null), item.path("accn").asText(null), item.path("form").asText(null)));
        }
        return points;
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
            if (!("10-Q".equals(form) || "10-K".equals(form) || "10-K/A".equals(form))) {
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
            ReportedFlowPoint existing = firstPublishedByEndDate.get(endDate);
            if (existing == null || point.filed().isBefore(existing.filed())) {
                firstPublishedByEndDate.put(endDate, point);
            }
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
            if (!("10-Q".equals(form) || "10-K".equals(form))) {
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
            ReportedFlowPoint existing = byPeriod.get(fp);
            if (existing == null || point.filed().isBefore(existing.filed())) {
                byPeriod.put(fp, point);
            }
        }

        List<MetricPoint> points = new ArrayList<>();
        for (Map<String, ReportedFlowPoint> byPeriod : byFiscalYearStart.values()) {
            BigDecimal previousYtd = null;
            for (String fp : List.of("Q1", "Q2", "Q3", "FY")) {
                ReportedFlowPoint current = byPeriod.get(fp);
                if (current == null) {
                    continue;
                }
                BigDecimal quarterValue = previousYtd == null ? ("Q1".equals(fp) ? current.value() : null) : current.value().subtract(previousYtd);
                if (quarterValue == null && "Q2".equals(fp)) {
                    ReportedFlowPoint standalone = standaloneByEndDate.get(current.endDate());
                    if (standalone != null && standalone.startDate().isAfter(current.startDate())) {
                        BigDecimal inferredQ1 = current.value().subtract(standalone.value());
                        LocalDate inferredQ1End = standalone.startDate().minusDays(1);
                        if (!inferredQ1End.isBefore(from) && !inferredQ1End.isAfter(to)) {
                            points.add(new MetricPoint(inferredQ1End, inferredQ1, current.filed(), current.fiscalYear(),
                                    "Q1", current.accessionNumber(), current.form()));
                        }
                        quarterValue = standalone.value();
                    }
                }
                previousYtd = current.value();
                if (quarterValue == null || current.endDate().isBefore(from) || current.endDate().isAfter(to)) {
                    continue;
                }
                points.add(new MetricPoint(current.endDate(), quarterValue, current.filed(), current.fiscalYear(),
                        fp, current.accessionNumber(), current.form()));
            }
        }
        return points;
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
        private BigDecimal debtIssuance;
        private BigDecimal debtRepayment;
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
            if (investedCapital == null && totalDebt != null && stockholdersEquity != null) {
                investedCapital = totalDebt.add(stockholdersEquity);
                if (cashAndEquivalents != null) {
                    investedCapital = investedCapital.subtract(cashAndEquivalents);
                }
                if (shortTermInvestments != null) investedCapital = investedCapital.subtract(shortTermInvestments);
                if (noncurrentMarketableSecurities != null) investedCapital = investedCapital.subtract(noncurrentMarketableSecurities);
            }
            if (netBorrowing == null && (debtIssuance != null || debtRepayment != null)) {
                netBorrowing = (debtIssuance == null ? BigDecimal.ZERO : debtIssuance)
                        .subtract(debtRepayment == null ? BigDecimal.ZERO : debtRepayment);
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
        private BigDecimal getDebtIssuance() { return debtIssuance; }
        private void setDebtIssuance(BigDecimal value) { debtIssuance = value == null ? null : value.abs(); }
        private BigDecimal getDebtRepayment() { return debtRepayment; }
        private void setDebtRepayment(BigDecimal value) { debtRepayment = value == null ? null : value.abs(); }
        private BigDecimal getShareRepurchases() { return shareRepurchases; }
        private void setShareRepurchases(BigDecimal value) { shareRepurchases = value == null ? null : value.abs(); }
        private BigDecimal getTotalAssets() { return totalAssets; }
        private void setTotalAssets(BigDecimal value) { totalAssets = value; }
    }
}
