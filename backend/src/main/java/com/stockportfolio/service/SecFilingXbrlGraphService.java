package com.stockportfolio.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.Duration;
import java.util.*;

/**
 * Filing-scoped XBRL graph reader. It intentionally combines presentation,
 * calculation and instance/context facts; calculation arcs alone never select
 * working-capital facts. Network failures return an unavailable graph so a
 * valuation remains safely incomplete rather than silently substituting tags.
 */
@Service
public class SecFilingXbrlGraphService {
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final String userAgent;

    public SecFilingXbrlGraphService(ObjectMapper objectMapper,
                                     @Value("${app.sec.user-agent:stock-portfolio kaihan@example.com}") String userAgent) {
        this.objectMapper = objectMapper;
        this.userAgent = userAgent;
    }

    public FilingGraph load(String cik, String accession) {
        try {
            String accessionDigits = accession.replace("-", "");
            String base = "https://www.sec.gov/Archives/edgar/data/" + Long.parseLong(cik) + "/" + accessionDigits + "/";
            JsonNode index = getJson(base + "index.json");
            List<String> names = new ArrayList<>();
            index.path("directory").path("item").forEach(item -> names.add(item.path("name").asText()));
            String pre = names.stream().filter(n -> n.endsWith("_pre.xml") || n.endsWith("-pre.xml")).findFirst().orElse(null);
            String cal = names.stream().filter(n -> n.endsWith("_cal.xml") || n.endsWith("-cal.xml")).findFirst().orElse(null);
            String instance = names.stream().filter(n -> n.endsWith(".xml") && !n.contains("_")
                    && !n.endsWith(".xsd") && !n.equals("FilingSummary.xml")).findFirst().orElse(null);
            if (instance == null) instance = names.stream().filter(n -> n.endsWith("_htm.xml")).findFirst().orElse(null);
            if (pre == null || instance == null) return FilingGraph.unavailable(accession, "missing presentation linkbase or XBRL instance");
            return parse(accession, getText(base + pre), cal == null ? null : getText(base + cal), getText(base + instance));
        } catch (Exception e) {
            return FilingGraph.unavailable(accession, "filing graph fetch failed: " + e.getClass().getSimpleName());
        }
    }

    FilingGraph parse(String accession, String presentationXml, String calculationXml, String instanceXml) throws Exception {
        Document presentation = xml(presentationXml), instance = xml(instanceXml);
        Map<String, String> concepts = locators(presentation);
        Set<String> cashFlowRoles = new LinkedHashSet<>();
        Map<String, Set<String>> children = new LinkedHashMap<>();
        Set<String> presented = new LinkedHashSet<>();
        NodeList links = presentation.getElementsByTagNameNS("*", "presentationLink");
        for (int i = 0; i < links.getLength(); i++) {
            Element link = (Element) links.item(i);
            String role = attr(link, "role");
            if (!role.toLowerCase(Locale.ROOT).contains("cashflow")) continue;
            cashFlowRoles.add(role);
            Map<String, String> local = locators(link);
            presented.addAll(local.values());
            NodeList arcs = link.getElementsByTagNameNS("*", "presentationArc");
            for (int j = 0; j < arcs.getLength(); j++) {
                Element arc = (Element) arcs.item(j);
                String parent = local.get(attr(arc, "from")), child = local.get(attr(arc, "to"));
                if (parent != null && child != null) children.computeIfAbsent(parent, ignored -> new LinkedHashSet<>()).add(child);
            }
        }
        children.forEach((parent, value) -> { presented.add(parent); presented.addAll(value); });
        Set<String> parents = children.keySet();
        Map<String, Integer> calcWeights = calculationWeights(calculationXml, cashFlowRoles);
        Map<String, Context> contexts = contexts(instance);
        List<Fact> leaves = new ArrayList<>();
        // Traditional instances expose facts as XBRL children. Modern SEC filings
        // are usually inline XBRL, where facts are ix:nonFraction anywhere in the
        // XHTML document and the concept is in the `name` attribute.
        List<Element> factNodes = new ArrayList<>();
        NodeList all = instance.getDocumentElement().getChildNodes();
        for (int i = 0; i < all.getLength(); i++) if (all.item(i) instanceof Element element) factNodes.add(element);
        NodeList inline = instance.getElementsByTagNameNS("http://www.xbrl.org/2013/inlineXBRL", "nonFraction");
        for (int i = 0; i < inline.getLength(); i++) factNodes.add((Element) inline.item(i));
        for (Element fact : factNodes) {
            String concept = fact.hasAttribute("name") ? fact.getAttribute("name").replaceFirst("^[^:]+:", "") : fact.getLocalName();
            boolean cashFlowLeaf = concept != null && presented.contains(concept) && !parents.contains(concept);
            boolean taxOrInterestDisclosure = concept != null && isTaxOrInterestDisclosure(concept);
            if (!cashFlowLeaf && !taxOrInterestDisclosure) continue;
            String contextRef = fact.getAttribute("contextRef"); Context context = contexts.get(contextRef);
            if (context == null || context.start == null || context.end == null || fact.getTextContent().isBlank()) continue;
            try {
                if ((cashFlowLeaf && isCashFlowEvidenceCandidate(concept)) || taxOrInterestDisclosure) {
                    leaves.add(new Fact(concept, fact.getTextContent().trim(), context.start, context.end,
                            fact.getAttribute("unitRef"), calcWeights.get(concept),
                            taxOrInterestDisclosure && !cashFlowLeaf ? "TAX_INTEREST_DISCLOSURE" : bucket(concept), accession));
                }
            } catch (RuntimeException ignored) { }
        }
        EquitySelection equity = equityFacts(presentation, instance, factNodes, accession);
        List<String> warnings = new ArrayList<>();
        if (cashFlowRoles.isEmpty()) warnings.add("no cash-flow statement role identified");
        if (equity.roles().isEmpty()) warnings.add("no stockholders-equity statement role identified");
        if (calculationXml == null) warnings.add("calculation linkbase missing; presentation and instance still retained");
        return new FilingGraph(accession, "AVAILABLE", List.copyOf(cashFlowRoles), List.copyOf(leaves),
                equity.roles(), equity.facts(), List.copyOf(warnings));
    }

    private EquitySelection equityFacts(Document presentation, Document instance, List<Element> factNodes, String accession) {
        Map<String, String> conceptRoles = new LinkedHashMap<>();
        NodeList links = presentation.getElementsByTagNameNS("*", "presentationLink");
        for (int i = 0; i < links.getLength(); i++) {
            Element link = (Element) links.item(i); String role = attr(link, "role");
            String normalized = role.toLowerCase(Locale.ROOT);
            if (!((normalized.contains("stockholder") || normalized.contains("shareholder") || normalized.contains("changesinequity"))
                    && normalized.contains("equity"))) continue;
            // Equity statements often use CommonStockSharesOutstanding as a presentation
            // subtotal/column heading rather than a leaf. The bridge resolver later selects
            // only its endpoint facts and specific non-overlapping movement concepts.
            for (String concept : locators(link).values()) conceptRoles.putIfAbsent(concept, role);
        }
        Map<String, EquityContext> contexts = equityContexts(instance); List<EquityFact> result = new ArrayList<>();
        for (Element fact : factNodes) {
            String concept = fact.hasAttribute("name") ? fact.getAttribute("name").replaceFirst("^[^:]+:", "") : fact.getLocalName();
            EquityContext context = contexts.get(fact.getAttribute("contextRef"));
            String role = conceptRoles.get(concept); String unit = fact.getAttribute("unitRef");
            boolean candidate = unit != null && unit.toLowerCase(Locale.ROOT).contains("share") && isShareCountEvidenceCandidate(concept);
            // Some issuers omit an otherwise visible statement row from the presentation
            // locator. The equity-axis/CommonStockMember context proves it belongs to the
            // selected statement column, so use the selected equity role only in that case.
            if (role == null && candidate && context != null && isSelectedEquityContext(concept, context) && !conceptRoles.isEmpty())
                role = conceptRoles.values().iterator().next();
            if (role == null || !candidate) continue;
            if (context == null || !isSelectedEquityContext(concept, context) || context.end() == null || fact.getTextContent().isBlank()) continue;
            try { result.add(new EquityFact(concept, fact.getTextContent().trim(), context.start(), context.end(), unit, shareBucket(concept), role, accession)); }
            catch (RuntimeException ignored) { }
        }
        return new EquitySelection(List.copyOf(new LinkedHashSet<>(conceptRoles.values())), List.copyOf(result));
    }

    private Map<String, EquityContext> equityContexts(Document instance) {
        Map<String, EquityContext> result = new HashMap<>(); NodeList values = instance.getElementsByTagNameNS("*", "context");
        for (int i = 0; i < values.getLength(); i++) { Element context = (Element) values.item(i); String id = context.getAttribute("id");
            String start = text(context, "startDate"), end = text(context, "endDate");
            if (end.isBlank()) end = text(context, "instant");
            try {
                if (!end.isBlank()) {
                    NodeList members = context.getElementsByTagNameNS("*", "explicitMember");
                    List<String> memberNames = new ArrayList<>();
                    for (int j = 0; j < members.getLength(); j++) memberNames.add(members.item(j).getTextContent());
                    result.put(id, new EquityContext(start.isBlank() ? null : LocalDate.parse(start), LocalDate.parse(end), String.join("|", memberNames)));
                }
            }
            catch (RuntimeException ignored) { }
        }
        return result;
    }

    private Map<String, Integer> calculationWeights(String calculationXml, Set<String> targetRoles) throws Exception {
        if (calculationXml == null) return Map.of();
        Document doc = xml(calculationXml); Map<String, Integer> result = new HashMap<>();
        NodeList links = doc.getElementsByTagNameNS("*", "calculationLink");
        for (int i = 0; i < links.getLength(); i++) {
            Element link = (Element) links.item(i); String role = attr(link, "role");
            if (!targetRoles.isEmpty() && !targetRoles.contains(role)) continue;
            Map<String, String> local = locators(link); NodeList arcs = link.getElementsByTagNameNS("*", "calculationArc");
            for (int j = 0; j < arcs.getLength(); j++) { Element arc = (Element) arcs.item(j); String child = local.get(attr(arc, "to"));
                if (child != null) result.put(child, "-1".equals(attr(arc, "weight")) ? -1 : 1); }
        }
        return result;
    }
    private Map<String, String> locators(Element root) {
        Map<String, String> result = new HashMap<>();
        // XBRL linkbases contain many statements. Locator labels are only
        // meaningful inside their own presentation/calculationLink scope.
        NodeList nodes = root.getElementsByTagNameNS("*", "loc");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element loc = (Element) nodes.item(i); String href = attr(loc, "href"); int hash = href.lastIndexOf('#');
            if (hash >= 0) result.put(attr(loc, "label"), href.substring(hash + 1).replaceFirst("^[^_]+_", ""));
        }
        return result;
    }
    private Map<String, String> locators(Node root) {
        Map<String, String> result = new HashMap<>(); NodeList nodes = ((root instanceof Document d) ? d : root.getOwnerDocument()).getElementsByTagNameNS("*", "loc");
        for (int i = 0; i < nodes.getLength(); i++) { Element loc = (Element) nodes.item(i); String href = attr(loc, "href"); int hash = href.lastIndexOf('#');
            if (hash >= 0) result.put(attr(loc, "label"), href.substring(hash + 1).replaceFirst("^[^_]+_", "")); }
        return result;
    }
    private Map<String, Context> contexts(Document instance) {
        Map<String, Context> result = new HashMap<>(); NodeList values = instance.getElementsByTagNameNS("*", "context");
        for (int i = 0; i < values.getLength(); i++) { Element context = (Element) values.item(i); String id = context.getAttribute("id");
            String start = text(context, "startDate"), end = text(context, "endDate");
            try { if (!start.isBlank() && !end.isBlank()) result.put(id, new Context(LocalDate.parse(start), LocalDate.parse(end))); } catch (RuntimeException ignored) { } }
        return result;
    }
    private String bucket(String concept) { String value = concept.toLowerCase(Locale.ROOT);
        if (value.contains("commercialpaper")) return "COMMERCIAL_PAPER_FINANCING";
        if (value.contains("issuance") && value.contains("debt")) return "DEBT_ISSUANCE";
        if (value.contains("repayment") && value.contains("debt")) return "DEBT_REPAYMENT";
        if (value.contains("debt")) return "DEBT_FINANCING_OTHER";
        if (value.contains("receivable")) return "AR_OR_RECEIVABLES"; if (value.contains("inventor")) return "INVENTORY";
        if (value.contains("payable")) return "AP_OR_PAYABLES"; if (value.contains("contract") || value.contains("deferredrevenue")) return "CONTRACT_LIABILITY";
        if (value.contains("asset")) return "OTHER_OPERATING_ASSETS"; if (value.contains("liabilit")) return "OTHER_OPERATING_LIABILITIES"; return "OTHER_CFO_LEAF"; }
    private boolean isCashFlowEvidenceCandidate(String concept) {
        String value = concept.toLowerCase(Locale.ROOT);
        if (value.contains("netcashprovidedbyusedinoperatingactivities") || value.contains("netincomeloss")
                || value.contains("depreciation") || value.contains("amortization") || value.contains("sharebasedcompensation")
                || value.contains("othernoncash") || value.contains("incometaxespaid") || value.contains("interestpaid")
                || value.contains("interestreceived") || value.contains("increasedecreasein") || value.contains("deferredtax")) return true;
        // Financing facts are persisted separately from the FCFF operating bridge.
        // They let the debt resolver prove an explicit zero in the same filing and
        // duration context; absence of a fact remains INCOMPLETE, never zero.
        if (value.contains("commercialpaper")) return true;
        if (value.contains("debt") && (value.contains("issuance") || value.contains("repayment")
                || value.contains("proceedsfromrepayments"))) return true;
        // Explicitly exclude capital allocation / financing concepts even where an issuer
        // presents them beside CFO adjustments in the same cash-flow statement role.
        return false;
    }
    private boolean isTaxOrInterestDisclosure(String concept) {
        String value = concept.toLowerCase(Locale.ROOT);
        return value.equals("interestexpense") || value.equals("interestexpenseoperating")
                || value.equals("interestexpensenonoperating") || value.equals("interestanddebtexpense")
                || value.equals("interestincomeexpenseoperatingnet")
                || value.contains("interestincome") || value.equals("incometaxexpensebenefit")
                || value.contains("deferredincometax") || value.contains("incomelossfromcontinuingoperationsbeforeincometaxes");
    }
    private boolean isShareCountEvidenceCandidate(String concept) {
        String value = concept == null ? "" : concept.toLowerCase(Locale.ROOT);
        return value.contains("commonstocksharesoutstanding") || value.contains("commonsharesoutstanding")
                || value.contains("treasurystocksharesacquired") || value.contains("sharebasedcompensation")
                || value.contains("stockissuedduringperiod") || value.contains("businesscombination");
    }
    private String shareBucket(String concept) {
        String value = concept.toLowerCase(Locale.ROOT);
        if (value.contains("treasurystocksharesacquired")) return "TREASURY_STOCK_PURCHASES";
        if (value.contains("sharebasedcompensation")) return "STOCK_BASED_COMPENSATION";
        if (value.contains("businesscombination")) return "BUSINESS_COMBINATIONS";
        if (value.contains("outstanding")) return "COMMON_SHARES_OUTSTANDING";
        return "OTHER_EQUITY_ACTIVITY";
    }
    private Document xml(String input) throws Exception { DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(true); factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); return factory.newDocumentBuilder().parse(new InputSource(new StringReader(input))); }
    private JsonNode getJson(String url) throws Exception { return objectMapper.readTree(getText(url)); }
    private String getText(String url) throws Exception { HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).header("User-Agent", userAgent).GET().build(); HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString()); if (response.statusCode() >= 400) throw new IllegalStateException("SEC HTTP " + response.statusCode()); return response.body(); }
    private String attr(Element element, String name) { String value = element.getAttributeNS("http://www.w3.org/1999/xlink", name); return value.isBlank() ? element.getAttribute(name) : value; }
    private String text(Element parent, String localName) { NodeList nodes = parent.getElementsByTagNameNS("*", localName); return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent(); }
    private boolean isSelectedEquityContext(String concept, EquityContext context) {
        String members = context.members().toLowerCase(Locale.ROOT);
        if (members.isBlank()) return true;
        // Equity statements may report treasury shares acquired in the Common Stock
        // column (the movement affects common shares outstanding), rather than in
        // Treasury Stock. We only persist movement facts from that common-share column.
        return members.contains("commonstockmember");
    }
    private record Context(LocalDate start, LocalDate end) { }
    private record EquityContext(LocalDate start, LocalDate end, String members) { }
    private record EquitySelection(List<String> roles, List<EquityFact> facts) { }
    public record Fact(String concept, String value, LocalDate start, LocalDate end, String unit, Integer calculationWeight, String bucket, String accession) { }
    public record EquityFact(String concept, String value, LocalDate start, LocalDate end, String unit, String bucket, String statementRole, String accession) { }
    public record FilingGraph(String accession, String status, List<String> statementRoles, List<Fact> nonOverlappingLeafFacts,
                              List<String> equityStatementRoles, List<EquityFact> nonOverlappingEquityLeafFacts, List<String> warnings) {
        static FilingGraph unavailable(String accession, String warning) { return new FilingGraph(accession, "UNAVAILABLE", List.of(), List.of(), List.of(), List.of(), List.of(warning)); }
    }
}
