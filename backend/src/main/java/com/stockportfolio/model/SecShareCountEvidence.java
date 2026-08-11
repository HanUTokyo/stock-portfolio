package com.stockportfolio.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Filing-scoped, selected share-count bridge evidence. Never represents a manual review overlay. */
@Entity
@Table(name = "sec_share_count_evidence")
public class SecShareCountEvidence {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, length = 20) private String symbol;
    @Column(name = "period_start", nullable = false) private LocalDate periodStart;
    @Column(name = "period_end", nullable = false) private LocalDate periodEnd;
    @Column(name = "component_type", nullable = false, length = 48) private String componentType;
    @Column(precision = 30, scale = 8) private BigDecimal amount;
    @Column(name = "coverage_status", nullable = false, length = 24) private String coverageStatus;
    @Column(name = "statement_role", columnDefinition = "text") private String statementRole;
    @Column(name = "source_concepts", columnDefinition = "text") private String sourceConcepts;
    @Column(name = "accession_number", length = 32) private String accessionNumber;
    @Column(length = 16) private String form;
    @Column(name = "filed_date") private LocalDate filedDate;
    @Column(name = "split_adjustment_factor", precision = 24, scale = 12) private BigDecimal splitAdjustmentFactor;
    @Column(name = "alignment_status", columnDefinition = "text") private String alignmentStatus;

    public String getSymbol() { return symbol; } public void setSymbol(String v) { symbol = v; }
    public LocalDate getPeriodStart() { return periodStart; } public void setPeriodStart(LocalDate v) { periodStart = v; }
    public LocalDate getPeriodEnd() { return periodEnd; } public void setPeriodEnd(LocalDate v) { periodEnd = v; }
    public String getComponentType() { return componentType; } public void setComponentType(String v) { componentType = v; }
    public BigDecimal getAmount() { return amount; } public void setAmount(BigDecimal v) { amount = v; }
    public String getCoverageStatus() { return coverageStatus; } public void setCoverageStatus(String v) { coverageStatus = v; }
    public String getStatementRole() { return statementRole; } public void setStatementRole(String v) { statementRole = v; }
    public String getSourceConcepts() { return sourceConcepts; } public void setSourceConcepts(String v) { sourceConcepts = v; }
    public String getAccessionNumber() { return accessionNumber; } public void setAccessionNumber(String v) { accessionNumber = v; }
    public String getForm() { return form; } public void setForm(String v) { form = v; }
    public LocalDate getFiledDate() { return filedDate; } public void setFiledDate(LocalDate v) { filedDate = v; }
    public BigDecimal getSplitAdjustmentFactor() { return splitAdjustmentFactor; } public void setSplitAdjustmentFactor(BigDecimal v) { splitAdjustmentFactor = v; }
    public String getAlignmentStatus() { return alignmentStatus; } public void setAlignmentStatus(String v) { alignmentStatus = v; }
}
