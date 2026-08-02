package com.stockportfolio.model;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "stock_split", uniqueConstraints = @UniqueConstraint(columnNames = {"symbol", "split_date"}))
public class StockSplit {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 20)
    private String symbol;
    @Column(name = "split_date", nullable = false)
    private LocalDate splitDate;
    @Column(nullable = false, precision = 19, scale = 8)
    private BigDecimal numerator;
    @Column(nullable = false, precision = 19, scale = 8)
    private BigDecimal denominator;
    @Column(name = "source_code", nullable = false, length = 24)
    private String sourceCode;
    @Column(name = "source_date", nullable = false)
    private LocalDate sourceDate;
    @Column(name = "retrieved_at", nullable = false)
    private OffsetDateTime retrievedAt;

    @PrePersist @PreUpdate
    void touch() { retrievedAt = OffsetDateTime.now(); }

    public Long getId() { return id; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public LocalDate getSplitDate() { return splitDate; }
    public void setSplitDate(LocalDate splitDate) { this.splitDate = splitDate; }
    public BigDecimal getNumerator() { return numerator; }
    public void setNumerator(BigDecimal numerator) { this.numerator = numerator; }
    public BigDecimal getDenominator() { return denominator; }
    public void setDenominator(BigDecimal denominator) { this.denominator = denominator; }
    public String getSourceCode() { return sourceCode; }
    public void setSourceCode(String sourceCode) { this.sourceCode = sourceCode; }
    public LocalDate getSourceDate() { return sourceDate; }
    public void setSourceDate(LocalDate sourceDate) { this.sourceDate = sourceDate; }
    public OffsetDateTime getRetrievedAt() { return retrievedAt; }
}
