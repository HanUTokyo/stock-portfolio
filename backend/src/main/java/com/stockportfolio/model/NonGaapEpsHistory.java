package com.stockportfolio.model;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(
        name = "non_gaap_eps_history",
        uniqueConstraints = @UniqueConstraint(columnNames = {"symbol", "as_of_date"})
)
public class NonGaapEpsHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String symbol;

    @Column(name = "as_of_date", nullable = false)
    private LocalDate asOfDate;

    @Column(name = "non_gaap_eps", precision = 19, scale = 4, nullable = false)
    private BigDecimal nonGaapEps;

    @Column(name = "source_label")
    private String sourceLabel;

    @Column(name = "source_url")
    private String sourceUrl;

    @Column(name = "captured_at", nullable = false)
    private OffsetDateTime capturedAt;

    @PrePersist
    @PreUpdate
    void touchCapturedAt() {
        capturedAt = OffsetDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public LocalDate getAsOfDate() {
        return asOfDate;
    }

    public void setAsOfDate(LocalDate asOfDate) {
        this.asOfDate = asOfDate;
    }

    public BigDecimal getNonGaapEps() {
        return nonGaapEps;
    }

    public void setNonGaapEps(BigDecimal nonGaapEps) {
        this.nonGaapEps = nonGaapEps;
    }

    public String getSourceLabel() {
        return sourceLabel;
    }

    public void setSourceLabel(String sourceLabel) {
        this.sourceLabel = sourceLabel;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public void setSourceUrl(String sourceUrl) {
        this.sourceUrl = sourceUrl;
    }

    public OffsetDateTime getCapturedAt() {
        return capturedAt;
    }

    public void setCapturedAt(OffsetDateTime capturedAt) {
        this.capturedAt = capturedAt;
    }
}
