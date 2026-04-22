package com.stockportfolio.model;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(
        name = "earnings_history",
        uniqueConstraints = @UniqueConstraint(columnNames = {"symbol", "as_of_date"})
)
public class EarningsHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String symbol;

    @Column(name = "as_of_date", nullable = false)
    private LocalDate asOfDate;

    @Column(name = "basic_eps", nullable = false, precision = 19, scale = 4)
    private BigDecimal basicEps;

    @Column(name = "currency_code", length = 10)
    private String currencyCode;

    @Column(name = "source_eps", precision = 19, scale = 4)
    private BigDecimal sourceEps;

    @Column(name = "eps_in_quote", precision = 19, scale = 4)
    private BigDecimal epsInQuote;

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

    public void setId(Long id) {
        this.id = id;
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

    public BigDecimal getBasicEps() {
        return basicEps;
    }

    public void setBasicEps(BigDecimal basicEps) {
        this.basicEps = basicEps;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }

    public void setCurrencyCode(String currencyCode) {
        this.currencyCode = currencyCode;
    }

    public OffsetDateTime getCapturedAt() {
        return capturedAt;
    }

    public void setCapturedAt(OffsetDateTime capturedAt) {
        this.capturedAt = capturedAt;
    }

    public BigDecimal getSourceEps() {
        return sourceEps;
    }

    public void setSourceEps(BigDecimal sourceEps) {
        this.sourceEps = sourceEps;
    }

    public BigDecimal getEpsInQuote() {
        return epsInQuote;
    }

    public void setEpsInQuote(BigDecimal epsInQuote) {
        this.epsInQuote = epsInQuote;
    }
}
