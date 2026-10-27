package com.goldcatcher.external;

import com.goldcatcher.TestSupport;
import com.goldcatcher.analysis.TrendAnalysis;
import com.goldcatcher.auth.PersonalAccessTokens.Identity;
import com.goldcatcher.information.NewsService;
import com.goldcatcher.market.*;
import com.goldcatcher.research.EvidenceCompression;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExternalDataTest {
    private final TestSupport f = new TestSupport();
    private final MarketFeed market = mock(MarketFeed.class);
    private final NewsService news = mock(NewsService.class);
    private final TrendAnalysis analysis = mock(TrendAnalysis.class);
    private final EvidenceCompression compression = mock(EvidenceCompression.class);
    private final ExternalRecords records = new ExternalRecords(f.jdbc, f.mapper, f.clock);
    private final ExternalData data = new ExternalData(market, news, analysis, compression, f.store, records, f.mapper, f.clock);
    @Test void candlesRemainExactOrderedAndClosedOnlyWithExplicitAvailableWindowAndNoBackfill() {
        var at = f.clock.instant();
        var value = new BigDecimal("4000.1234567890123456789");
        var old = new MarketTypes.Candle(at.minusSeconds(120), at.minusSeconds(60), value, value, value, value, BigDecimal.ZERO);
        var recent = new MarketTypes.Candle(at.minusSeconds(60), at, value, value, value, value, null);
        var open = new MarketTypes.Candle(at, at.plusSeconds(60), value, value, value, value, null);
        when(market.candles("XAU_USDT", "1")).thenReturn(List.of(recent, old, open));
        when(market.snapshot("XAU_USDT")).thenReturn(new MarketTypes.Snapshot("XAU_USDT", "LIVE", null, null, Map.of(), List.of(), at, null));
        var result = f.mapper.valueToTree(data.candles("XAU_USDT", "1", at.minusSeconds(300), null, 1, true));
        assertThat(result.path("candles")).hasSize(1);
        assertThat(result.path("candles").get(0).path("close").asText()).isEqualTo(value.toPlainString());
        assertThat(result.path("nextBefore").asText()).isEqualTo(recent.start().toString());
        assertThat(result.path("requestedFromOutsideCache").asBoolean()).isTrue();
        var previous = f.mapper.valueToTree(data.candles("XAU_USDT", "1", null, recent.start(), 200, true));
        assertThat(previous.path("candles")).hasSize(1); assertThat(previous.path("candles").get(0).path("start").asText()).isEqualTo(old.start().toString());
        verifyNoInteractions(news, compression, analysis);
    }
    @Test void recordPagingHasStableTieBreakNoDuplicatesAndResumesAcrossEmptyFilteredPages() {
        for (int i = 0; i < 503; i++) f.store.save("news", "id" + String.format("%04d", i), Map.of("id", "id" + String.format("%04d", i), "match", i < 2));
        var first = records.page("news", null, 10, "same-query", n -> n.path("match").asBoolean() ? n : null);
        assertThat(first.items()).isEmpty(); assertThat(first.scanned()).isEqualTo(500); assertThat(first.nextCursor()).isNotNull();
        var second = records.page("news", first.nextCursor(), 10, "same-query", n -> n.path("match").asBoolean() ? n : null);
        assertThat(second.items()).hasSize(2); assertThat(second.nextCursor()).isNull();
        assertThatThrownBy(() -> records.page("news", first.nextCursor(), 10, "different-query", n -> n)).hasMessage("INVALID_CURSOR");
        assertThatThrownBy(() -> records.page("ai-configuration", null, 10, "", n -> n)).hasMessage("UNKNOWN_COLLECTION");
    }
    @Test void newsReadsDoNotStartCompressionAndUnicodeBodyPagesRoundTrip() {
        String text = "黄金😀AB"; String id = "article";
        var article = new NewsService.News(id, "FED_PRESS", "title", "excerpt", "https://example.test", f.clock.instant(), f.clock.instant(), "OFFICIAL", 1, "feed-hash");
        var body = new NewsService.ArticleBody(id, "FED_PRESS", article.link(), text, "READY", "hash", f.clock.instant(), f.clock.instant(), null, 1, null, false, "feed-hash", null, "HTML");
        f.store.save("news", id, article);
        when(news.detail(id)).thenReturn(Map.of("news", article, "body", body));
        var summary = new EvidenceCompression.Summary(id, "EXTRACTED", "summary", "精华", true, true, "mimo", "mimo", List.of("mimo"), f.clock.instant(), "HTML", 1, 1, null, null, null);
        when(compression.summaries(List.of(id))).thenReturn(List.of(summary));
        var first = data.article(id, "original", 0, 3); var second = data.article(id, "original", 3, 3);
        assertThat(first.get("text").toString() + second.get("text")).isEqualTo(text);
        assertThat(first).containsEntry("nextOffset", 3).containsEntry("bodyMatchesCurrentFeed", true);
        assertThat(second).containsEntry("nextOffset", null);
        verify(compression, times(2)).summaries(List.of(id)); verifyNoMoreInteractions(compression);
        assertThat(f.store.ids("compression-job", 10)).isEmpty();
    }
    @Test void researchProjectionExcludesCredentialAndPromptSnapshotsAndPreservesFinancialPlanPrecisely() {
        var run = f.mapper.readTree("""
            {"id":"run","symbol":"XAU_USDT","status":"LOW_QUORUM","routeConfiguration":{"apiKey":"secret"},"evidence":{"account":"private"},
             "decisionExpiresAt":"2026-01-01T00:00:00Z","verdict":{"output":"partial","plan":{"entryPrice":"4000.00000000000001"}},
             "messages":[{"actorId":"A","stage":0,"state":"SUBMITTED","readingOrder":["private"],"answer":{"analysis":"thinking","output":{"output":"view","plan":{"margin":"100.123456789"}},"budget":{"prompt":"secret"}}}]}
            """);
        ((tools.jackson.databind.node.ObjectNode)run.path("verdict").path("plan")).put("legacyDecimal",new BigDecimal("4000.1234567890123456789"));
        f.store.save("research", "run", run);
        String response = f.mapper.writeValueAsString(data.researchDetail("run", 0, 6));
        assertThat(response).doesNotContain("secret", "private", "apiKey", "readingOrder", "budget", "routeConfiguration");
        assertThat(response).contains("4000.00000000000001", "100.123456789", "LOW_QUORUM", "\"decisionExpired\":true");
        assertThat(response).contains("4000.1234567890123456789");
    }
    @Test void toolCatalogIsScopeFilteredAndArgumentsAreStrict() {
        var tools = new ExternalTools(data); var identity = new Identity("id", "admin", Set.of("market:read"), 120);
        assertThat(tools.available(identity)).hasSize(5);
        assertThatThrownBy(() -> tools.call(identity, "search_news", f.mapper.createObjectNode())).hasMessage("INSUFFICIENT_SCOPE");
        for (String json : List.of("{\"limit\":0}", "{\"limit\":1.5}", "{\"closedOnly\":\"false\"}", "{\"frame\":\"99\"}", "{\"from\":\"yesterday\"}", "{\"apiKey\":\"x\"}"))
            assertThatThrownBy(() -> tools.call(identity, "get_candles", f.mapper.readTree(json))).isInstanceOf(IllegalArgumentException.class);
    }
}
