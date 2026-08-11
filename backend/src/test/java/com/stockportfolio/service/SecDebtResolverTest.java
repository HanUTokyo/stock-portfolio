package com.stockportfolio.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SecDebtResolverTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void aaplReportedPeriodFcfeReconcilesExactlyToTtmAcceptanceValue() {
        BigDecimal fy25Fcfe = bd("90284000000");
        BigDecimal nineMonths25Fcfe = bd("67015000000");
        BigDecimal nineMonths26Fcfe = bd("96140000000");

        assertThat(fy25Fcfe).isEqualByComparingTo("90284000000");
        assertThat(nineMonths25Fcfe).isEqualByComparingTo("67015000000");
        assertThat(nineMonths26Fcfe).isEqualByComparingTo("96140000000");
        assertThat(fy25Fcfe.subtract(nineMonths25Fcfe).add(nineMonths26Fcfe))
                .isEqualByComparingTo("119409000000");
    }

    @Test
    void resolvesAaplCommercialPaperAndRebuildsCompleteTtmNetBorrowing() throws Exception {
        JsonNode usGaap = fixture("/sec/aapl-debt-fixture.json");

        SecDebtResolver.Resolution resolution = SecDebtResolver.resolve(
                usGaap, LocalDate.parse("2025-01-01"), LocalDate.parse("2026-12-31"));

        SecDebtResolver.Metric debt = metricAt(resolution.totalDebt(), "2026-06-27");
        assertThat(debt.value()).isEqualByComparingTo("84344000000");
        assertThat(debt.coverage()).isEqualTo(SecDebtResolver.Coverage.COMPLETE);
        assertThat(debt.route()).isEqualTo(SecDebtResolver.Route.SHORT_TERM_PLUS_TERM_COMPONENTS);
        assertThat(debt.concepts()).containsExactlyInAnyOrder(
                "CommercialPaper", "LongTermDebtCurrent", "LongTermDebtNoncurrent");

        List<SecDebtResolver.Metric> latestFour = resolution.netBorrowing().stream()
                .filter(metric -> !metric.asOfDate().isBefore(LocalDate.parse("2025-09-27")))
                .toList();
        assertThat(latestFour).extracting(SecDebtResolver.Metric::value)
                .containsExactly(
                        bd("-3217000000"),
                        bd("-8074000000"),
                        bd("-5751000000"),
                        bd("-232000000")
                );
        assertThat(latestFour).allMatch(metric -> metric.coverage() == SecDebtResolver.Coverage.COMPLETE);
        assertThat(latestFour).allMatch(metric -> metric.concepts()
                .contains("ProceedsFromRepaymentsOfCommercialPaper"));
        SecDebtResolver.Metric firstQuarter = metricAt(resolution.netBorrowing(), "2025-12-27");
        assertThat(firstQuarter.evidence()).anySatisfy(component -> {
            assertThat(component.componentType()).isEqualTo("LONG_TERM");
            assertThat(component.amount()).isEqualByComparingTo("-2164000000");
        });

        BigDecimal ttmNetBorrowing = latestFour.stream().map(SecDebtResolver.Metric::value)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(ttmNetBorrowing).isEqualByComparingTo("-17274000000");
        assertThat(bd("136683000000").add(ttmNetBorrowing)).isEqualByComparingTo("119409000000");
    }

    @Test
    void usesDebtCurrentAsBroadCurrentBucketWithoutDoubleCountingPfeComponents() throws Exception {
        SecDebtResolver.Resolution resolution = SecDebtResolver.resolve(
                fixture("/sec/pfe-debt-overlap-fixture.json"),
                LocalDate.parse("2025-01-01"), LocalDate.parse("2025-12-31"));

        SecDebtResolver.Metric debt = metricAt(resolution.totalDebt(), "2025-03-30");
        assertThat(debt.value()).isEqualByComparingTo("62109000000");
        assertThat(debt.route()).isEqualTo(SecDebtResolver.Route.CURRENT_DEBT_PLUS_NONCURRENT);
        assertThat(debt.accessionNumber()).isEqualTo("pfe-original");
        assertThat(debt.concepts()).containsExactlyInAnyOrder("DebtCurrent", "LongTermDebtNoncurrent");
        assertThat(debt.concepts()).doesNotContain("CommercialPaper", "LongTermDebtCurrent");
    }

    @Test
    void aggregateRoutesWinOverComponentRoutes() throws Exception {
        JsonNode usGaap = objectMapper.readTree("""
                {"DebtLongtermAndShorttermCombinedAmount":{"units":{"USD":[
                  {"end":"2026-03-31","val":100,"accn":"a","fy":2026,"fp":"Q1","form":"10-Q","filed":"2026-05-01"}
                ]}},"DebtCurrent":{"units":{"USD":[
                  {"end":"2026-03-31","val":20,"accn":"a","fy":2026,"fp":"Q1","form":"10-Q","filed":"2026-05-01"}
                ]}},"LongTermDebtNoncurrent":{"units":{"USD":[
                  {"end":"2026-03-31","val":80,"accn":"a","fy":2026,"fp":"Q1","form":"10-Q","filed":"2026-05-01"}
                ]}},"ProceedsFromRepaymentsOfDebt":{"units":{"USD":[
                  {"start":"2026-01-01","end":"2026-03-31","val":25,"accn":"a","fy":2026,"fp":"Q1","form":"10-Q","filed":"2026-05-01"}
                ]}},"ProceedsFromRepaymentsOfCommercialPaper":{"units":{"USD":[
                  {"start":"2026-01-01","end":"2026-03-31","val":-5,"accn":"a","fy":2026,"fp":"Q1","form":"10-Q","filed":"2026-05-01"}
                ]}}}
                """);

        SecDebtResolver.Resolution resolution = SecDebtResolver.resolve(
                usGaap, LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"));

        SecDebtResolver.Metric debt = metricAt(resolution.totalDebt(), "2026-03-31");
        assertThat(debt.value()).isEqualByComparingTo("100");
        assertThat(debt.route()).isEqualTo(SecDebtResolver.Route.COMBINED_DEBT);
        SecDebtResolver.Metric borrowing = metricAt(resolution.netBorrowing(), "2026-03-31");
        assertThat(borrowing.value()).isEqualByComparingTo("25");
        assertThat(borrowing.route()).isEqualTo(SecDebtResolver.Route.AGGREGATE_NET_BORROWING);
    }

    @Test
    void sumsCommercialPaperAndOtherShortTermComponentsOnlyWhenBroadBucketIsAbsent() throws Exception {
        JsonNode usGaap = objectMapper.readTree("""
                {"CommercialPaper":{"units":{"USD":[
                  {"end":"2026-03-31","val":10,"accn":"a","fy":2026,"fp":"Q1","form":"10-Q","filed":"2026-05-01"}
                ]}},"OtherShortTermBorrowings":{"units":{"USD":[
                  {"end":"2026-03-31","val":5,"accn":"a","fy":2026,"fp":"Q1","form":"10-Q","filed":"2026-05-01"}
                ]}},"ProceedsFromRepaymentsOfCommercialPaper":{"units":{"USD":[
                  {"start":"2026-01-01","end":"2026-03-31","val":-3,"accn":"a","fy":2026,"fp":"Q1","form":"10-Q","filed":"2026-05-01"}
                ]}},"ProceedsFromOtherShortTermDebt":{"units":{"USD":[
                  {"start":"2026-01-01","end":"2026-03-31","val":7,"accn":"a","fy":2026,"fp":"Q1","form":"10-Q","filed":"2026-05-01"}
                ]}},"RepaymentsOfOtherShortTermDebt":{"units":{"USD":[
                  {"start":"2026-01-01","end":"2026-03-31","val":2,"accn":"a","fy":2026,"fp":"Q1","form":"10-Q","filed":"2026-05-01"}
                ]}}}
                """);

        SecDebtResolver.Resolution resolution = SecDebtResolver.resolve(
                usGaap, LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"));

        assertThat(metricAt(resolution.totalDebt(), "2026-03-31").value()).isEqualByComparingTo("15");
        SecDebtResolver.Metric borrowing = metricAt(resolution.netBorrowing(), "2026-03-31");
        assertThat(borrowing.value()).isEqualByComparingTo("2");
        assertThat(borrowing.coverage()).isEqualTo(SecDebtResolver.Coverage.COMPLETE);
    }

    @Test
    void marksOneSidedGrossFlowIncompleteInsteadOfAssumingMissingSideIsZero() throws Exception {
        JsonNode usGaap = objectMapper.readTree("""
                {"CommercialPaper":{"units":{"USD":[
                  {"end":"2026-03-31","val":10,"accn":"a","fy":2026,"fp":"Q1","form":"10-Q","filed":"2026-05-01"}
                ]}},"ProceedsFromIssuanceOfCommercialPaper":{"units":{"USD":[
                  {"start":"2026-01-01","end":"2026-03-31","val":7,"accn":"a","fy":2026,"fp":"Q1","form":"10-Q","filed":"2026-05-01"}
                ]}}}
                """);

        SecDebtResolver.Resolution resolution = SecDebtResolver.resolve(
                usGaap, LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"));

        SecDebtResolver.Metric borrowing = metricAt(resolution.netBorrowing(), "2026-03-31");
        assertThat(borrowing.value()).isEqualByComparingTo("7");
        assertThat(borrowing.coverage()).isEqualTo(SecDebtResolver.Coverage.INCOMPLETE);

        JsonNode aggregateOnly = objectMapper.readTree("""
                {"ProceedsFromIssuanceOfDebt":{"units":{"USD":[
                  {"start":"2026-01-01","end":"2026-03-31","val":11,"accn":"a","fy":2026,"fp":"Q1","form":"10-Q","filed":"2026-05-01"}
                ]}}}
                """);
        SecDebtResolver.Metric aggregate = metricAt(SecDebtResolver.resolve(
                        aggregateOnly, LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"))
                .netBorrowing(), "2026-03-31");
        assertThat(aggregate.value()).isEqualByComparingTo("11");
        assertThat(aggregate.route()).isEqualTo(SecDebtResolver.Route.AGGREGATE_GROSS_BORROWING);
        assertThat(aggregate.coverage()).isEqualTo(SecDebtResolver.Coverage.INCOMPLETE);
    }

    @Test
    void usesLaterExplicitZeroYtdIssuanceAsProofForEarlierCoveredQuarters() throws Exception {
        JsonNode usGaap = objectMapper.readTree("""
                {"CommercialPaper":{"units":{"USD":[
                  {"end":"2026-03-31","val":10,"accn":"q1","fy":2026,"fp":"Q1","form":"10-Q","filed":"2026-05-01"},
                  {"end":"2026-06-30","val":10,"accn":"q2","fy":2026,"fp":"Q2","form":"10-Q","filed":"2026-08-01"},
                  {"end":"2026-09-30","val":10,"accn":"q3","fy":2026,"fp":"Q3","form":"10-Q","filed":"2026-11-01"}
                ]}},"LongTermDebtNoncurrent":{"units":{"USD":[
                  {"end":"2026-03-31","val":20,"accn":"q1","fy":2026,"fp":"Q1","form":"10-Q","filed":"2026-05-01"},
                  {"end":"2026-06-30","val":20,"accn":"q2","fy":2026,"fp":"Q2","form":"10-Q","filed":"2026-08-01"},
                  {"end":"2026-09-30","val":20,"accn":"q3","fy":2026,"fp":"Q3","form":"10-Q","filed":"2026-11-01"}
                ]}},"ProceedsFromRepaymentsOfCommercialPaper":{"units":{"USD":[
                  {"start":"2026-01-01","end":"2026-03-31","val":-3,"accn":"q1","fy":2026,"fp":"Q1","form":"10-Q","filed":"2026-05-01"},
                  {"start":"2026-01-01","end":"2026-06-30","val":-5,"accn":"q2","fy":2026,"fp":"Q2","form":"10-Q","filed":"2026-08-01"},
                  {"start":"2026-01-01","end":"2026-09-30","val":-6,"accn":"q3","fy":2026,"fp":"Q3","form":"10-Q","filed":"2026-11-01"}
                ]}},"RepaymentsOfLongTermDebt":{"units":{"USD":[
                  {"start":"2026-01-01","end":"2026-03-31","val":1,"accn":"q1","fy":2026,"fp":"Q1","form":"10-Q","filed":"2026-05-01"},
                  {"start":"2026-01-01","end":"2026-06-30","val":3,"accn":"q2","fy":2026,"fp":"Q2","form":"10-Q","filed":"2026-08-01"},
                  {"start":"2026-01-01","end":"2026-09-30","val":6,"accn":"q3","fy":2026,"fp":"Q3","form":"10-Q","filed":"2026-11-01"}
                ]}},"ProceedsFromIssuanceOfLongTermDebt":{"units":{"USD":[
                  {"start":"2026-01-01","end":"2026-09-30","val":0,"accn":"q3","fy":2026,"fp":"Q3","form":"10-Q","filed":"2026-11-01"}
                ]}}}
                """);

        SecDebtResolver.Resolution resolution = SecDebtResolver.resolve(
                usGaap, LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"));

        SecDebtResolver.Metric q1 = metricAt(resolution.netBorrowing(), "2026-03-31");
        SecDebtResolver.Metric q2 = metricAt(resolution.netBorrowing(), "2026-06-30");
        assertThat(q1.coverage()).isEqualTo(SecDebtResolver.Coverage.COMPLETE);
        assertThat(q2.coverage()).isEqualTo(SecDebtResolver.Coverage.COMPLETE);
        assertThat(q1.value()).isEqualByComparingTo("-4");
        assertThat(q2.value()).isEqualByComparingTo("-4");
        assertThat(q1.evidence()).anySatisfy(component -> assertThat(component.concepts())
                .contains("ProceedsFromIssuanceOfLongTermDebt"));
    }

    private JsonNode fixture(String resource) throws Exception {
        try (InputStream input = SecDebtResolverTest.class.getResourceAsStream(resource)) {
            assertThat(input).as("fixture %s", resource).isNotNull();
            return objectMapper.readTree(input).path("facts").path("us-gaap");
        }
    }

    private SecDebtResolver.Metric metricAt(List<SecDebtResolver.Metric> metrics, String date) {
        LocalDate target = LocalDate.parse(date);
        return metrics.stream().filter(metric -> target.equals(metric.asOfDate())).findFirst().orElseThrow();
    }

    private BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
