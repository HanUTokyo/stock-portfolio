package com.stockportfolio.model;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(
        name = "earnings_estimates",
        uniqueConstraints = @UniqueConstraint(columnNames = {"symbol", "period_type", "period_code"})
)
public class EarningsEstimate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String symbol;

    @Column(name = "period_type", nullable = false, length = 20)
    private String periodType;

    @Column(name = "period_code", nullable = false, length = 10)
    private String periodCode;

    @Column(name = "period_end_date", nullable = false)
    private LocalDate periodEndDate;

    @Column(name = "eps_avg", precision = 19, scale = 4)
    private BigDecimal epsAvg;

    @Column(name = "eps_low", precision = 19, scale = 4)
    private BigDecimal epsLow;

    @Column(name = "eps_high", precision = 19, scale = 4)
    private BigDecimal epsHigh;

    @Column(name = "number_of_analysts")
    private Integer numberOfAnalysts;

    @Column(name = "revenue_avg", precision = 24, scale = 4)
    private BigDecimal revenueAvg;

    @Column(name = "revenue_low", precision = 24, scale = 4)
    private BigDecimal revenueLow;

    @Column(name = "revenue_high", precision = 24, scale = 4)
    private BigDecimal revenueHigh;

    @Column(name = "revenue_analysts")
    private Integer revenueAnalysts;

    @Column(name = "fetched_at", nullable = false)
    private OffsetDateTime fetchedAt;

    @PrePersist
    @PreUpdate
    void touchFetchedAt() {
        fetchedAt = OffsetDateTime.now();
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

    public String getPeriodType() {
        return periodType;
    }

    public void setPeriodType(String periodType) {
        this.periodType = periodType;
    }

    public String getPeriodCode() {
        return periodCode;
    }

    public void setPeriodCode(String periodCode) {
        this.periodCode = periodCode;
    }

    public LocalDate getPeriodEndDate() {
        return periodEndDate;
    }

    public void setPeriodEndDate(LocalDate periodEndDate) {
        this.periodEndDate = periodEndDate;
    }

    public BigDecimal getEpsAvg() {
        return epsAvg;
    }

    public void setEpsAvg(BigDecimal epsAvg) {
        this.epsAvg = epsAvg;
    }

    public BigDecimal getEpsLow() {
        return epsLow;
    }

    public void setEpsLow(BigDecimal epsLow) {
        this.epsLow = epsLow;
    }

    public BigDecimal getEpsHigh() {
        return epsHigh;
    }

    public void setEpsHigh(BigDecimal epsHigh) {
        this.epsHigh = epsHigh;
    }

    public Integer getNumberOfAnalysts() {
        return numberOfAnalysts;
    }

    public void setNumberOfAnalysts(Integer numberOfAnalysts) {
        this.numberOfAnalysts = numberOfAnalysts;
    }

    public BigDecimal getRevenueAvg() { return revenueAvg; }
    public void setRevenueAvg(BigDecimal revenueAvg) { this.revenueAvg = revenueAvg; }
    public BigDecimal getRevenueLow() { return revenueLow; }
    public void setRevenueLow(BigDecimal revenueLow) { this.revenueLow = revenueLow; }
    public BigDecimal getRevenueHigh() { return revenueHigh; }
    public void setRevenueHigh(BigDecimal revenueHigh) { this.revenueHigh = revenueHigh; }
    public Integer getRevenueAnalysts() { return revenueAnalysts; }
    public void setRevenueAnalysts(Integer revenueAnalysts) { this.revenueAnalysts = revenueAnalysts; }

    public OffsetDateTime getFetchedAt() {
        return fetchedAt;
    }

    public void setFetchedAt(OffsetDateTime fetchedAt) {
        this.fetchedAt = fetchedAt;
    }
}
