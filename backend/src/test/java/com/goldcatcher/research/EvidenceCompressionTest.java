package com.goldcatcher.research;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.goldcatcher.*;
import com.goldcatcher.information.NewsService;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;

class EvidenceCompressionTest {
    TestSupport f;Any2ApiClient ai;NewsService news;EvidenceCompression compression;
    NewsService.News article;NewsService.ArticleBody body;
    @BeforeEach void setup(){
        f=new TestSupport();ai=mock(Any2ApiClient.class);news=mock(NewsService.class);
        article=new NewsService.News("a","FED_PRESS","官方事实","摘要","https://www.federalreserve.gov/test",f.clock.instant().minusSeconds(10),f.clock.instant().minusSeconds(5),"OFFICIAL",1,"feed");
        body=body("The rate is 4.00 percent. However, this is not a forecast. A revision may change the prior value. ");
        f.store.save("news","a",article);when(news.bodyForEvidence(eq("a"),any())).thenAnswer(call->body);
        compression=new EvidenceCompression(f.settings,news,ai,f.store,f.mapper,f.clock,new EventHub());
    }
    NewsService.ArticleBody body(String text){return new NewsService.ArticleBody("a","FED_PRESS",article.link(),text,"AVAILABLE",NewsService.hash(text),f.clock.instant().minusSeconds(2),f.clock.instant(),f.clock.instant().plusSeconds(100),1,null,false,"feed",null,"HTML");}
    @AfterEach void stop(){compression.stop();}
    void answer(String output) throws Exception {when(ai.compress(any(),anyString(),any())).thenAnswer(call->{var parsed=f.mapper.readTree(output);return new Any2ApiClient.Answer("mimo/mimo-v2.6-pro","","NOT_PROVIDED",parsed.toString(),parsed,f.mapper.createObjectNode(),"UNKNOWN","stop",null,1);});}
    @Test void directSummaryOnlyReplacesExcerptWithoutAddingReferencesAndHonorsFrozenVersion() throws Exception {
        answer("{\"summary\":\"利率为4.00%，此数据不构成预测；历史值可能修订。\"}");Instant before=f.clock.instant().minusMillis(1);var result=compression.process(article,body);
        assertThat(result.state()).isEqualTo("EXTRACTED");assertThat(result.excerpt()).contains("4.00%","不构成预测");assertThat(result.selectedSpans()).isEmpty();
        var frozen=new LinkedHashMap<String,Object>();frozen.put("id","a");frozen.put("bodyHash",body.hash());frozen.put("excerpt","old deterministic");frozen.put("actual","4.00");
        assertThat(compression.apply(List.of(frozen),before,"earlier").getFirst().get("excerpt")).isEqualTo("old deterministic");
        var evidence=compression.apply(List.of(frozen),f.clock.instant(),"later").getFirst();
        assertThat(evidence).containsExactlyInAnyOrderEntriesOf(new LinkedHashMap<>(Map.of("id","a","bodyHash",body.hash(),"excerpt",result.excerpt(),"actual","4.00")));
        assertThat(compression.detail(result.id()).toString()).contains("VERIFIED");
        f.clock.advance(10);var repeated=compression.process(article,body);verify(ai,times(1)).compress(any(),anyString(),any());
        assertThat(compression.find(result.id())).isEqualTo(result);assertThat(repeated.excerpt()).isEqualTo(result.excerpt());
        body=body("The rate has since changed. ");frozen.put("bodyHash",body.hash());
        assertThat(compression.apply(List.of(frozen),f.clock.instant(),"revised").getFirst().get("excerpt")).isEqualTo("old deterministic");
    }
    @Test void extraTradingOutputAndInvalidSummariesFallBackWithoutVoting() throws Exception {
        answer("{\"summary\":\"利率未变\",\"plan\":{\"action\":\"CREATE\"}}");var result=compression.process(article,body);
        assertThat(result.state()).isEqualTo("FAILED");assertThat(result.error()).isEqualTo("INVALID_COMPRESSION_OUTPUT");
        var frozen=Map.<String,Object>of("id","a","bodyHash",body.hash(),"excerpt","official fallback");
        assertThat(compression.apply(List.of(frozen),f.clock.instant(),"failed").getFirst().get("excerpt")).isEqualTo("official fallback");
        for(String invalid:List.of("{}","{\"selectedIds\":[]}","{\"summary\":\" \"}","{\"summary\":123}",f.mapper.writeValueAsString(Map.of("summary","😄".repeat(301)))))
            assertThatThrownBy(()->EvidenceCompression.validate(f.mapper.readTree(invalid))).hasMessage("INVALID_COMPRESSION_OUTPUT");
        assertThat(EvidenceCompression.validate(f.mapper.readTree(f.mapper.writeValueAsString(Map.of("summary","😄".repeat(300)))))).hasSize(600);
        assertThat(f.store.find("research-preprocessing","failed",Map.class).orElseThrow().get("voting")).isEqualTo(false);
    }
    @Test void rawChunksAreLosslessUnicodeBoundedAndLongArticleMergesCachedSummaries() throws Exception {
        String text=("价格 4.00 😄 is unchanged. This is not an unconditional forecast. ").repeat(600);body=body(text);
        var chunks=EvidenceCompression.chunks(text,"long",f.settings.compressionRoute());assertThat(chunks).hasSizeGreaterThan(2);
        var joined=new StringBuilder();for(var chunk:chunks){joined.append(chunk.text());assertThat(text.substring(chunk.start(),chunk.end())).isEqualTo(chunk.text());
            assertThat(chunk.text().codePointCount(0,chunk.text().length())).isLessThanOrEqualTo(5500);assertThat(chunk.text().getBytes(java.nio.charset.StandardCharsets.UTF_8)).hasSizeLessThanOrEqualTo(14000);}
        assertThat(joined.toString()).isEqualTo(text);
        var tasks=new ArrayList<String>();when(ai.compress(any(),anyString(),any())).thenAnswer(call->{var packet=(Map<?,?>)call.getArgument(2);tasks.add(String.valueOf(packet.get("task")));
            var expires=(Instant)((Map<?,?>)packet.get("evidence")).get("decisionExpiresAt");
            assertThat(expires).isAfter(f.clock.instant()).isBeforeOrEqualTo(f.clock.instant().plusSeconds(75));
            var output=f.mapper.valueToTree(Map.of("summary",packet.get("task").equals("MERGE")?"全文要点：4.00不变，未来并无无条件保证。":"局部要点：4.00未变，但不保证未来。"));
            return new Any2ApiClient.Answer("mimo/mimo-v2.6-pro","","NOT_PROVIDED",output.toString(),output,f.mapper.createObjectNode(),"UNKNOWN","stop",null,1);});
        var partial=compression.process(article,body);assertThat(partial.state()).isEqualTo("PARTIAL");assertThat(partial.processedChunks()).isEqualTo(2);assertThat(tasks).doesNotContain("MERGE");
        var frozen=Map.<String,Object>of("id","a","bodyHash",body.hash(),"excerpt","original fallback");
        assertThat(compression.apply(List.of(frozen),f.clock.instant(),"pending").getFirst().get("excerpt")).isEqualTo("original fallback");
        EvidenceCompression.Result complete=partial;for(int i=0;i<chunks.size()&&complete.state().equals("PARTIAL");i++)complete=compression.process(article,body);
        assertThat(complete.state()).isEqualTo("EXTRACTED");assertThat(complete.excerpt()).startsWith("全文要点");assertThat(Collections.frequency(tasks,"SUMMARIZE")).isEqualTo(chunks.size());
        assertThat(compression.apply(List.of(frozen),f.clock.instant(),"merged").getFirst().get("excerpt")).isEqualTo(complete.excerpt());
        int requests=tasks.size();compression.process(article,body);assertThat(tasks).hasSize(requests);assertThat(compression.find(partial.id())).isEqualTo(partial);
    }
    @Test void explicitNonMimoConfigurationIsRejected() throws Exception {
        var wrong=new RuntimeSettings.AiRoute("compressor-mimo","glm","glm/test","https://localhost/v1","TEST",2000,2000,6000,2,128,1,false);
        f.environment.setProperty("GOLD_AI_COMPRESSION_ROUTE_JSON",f.mapper.writeValueAsString(wrong));
        assertThatThrownBy(f.settings::compressionRoute).hasMessage("COMPRESSION_FIXED_TO_MIMO");
    }
    @Test void endpointChangeCannotReuseSummaryFromThePreviousProviderEndpoint()throws Exception{
        answer("{\"summary\":\"官方事实摘要\"}");var original=compression.process(article,body);
        f.environment.setProperty("GOLD_AI_MIMO_BASE_URL","https://new.example.test/mimo/v1");
        var frozen=Map.<String,Object>of("id","a","bodyHash",body.hash(),"excerpt","original fallback");
        assertThat(compression.apply(List.of(frozen),f.clock.instant(),"new-endpoint").getFirst().get("excerpt")).isEqualTo("original fallback");
        var updated=compression.process(article,body);assertThat(updated.cacheKey()).isNotEqualTo(original.cacheKey());verify(ai,times(2)).compress(any(),anyString(),any());
    }
    @Test void upstreamOutageBacksOffAcrossArticlesInsteadOfSendingEveryPendingDocument() throws Exception {
        f.environment.setProperty("ANY2API_MIMO_API_KEY","test-only");body=body(body.text().repeat(20));
        var second=new NewsService.News("b",article.source(),article.title(),article.excerpt(),article.link(),article.publishedAt(),article.receivedAt(),article.verification(),article.version(),article.contentHash());
        when(news.evidence(any())).thenReturn(List.of(article,second));when(news.bodyForEvidence(eq("b"),any())).thenAnswer(call->body);
        when(ai.compress(any(),anyString(),any())).thenThrow(new IllegalStateException("INFERENCE_HTTP_503_MODEL_UNAVAILABLE"));
        compression.collect();long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
        while(String.valueOf(compression.status().get("retryAt")).isBlank()&&System.nanoTime()<deadline)Thread.sleep(10);
        assertThat(compression.status().get("retryAt")).isEqualTo(f.clock.instant().plusSeconds(60).toString());
        ((java.util.concurrent.Future<?>)org.springframework.test.util.ReflectionTestUtils.getField(compression,"pending")).get(2,java.util.concurrent.TimeUnit.SECONDS);
        compression.collect();verify(ai,times(1)).compress(any(),anyString(),any());
        f.clock.advance(61000);compression.collect();verify(ai,timeout(2000).times(2)).compress(any(),anyString(),any());
    }
    @Test void timeoutBackoffSurvivesRestartEscalatesAndClearsAfterSuccessfulSummary() throws Exception {
        f.environment.setProperty("ANY2API_MIMO_API_KEY","test-only");body=body(body.text().repeat(20));
        when(news.evidence(any())).thenReturn(List.of(article));
        when(ai.compress(any(),anyString(),any())).thenThrow(new java.net.http.HttpTimeoutException("test timeout"));
        compression.collect();awaitPass();
        assertThat(compression.status()).containsEntry("consecutiveFailures",1).containsEntry("error","REQUEST_TIMEOUT")
            .containsEntry("retryAt",f.clock.instant().plusSeconds(60).toString());
        compression.stop();compression=new EvidenceCompression(f.settings,news,ai,f.store,f.mapper,f.clock,new EventHub());
        compression.collect();verify(ai,times(1)).compress(any(),anyString(),any());
        assertThat(compression.status()).containsEntry("consecutiveFailures",1).containsEntry("error","REQUEST_TIMEOUT");
        // The same failed article keeps its own five-minute retry; the global checkpoint still escalates.
        f.clock.advance(301000);compression.collect();awaitPass();
        assertThat(compression.status()).containsEntry("consecutiveFailures",2).containsEntry("retryAt",f.clock.instant().plusSeconds(120).toString());
        f.clock.advance(301000);reset(ai);answer("{\"summary\":\"利率为4.00%，历史值可能修订，不构成预测。\"}");
        compression.collect();awaitPass();
        assertThat(compression.status()).containsEntry("consecutiveFailures",0).containsEntry("retryAt","")
            .containsEntry("error","").containsEntry("lastSuccessfulAt",f.clock.instant());
        compression.stop();compression=new EvidenceCompression(f.settings,news,ai,f.store,f.mapper,f.clock,new EventHub());
        assertThat(compression.status()).containsEntry("consecutiveFailures",0).containsEntry("lastSuccessfulAt",f.clock.instant());
    }
    void awaitPass() throws Exception {
        var pending=(java.util.concurrent.Future<?>)org.springframework.test.util.ReflectionTestUtils.getField(compression,"pending");
        assertThat(pending).isNotNull();pending.get(2,java.util.concurrent.TimeUnit.SECONDS);
    }
    @Test void failedPartialArticleYieldsToFreshEvidenceAfterProviderCooldown() throws Exception {
        f.environment.setProperty("ANY2API_MIMO_API_KEY","test-only");body=body(body.text().repeat(130));
        var second=new NewsService.News("b",article.source(),"另一份官方资料",article.excerpt(),article.link(),article.publishedAt(),article.receivedAt(),article.verification(),article.version(),article.contentHash());
        var shortBody=new NewsService.ArticleBody("b",body.source(),body.link(),"Official rate 4.00. ".repeat(40),body.state(),"short",body.receivedAt(),body.checkedAt(),body.nextPoll(),1,null,false,"feed",null,"HTML");
        when(news.evidence(any())).thenReturn(List.of(article,second));when(news.bodyForEvidence(eq("b"),any())).thenReturn(shortBody);
        when(ai.compress(any(),anyString(),any())).thenAnswer(call->{var packet=(Map<?,?>)call.getArgument(2);
            if(packet.get("title").equals(article.title())&&((Number)packet.get("chunkIndex")).intValue()>0)throw new java.net.http.HttpTimeoutException("test timeout");
            var output=f.mapper.valueToTree(Map.of("summary","利率4.00，保留原口径。"));
            return new Any2ApiClient.Answer("mimo/mimo-v2.6-flash","","NOT_PROVIDED",output.toString(),output,f.mapper.createObjectNode(),"UNKNOWN","stop",null,1);
        });
        compression.collect();awaitPass();var first=f.store.list("compression-result",EvidenceCompression.Result.class,1).getFirst();
        assertThat(first.state()).isEqualTo("PARTIAL");assertThat(first.nextAttempt()).isEqualTo(f.clock.instant().plusSeconds(300));
        f.clock.advance(61000);compression.collect();awaitPass();
        var latest=f.store.list("compression-result",EvidenceCompression.Result.class,1).getFirst();
        assertThat(latest.newsId()).isEqualTo("b");assertThat(latest.state()).isEqualTo("EXTRACTED");
        assertThat(compression.status()).containsEntry("consecutiveFailures",0).containsEntry("retryAt","");
        verify(ai,times(3)).compress(any(),anyString(),any());
    }
    @Test void slowSuccessfulChunksDeferTheNextRequestAndMergeWithoutCreatingUpstreamFailures() throws Exception {
        body=body("Official rate is 4.00 and is not a forecast. ".repeat(200));
        assertThat(EvidenceCompression.chunks(body.text(),"slow",f.settings.compressionRoute())).hasSize(2);
        var monotonic=new java.util.concurrent.atomic.AtomicLong();
        org.springframework.test.util.ReflectionTestUtils.setField(compression,"nanos",(java.util.function.LongSupplier)monotonic::get);
        var tasks=new ArrayList<String>();when(ai.compress(any(),anyString(),any())).thenAnswer(call->{
            var packet=(Map<?,?>)call.getArgument(2);tasks.add(String.valueOf(packet.get("task")));
            var expiry=(Instant)((Map<?,?>)packet.get("evidence")).get("decisionExpiresAt");
            assertThat(Duration.between(f.clock.instant(),expiry)).isEqualTo(Duration.ofSeconds(75));
            monotonic.addAndGet(java.util.concurrent.TimeUnit.SECONDS.toNanos(35));f.clock.advance(35000);
            var output=f.mapper.valueToTree(Map.of("summary","利率4.00，不构成预测。"));
            return new Any2ApiClient.Answer("mimo/mimo-v2.6-flash","","NOT_PROVIDED",output.toString(),output,f.mapper.createObjectNode(),"UNKNOWN","stop",null,35000);
        });
        var first=compression.process(article,body);assertThat(first.state()).isEqualTo("PARTIAL");assertThat(first.processedChunks()).isEqualTo(1);assertThat(first.error()).isNull();
        var second=compression.process(article,body);assertThat(second.state()).isEqualTo("PARTIAL");assertThat(second.processedChunks()).isEqualTo(2);assertThat(second.error()).isNull();
        var merged=compression.process(article,body);assertThat(merged.state()).isEqualTo("EXTRACTED");assertThat(merged.error()).isNull();
        assertThat(tasks).containsExactly("SUMMARIZE","SUMMARIZE","MERGE");verify(ai,times(3)).compress(any(),anyString(),any());
    }
}
