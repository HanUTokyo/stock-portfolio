package com.stockportfolio.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SecFilingXbrlGraphServiceTest {
    @Test
    void selectsOnlyCashFlowPresentationLeavesWithMatchingDurationContext() throws Exception {
        String pre = """
                <link:linkbase xmlns:link='http://www.xbrl.org/2003/linkbase' xmlns:xlink='http://www.w3.org/1999/xlink'>
                  <link:presentationLink xlink:role='role/ConsolidatedCashFlows'>
                    <link:loc xlink:label='root' xlink:href='a.xsd#us-gaap_IncreaseDecreaseInOperatingCapital'/>
                    <link:loc xlink:label='ar' xlink:href='a.xsd#us-gaap_IncreaseDecreaseInAccountsReceivable'/>
                    <link:loc xlink:label='ap' xlink:href='a.xsd#us-gaap_IncreaseDecreaseInAccountsPayable'/>
                    <link:presentationArc xlink:from='root' xlink:to='ar'/><link:presentationArc xlink:from='root' xlink:to='ap'/>
                  </link:presentationLink></link:linkbase>""";
        String cal = """
                <link:linkbase xmlns:link='http://www.xbrl.org/2003/linkbase' xmlns:xlink='http://www.w3.org/1999/xlink'>
                  <link:calculationLink xlink:role='role/ConsolidatedCashFlows'>
                    <link:loc xlink:label='root' xlink:href='a.xsd#us-gaap_IncreaseDecreaseInOperatingCapital'/><link:loc xlink:label='ar' xlink:href='a.xsd#us-gaap_IncreaseDecreaseInAccountsReceivable'/>
                    <link:calculationArc xlink:from='root' xlink:to='ar' weight='-1'/></link:calculationLink></link:linkbase>""";
        String instance = """
                <xbrl xmlns='http://www.xbrl.org/2003/instance' xmlns:us-gaap='http://fasb.org/us-gaap/2025'>
                  <context id='q'><entity/><period><startDate>2025-01-01</startDate><endDate>2025-03-31</endDate></period></context>
                  <us-gaap:IncreaseDecreaseInOperatingCapital contextRef='q' unitRef='USD'>99</us-gaap:IncreaseDecreaseInOperatingCapital>
                  <us-gaap:IncreaseDecreaseInAccountsReceivable contextRef='q' unitRef='USD'>5</us-gaap:IncreaseDecreaseInAccountsReceivable>
                  <us-gaap:IncreaseDecreaseInAccountsPayable contextRef='q' unitRef='USD'>7</us-gaap:IncreaseDecreaseInAccountsPayable>
                </xbrl>""";
        SecFilingXbrlGraphService.FilingGraph graph = new SecFilingXbrlGraphService(new ObjectMapper(), "test").parse("0001", pre, cal, instance);
        assertThat(graph.status()).isEqualTo("AVAILABLE");
        assertThat(graph.nonOverlappingLeafFacts()).extracting(SecFilingXbrlGraphService.Fact::concept)
                .containsExactlyInAnyOrder("IncreaseDecreaseInAccountsReceivable", "IncreaseDecreaseInAccountsPayable");
        assertThat(graph.nonOverlappingLeafFacts()).allMatch(fact -> fact.start().toString().equals("2025-01-01"));
    }

    @Test
    void readsInlineXbrlFactsUsingNameAndContextRef() throws Exception {
        String pre = """
                <link:linkbase xmlns:link='http://www.xbrl.org/2003/linkbase' xmlns:xlink='http://www.w3.org/1999/xlink'><link:presentationLink xlink:role='role/CashFlow'><link:loc xlink:label='ar' xlink:href='a.xsd#us-gaap_IncreaseDecreaseInAccountsReceivable'/></link:presentationLink></link:linkbase>""";
        String inline = """
                <html xmlns:ix='http://www.xbrl.org/2013/inlineXBRL' xmlns:xbrli='http://www.xbrl.org/2003/instance'><ix:resources><xbrli:context id='q'><xbrli:entity/><xbrli:period><xbrli:startDate>2025-01-01</xbrli:startDate><xbrli:endDate>2025-03-31</xbrli:endDate></xbrli:period></xbrli:context></ix:resources><body><ix:nonFraction name='us-gaap:IncreaseDecreaseInAccountsReceivable' contextRef='q' unitRef='USD'>5</ix:nonFraction></body></html>""";
        SecFilingXbrlGraphService.FilingGraph graph = new SecFilingXbrlGraphService(new ObjectMapper(), "test").parse("0002", pre, null, inline);
        assertThat(graph.nonOverlappingLeafFacts()).singleElement().satisfies(fact -> {
            assertThat(fact.concept()).isEqualTo("IncreaseDecreaseInAccountsReceivable");
            assertThat(fact.bucket()).isEqualTo("AR_OR_RECEIVABLES");
        });
    }

    @Test
    void retainsCashFlowStatementDebtLeavesForFinancingCoverageWithoutTreatingAbsenceAsZero() throws Exception {
        String pre = """
                <link:linkbase xmlns:link='http://www.xbrl.org/2003/linkbase' xmlns:xlink='http://www.w3.org/1999/xlink'>
                  <link:presentationLink xlink:role='role/CashFlow'>
                    <link:loc xlink:label='issue' xlink:href='a.xsd#us-gaap_ProceedsFromIssuanceOfLongTermDebt'/>
                    <link:loc xlink:label='repay' xlink:href='a.xsd#us-gaap_RepaymentsOfLongTermDebt'/>
                  </link:presentationLink></link:linkbase>""";
        String instance = """
                <xbrl xmlns='http://www.xbrl.org/2003/instance' xmlns:us-gaap='http://fasb.org/us-gaap/2025'>
                  <context id='q'><entity/><period><startDate>2025-01-01</startDate><endDate>2025-03-31</endDate></period></context>
                  <us-gaap:ProceedsFromIssuanceOfLongTermDebt contextRef='q' unitRef='USD'>0</us-gaap:ProceedsFromIssuanceOfLongTermDebt>
                  <us-gaap:RepaymentsOfLongTermDebt contextRef='q' unitRef='USD'>21</us-gaap:RepaymentsOfLongTermDebt>
                </xbrl>""";

        SecFilingXbrlGraphService.FilingGraph graph = new SecFilingXbrlGraphService(new ObjectMapper(), "test")
                .parse("0003", pre, null, instance);

        assertThat(graph.nonOverlappingLeafFacts()).extracting(SecFilingXbrlGraphService.Fact::concept)
                .containsExactlyInAnyOrder("ProceedsFromIssuanceOfLongTermDebt", "RepaymentsOfLongTermDebt");
        assertThat(graph.nonOverlappingLeafFacts()).anySatisfy(fact -> {
            assertThat(fact.concept()).isEqualTo("ProceedsFromIssuanceOfLongTermDebt");
            assertThat(fact.value()).isEqualTo("0");
            assertThat(fact.bucket()).isEqualTo("DEBT_ISSUANCE");
        });
    }
}
