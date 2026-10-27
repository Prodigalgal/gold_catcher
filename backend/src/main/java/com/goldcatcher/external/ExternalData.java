package com.goldcatcher.external;

import com.goldcatcher.analysis.TrendAnalysis;
import com.goldcatcher.information.NewsService;
import com.goldcatcher.market.MarketFeed;
import com.goldcatcher.market.MarketTypes.Candle;
import com.goldcatcher.persistence.DocumentStore;
import com.goldcatcher.research.EvidenceCompression;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;

/** Shared read-only data boundary for REST and MCP. Does not refresh sources, invoke AI or execute plans. */
@Service
public class ExternalData {
    public static final List<String> PRODUCTS = List.of("XAU_USDT", "GATE:XAUUSD");
    public static final List<String> FRAMES = List.of("1", "5", "15", "60", "240", "D");
    private final MarketFeed market;
    private final NewsService news;
    private final TrendAnalysis analysis;
    private final EvidenceCompression compression;
    private final DocumentStore store;
    private final ExternalRecords records;
    private final ObjectMapper mapper;
    private final Clock clock;
    public ExternalData(MarketFeed market, NewsService news, TrendAnalysis analysis, EvidenceCompression compression,
                        DocumentStore store, ExternalRecords records, ObjectMapper mapper, Clock clock) {
        this.market = market; this.news = news; this.analysis = analysis; this.compression = compression;
        this.store = store; this.records = records; this.mapper = mapper; this.clock = clock;
    }
    public Map<String,Object> products() {
        return Map.of("products", List.of(Map.of("symbol", "XAU_USDT", "venue", "GATE", "type", "USDT_PERPETUAL", "settlement", "USDT"),
            Map.of("symbol", "GATE:XAUUSD", "venue", "GATE", "type", "CFD", "settlement", "USD")), "frames", FRAMES,
            "readOnly", true, "timeZone", "UTC", "financialNumbers", "DECIMAL_STRINGS", "serverTime", clock.instant());
    }
    public Map<String,Object> quote(String symbol) {
        product(symbol); var snapshot = market.snapshot(symbol); var result = envelope(symbol);
        result.put("state", snapshot.state()); result.put("quote", snapshot.quote()); result.put("rules", snapshot.rules());
        result.put("provenance", market.provenance(symbol)); result.put("error", snapshot.error());
        result.put("coverage", "LATEST_OBSERVED_QUOTE; INSPECT_STATE_AND_TIMESTAMPS; CFD_REST_IS_NOT_TICK_CONTINUITY"); return result;
    }
    public Map<String,Object> candles(String symbol, String frame, Instant from, Instant to, int limit, boolean closedOnly) {
        product(symbol); frame(frame); range(from, to); if (limit < 1 || limit > 200) throw new IllegalArgumentException("INVALID_LIMIT");
        Instant now = clock.instant();
        var available = market.candles(symbol, frame).stream().sorted(Comparator.comparing(Candle::start)).toList();
        var matching = available.stream().filter(c -> !closedOnly || !c.end().isAfter(now))
            .filter(c -> from == null || !c.start().isBefore(from)).filter(c -> to == null || c.start().isBefore(to)).toList();
        var returned = matching.subList(Math.max(0, matching.size() - limit), matching.size());
        var result = envelope(symbol); result.put("frame", frame); result.put("state", market.snapshot(symbol).state());
        result.put("closedOnly", closedOnly); result.put("candles", returned); result.put("availableCount", available.size());
        result.put("availableFrom", available.isEmpty() ? null : available.getFirst().start());
        result.put("availableThrough", available.isEmpty() ? null : available.getLast().end());
        result.put("hasEarlierInWindow", matching.size() > limit);
        result.put("nextBefore", matching.size() > limit ? returned.getFirst().start() : null);
        result.put("requestedFromOutsideCache", from != null && (available.isEmpty() || from.isBefore(available.getFirst().start())));
        result.put("coverage", "CURRENT_COLLECTOR_WINDOW_ONLY; MAX_200_CANDLES; ASCENDING; FROM_INCLUSIVE_TO_EXCLUSIVE_ON_START; NO_ON_DEMAND_BACKFILL"); return result;
    }
    public Map<String,Object> indicators(String symbol, String selectedFrame) {
        product(symbol); if (selectedFrame != null) frame(selectedFrame); var snapshot = market.snapshot(symbol); var result = envelope(symbol);
        result.put("state", snapshot.state()); result.put("indicators", selectedFrame == null ? snapshot.indicators() :
            snapshot.indicators().containsKey(selectedFrame) ? Map.of(selectedFrame, snapshot.indicators().get(selectedFrame)) : Map.of());
        result.put("coverage", "JAVA_CALCULATED_ON_CLOSED_CANDLES; INSPECT_SAMPLES_CLOSEDTHROUGH_AND_FRESHNESS"); return result;
    }
    public Map<String,Object> depth(String symbol) {
        product(symbol); var result = envelope(symbol); result.put("depth", symbol.equals("XAU_USDT") ? market.depth() : Map.of("state", "NOT_PROVIDED")); return result;
    }
    public Map<String,Object> searchNews(String query, String source, Instant since, Instant until, String cursor, int limit) {
        range(since, until); query = textFilter(query, 200); source = textFilter(source, 80);
        String q = query.toLowerCase(Locale.ROOT), s = source;
        var page = records.page("news", cursor, limit, mapper.writeValueAsString(Arrays.asList(q, s, since, until)), n -> {
            if (!s.isEmpty() && !s.equals(n.path("source").asText())) return null;
            if (!q.isEmpty() && !(n.path("title").asText() + "\n" + n.path("excerpt").asText()).toLowerCase(Locale.ROOT).contains(q)) return null;
            if (!within(n.path("receivedAt"), since, until)) return null;
            return project(mapper.valueToTree(news.detail(n.path("id").asText()).get("news")), "id", "source", "title", "excerpt", "link", "publishedAt", "receivedAt", "verification", "version", "publishedPrecision", "contentState", "freshness", "archived");
        });
        if (!page.items().isEmpty()) {
            var summaries = compression.summaries(page.items().stream().map(n -> n.path("id").asText()).toList());
            for (int i = 0; i < page.items().size(); i++) ((ObjectNode) page.items().get(i)).set("compression", mapper.valueToTree(summaries.get(i)));
        }
        return page(page, "receivedAt", "TITLE_AND_FEED_EXCERPT_LITERAL_SEARCH; ORIGINAL_BODY_VIA_GET_NEWS");
    }
    public Map<String,Object> article(String id, String representation, int offset, int maxCharacters) {
        id(id); if (!List.of("summary", "original").contains(representation)) throw new IllegalArgumentException("INVALID_REPRESENTATION");
        if (offset < 0 || maxCharacters < 1 || maxCharacters > 16000) throw new IllegalArgumentException("INVALID_TEXT_WINDOW");
        var detail = news.detail(id); var result = new LinkedHashMap<String,Object>(); result.put("serverTime", clock.instant());
        result.put("news", project(mapper.valueToTree(detail.get("news")), "id", "source", "title", "link", "publishedAt", "receivedAt", "verification", "version", "publishedPrecision", "contentState", "freshness", "archived"));
        var summary = compression.summaries(List.of(id)).getFirst(); result.put("compression", summary); result.put("representation", representation);
        String text; String state;
        if (representation.equals("summary")) { text = summary.excerpt(); state = summary.state(); }
        else {
            var body = (NewsService.ArticleBody) detail.get("body");
            text = body == null ? "" : body.text(); state = body == null ? "BODY_NOT_AVAILABLE" : body.state();
            result.put("bodyReceivedAt", body == null ? null : body.receivedAt()); result.put("bodyError", body == null ? null : body.error());
            result.put("sourceTruncated", body != null && body.truncated());
            var original = store.find("news", id, NewsService.News.class).orElseThrow();
            result.put("bodyMatchesCurrentFeed", body != null && Objects.equals(body.feedHash(), original.contentHash()));
        }
        text = text == null ? "" : text; int length = text.codePointCount(0, text.length());
        if (offset > length) throw new IllegalArgumentException("INVALID_TEXT_OFFSET");
        int end = (int) Math.min(length, (long) offset + maxCharacters);
        result.put("text", text.substring(text.offsetByCodePoints(0, offset), text.offsetByCodePoints(0, end)));
        result.put("state", state); result.put("offset", offset); result.put("totalCharacters", length); result.put("nextOffset", end < length ? end : null);
        result.put("coverage", "STORED_CONTENT_ONLY; CODE_POINT_OFFSETS; SUMMARY_CAN_BE_PENDING_OR_STALE; BODY_COHERENCE_EXPLICIT"); return result;
    }
    public Map<String,Object> calendar(Instant from, Instant to, String cursor, int limit) {
        range(from, to);
        var page = records.page("calendar", cursor, limit, mapper.writeValueAsString(Arrays.asList(from, to)), n -> {
            // Date-only and date-range events retain originalZone/precision; never invent a release time.
            Instant start; Instant end;
            if (n.path("scheduledAt").isTextual()) { start = Instant.parse(n.path("scheduledAt").asText()); end = start.plusNanos(1); }
            else if (n.path("scheduledDate").isTextual()) {
                var zone = ZoneId.of(n.path("originalZone").asText());
                start = LocalDate.parse(n.path("scheduledDate").asText()).atStartOfDay(zone).toInstant();
                end = LocalDate.parse(n.path("endDate").isTextual() ? n.path("endDate").asText() : n.path("scheduledDate").asText()).plusDays(1).atStartOfDay(zone).toInstant();
            } else return from == null && to == null ? n : null;
            return (from == null || end.isAfter(from)) && (to == null || start.isBefore(to)) ? n : null;
        });
        return page(page, "scheduledAt_OR_originalZone_date_range", "STORED_ECONOMIC_CALENDAR; ORDER_BY_RECORD_UPDATE_NOT_EVENT_TIME; PRESERVE_TIME_PRECISION");
    }
    public Map<String,Object> drivers(String symbol) {
        product(symbol); var view = analysis.view(symbol); var result = envelope(symbol);
        result.put("calculatedAt", view.calculatedAt()); result.put("drivers", view.drivers()); result.put("releases", view.releases());
        result.put("expectations", view.expectations()); result.put("supplemental", view.supplemental()); result.put("gaps", view.gaps());
        result.put("coverage", "OBSERVED_PUBLIC_DATA; EXPECTATION_IS_NOT_ACTUAL; INSPECT_FREQUENCY_AND_TIMESTAMPS"); return result;
    }
    public Map<String,Object> sources() {
        var view = analysis.view("XAU_USDT"); var result = new LinkedHashMap<String,Object>(); result.put("serverTime", clock.instant());
        result.put("officialAndMedia", news.sources()); result.put("drivers", view.sources());
        result.put("supplemental", view.supplemental() == null ? List.of() : view.supplemental().sources()); result.put("gaps", view.gaps()); return result;
    }
    public Map<String,Object> research(String symbol, String horizon, String cursor, int limit) {
        product(symbol); horizon(horizon);
        var page = records.page("research-summary", cursor, limit, mapper.writeValueAsString(Arrays.asList(symbol, horizon)), n ->
            symbol.equals(n.path("symbol").asText()) && (horizon == null || horizon.equals(n.path("horizon").asText())) ?
                project(n, "id", "symbol", "horizon", "status", "startedAt", "completedAt", "stage", "error") : null);
        return page(page, "startedAt", "RECORDED_RESEARCH; LOW_QUORUM_OR_TIMEOUT_IS_NOT_A_VALID_DECISION");
    }
    public Map<String,Object> researchDetail(String id, int messageOffset, int limit) {
        id(id); if (messageOffset < 0 || limit < 1 || limit > 12) throw new IllegalArgumentException("INVALID_MESSAGE_WINDOW");
        var run = store.findExact("research", id, JsonNode.class).orElseThrow(() -> new IllegalArgumentException("RESEARCH_NOT_FOUND"));
        var result = project(run, "id", "symbol", "horizon", "status", "stage", "error", "startedAt", "completedAt", "evidenceCutoff", "decisionExpiresAt");
        var expiry = run.path("decisionExpiresAt"); result.put("decisionExpired", !expiry.isTextual() || !Instant.parse(expiry.asText()).isAfter(clock.instant()));
        result.set("verdict", answer(run.path("verdict")));
        result.set("adjudication", project(run.path("adjudication"), "method", "state", "minimumJudges", "validJudges", "majorityThreshold", "winningVotes", "selectedCandidateId", "fallbackJudges", "distinctReportedJudgeModels", "modelIdentityBasis"));
        var actors = mapper.createArrayNode();
        if (run.path("actors").isArray()) for (var actor : run.path("actors")) actors.add(project(actor, "id", "role", "provider", "model"));
        result.set("actors", actors);
        var messages = run.path("messages"); int size = messages.isArray() ? messages.size() : 0;
        if (messageOffset > size) throw new IllegalArgumentException("INVALID_MESSAGE_OFFSET");
        var selected = mapper.createArrayNode(); int end = Math.min(size, messageOffset + limit);
        for (int i = messageOffset; i < end; i++) {
            var m = messages.get(i); var safe = project(m, "actorId", "role", "provider", "model", "stage", "state", "error", "startedAt", "completedAt");
            var rawAnswer = m.path("answer");
            if (rawAnswer.isObject()) {
                var response = project(rawAnswer, "model", "analysisSource", "finishReason", "durationMs");
                String reasoning = rawAnswer.path("analysis").asText(""); boolean shortened = reasoning.codePointCount(0, reasoning.length()) > 8000;
                response.put("analysis", shortened ? reasoning.substring(0, reasoning.offsetByCodePoints(0, 8000)) : reasoning);
                response.put("analysisTruncated", shortened); response.set("output", answer(rawAnswer.path("output")));
                response.set("routing", project(rawAnswer.path("routing"), "requestedProvider", "requestedModel", "usedProvider", "usedModel", "fallbackUsed"));
                safe.set("answer", response);
            } else safe.putNull("answer");
            selected.add(safe);
        }
        result.set("messages", selected); result.put("messageOffset", messageOffset); result.put("totalMessages", size);
        if (end < size) result.put("nextMessageOffset", end); else result.putNull("nextMessageOffset");
        result.put("coverage", "PROJECTED_RESEARCH_ONLY; CONFIGURATION_CREDENTIALS_RAW_PROMPTS_AND_ACCOUNT_STATE_EXCLUDED; TEXT_FIELDS_CAPPED_8000_CODE_POINTS; PLANS_UNMODIFIED");
        return mapper.convertValue(result, new tools.jackson.core.type.TypeReference<Map<String,Object>>() {});
    }
    private JsonNode answer(JsonNode answer) {
        if (!answer.isObject()) return mapper.nullNode();
        var result = project(answer, "title", "analysisSummary", "output", "shortDirection", "longDirection", "plan", "changes", "impacts");
        var truncated = mapper.createArrayNode();
        for (String field : List.of("title", "analysisSummary", "output", "changes")) {
            String text = result.path(field).asText(""); if (text.codePointCount(0, text.length()) > 8000) {
                result.put(field, text.substring(0, text.offsetByCodePoints(0, 8000))); truncated.add(field);
            }
        }
        result.set("truncatedTextFields", truncated); return result;
    }
    private ObjectNode project(JsonNode input, String... fields) {
        var result = mapper.createObjectNode(); for (String field : fields) if (input.has(field)) result.set(field, input.get(field)); return result;
    }
    private Map<String,Object> envelope(String symbol) { var result = new LinkedHashMap<String,Object>(); result.put("symbol", symbol); result.put("serverTime", clock.instant()); return result; }
    private Map<String,Object> page(ExternalRecords.Page page, String timeField, String coverage) {
        var result = new LinkedHashMap<String,Object>(); result.put("items", page.items()); result.put("nextCursor", page.nextCursor());
        result.put("scanned", page.scanned()); result.put("asOf", page.asOf()); result.put("filterTimeField", timeField);
        result.put("paginationCoverage", page.coverage()); result.put("coverage", coverage); return result;
    }
    private static boolean within(JsonNode value, Instant from, Instant to) {
        if (!value.isTextual()) return from == null && to == null; Instant at = Instant.parse(value.asText());
        return (from == null || !at.isBefore(from)) && (to == null || at.isBefore(to));
    }
    public static void product(String symbol) { if (!PRODUCTS.contains(symbol)) throw new IllegalArgumentException("UNKNOWN_PRODUCT"); }
    private static void frame(String frame) { if (!FRAMES.contains(frame)) throw new IllegalArgumentException("UNKNOWN_FRAME"); }
    private static void horizon(String horizon) { if (horizon != null && !List.of("SHORT", "LONG").contains(horizon)) throw new IllegalArgumentException("INVALID_HORIZON"); }
    private static void range(Instant from, Instant to) { if (from != null && to != null && !from.isBefore(to)) throw new IllegalArgumentException("INVALID_TIME_RANGE"); }
    private static void id(String id) { if (id == null || !id.matches("[A-Za-z0-9_-]{1,120}")) throw new IllegalArgumentException("INVALID_RECORD_ID"); }
    private static String textFilter(String text, int maximum) { if (text == null) return ""; if (text.length() > maximum || text.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("INVALID_FILTER"); return text.strip(); }
}
