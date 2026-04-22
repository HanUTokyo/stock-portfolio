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

        try {
            JsonNode sparkMeta = fetchSparkMeta(pricingSymbol);
            price = parseRegularMarketPrice(sparkMeta).orElse(null);
            marketTime = parseRegularMarketTime(sparkMeta).orElse(null);
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

        return Optional.of(new YahooMarketSnapshot(price, trailingPe, pegRatio, marketTime));
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
                + "&interval=1d&events=history&includeAdjustedClose=false";

        JsonNode root = readJson(url);
        JsonNode result = root.path("chart").path("result").path(0);
        JsonNode timestamps = result.path("timestamp");
        JsonNode closes = result.path("indicators").path("quote").path(0).path("close");

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

            points.add(new YahooDailyPricePoint(tradeDate, closeNode.decimalValue()));
        }

        return points;
    }

    public List<QuarterlyEpsPoint> fetchTrailingBasicEpsHistory(String symbol, LocalDate from, LocalDate to)
            throws IOException, InterruptedException {
        if (to.isBefore(from)) {
            return List.of();
        }

        String encodedSymbol = URLEncoder.encode(resolvePricingSymbol(symbol), StandardCharsets.UTF_8);
        long period1 = from.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        long period2 = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        String url = yahooBaseUrl + "/ws/fundamentals-timeseries/v1/finance/timeseries/" + encodedSymbol
                + "?type=trailingBasicEPS"
                + "&period1=" + period1
                + "&period2=" + period2;

        JsonNode root = readJson(url);
        JsonNode result = root.path("timeseries").path("result").path(0);
        JsonNode epsArray = result.path("trailingBasicEPS");
        String defaultCurrencyCode = result.path("meta").path("currencyCode").asText("");

        if (!epsArray.isArray()) {
            return List.of();
        }

        List<QuarterlyEpsPoint> points = new ArrayList<>();
        for (JsonNode item : epsArray) {
            String asOfDateRaw = item.path("asOfDate").asText("");
            JsonNode rawNode = item.path("reportedValue").path("raw");
            String currencyCode = item.path("currencyCode").asText("");
            if (asOfDateRaw.isBlank() || rawNode.isMissingNode() || rawNode.isNull()) {
                continue;
            }

            LocalDate asOfDate = LocalDate.parse(asOfDateRaw);
            if (asOfDate.isBefore(from) || asOfDate.isAfter(to)) {
                continue;
            }

            String normalizedCurrencyCode = currencyCode.isBlank() ? defaultCurrencyCode : currencyCode;
            points.add(new QuarterlyEpsPoint(
                    asOfDate,
                    rawNode.decimalValue(),
                    normalizedCurrencyCode == null || normalizedCurrencyCode.isBlank() ? null : normalizedCurrencyCode
            ));
        }
        return points;
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

    public record YahooMarketSnapshot(
            BigDecimal regularMarketPrice,
            BigDecimal trailingPe,
            BigDecimal pegRatio,
            OffsetDateTime regularMarketTime
    ) {
    }

    public record YahooDailyPricePoint(
            LocalDate tradeDate,
            BigDecimal closePrice
    ) {
    }

    public record QuarterlyEpsPoint(
            LocalDate asOfDate,
            BigDecimal eps,
            String currencyCode
    ) {
    }

    private record ValuationMetrics(
            BigDecimal trailingPe,
            BigDecimal pegRatio
    ) {
    }
}
