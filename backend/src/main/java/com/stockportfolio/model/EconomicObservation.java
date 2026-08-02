package com.stockportfolio.model;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "economic_observation", uniqueConstraints =
        @UniqueConstraint(columnNames = {"series_id", "observation_date"}))
public class EconomicObservation {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "series_id", nullable = false, length = 40)
    private String seriesId;
    @Column(name = "observation_date", nullable = false)
    private LocalDate observationDate;
    @Column(name = "observation_value", nullable = false, precision = 24, scale = 8)
    private BigDecimal value;
    @Column(name = "source_code", nullable = false, length = 32)
    private String sourceCode;
    @Column(name = "source_name", nullable = false, length = 120)
    private String sourceName;
    @Column(name = "retrieved_at", nullable = false)
    private OffsetDateTime retrievedAt;

    @PrePersist @PreUpdate
    void touch() { retrievedAt = OffsetDateTime.now(); }

    public Long getId() { return id; }
    public String getSeriesId() { return seriesId; }
    public void setSeriesId(String seriesId) { this.seriesId = seriesId; }
    public LocalDate getObservationDate() { return observationDate; }
    public void setObservationDate(LocalDate observationDate) { this.observationDate = observationDate; }
    public BigDecimal getValue() { return value; }
    public void setValue(BigDecimal value) { this.value = value; }
    public String getSourceCode() { return sourceCode; }
    public void setSourceCode(String sourceCode) { this.sourceCode = sourceCode; }
    public String getSourceName() { return sourceName; }
    public void setSourceName(String sourceName) { this.sourceName = sourceName; }
    public OffsetDateTime getRetrievedAt() { return retrievedAt; }
}
