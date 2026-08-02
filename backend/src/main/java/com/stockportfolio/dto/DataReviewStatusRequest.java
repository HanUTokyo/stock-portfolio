package com.stockportfolio.dto;

public record DataReviewStatusRequest(String note, String reasonCode, String expectedRevision) {
    public DataReviewStatusRequest(String note) {
        this(note, null, null);
    }
}
