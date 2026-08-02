package com.stockportfolio.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Optional;

@Service
public class TreasuryYieldService {
    private static final String SOURCE = "U.S. Treasury daily par yield curve";
    private static final String MATURITY = "10Y";

    private final String yieldCurveUrl;
    private final HttpClient httpClient;

    public TreasuryYieldService(
            @Value("${app.market-assumptions.treasury-yield-curve-url:https://home.treasury.gov/resource-center/data-chart-center/interest-rates/pages/xml?data=daily_treasury_yield_curve}") String yieldCurveUrl
    ) {
        this.yieldCurveUrl = yieldCurveUrl;
        this.httpClient = HttpClient.newHttpClient();
    }

    public Optional<RiskFreeRate> fetchTenYearParYield() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(buildYieldCurveUrl(LocalDate.now().getYear())))
                .header("User-Agent", "Mozilla/5.0")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new IOException("Treasury yield request failed with status " + response.statusCode());
        }
        return parseTenYearParYield(response.body());
    }

    String buildYieldCurveUrl(int year) {
        if (yieldCurveUrl.contains("field_tdr_date_value=") || yieldCurveUrl.contains("field_tdr_date_value_month=")) {
            return yieldCurveUrl;
        }
        String separator = yieldCurveUrl.contains("?") ? "&" : "?";
        return yieldCurveUrl + separator + "field_tdr_date_value=" + year;
    }

    Optional<RiskFreeRate> parseTenYearParYield(String xml) {
        if (xml == null || xml.isBlank()) {
            return Optional.empty();
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            Document document = factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            NodeList entries = document.getElementsByTagNameNS("*", "entry");
            RiskFreeRate latest = null;
            for (int i = 0; i < entries.getLength(); i += 1) {
                Element entry = (Element) entries.item(i);
                String dateText = firstText(entry, "NEW_DATE");
                String rateText = firstText(entry, "BC_10YEAR");
                if (dateText == null || dateText.isBlank() || rateText == null || rateText.isBlank()) {
                    continue;
                }
                LocalDate date = LocalDate.parse(dateText.substring(0, 10));
                BigDecimal rate = new BigDecimal(rateText.trim());
                RiskFreeRate candidate = new RiskFreeRate(rate, MATURITY, date, SOURCE);
                if (latest == null || candidate.date().isAfter(latest.date())) {
                    latest = candidate;
                }
            }
            return Optional.ofNullable(latest);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private String firstText(Element parent, String localName) {
        NodeList nodes = parent.getElementsByTagNameNS("*", localName);
        if (nodes.getLength() == 0 || nodes.item(0) == null) {
            return null;
        }
        return nodes.item(0).getTextContent();
    }

    public record RiskFreeRate(
            BigDecimal rate,
            String maturity,
            LocalDate date,
            String source
    ) {
    }
}
