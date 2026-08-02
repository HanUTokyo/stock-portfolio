package com.stockportfolio.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockportfolio.model.DataReviewRecord;
import com.stockportfolio.repository.DataReviewRecordRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Resolves review records at read time. Source tables remain untouched; only a
 * corrected review record can contribute an override to application responses.
 */
@Service
public class ReviewedDataResolver {

    private static final String CORRECTED = "corrected";
    private static final String REJECTED = "rejected";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final DataReviewRecordRepository recordRepository;
    private final ObjectMapper objectMapper;

    public ReviewedDataResolver(DataReviewRecordRepository recordRepository, ObjectMapper objectMapper) {
        this.recordRepository = recordRepository;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> correctedValues(String sourceName, Long recordId) {
        if (recordId == null) {
            return Map.of();
        }
        return recordRepository.findBySourceNameAndRecordId(sourceName, String.valueOf(recordId))
                .filter(record -> CORRECTED.equals(record.getReviewStatus()))
                .map(this::readValues)
                .orElseGet(Map::of);
    }

    public String reviewStatus(String sourceName, Long recordId) {
        if (recordId == null) {
            return "pending";
        }
        return recordRepository.findBySourceNameAndRecordId(sourceName, String.valueOf(recordId))
                .map(DataReviewRecord::getReviewStatus)
                .orElse("pending");
    }

    public Map<String, Map<String, Object>> correctedValues(String sourceName, Collection<Long> recordIds) {
        List<String> ids = stringifyIds(recordIds);
        if (ids.isEmpty()) {
            return Map.of();
        }
        return recordRepository.findBySourceNameAndRecordIdInAndReviewStatus(sourceName, ids, CORRECTED).stream()
                .collect(Collectors.toMap(
                        DataReviewRecord::getRecordId,
                        this::readValues,
                        (left, right) -> right,
                        LinkedHashMap::new
                ));
    }

    public Set<String> rejectedRecordIds(String sourceName, Collection<Long> recordIds) {
        List<String> ids = stringifyIds(recordIds);
        if (ids.isEmpty()) {
            return Set.of();
        }
        return recordRepository.findBySourceNameAndRecordIdInAndReviewStatus(sourceName, ids, REJECTED).stream()
                .map(DataReviewRecord::getRecordId)
                .collect(Collectors.toSet());
    }

    public BigDecimal decimal(Map<String, Object> overrides, String fieldName, BigDecimal fallback) {
        Object value = overrides.get(fieldName);
        if (value == null) {
            return fallback;
        }
        try {
            return value instanceof BigDecimal decimal ? decimal : new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    public String text(Map<String, Object> overrides, String fieldName, String fallback) {
        Object value = overrides.get(fieldName);
        return value == null ? fallback : String.valueOf(value);
    }

    public OffsetDateTime dateTime(Map<String, Object> overrides, String fieldName, OffsetDateTime fallback) {
        Object value = overrides.get(fieldName);
        if (value == null) {
            return fallback;
        }
        try {
            return value instanceof OffsetDateTime dateTime ? dateTime : OffsetDateTime.parse(String.valueOf(value));
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private Map<String, Object> readValues(DataReviewRecord record) {
        String json = record.getReviewedValueJson();
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return new LinkedHashMap<>(objectMapper.readValue(json, MAP_TYPE));
        } catch (JsonProcessingException ignored) {
            return Map.of();
        }
    }

    private List<String> stringifyIds(Collection<Long> recordIds) {
        if (recordIds == null) {
            return List.of();
        }
        return recordIds.stream()
                .filter(id -> id != null)
                .map(String::valueOf)
                .toList();
    }
}
