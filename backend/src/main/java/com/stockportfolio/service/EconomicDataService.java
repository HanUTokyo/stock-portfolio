package com.stockportfolio.service;

import com.stockportfolio.model.EconomicObservation;
import com.stockportfolio.repository.EconomicObservationRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Service
public class EconomicDataService {
    public static final String CPI_SERIES = "CPIAUCSL";
    private static final String CPI_SOURCE = "FRED CPI-U, seasonally adjusted";

    private final EconomicObservationRepository repository;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final String cpiCsvUrl;

    public EconomicDataService(EconomicObservationRepository repository,
                               @Value("${app.economic-data.cpi-csv-url:https://fred.stlouisfed.org/graph/fredgraph.csv?id=CPIAUCSL}") String cpiCsvUrl) {
        this.repository = repository;
        this.cpiCsvUrl = cpiCsvUrl;
    }

    @Scheduled(cron = "${app.economic-data.cpi-cron:0 15 4 * * *}", zone = "${app.pricing.timezone:America/New_York}")
    public void scheduledRefresh() {
        try { refreshCpi(); } catch (Exception ignored) { /* cached observations remain authoritative */ }
    }

    @Transactional
    public int refreshCpi() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(cpiCsvUrl))
                .header("User-Agent", "stock-portfolio/valuation-java")
                .GET().build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) throw new IOException("FRED CPI CSV failed with status " + response.statusCode());
        int written = 0;
        for (String line : response.body().split("\\R")) {
            if (line.isBlank() || line.startsWith("observation_date")) continue;
            String[] parts = line.split(",", -1);
            if (parts.length < 2 || parts[1].isBlank() || ".".equals(parts[1])) continue;
            try {
                LocalDate date = LocalDate.parse(parts[0].trim());
                BigDecimal value = new BigDecimal(parts[1].trim());
                EconomicObservation row = repository.findBySeriesIdAndObservationDate(CPI_SERIES, date)
                        .orElseGet(EconomicObservation::new);
                row.setSeriesId(CPI_SERIES);
                row.setObservationDate(date);
                row.setValue(value);
                row.setSourceCode("FRED");
                row.setSourceName(CPI_SOURCE);
                repository.save(row);
                written++;
            } catch (RuntimeException ignored) {
                // Ignore malformed rows; a valid cache must never be cleared by a bad response row.
            }
        }
        if (written == 0) throw new IOException("FRED CPI CSV contained no observations");
        return written;
    }

    @Transactional
    public void refreshIfEmpty() {
        if (repository.findTopBySeriesIdOrderByObservationDateDesc(CPI_SERIES).isPresent()) return;
        try { refreshCpi(); } catch (Exception ignored) { }
    }

    @Transactional(readOnly = true)
    public Optional<EconomicObservation> latestCpi() {
        return repository.findTopBySeriesIdOrderByObservationDateDesc(CPI_SERIES);
    }

    @Transactional(readOnly = true)
    public List<EconomicObservation> cpiBetween(LocalDate from, LocalDate to) {
        return repository.findBySeriesIdAndObservationDateBetweenOrderByObservationDateAsc(CPI_SERIES, from, to);
    }
}
