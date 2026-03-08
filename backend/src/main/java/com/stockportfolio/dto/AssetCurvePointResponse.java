package com.stockportfolio.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record AssetCurvePointResponse(
        OffsetDateTime timestamp,
        BigDecimal totalAssets
) {
}

