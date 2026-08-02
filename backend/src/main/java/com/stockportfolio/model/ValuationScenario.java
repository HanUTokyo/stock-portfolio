package com.stockportfolio.model;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "valuation_scenario", uniqueConstraints =
        @UniqueConstraint(columnNames = {"symbol", "scenario_type"}))
public class ValuationScenario {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 20)
    private String symbol;
    @Column(name = "scenario_type", nullable = false, length = 8)
    private String scenarioType;
    @Column(name = "model_mode", nullable = false, length = 16)
    private String modelMode = "AUTO";
    @Lob @Column(name = "assumptions_json", nullable = false, columnDefinition = "text")
    private String assumptionsJson;
    @Column(name = "engine_version", nullable = false, length = 40)
    private String engineVersion;
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist @PreUpdate
    void touch() { updatedAt = OffsetDateTime.now(); }

    public Long getId() { return id; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public String getScenarioType() { return scenarioType; }
    public void setScenarioType(String scenarioType) { this.scenarioType = scenarioType; }
    public String getModelMode() { return modelMode; }
    public void setModelMode(String modelMode) { this.modelMode = modelMode; }
    public String getAssumptionsJson() { return assumptionsJson; }
    public void setAssumptionsJson(String assumptionsJson) { this.assumptionsJson = assumptionsJson; }
    public String getEngineVersion() { return engineVersion; }
    public void setEngineVersion(String engineVersion) { this.engineVersion = engineVersion; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
