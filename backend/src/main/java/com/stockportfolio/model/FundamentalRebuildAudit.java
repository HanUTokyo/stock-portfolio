package com.stockportfolio.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "fundamental_rebuild_audit")
public class FundamentalRebuildAudit {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false, length = 36)
    private String runId;

    @Column(nullable = false, length = 20)
    private String symbol;

    @Column(name = "as_of_date", nullable = false)
    private LocalDate asOfDate;

    @Column(name = "fiscal_period_key", nullable = false, length = 24)
    private String fiscalPeriodKey;

    @Column(name = "field_name", nullable = false, length = 64)
    private String fieldName;

    @Column(name = "before_value", precision = 24, scale = 4)
    private BigDecimal beforeValue;

    @Column(name = "after_value", precision = 24, scale = 4)
    private BigDecimal afterValue;

    @Column(nullable = false, length = 80)
    private String trigger;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        createdAt = OffsetDateTime.now();
    }

    public void setRunId(String runId) { this.runId = runId; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public void setAsOfDate(LocalDate asOfDate) { this.asOfDate = asOfDate; }
    public void setFiscalPeriodKey(String fiscalPeriodKey) { this.fiscalPeriodKey = fiscalPeriodKey; }
    public void setFieldName(String fieldName) { this.fieldName = fieldName; }
    public void setBeforeValue(BigDecimal beforeValue) { this.beforeValue = beforeValue; }
    public void setAfterValue(BigDecimal afterValue) { this.afterValue = afterValue; }
    public void setTrigger(String trigger) { this.trigger = trigger; }
}
