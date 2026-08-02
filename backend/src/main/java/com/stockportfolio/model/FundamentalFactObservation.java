package com.stockportfolio.model;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "fundamental_fact_observation")
public class FundamentalFactObservation {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 20) private String symbol;
    @Column(name = "period_end", nullable = false) private LocalDate periodEnd;
    @Column(name = "fiscal_year") private Integer fiscalYear;
    @Column(name = "fiscal_period", length = 8) private String fiscalPeriod;
    @Column(name = "field_name", nullable = false, length = 80) private String fieldName;
    @Column(name = "fact_value", nullable = false, precision = 30, scale = 8) private BigDecimal value;
    @Column(length = 32) private String unit;
    @Column(name = "currency_code", length = 10) private String currencyCode;
    @Column(name = "source_code", nullable = false, length = 24) private String sourceCode;
    @Column(name = "source_date", nullable = false) private LocalDate sourceDate;
    @Column(name = "accession_number", length = 32) private String accessionNumber;
    @Column(length = 16) private String form;
    @Column(name = "captured_at", nullable = false) private OffsetDateTime capturedAt;

    @PrePersist void create() { capturedAt = OffsetDateTime.now(); }
    public Long getId() { return id; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public LocalDate getPeriodEnd() { return periodEnd; }
    public void setPeriodEnd(LocalDate periodEnd) { this.periodEnd = periodEnd; }
    public Integer getFiscalYear() { return fiscalYear; }
    public void setFiscalYear(Integer fiscalYear) { this.fiscalYear = fiscalYear; }
    public String getFiscalPeriod() { return fiscalPeriod; }
    public void setFiscalPeriod(String fiscalPeriod) { this.fiscalPeriod = fiscalPeriod; }
    public String getFieldName() { return fieldName; }
    public void setFieldName(String fieldName) { this.fieldName = fieldName; }
    public BigDecimal getValue() { return value; }
    public void setValue(BigDecimal value) { this.value = value; }
    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }
    public String getCurrencyCode() { return currencyCode; }
    public void setCurrencyCode(String currencyCode) { this.currencyCode = currencyCode; }
    public String getSourceCode() { return sourceCode; }
    public void setSourceCode(String sourceCode) { this.sourceCode = sourceCode; }
    public LocalDate getSourceDate() { return sourceDate; }
    public void setSourceDate(LocalDate sourceDate) { this.sourceDate = sourceDate; }
    public String getAccessionNumber() { return accessionNumber; }
    public void setAccessionNumber(String accessionNumber) { this.accessionNumber = accessionNumber; }
    public String getForm() { return form; }
    public void setForm(String form) { this.form = form; }
    public OffsetDateTime getCapturedAt() { return capturedAt; }
}
