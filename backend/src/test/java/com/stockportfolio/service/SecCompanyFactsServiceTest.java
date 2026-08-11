package com.stockportfolio.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SecCompanyFactsServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void readYtdConceptDerivesFourthQuarterEpsFromAnnualAndQ3Ytd() throws Exception {
        JsonNode usGaap = objectMapper.valueToTree(Map.of(
                "EarningsPerShareBasic", Map.of(
                        "units", Map.of(
                                "USD/shares", List.of(
                                        fact("Q1", "10-Q", "2024-04-30", "2023-12-31", "2024-03-30", "0.08"),
                                        fact("Q2", "10-Q", "2024-07-30", "2023-12-31", "2024-06-29", "0.24"),
                                        fact("Q3", "10-Q", "2024-10-30", "2023-12-31", "2024-09-28", "0.72"),
                                        fact("FY", "10-K", "2025-02-05", "2023-12-31", "2024-12-28", "1.01")
                                )
                        )
                )
        ));

        List<?> points = invokeReadYtdConcept(usGaap);

        assertEquals(4, points.size());
        assertPoint(points.get(0), LocalDate.of(2024, 3, 30), "0.08");
        assertPoint(points.get(1), LocalDate.of(2024, 6, 29), "0.16");
        assertPoint(points.get(2), LocalDate.of(2024, 9, 28), "0.48");
        assertPoint(points.get(3), LocalDate.of(2024, 12, 28), "0.29");
    }

    @Test
    void readYtdConceptMatchesRestatedAnnualFactsByFiscalStartDate() throws Exception {
        JsonNode usGaap = objectMapper.valueToTree(Map.of(
                "EarningsPerShareBasic", Map.of(
                        "units", Map.of(
                                "USD/shares", List.of(
                                        fact("Q3", "10-Q", "2024-10-30", "2023-12-31", "2024-09-28", "0.72"),
                                        fact("FY", "10-K", "2025-02-05", "2023-12-31", "2024-12-28", "1.01"),
                                        fact("FY", "10-K", "2026-02-04", "2023-12-31", "2024-12-28", "1.01")
                                )
                        )
                )
        ));

        List<?> points = invokeReadYtdConcept(usGaap);

        assertEquals(1, points.size());
        assertPoint(points.getFirst(), LocalDate.of(2024, 12, 28), "0.29");
    }

    @Test
    void readStandaloneDurationConceptReadsQuarterWithoutFrame() throws Exception {
        JsonNode usGaap = objectMapper.valueToTree(Map.of(
                "EarningsPerShareBasic", Map.of(
                        "units", Map.of(
                                "USD/shares", List.of(
                                        fact("Q2", "10-Q", "2023-08-02", "2023-04-02", "2023-07-01", "0.02")
                                )
                        )
                )
        ));

        List<?> points = invokeReadStandaloneDurationConcept(usGaap, 2023);

        assertEquals(1, points.size());
        assertPoint(points.getFirst(), LocalDate.of(2023, 7, 1), "0.02");
    }

    @Test
    void readStandaloneDurationConceptUsesFirstPublishedQuarterForPointInTimeHistory() throws Exception {
        JsonNode usGaap = objectMapper.valueToTree(Map.of(
                "EarningsPerShareBasic", Map.of(
                        "units", Map.of(
                                "USD/shares", List.of(
                                        fact("Q2", "10-Q", "2017-08-03", "2017-04-02", "2017-07-01", "-0.02"),
                                        fact("Q2", "10-Q", "2018-08-02", "2017-04-02", "2017-07-01", "-0.04")
                                )
                        )
                )
        ));

        List<?> points = invokeReadStandaloneDurationConcept(usGaap, 2017);

        assertEquals(1, points.size());
        assertPoint(points.getFirst(), LocalDate.of(2017, 7, 1), "-0.02");
    }

    @Test
    void readYtdConceptInfersFirstQuarterFromSecondQuarterYtdAndStandaloneQuarter() throws Exception {
        JsonNode usGaap = objectMapper.valueToTree(Map.of(
                "EarningsPerShareBasic", Map.of(
                        "units", Map.of(
                                "USD/shares", List.of(
                                        fact("Q2", "10-Q", "2010-08-04", "2008-12-28", "2009-06-27", "-1.15"),
                                        fact("Q2", "10-Q", "2010-08-04", "2009-03-29", "2009-06-27", "-0.49")
                                )
                        )
                )
        ));

        List<?> points = invokeReadYtdConcept(usGaap, 2009);

        assertEquals(2, points.size());
        assertPoint(points.get(0), LocalDate.of(2009, 3, 28), "-0.66");
        assertPoint(points.get(1), LocalDate.of(2009, 6, 27), "-0.49");
    }

    @Test
    void readYtdConceptDoesNotCombineNonConsecutiveMissingQuarterIntoNextQuarter() throws Exception {
        JsonNode usGaap = objectMapper.valueToTree(Map.of(
                "EarningsPerShareBasic", Map.of(
                        "units", Map.of(
                                "USD/shares", List.of(
                                        fact("Q1", "10-Q", "2024-04-30", "2023-12-31", "2024-03-30", "0.08"),
                                        fact("Q3", "10-Q", "2024-10-30", "2023-12-31", "2024-09-28", "0.72"),
                                        fact("FY", "10-K", "2025-02-05", "2023-12-31", "2024-12-28", "1.01")
                                )
                        )
                )
        ));

        List<?> points = invokeReadYtdConcept(usGaap);

        assertEquals(2, points.size());
        assertPoint(points.get(0), LocalDate.of(2024, 3, 30), "0.08");
        assertPoint(points.get(1), LocalDate.of(2024, 12, 28), "0.29");
    }

    private List<?> invokeReadYtdConcept(JsonNode usGaap) throws Exception {
        return invokeReadYtdConcept(usGaap, 2024);
    }

    private List<?> invokeReadYtdConcept(JsonNode usGaap, int year) throws Exception {
        SecCompanyFactsService service = new SecCompanyFactsService(objectMapper, "stock-portfolio test@example.com");
        Method method = SecCompanyFactsService.class.getDeclaredMethod(
                "readYtdConcept",
                JsonNode.class,
                String.class,
                String.class,
                LocalDate.class,
                LocalDate.class
        );
        method.setAccessible(true);
        return (List<?>) method.invoke(
                service,
                usGaap,
                "EarningsPerShareBasic",
                "USD/shares",
                LocalDate.of(year, 1, 1),
                LocalDate.of(year, 12, 31)
        );
    }

    private List<?> invokeReadStandaloneDurationConcept(JsonNode usGaap, int year) throws Exception {
        SecCompanyFactsService service = new SecCompanyFactsService(objectMapper, "stock-portfolio test@example.com");
        Method method = SecCompanyFactsService.class.getDeclaredMethod(
                "readStandaloneDurationConcept",
                JsonNode.class,
                String.class,
                String.class,
                LocalDate.class,
                LocalDate.class
        );
        method.setAccessible(true);
        return (List<?>) method.invoke(
                service,
                usGaap,
                "EarningsPerShareBasic",
                "USD/shares",
                LocalDate.of(year, 1, 1),
                LocalDate.of(year, 12, 31)
        );
    }

    private void assertPoint(Object point, LocalDate asOfDate, String value) throws Exception {
        Method asOfDateMethod = point.getClass().getDeclaredMethod("asOfDate");
        Method valueMethod = point.getClass().getDeclaredMethod("value");
        asOfDateMethod.setAccessible(true);
        valueMethod.setAccessible(true);
        assertEquals(asOfDate, asOfDateMethod.invoke(point));
        assertEquals(new BigDecimal(value), valueMethod.invoke(point));
    }

    private Map<String, Object> fact(String fp,
                                     String form,
                                     String filed,
                                     String start,
                                     String end,
                                     String val) {
        return Map.of(
                "fp", fp,
                "form", form,
                "filed", filed,
                "start", start,
                "end", end,
                "val", new BigDecimal(val)
        );
    }
}
