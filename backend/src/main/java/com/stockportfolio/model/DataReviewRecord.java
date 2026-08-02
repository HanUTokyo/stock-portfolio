package com.stockportfolio.model;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

@Entity
@Table(
        name = "data_review_records",
        uniqueConstraints = @UniqueConstraint(columnNames = {"source_name", "record_id"})
)
public class DataReviewRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "source_name", nullable = false, length = 80)
    private String sourceName;

    @Column(name = "record_id", nullable = false, length = 80)
    private String recordId;

    @Column(name = "review_status", nullable = false, length = 24)
    private String reviewStatus = "pending";

    @Lob
    @Column(name = "reviewed_value_json", columnDefinition = "text")
    private String reviewedValueJson;

    @Column(columnDefinition = "text")
    private String note;

    @Column(name = "reason_code", length = 64)
    private String reasonCode;

    @Column(length = 80)
    private String reviewer;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }

    public String getSourceName() {
        return sourceName;
    }

    public void setSourceName(String sourceName) {
        this.sourceName = sourceName;
    }

    public String getRecordId() {
        return recordId;
    }

    public void setRecordId(String recordId) {
        this.recordId = recordId;
    }

    public String getReviewStatus() {
        return reviewStatus;
    }

    public void setReviewStatus(String reviewStatus) {
        this.reviewStatus = reviewStatus;
    }

    public String getReviewedValueJson() {
        return reviewedValueJson;
    }

    public void setReviewedValueJson(String reviewedValueJson) {
        this.reviewedValueJson = reviewedValueJson;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public String getReviewer() {
        return reviewer;
    }

    public void setReviewer(String reviewer) {
        this.reviewer = reviewer;
    }

    public String getReasonCode() { return reasonCode; }
    public void setReasonCode(String reasonCode) { this.reasonCode = reasonCode; }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
