package com.stockportfolio.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "external_wacc_reference", uniqueConstraints = @UniqueConstraint(columnNames = {"symbol", "provider"}))
public class ExternalWaccReference {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, length = 20) private String symbol;
    @Column(nullable = false, length = 32) private String provider;
    @Column(name = "rate_pct", precision = 12, scale = 6) private BigDecimal ratePct;
    @Column(name = "source_url", nullable = false, columnDefinition = "text") private String sourceUrl;
    @Column(name = "provider_as_of") private LocalDate providerAsOf;
    @Column(name = "retrieved_at", nullable = false) private OffsetDateTime retrievedAt;
    @Column(nullable = false, length = 16) private String status;
    @Column(name = "error_message", columnDefinition = "text") private String errorMessage;
    public String getSymbol() { return symbol; } public void setSymbol(String v) { symbol = v; }
    public String getProvider() { return provider; } public void setProvider(String v) { provider = v; }
    public BigDecimal getRatePct() { return ratePct; } public void setRatePct(BigDecimal v) { ratePct = v; }
    public String getSourceUrl() { return sourceUrl; } public void setSourceUrl(String v) { sourceUrl = v; }
    public LocalDate getProviderAsOf() { return providerAsOf; } public void setProviderAsOf(LocalDate v) { providerAsOf = v; }
    public OffsetDateTime getRetrievedAt() { return retrievedAt; } public void setRetrievedAt(OffsetDateTime v) { retrievedAt = v; }
    public String getStatus() { return status; } public void setStatus(String v) { status = v; }
    public String getErrorMessage() { return errorMessage; } public void setErrorMessage(String v) { errorMessage = v; }
}
