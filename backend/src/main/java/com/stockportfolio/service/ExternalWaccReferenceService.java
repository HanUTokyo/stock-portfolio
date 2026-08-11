package com.stockportfolio.service;

import com.stockportfolio.dto.*;
import com.stockportfolio.model.ExternalWaccReference;
import com.stockportfolio.repository.ExternalWaccReferenceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.*;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.regex.*;

/** Fetches only an explicitly displayed public WACC field. No credentials, retries around access controls, or scraping bypasses. */
@Service
@Transactional
public class ExternalWaccReferenceService {
    static final String DEEPVIEWS = "DEEPVIEWS", ALPHA_SPREAD = "ALPHA_SPREAD", SYSTEM = "SYSTEM_ESTIMATE";
    private static final Pattern WACC = Pattern.compile("(?is)(?:weighted\\s+average\\s+cost\\s+of\\s+capital|\\bwacc\\b).{0,220}?([0-9]{1,2}(?:\\.[0-9]{1,4})?)\\s*%");
    private static final Set<String> BLOCKED = Set.of("captcha", "sign in", "log in", "paywall", "subscription required", "access denied");
    private final ExternalWaccReferenceRepository repository;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    public ExternalWaccReferenceService(ExternalWaccReferenceRepository repository) { this.repository = repository; }

    public WaccReferencesResponse references(String symbol, BigDecimal systemWaccPct) {
        Map<String, ExternalWaccReference> stored = new HashMap<>();
        repository.findBySymbolOrderByProviderAsc(symbol).forEach(row -> stored.put(row.getProvider(), row));
        List<ExternalWaccReferenceResponse> rows = new ArrayList<>();
        rows.add(response(SYSTEM, systemWaccPct, systemUrl(), null, OffsetDateTime.now(), systemWaccPct, "AVAILABLE", null));
        for (String provider : List.of(DEEPVIEWS, ALPHA_SPREAD)) {
            ExternalWaccReference row = stored.get(provider);
            rows.add(row == null ? response(provider, null, sourceUrl(provider, symbol), null, null, systemWaccPct,
                    "UNAVAILABLE", "No successful manual refresh has been stored.") :
                    response(provider, row.getRatePct(), row.getSourceUrl(), row.getProviderAsOf(), row.getRetrievedAt(), systemWaccPct,
                            row.getStatus(), row.getErrorMessage()));
        }
        return new WaccReferencesResponse(symbol, systemWaccPct, rows);
    }

    public WaccReferencesResponse refresh(String symbol, BigDecimal systemWaccPct) {
        for (String provider : List.of(DEEPVIEWS, ALPHA_SPREAD)) refreshOne(symbol, provider);
        return references(symbol, systemWaccPct);
    }

    private void refreshOne(String symbol, String provider) {
        ExternalWaccReference row = repository.findBySymbolAndProvider(symbol, provider).orElseGet(ExternalWaccReference::new);
        row.setSymbol(symbol); row.setProvider(provider); row.setSourceUrl(sourceUrl(provider, symbol)); row.setRetrievedAt(OffsetDateTime.now());
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(row.getSourceUrl())).timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "StockPortfolioResearch/2.1 (public-reference-fetch)")
                    .header("Accept", "text/html,application/xhtml+xml").GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IOException("HTTP " + response.statusCode());
            Optional<BigDecimal> parsed = parseRate(response.body());
            if (parsed.isEmpty()) throw new IOException("No explicit public WACC field found (or page requires access).");
            row.setRatePct(parsed.get()); row.setStatus("AVAILABLE"); row.setErrorMessage(null);
        } catch (Exception exception) {
            row.setStatus(row.getRatePct() == null ? "UNAVAILABLE" : "STALE");
            row.setErrorMessage(safeError(exception));
        }
        repository.save(row);
    }

    static Optional<BigDecimal> parseRate(String html) {
        if (html == null || html.isBlank()) return Optional.empty();
        String normalized = html.toLowerCase(Locale.ROOT);
        if (BLOCKED.stream().anyMatch(normalized::contains)) return Optional.empty();
        Matcher matcher = WACC.matcher(html);
        if (!matcher.find()) return Optional.empty();
        try {
            BigDecimal value = new BigDecimal(matcher.group(1));
            return value.compareTo(BigDecimal.ZERO) > 0 && value.compareTo(new BigDecimal("40")) < 0
                    ? Optional.of(value.setScale(4, RoundingMode.HALF_UP)) : Optional.empty();
        } catch (NumberFormatException ex) { return Optional.empty(); }
    }

    private ExternalWaccReferenceResponse response(String provider, BigDecimal rate, String url, LocalDate asOf,
                                                   OffsetDateTime retrieved, BigDecimal system, String status, String error) {
        BigDecimal difference = rate == null || system == null ? null : rate.subtract(system).setScale(4, RoundingMode.HALF_UP);
        boolean selectable = rate != null && ("AVAILABLE".equals(status) || SYSTEM.equals(provider));
        return new ExternalWaccReferenceResponse(provider, rate, difference, url, asOf, retrieved, status, error, selectable);
    }
    private String sourceUrl(String provider, String symbol) {
        String ticker = symbol.toLowerCase(Locale.ROOT);
        return DEEPVIEWS.equals(provider) ? "https://www.deepviews.dev/en/wacc/" + ticker
                : "https://www.alphaspread.com/en/security/nasdaq/" + ticker + "/discount-rate";
    }
    private String systemUrl() { return null; }
    private String safeError(Exception e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage().substring(0, Math.min(500, e.getMessage().length())); }
}
