package com.stockportfolio.model;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "valuation_notes", uniqueConstraints = @UniqueConstraint(columnNames = "symbol"))
public class ValuationNote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(nullable = false, length = 20)
    private String symbol;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String note;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    @PreUpdate
    void touchUpdatedAt() {
        updatedAt = OffsetDateTime.now();
    }

    public Long getId() { return id; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
