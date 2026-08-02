package com.stockportfolio.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.stockportfolio.dto.AssetCurvePointResponse;
import com.stockportfolio.dto.DividendResponse;
import com.stockportfolio.dto.PriceHistoryPointResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class ApiContractSerializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void serializesLocalDateAsIsoDateForSwiftLocalDateDecoder() throws Exception {
        String json = objectMapper.writeValueAsString(new PriceHistoryPointResponse(
                LocalDate.parse("2026-07-08"),
                new BigDecimal("123.45")
        ));

        assertThat(json).contains("\"tradeDate\":\"2026-07-08\"");
        assertThat(json).contains("\"closePrice\":123.45");
    }

    @Test
    void serializesOffsetDateTimeAsIsoDateTimeForSwiftDateDecoder() throws Exception {
        String json = objectMapper.writeValueAsString(new AssetCurvePointResponse(
                OffsetDateTime.parse("2026-07-08T12:34:56Z"),
                new BigDecimal("1000.25"),
                new BigDecimal("800.00"),
                new BigDecimal("900.00"),
                new BigDecimal("100.25")
        ));

        assertThat(json).contains("\"timestamp\":\"2026-07-08T12:34:56Z\"");
        assertThat(json).contains("\"totalAssets\":1000.25");
    }

    @Test
    void dividendContractUsesDateAndDecimalShapes() throws Exception {
        String json = objectMapper.writeValueAsString(new DividendResponse(
                42L,
                "AAPL",
                new BigDecimal("1.23"),
                LocalDate.parse("2026-07-08"),
                OffsetDateTime.parse("2026-07-08T00:00:00Z"),
                0L
        ));

        assertThat(json).contains("\"paidDate\":\"2026-07-08\"");
        assertThat(json).contains("\"amount\":1.23");
        assertThat(json).contains("\"createdAt\":\"2026-07-08T00:00:00Z\"");
    }
}
