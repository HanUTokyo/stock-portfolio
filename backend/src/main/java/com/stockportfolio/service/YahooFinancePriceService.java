package com.stockportfolio.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class YahooFinancePriceService {
    private static final Map<String, String> PRICING_SYMBOL_ALIASES = Map.of(
            "BTC", "BTC-USD"
    );

    private final String yahooBaseUrl;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    private volatile String crumb;

    public YahooFinancePriceService(@Value("${app.pricing.yahoo-base-url:https://query1.finance.yahoo.com}") String yahooBaseUrl,
                                    ObjectMapper objectMapper) {
        this.yahooBaseUrl = yahooBaseUrl;
        this.objectMapper = objectMapper;

        CookieManager cookieManager = new CookieManager();
        cookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ALL);

        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .cookieHandler(cookieManager)
                .build();
    }

    public Optional<YahooMarketSnapshot> fetchSnapshot(String symbol) {
        String pricingSymbol = resolvePricingSymbol(symbol);
        BigDecimal price = null;
        OffsetDateTime marketTime = null;
        BigDecimal trailingPe = null;
        BigDecimal pegRatio = null;
        String currency = null;

        try {
            JsonNode sparkMeta = fetchSparkMeta(pricingSymbol);
            price = parseRegularMarketPrice(sparkMeta).orElse(null);
            marketTime = parseRegularMarketTime(sparkMeta).orElse(null);
            currency = sparkMeta.path("currency").asText(null);
        } catch (Exception ignored) {
        }

        try {
            ValuationMetrics metrics = fetchValuationMetrics(pricingSymbol);
            trailingPe = metrics.trailingPe();
            pegRatio = metrics.pegRatio();
        } catch (Exception ignored) {
        }

        if (price == null && trailingPe == null && pegRatio == null && marketTime == null) {
            return Optional.empty();
        }

        return Optional.of(new YahooMarketSnapshot(price, trailingPe, pegRatio, marketTime, currency));
    }

    public List<YahooDailyPricePoint> fetchDailyCloseHistory(String symbol, LocalDate from, LocalDate to)
            throws IOException, InterruptedException {
        if (to.isBefore(from)) {
            return List.of();
        }

        String encodedSymbol = URLEncoder.encode(resolvePricingSymbol(symbol), StandardCharsets.UTF_8);
        long period1 = from.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        long period2 = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        String url = yahooBaseUrl + "/v8/finance/chart/" + encodedSymbol
                + "?period1=" + period1
                + "&period2=" + period2
                + "&interval=1d&events=history&includeAdjustedClose=true";

        JsonNode root = readJson(url);
        JsonNode result = root.path("chart").path("result").path(0);
        JsonNode timestamps = result.path("timestamp");
        JsonNode closes = result.path("indicators").path("quote").path(0).path("close");
        JsonNode adjustedCloses = result.path("indicators").path("adjclose").path(0).path("adjclose");

        if (!timestamps.isArray() || !closes.isArray()) {
            return List.of();
        }

        int size = Math.min(timestamps.size(), closes.size());
        List<YahooDailyPricePoint> points = new ArrayList<>(size);

        for (int i = 0; i < size; i++) {
            JsonNode tsNode = timestamps.get(i);
            JsonNode closeNode = closes.get(i);
            if (tsNode == null || closeNode == null || closeNode.isNull()) {
                continue;
            }

            LocalDate tradeDate = Instant.ofEpochSecond(tsNode.asLong()).atZone(ZoneOffset.UTC).toLocalDate();
            if (tradeDate.isBefore(from) || tradeDate.isAfter(to)) {
                continue;
            }

            JsonNode adjustedNode = adjustedCloses.isArray() && i < adjustedCloses.size() ? adjustedCloses.get(i) : null;
            points.add(new YahooDailyPricePoint(tradeDate, closeNode.decimalValue(),
                    adjustedNode == null || adjustedNode.isNull() ? null : adjustedNode.decimalValue()));
        }

        return points;
    }

    public List<YahooStockSplitPoint> fetchStockSplits(String symbol, LocalDate from, LocalDate to)
            throws IOException, InterruptedException {
        if (to.isBefore(from)) return List.of();
        String encodedSymbol = URLEncoder.encode(resolvePricingSymbol(symbol), StandardCharsets.UTF_8);
        long period1 = from.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        long period2 = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        String url = yahooBaseUrl + "/v8/finance/chart/" + encodedSymbol
                + "?period1=" + period1 + "&period2=" + period2 + "&interval=1d&events=splits";
        JsonNode splits = readJson(url).path("chart").path("result").path(0).path("events").path("splits");
        if (!splits.isObject()) return List.of();
        List<YahooStockSplitPoint> result = new ArrayList<>();
        splits.fields().forEachRemaining(entry -> {
            JsonNode item = entry.getValue();
            BigDecimal numerator = decimalNode(item.path("numerator"));
            BigDecimal denominator = decimalNode(item.path("denominator"));
            if (numerator == null || denominator == null || numerator.signum() <= 0 || denominator.signum() <= 0) return;
            long epoch = item.path("date").asLong(0);
            if (epoch <= 0) return;
            LocalDate date = Instant.ofEpochSecond(epoch).atZone(ZoneOffset.UTC).toLocalDate();
            if (!date.isBefore(from) && !date.isAfter(to)) result.add(new YahooStockSplitPoint(date, numerator, denominator));
        });
        return result.stream().sorted(Comparator.comparing(YahooStockSplitPoint::splitDate)).toList();
    }

    public List<QuarterlyFundamentalPoint> fetchQuarterlyFundamentalsHistory(String symbol, LocalDate from, LocalDate to)
            throws IOException, InterruptedException {
        if (to.isBefore(from)) {
            return List.of();
        }

        String encodedSymbol = URLEncoder.encode(resolvePricingSymbol(symbol), StandardCharsets.UTF_8);
        long period1 = from.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        long period2 = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        String url = yahooBaseUrl + "/ws/fundamentals-timeseries/v1/finance/timeseries/" + encodedSymbol
                + "?type=" + String.join(",",
                "quarterlyBasicEPS",
                "quarterlyDilutedEPS",
                "quarterlyDilutedAverageShares",
                "quarterlyOperatingCashFlow",
                "quarterlyFreeCashFlow",
                "quarterlyCapitalExpenditure",
                "quarterlyDepreciationAndAmortization",
                "quarterlyChangeInWorkingCapital",
                "quarterlyNetIssuancePaymentsOfDebt",
                "quarterlyTotalAssets",
                "quarterlyTotalRevenue",
                "quarterlyGrossProfit",
                "quarterlyOperatingIncome",
                "quarterlyInterestExpense",
                "quarterlyNetIncome",
                "quarterlyStockholdersEquity",
                "quarterlyTotalDebt",
                "quarterlyCashAndCashEquivalents",
                "quarterlyCashCashEquivalentsAndShortTermInvestments",
                "quarterlyOtherShortTermInvestments",
                "quarterlyTaxProvision",
                "quarterlyPretaxIncome",
                "quarterlyInvestedCapital",
                "quarterlyGrossMargin",
                "quarterlyROIC")
                + "&period1=" + period1
                + "&period2=" + period2;

        JsonNode root = readJson(url);
        JsonNode results = root.path("timeseries").path("result");
        if (!results.isArray()) {
            return List.of();
        }

        Map<LocalDate, MutableQuarterlyFundamentalPoint> byDate = new LinkedHashMap<>();
        String defaultCurrencyCode = null;
        for (JsonNode result : results) {
            if (defaultCurrencyCode == null || defaultCurrencyCode.isBlank()) {
                defaultCurrencyCode = result.path("meta").path("currencyCode").asText("");
            }
            readMetric(result, "quarterlyBasicEPS", from, to, byDate, MutableQuarterlyFundamentalPoint::basicEps);
            readMetric(result, "quarterlyDilutedEPS", from, to, byDate, MutableQuarterlyFundamentalPoint::dilutedEps);
            readMetric(result, "quarterlyDilutedAverageShares", from, to, byDate, MutableQuarterlyFundamentalPoint::dilutedWeightedAverageShares);
            readMetric(result, "quarterlyOperatingCashFlow", from, to, byDate, MutableQuarterlyFundamentalPoint::cashFlow);
            readMetric(result, "quarterlyFreeCashFlow", from, to, byDate, MutableQuarterlyFundamentalPoint::fcf);
            readMetric(result, "quarterlyCapitalExpenditure", from, to, byDate, MutableQuarterlyFundamentalPoint::capex);
            readMetric(result, "quarterlyDepreciationAndAmortization", from, to, byDate, MutableQuarterlyFundamentalPoint::depreciationAmortization);
            readMetric(result, "quarterlyChangeInWorkingCapital", from, to, byDate, MutableQuarterlyFundamentalPoint::changeInWorkingCapital);
            readMetric(result, "quarterlyNetIssuancePaymentsOfDebt", from, to, byDate, MutableQuarterlyFundamentalPoint::netBorrowing);
            readMetric(result, "quarterlyTotalAssets", from, to, byDate, MutableQuarterlyFundamentalPoint::totalAssets);
            readMetric(result, "quarterlyTotalRevenue", from, to, byDate, MutableQuarterlyFundamentalPoint::revenue);
            readMetric(result, "quarterlyGrossProfit", from, to, byDate, MutableQuarterlyFundamentalPoint::grossProfit);
            readMetric(result, "quarterlyOperatingIncome", from, to, byDate, MutableQuarterlyFundamentalPoint::operatingIncome);
            readMetric(result, "quarterlyInterestExpense", from, to, byDate, MutableQuarterlyFundamentalPoint::interestExpense);
            readMetric(result, "quarterlyNetIncome", from, to, byDate, MutableQuarterlyFundamentalPoint::netIncome);
            readMetric(result, "quarterlyStockholdersEquity", from, to, byDate, MutableQuarterlyFundamentalPoint::stockholdersEquity);
            readMetric(result, "quarterlyTotalDebt", from, to, byDate, MutableQuarterlyFundamentalPoint::totalDebt);
            readMetric(result, "quarterlyCashAndCashEquivalents", from, to, byDate, MutableQuarterlyFundamentalPoint::cashAndEquivalents);
            readMetric(result, "quarterlyCashCashEquivalentsAndShortTermInvestments", from, to, byDate, MutableQuarterlyFundamentalPoint::cashAndShortTermInvestments);
            readMetric(result, "quarterlyOtherShortTermInvestments", from, to, byDate, MutableQuarterlyFundamentalPoint::shortTermInvestments);
            readMetric(result, "quarterlyTaxProvision", from, to, byDate, MutableQuarterlyFundamentalPoint::taxProvision);
            readMetric(result, "quarterlyPretaxIncome", from, to, byDate, MutableQuarterlyFundamentalPoint::pretaxIncome);
            readMetric(result, "quarterlyInvestedCapital", from, to, byDate, MutableQuarterlyFundamentalPoint::investedCapital);
            readRatioMetric(result, "quarterlyGrossMargin", from, to, byDate, MutableQuarterlyFundamentalPoint::grossMargin);
            readRatioMetric(result, "quarterlyROIC", from, to, byDate, MutableQuarterlyFundamentalPoint::roic);
        }

        final String currencyCode = defaultCurrencyCode == null || defaultCurrencyCode.isBlank() ? null : defaultCurrencyCode;
        return byDate.values().stream()
                .map(point -> point.toPoint(currencyCode))
                .sorted(Comparator.comparing(QuarterlyFundamentalPoint::asOfDate))
                .toList();
    }

    public Optional<BigDecimal> fetchForwardEps(String symbol) throws IOException, InterruptedException {
        String encodedSymbol = URLEncoder.encode(resolvePricingSymbol(symbol), StandardCharsets.UTF_8);
        for (int i = 0; i < 2; i++) {
            String currentCrumb = ensureCrumb();
            String url = yahooBaseUrl + "/v10/finance/quoteSummary/" + encodedSymbol
                    + "?modules=defaultKeyStatistics&crumb=" + URLEncoder.encode(currentCrumb, StandardCharsets.UTF_8);
            JsonNode root = readJson(url);
            String errorCode = root.path("finance").path("error").path("code").asText("");
            if ("Unauthorized".equalsIgnoreCase(errorCode) || "Invalid Crumb".equalsIgnoreCase(errorCode)) {
                crumb = null;
                continue;
            }
            JsonNode forwardEpsNode = root.path("quoteSummary")
                    .path("result")
                    .path(0)
                    .path("defaultKeyStatistics")
                    .path("forwardEps")
                    .path("raw");
            if (!forwardEpsNode.isMissingNode() && !forwardEpsNode.isNull()) {
                return Optional.of(forwardEpsNode.decimalValue());
            }
        }
        return Optional.empty();
    }

    public Optional<BigDecimal> fetchSharesOutstanding(String symbol) throws IOException, InterruptedException {
        String encodedSymbol = URLEncoder.encode(resolvePricingSymbol(symbol), StandardCharsets.UTF_8);
        for (int i = 0; i < 2; i++) {
            String currentCrumb = ensureCrumb();
            String url = yahooBaseUrl + "/v10/finance/quoteSummary/" + encodedSymbol
                    + "?modules=defaultKeyStatistics&crumb=" + URLEncoder.encode(currentCrumb, StandardCharsets.UTF_8);
            JsonNode root = readJson(url);
            String errorCode = root.path("finance").path("error").path("code").asText("");
            if ("Unauthorized".equalsIgnoreCase(errorCode) || "Invalid Crumb".equalsIgnoreCase(errorCode)) {
                crumb = null;
                continue;
            }
            JsonNode stats = root.path("quoteSummary")
                    .path("result")
                    .path(0)
                    .path("defaultKeyStatistics");
            Optional<BigDecimal> shares = readRawDecimal(stats.path("sharesOutstanding"));
            if (shares.isEmpty()) {
                shares = readRawDecimal(stats.path("impliedSharesOutstanding"));
            }
            if (shares.isPresent()) {
                return shares;
            }
        }
        return Optional.empty();
    }

    public Optional<BigDecimal> fetchBeta(String symbol) throws IOException, InterruptedException {
        String encodedSymbol = URLEncoder.encode(resolvePricingSymbol(symbol), StandardCharsets.UTF_8);
        for (int i = 0; i < 2; i++) {
            String currentCrumb = ensureCrumb();
            String url = yahooBaseUrl + "/v10/finance/quoteSummary/" + encodedSymbol
                    + "?modules=summaryDetail,defaultKeyStatistics&crumb=" + URLEncoder.encode(currentCrumb, StandardCharsets.UTF_8);
            JsonNode root = readJson(url);
            String errorCode = root.path("finance").path("error").path("code").asText("");
            if ("Unauthorized".equalsIgnoreCase(errorCode) || "Invalid Crumb".equalsIgnoreCase(errorCode)) {
                crumb = null;
                continue;
            }
            JsonNode result = root.path("quoteSummary").path("result").path(0);
            Optional<BigDecimal> beta = readRawDecimal(result.path("defaultKeyStatistics").path("beta"));
            if (beta.isEmpty()) {
                beta = readRawDecimal(result.path("summaryDetail").path("beta"));
            }
            if (beta.isPresent()) {
                return beta;
            }
        }
        return Optional.empty();
    }

    public List<ForwardEpsEstimatePoint> fetchForwardEpsEstimates(String symbol) throws IOException, InterruptedException {
        String encodedSymbol = URLEncoder.encode(resolvePricingSymbol(symbol), StandardCharsets.UTF_8);
        for (int i = 0; i < 2; i++) {
            String currentCrumb = ensureCrumb();
            String url = yahooBaseUrl + "/v10/finance/quoteSummary/" + encodedSymbol
                    + "?modules=earningsTrend&crumb=" + URLEncoder.encode(currentCrumb, StandardCharsets.UTF_8);
            JsonNode root = readJson(url);
            String errorCode = root.path("finance").path("error").path("code").asText("");
            if ("Unauthorized".equalsIgnoreCase(errorCode) || "Invalid Crumb".equalsIgnoreCase(errorCode)) {
                crumb = null;
                continue;
            }

            JsonNode trend = root.path("quoteSummary")
                    .path("result")
                    .path(0)
                    .path("earningsTrend")
                    .path("trend");
            if (!trend.isArray()) {
                return List.of();
            }

            List<ForwardEpsEstimatePoint> points = new ArrayList<>();
            for (JsonNode item : trend) {
                String period = item.path("period").asText("");
                String periodType = estimatePeriodType(period);
                if (periodType == null) {
                    continue;
                }
                String endDateText = item.path("endDate").asText("");
                BigDecimal eps = readRawDecimal(item.path("earningsEstimate").path("avg")).orElse(null);
                BigDecimal revenue = readRawDecimal(item.path("revenueEstimate").path("avg")).orElse(null);
                if (endDateText.isBlank() || (eps == null && revenue == null)) {
                    continue;
                }
                try {
                    points.add(new ForwardEpsEstimatePoint(
                            periodType,
                            period,
                            LocalDate.parse(endDateText),
                            eps,
                            readRawDecimal(item.path("earningsEstimate").path("low")).orElse(null),
                            readRawDecimal(item.path("earningsEstimate").path("high")).orElse(null),
                            readRawInteger(item.path("earningsEstimate").path("numberOfAnalysts")).orElse(null),
                            revenue,
                            readRawDecimal(item.path("revenueEstimate").path("low")).orElse(null),
                            readRawDecimal(item.path("revenueEstimate").path("high")).orElse(null),
                            readRawInteger(item.path("revenueEstimate").path("numberOfAnalysts")).orElse(null)
                    ));
                } catch (RuntimeException ignored) {
                }
            }
            return points.stream()
                    .sorted(Comparator.comparing(ForwardEpsEstimatePoint::asOfDate))
                    .toList();
        }
        return List.of();
    }

    private String estimatePeriodType(String period) {
        if ("0q".equals(period) || period.matches("\\+\\d+q")) {
            return "QUARTERLY";
        }
        if ("0y".equals(period) || period.matches("\\+\\d+y")) {
            return "ANNUAL";
        }
        return null;
    }

    private Optional<BigDecimal> readRawDecimal(JsonNode node) {
        JsonNode raw = node.path("raw");
        if (raw.isMissingNode() || raw.isNull()) {
            return Optional.empty();
        }
        return Optional.of(raw.decimalValue());
    }

    private Optional<Integer> readRawInteger(JsonNode node) {
        JsonNode raw = node.path("raw");
        if (raw.isMissingNode() || raw.isNull()) {
            return Optional.empty();
        }
        return Optional.of(raw.asInt());
    }

    public Optional<BigDecimal> fetchFxRate(String baseCurrency, String quoteCurrency)
            throws IOException, InterruptedException {
        if (baseCurrency == null || quoteCurrency == null || baseCurrency.isBlank() || quoteCurrency.isBlank()) {
            return Optional.empty();
        }
        if (baseCurrency.equalsIgnoreCase(quoteCurrency)) {
            return Optional.of(BigDecimal.ONE);
        }

        String direct = (baseCurrency + quoteCurrency + "=X").toUpperCase();
        Optional<BigDecimal> directRate = fetchLatestCloseForSymbol(direct);
        if (directRate.isPresent()) {
            return directRate;
        }

        String reverse = (quoteCurrency + baseCurrency + "=X").toUpperCase();
        Optional<BigDecimal> reverseRate = fetchLatestCloseForSymbol(reverse);
        if (reverseRate.isPresent() && reverseRate.get().compareTo(BigDecimal.ZERO) > 0) {
            return Optional.of(BigDecimal.ONE.divide(reverseRate.get(), 8, java.math.RoundingMode.HALF_UP));
        }

        return Optional.empty();
    }

    private Optional<BigDecimal> fetchLatestCloseForSymbol(String symbol) throws IOException, InterruptedException {
        LocalDate to = LocalDate.now(ZoneOffset.UTC);
        LocalDate from = to.minusDays(14);
        List<YahooDailyPricePoint> points = fetchDailyCloseHistory(symbol, from, to);
        return points.stream()
                .max(Comparator.comparing(YahooDailyPricePoint::tradeDate))
                .map(YahooDailyPricePoint::closePrice);
    }

    private Optional<BigDecimal> parseRegularMarketPrice(JsonNode meta) {
        JsonNode priceNode = meta.path("regularMarketPrice");
        if (priceNode.isMissingNode() || priceNode.isNull()) {
            return Optional.empty();
        }
        return Optional.of(priceNode.decimalValue());
    }

    private Optional<OffsetDateTime> parseRegularMarketTime(JsonNode meta) {
        JsonNode marketTimeNode = meta.path("regularMarketTime");
        if (marketTimeNode.isMissingNode() || marketTimeNode.isNull()) {
            return Optional.empty();
        }
        return Optional.of(OffsetDateTime.ofInstant(
                java.time.Instant.ofEpochSecond(marketTimeNode.asLong()),
                ZoneOffset.UTC
        ));
    }

    private ValuationMetrics fetchValuationMetrics(String symbol) throws IOException, InterruptedException {
        String encodedSymbol = URLEncoder.encode(symbol, StandardCharsets.UTF_8);

        for (int i = 0; i < 2; i++) {
            String currentCrumb = ensureCrumb();
            String url = yahooBaseUrl + "/v10/finance/quoteSummary/" + encodedSymbol
                    + "?modules=summaryDetail,defaultKeyStatistics&crumb=" + URLEncoder.encode(currentCrumb, StandardCharsets.UTF_8);

            JsonNode root = readJson(url);
            String errorCode = root.path("finance").path("error").path("code").asText("");
            if ("Unauthorized".equalsIgnoreCase(errorCode) || "Invalid Crumb".equalsIgnoreCase(errorCode)) {
                crumb = null;
                continue;
            }

            JsonNode summaryDetail = root.path("quoteSummary")
                    .path("result")
                    .path(0)
                    .path("summaryDetail");
            JsonNode defaultKeyStats = root.path("quoteSummary")
                    .path("result")
                    .path(0)
                    .path("defaultKeyStatistics");
            JsonNode trailingPeNode = summaryDetail.path("trailingPE").path("raw");
            JsonNode pegRatioNode = summaryDetail.path("pegRatio").path("raw");
            JsonNode pegRatioFallbackNode = defaultKeyStats.path("pegRatio").path("raw");

            BigDecimal trailingPe = trailingPeNode.isMissingNode() || trailingPeNode.isNull()
                    ? null
                    : trailingPeNode.decimalValue();
            BigDecimal pegRatio = pegRatioNode.isMissingNode() || pegRatioNode.isNull()
                    ? null
                    : pegRatioNode.decimalValue();
            if (pegRatio == null && !pegRatioFallbackNode.isMissingNode() && !pegRatioFallbackNode.isNull()) {
                pegRatio = pegRatioFallbackNode.decimalValue();
            }
            return new ValuationMetrics(trailingPe, pegRatio);
        }

        return new ValuationMetrics(null, null);
    }

    private JsonNode fetchSparkMeta(String symbol) throws IOException, InterruptedException {
        String encodedSymbol = URLEncoder.encode(symbol, StandardCharsets.UTF_8);
        String url = yahooBaseUrl + "/v7/finance/spark?symbols=" + encodedSymbol + "&range=1d&interval=5m";
        JsonNode root = readJson(url);
        return root.path("spark")
                .path("result")
                .path(0)
                .path("response")
                .path(0)
                .path("meta");
    }

    private String resolvePricingSymbol(String symbol) {
        if (symbol == null) {
            return null;
        }
        String normalized = symbol.trim().toUpperCase();
        return PRICING_SYMBOL_ALIASES.getOrDefault(normalized, normalized);
    }

    private String ensureCrumb() throws IOException, InterruptedException {
        if (crumb != null && !crumb.isBlank()) {
            return crumb;
        }

        HttpRequest primeCookieRequest = HttpRequest.newBuilder(URI.create("https://fc.yahoo.com"))
                .header("User-Agent", "Mozilla/5.0")
                .GET()
                .build();
        httpClient.send(primeCookieRequest, HttpResponse.BodyHandlers.discarding());

        HttpRequest crumbRequest = HttpRequest.newBuilder(URI.create(yahooBaseUrl + "/v1/test/getcrumb"))
                .header("User-Agent", "Mozilla/5.0")
                .GET()
                .build();
        HttpResponse<String> crumbResponse = httpClient.send(crumbRequest, HttpResponse.BodyHandlers.ofString());

        if (crumbResponse.statusCode() >= 400 || crumbResponse.body() == null || crumbResponse.body().isBlank()) {
            throw new IOException("Failed to obtain Yahoo crumb");
        }

        crumb = crumbResponse.body().trim();
        return crumb;
    }

    private JsonNode readJson(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "Mozilla/5.0")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new IOException("Yahoo request failed with status " + response.statusCode());
        }

        return objectMapper.readTree(response.body());
    }

    private void readMetric(JsonNode result,
                            String fieldName,
                            LocalDate from,
                            LocalDate to,
                            Map<LocalDate, MutableQuarterlyFundamentalPoint> byDate,
                            MetricSetter setter) {
        JsonNode values = result.path(fieldName);
        if (!values.isArray()) {
            return;
        }
        for (JsonNode item : values) {
            String asOfDateRaw = item.path("asOfDate").asText("");
            JsonNode rawNode = item.path("reportedValue").path("raw");
            if (asOfDateRaw.isBlank() || rawNode.isMissingNode() || rawNode.isNull()) {
                continue;
            }
            LocalDate asOfDate = LocalDate.parse(asOfDateRaw);
            if (asOfDate.isBefore(from) || asOfDate.isAfter(to)) {
                continue;
            }
            MutableQuarterlyFundamentalPoint point = byDate.computeIfAbsent(
                    asOfDate,
                    MutableQuarterlyFundamentalPoint::new
            );
            setter.set(point, rawNode.decimalValue());
        }
    }

    private void readRatioMetric(JsonNode result,
                                 String fieldName,
                                 LocalDate from,
                                 LocalDate to,
                                 Map<LocalDate, MutableQuarterlyFundamentalPoint> byDate,
                                 MetricSetter setter) {
        readMetric(result, fieldName, from, to, byDate,
                (point, value) -> setter.set(point, value == null ? null : value.multiply(BigDecimal.valueOf(100))));
    }

    public record YahooMarketSnapshot(
            BigDecimal regularMarketPrice,
            BigDecimal trailingPe,
            BigDecimal pegRatio,
            OffsetDateTime regularMarketTime,
            String currency
    ) {
        public YahooMarketSnapshot(BigDecimal regularMarketPrice, BigDecimal trailingPe, BigDecimal pegRatio,
                                   OffsetDateTime regularMarketTime) {
            this(regularMarketPrice, trailingPe, pegRatio, regularMarketTime, null);
        }
    }

    public record YahooDailyPricePoint(
            LocalDate tradeDate,
            BigDecimal closePrice,
            BigDecimal adjustedClosePrice
    ) {
        public YahooDailyPricePoint(LocalDate tradeDate, BigDecimal closePrice) { this(tradeDate, closePrice, null); }
    }

    public record YahooStockSplitPoint(LocalDate splitDate, BigDecimal numerator, BigDecimal denominator) { }

    public record QuarterlyFundamentalPoint(
            LocalDate asOfDate,
            BigDecimal basicEps,
            String currencyCode,
            BigDecimal ttmEps,
            BigDecimal forwardEps,
            BigDecimal cashFlow,
            BigDecimal fcf,
            BigDecimal capex,
            BigDecimal adjustedFcf,
            BigDecimal roe,
            BigDecimal roic,
            BigDecimal grossMargin,
            BigDecimal revenue,
            BigDecimal grossProfit,
            BigDecimal operatingIncome,
            BigDecimal interestExpense,
            BigDecimal netIncome,
            BigDecimal stockholdersEquity,
            BigDecimal totalDebt,
            BigDecimal cashAndEquivalents,
            BigDecimal shortTermInvestments,
            BigDecimal noncurrentMarketableSecurities,
            BigDecimal taxProvision,
            BigDecimal pretaxIncome,
            BigDecimal investedCapital,
            BigDecimal dilutedEps,
            BigDecimal dilutedWeightedAverageShares,
            BigDecimal depreciationAmortization,
            BigDecimal changeInWorkingCapital,
            BigDecimal netBorrowing,
            BigDecimal shareRepurchases,
            BigDecimal totalAssets,
            Integer fiscalYear,
            String fiscalPeriod,
            LocalDate filingDate
    ) {
        public QuarterlyFundamentalPoint(
                LocalDate asOfDate, BigDecimal basicEps, String currencyCode, BigDecimal ttmEps,
                BigDecimal forwardEps, BigDecimal cashFlow, BigDecimal fcf, BigDecimal capex,
                BigDecimal adjustedFcf, BigDecimal roe, BigDecimal roic, BigDecimal grossMargin,
                BigDecimal revenue, BigDecimal grossProfit, BigDecimal operatingIncome,
                BigDecimal interestExpense, BigDecimal netIncome, BigDecimal stockholdersEquity,
                BigDecimal totalDebt, BigDecimal cashAndEquivalents, BigDecimal taxProvision,
                BigDecimal pretaxIncome, BigDecimal investedCapital
        ) {
            this(asOfDate, basicEps, currencyCode, ttmEps, forwardEps, cashFlow, fcf, capex,
                    adjustedFcf, roe, roic, grossMargin, revenue, grossProfit, operatingIncome,
                    interestExpense, netIncome, stockholdersEquity, totalDebt, cashAndEquivalents,
                    null, null, taxProvision, pretaxIncome, investedCapital, null, null, null, null, null,
                    null, null, null, null, null);
        }
    }

    public record ForwardEpsEstimatePoint(
            String periodType,
            String periodCode,
            LocalDate asOfDate,
            BigDecimal eps,
            BigDecimal epsLow,
            BigDecimal epsHigh,
            Integer numberOfAnalysts,
            BigDecimal revenue,
            BigDecimal revenueLow,
            BigDecimal revenueHigh,
            Integer revenueAnalysts
    ) {
    }

    private BigDecimal decimalNode(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        if (node.isNumber()) return node.decimalValue();
        try { return new BigDecimal(node.asText()); } catch (RuntimeException ignored) { return null; }
    }

    private interface MetricSetter {
        void set(MutableQuarterlyFundamentalPoint point, BigDecimal value);
    }

    private static class MutableQuarterlyFundamentalPoint {
        private final LocalDate asOfDate;
        private BigDecimal basicEps;
        private BigDecimal cashFlow;
        private BigDecimal fcf;
        private BigDecimal capex;
        private BigDecimal adjustedFcf;
        private BigDecimal roic;
        private BigDecimal grossMargin;
        private BigDecimal revenue;
        private BigDecimal grossProfit;
        private BigDecimal operatingIncome;
        private BigDecimal interestExpense;
        private BigDecimal netIncome;
        private BigDecimal stockholdersEquity;
        private BigDecimal totalDebt;
        private BigDecimal cashAndEquivalents;
        private BigDecimal cashAndShortTermInvestments;
        private BigDecimal shortTermInvestments;
        private BigDecimal taxProvision;
        private BigDecimal pretaxIncome;
        private BigDecimal investedCapital;
        private BigDecimal dilutedEps;
        private BigDecimal dilutedWeightedAverageShares;
        private BigDecimal depreciationAmortization;
        private BigDecimal changeInWorkingCapital;
        private BigDecimal netBorrowing;
        private BigDecimal totalAssets;

        private MutableQuarterlyFundamentalPoint(LocalDate asOfDate) {
            this.asOfDate = asOfDate;
        }

        private void basicEps(BigDecimal value) {
            this.basicEps = value;
        }

        private void dilutedEps(BigDecimal value) { this.dilutedEps = value; }
        private void dilutedWeightedAverageShares(BigDecimal value) { this.dilutedWeightedAverageShares = value; }
        private void depreciationAmortization(BigDecimal value) { this.depreciationAmortization = value; }
        private void changeInWorkingCapital(BigDecimal value) { this.changeInWorkingCapital = value; }
        private void netBorrowing(BigDecimal value) { this.netBorrowing = value; }
        private void totalAssets(BigDecimal value) { this.totalAssets = value; }

        private void cashFlow(BigDecimal value) {
            this.cashFlow = value;
        }

        private void fcf(BigDecimal value) {
            this.fcf = value;
        }

        private void capex(BigDecimal value) {
            this.capex = value == null ? null : value.abs();
        }

        private void revenue(BigDecimal value) {
            this.revenue = value;
        }

        private void grossProfit(BigDecimal value) {
            this.grossProfit = value;
        }

        private void operatingIncome(BigDecimal value) {
            this.operatingIncome = value;
        }

        private void interestExpense(BigDecimal value) {
            this.interestExpense = value;
        }

        private void netIncome(BigDecimal value) {
            this.netIncome = value;
        }

        private void stockholdersEquity(BigDecimal value) {
            this.stockholdersEquity = value;
        }

        private void totalDebt(BigDecimal value) {
            this.totalDebt = value;
        }

        private void cashAndEquivalents(BigDecimal value) {
            this.cashAndEquivalents = value;
        }
        private void cashAndShortTermInvestments(BigDecimal value) { this.cashAndShortTermInvestments = value; }
        private void shortTermInvestments(BigDecimal value) { this.shortTermInvestments = value; }

        private void taxProvision(BigDecimal value) {
            this.taxProvision = value;
        }

        private void pretaxIncome(BigDecimal value) {
            this.pretaxIncome = value;
        }

        private void investedCapital(BigDecimal value) {
            this.investedCapital = value;
        }

        private void roic(BigDecimal value) {
            this.roic = value;
        }

        private void grossMargin(BigDecimal value) {
            this.grossMargin = value;
        }

        private QuarterlyFundamentalPoint toPoint(String currencyCode) {
            BigDecimal resolvedAdjustedFcf = adjustedFcf;
            if (resolvedAdjustedFcf == null && cashFlow != null && capex != null) {
                resolvedAdjustedFcf = cashFlow.subtract(capex);
            }
            BigDecimal resolvedShortTermInvestments = shortTermInvestments;
            if (resolvedShortTermInvestments == null && cashAndShortTermInvestments != null && cashAndEquivalents != null) {
                resolvedShortTermInvestments = cashAndShortTermInvestments.subtract(cashAndEquivalents).max(BigDecimal.ZERO);
            }
            return new QuarterlyFundamentalPoint(
                    asOfDate,
                    basicEps,
                    currencyCode,
                    null,
                    null,
                    cashFlow,
                    fcf,
                    capex,
                    resolvedAdjustedFcf,
                    null,
                    roic,
                    grossMargin,
                    revenue,
                    grossProfit,
                    operatingIncome,
                    interestExpense,
                    netIncome,
                    stockholdersEquity,
                    totalDebt,
                    cashAndEquivalents,
                    resolvedShortTermInvestments,
                    null,
                    taxProvision,
                    pretaxIncome,
                    investedCapital,
                    dilutedEps,
                    dilutedWeightedAverageShares,
                    depreciationAmortization,
                    changeInWorkingCapital,
                    netBorrowing,
                    null,
                    totalAssets,
                    asOfDate.getYear(),
                    "Q" + (((asOfDate.getMonthValue() - 1) / 3) + 1),
                    null
            );
        }
    }

    private record ValuationMetrics(
            BigDecimal trailingPe,
            BigDecimal pegRatio
    ) {
    }
}
