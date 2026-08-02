package com.stockportfolio.dto;

import java.util.List;

public record DataReviewSourceResponse(
        String name,
        String label,
        String rawTable,
        List<String> displayFields,
        List<String> editableFields
) {
}
