package com.stockportfolio.model;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "overview_notes", uniqueConstraints = @UniqueConstraint(columnNames = "note_type"))
public class OverviewNote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    @Column(nullable = false)
    private Long version;

    @Enumerated(EnumType.STRING)
    @Column(name = "note_type", nullable = false, length = 20)
    private OverviewNoteType noteType;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String note;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    @PreUpdate
    void touchUpdatedAt() {
        updatedAt = OffsetDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }

    public OverviewNoteType getNoteType() {
        return noteType;
    }

    public void setNoteType(OverviewNoteType noteType) {
        this.noteType = noteType;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
