package com.stockportfolio.model;
import jakarta.persistence.*; import java.math.BigDecimal; import java.time.LocalDate;
@Entity @Table(name="sec_debt_evidence") public class SecDebtEvidence {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @Column(nullable=false) private String symbol; @Column(name="period_end",nullable=false) private LocalDate periodEnd;
 @Column(name="metric_type",nullable=false) private String metricType; @Column(name="component_type",nullable=false) private String componentType;
 @Column(precision=30,scale=8) private BigDecimal amount; @Column(name="coverage_status",nullable=false) private String coverageStatus;
 @Column(name="selected_route",nullable=false) private String selectedRoute; @Column(name="source_concepts",nullable=false,columnDefinition="text") private String sourceConcepts;
 @Column(name="accession_numbers") private String accessionNumbers; @Column(name="form") private String form; @Column(name="filed_date") private LocalDate filedDate;
 @Column(name="source_start") private LocalDate sourceStart; @Column(name="source_end") private LocalDate sourceEnd; @Column(name="quarterization_method") private String quarterizationMethod;
 public String getMetricType(){return metricType;} public String getComponentType(){return componentType;} public BigDecimal getAmount(){return amount;} public String getCoverageStatus(){return coverageStatus;} public String getSelectedRoute(){return selectedRoute;} public String getSourceConcepts(){return sourceConcepts;} public String getAccessionNumbers(){return accessionNumbers;}
 public void setSymbol(String v){symbol=v;} public void setPeriodEnd(LocalDate v){periodEnd=v;} public void setMetricType(String v){metricType=v;} public void setComponentType(String v){componentType=v;} public void setAmount(BigDecimal v){amount=v;} public void setCoverageStatus(String v){coverageStatus=v;} public void setSelectedRoute(String v){selectedRoute=v;} public void setSourceConcepts(String v){sourceConcepts=v;} public void setAccessionNumbers(String v){accessionNumbers=v;} public void setForm(String v){form=v;} public void setFiledDate(LocalDate v){filedDate=v;}
 public LocalDate getPeriodEnd(){return periodEnd;} public LocalDate getFiledDate(){return filedDate;} public LocalDate getSourceStart(){return sourceStart;} public LocalDate getSourceEnd(){return sourceEnd;} public String getQuarterizationMethod(){return quarterizationMethod;}
 public void setSourceStart(LocalDate v){sourceStart=v;} public void setSourceEnd(LocalDate v){sourceEnd=v;} public void setQuarterizationMethod(String v){quarterizationMethod=v;}
}
