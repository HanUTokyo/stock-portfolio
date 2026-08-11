package com.stockportfolio.model;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

/** Separate from legacy valuation_scenario so a 3.0 snapshot cannot alter legacy reads. */
@Entity
@Table(name = "forecast_scenario_snapshot", uniqueConstraints = @UniqueConstraint(columnNames = "symbol"))
public class ForecastScenarioSnapshot {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, length = 20) private String symbol;
    @Column(name = "archetype", nullable = false, length = 48) private String archetype;
    @Column(name = "template_version", nullable = false, length = 48) private String templateVersion;
    @Column(name = "snapshot_json", nullable = false, columnDefinition = "text") private String snapshotJson;
    @Column(name = "updated_at", nullable = false) private OffsetDateTime updatedAt;
    @PrePersist @PreUpdate void touch() { updatedAt = OffsetDateTime.now(); }
    public Long getId() { return id; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public String getArchetype() { return archetype; }
    public void setArchetype(String archetype) { this.archetype = archetype; }
    public String getTemplateVersion() { return templateVersion; }
    public void setTemplateVersion(String templateVersion) { this.templateVersion = templateVersion; }
    public String getSnapshotJson() { return snapshotJson; }
    public void setSnapshotJson(String snapshotJson) { this.snapshotJson = snapshotJson; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
