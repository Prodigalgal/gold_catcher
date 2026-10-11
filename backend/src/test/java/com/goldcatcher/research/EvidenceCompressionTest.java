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
        f=new TestSupport();ai=mock(Any2ApiClient.class);news=mock(NewsService.class);when(ai.compressionAvailable(any())).thenReturn(true);
        article=new NewsService.News("a","FED_PRESS","官方事实","摘要","https://www.federalreserve.gov/test",f.clock.instant().minusSeconds(10),f.clock.instant().minusSeconds(5),"OFFICIAL",1,"feed");
        body=body("The rate is 4.00 percent. However, this is not a forecast. A revision may change the prior value. ");
        f.store.save("news","a",article);when(news.textForCompression(eq("a"),any())).thenAnswer(call->NewsService.SourceText.fromBody(body));
        compression=new EvidenceCompression(f.settings,news,ai,f.store,f.mapper,f.clock,new EventHub());
    }
    NewsService.ArticleBody body(String text){return new NewsService.ArticleBody("a","FED_PRESS",article.link(),text,"AVAILABLE",NewsService.hash(text),f.clock.instant().minusSeconds(2),f.clock.instant(),f.clock.instant().plusSeconds(100),1,null,false,"feed",null,"HTML");}
    @AfterEach void stop(){compression.stop();}
    void answer(String output) throws Exception {when(ai.compressionAvailable(any())).thenReturn(true);doAnswer(call->{var parsed=f.mapper.readTree(output);return new Any2ApiClient.Answer("mimo/mimo-v2.6-pro","","NOT_PROVIDED",parsed.toString(),parsed,f.mapper.createObjectNode(),"UNKNOWN","stop",null,1);}).when(ai).compress(any(),anyString(),any());}
    @Test void directSummaryOnlyReplacesExcerptWithoutAddingReferencesAndHonorsFrozenVersion() throws Exception {
        answer("{\"summary\":\"利率为4.00%，此数据不构成预测；历史值可能修订。\"}");Instant before=f.clock.instant().minusMillis(1);var result=compression.process(article,body);
        assertThat(result.state()).isEqualTo("EXTRACTED");assertThat(result.excerpt()).contains("4.00%","不构成预测");assertThat(result.selectedSpans()).isEmpty();
        var frozen=new LinkedHashMap<String,Object>();frozen.put("id","a");frozen.put("bodyHash",body.hash());frozen.put("excerpt","old deterministic");frozen.put("actual","4.00");
        assertThat(compression.apply(List.of(frozen),before,"earlier")).isEmpty();
        var evidence=compression.apply(List.of(frozen),f.clock.instant(),"later").getFirst();
        assertThat(evidence).containsExactlyInAnyOrderEntriesOf(new LinkedHashMap<>(Map.of("id","a","bodyHash",body.hash(),"excerpt",result.excerpt(),"actual","4.00")));
        assertThat(compression.detail(result.id()).toString()).contains("VERIFIED");
        f.clock.advance(10);var repeated=compression.process(article,body);verify(ai,times(1)).compress(any(),anyString(),any());
        assertThat(compression.find(result.id())).isEqualTo(result);assertThat(repeated.excerpt()).isEqualTo(result.excerpt());
        body=body("The rate has since changed. ");frozen.put("bodyHash",body.hash());
        assertThat(compression.apply(List.of(frozen),f.clock.instant(),"revised")).isEmpty();
    }
    @Test void extraTradingOutputAndInvalidSummariesAreOmittedWithoutVoting() throws Exception {
        answer("{\"summary\":\"利率未变\",\"plan\":{\"action\":\"CREATE\"}}");var result=compression.process(article,body);
        assertThat(result.state()).isEqualTo("FAILED");assertThat(result.error()).isEqualTo("INVALID_COMPRESSION_OUTPUT");
        var frozen=Map.<String,Object>of("id","a","bodyHash",body.hash(),"excerpt","official fallback");
        assertThat(compression.apply(List.of(frozen),f.clock.instant(),"failed")).isEmpty();
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
        assertThat(compression.apply(List.of(frozen),f.clock.instant(),"pending")).isEmpty();
        EvidenceCompression.Result complete=partial;for(int i=0;i<chunks.size()&&complete.state().equals("PARTIAL");i++)complete=compression.process(article,body);
        assertThat(complete.state()).isEqualTo("EXTRACTED");assertThat(complete.excerpt()).startsWith("全文要点");assertThat(Collections.frequency(tasks,"SUMMARIZE")).isEqualTo(chunks.size());
        assertThat(compression.apply(List.of(frozen),f.clock.instant(),"merged").getFirst().get("excerpt")).isEqualTo(complete.excerpt());
        int requests=tasks.size();compression.process(article,body);assertThat(tasks).hasSize(requests);assertThat(compression.find(partial.id())).isEqualTo(partial);
    }
    @Test void compressionProviderCanChangeButReservedRoleAndDisabledProviderAreEnforced() throws Exception {
        var wrong=new RuntimeSettings.AiRoute("compressor-mimo","glm","glm/test","https://localhost/v1","TEST",2000,2000,6000,2,128,1,false);
        f.environment.setProperty("GOLD_AI_COMPRESSION_ROUTE_JSON",f.mapper.writeValueAsString(wrong));
        assertThat(f.settings.compressionRoute().provider()).isEqualTo("glm");
        var disabled=new RuntimeSettings.AiRoute("compressor-mimo","qwen","qwen/test","https://localhost/v1","TEST",2000,2000,6000,2,128,1,false);
        f.environment.setProperty("GOLD_AI_COMPRESSION_ROUTE_JSON",f.mapper.writeValueAsString(disabled));
        assertThatThrownBy(f.settings::compressionRoute).hasMessage("AI_PROVIDER_DISABLED");
    }
    @Test void endpointChangeCannotReuseSummaryFromThePreviousProviderEndpoint()throws Exception{
        answer("{\"summary\":\"官方事实摘要\"}");var original=compression.process(article,body);
        f.environment.setProperty("GOLD_AI_MIMO_BASE_URL","https://new.example.test/mimo/v1");
        var frozen=Map.<String,Object>of("id","a","bodyHash",body.hash(),"excerpt","original fallback");
        assertThat(compression.apply(List.of(frozen),f.clock.instant(),"new-endpoint")).isEmpty();
        var updated=compression.process(article,body);assertThat(updated.cacheKey()).isNotEqualTo(original.cacheKey());verify(ai,times(2)).compress(any(),anyString(),any());
    }
    @Test void upstreamOutageBacksOffAcrossArticlesInsteadOfSendingEveryPendingDocument() throws Exception {
        f.environment.setProperty("ANY2API_MIMO_API_KEY","test-only");body=body(body.text().repeat(20));
        var second=new NewsService.News("b",article.source(),article.title(),article.excerpt(),article.link(),article.publishedAt(),article.receivedAt(),article.verification(),article.version(),article.contentHash());
        when(news.selectedEvidence(any(),anyInt())).thenReturn(List.of(article));when(news.compressionCandidates(any())).thenReturn(List.of(article,second));when(news.textForCompression(eq("b"),any())).thenAnswer(call->NewsService.SourceText.fromBody(body));
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
        when(news.selectedEvidence(any(),anyInt())).thenReturn(List.of(article));when(news.compressionCandidates(any())).thenReturn(List.of(article));
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
        when(news.selectedEvidence(any(),anyInt())).thenReturn(List.of(article));when(news.compressionCandidates(any())).thenReturn(List.of(article,second));when(news.textForCompression(eq("b"),any())).thenReturn(NewsService.SourceText.fromBody(shortBody));
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
    @Test void queueReportsEligibleBodiesAndProviderPauseThenCompletesWhenMimoIsFree()throws Exception{
        f.environment.setProperty("ANY2API_MIMO_API_KEY","test-only");body=body(body.text().repeat(15));
        var media=new NewsService.News("media","CNBC_WORLD","Media headline","Feed summary","https://www.cnbc.com/story",
            f.clock.instant().minusSeconds(5),f.clock.instant().minusSeconds(1),"MEDIA_REPORT",1,"media-feed");
        when(news.selectedEvidence(any(),anyInt())).thenReturn(List.of(article));when(news.compressionCandidates(any())).thenReturn(List.of(media,article));when(ai.compressionAvailable(any())).thenReturn(false);
        compression.collect();var paused=f.mapper.valueToTree(compression.status());
        assertThat(paused.path("state").asText()).isEqualTo("PAUSED_PROVIDER_RESEARCH");
        assertThat(paused.path("queue").path("candidateArticles").asInt()).isEqualTo(2);
        assertThat(paused.path("queue").path("eligibleBodies").asInt()).isEqualTo(1);
        assertThat(paused.path("queue").path("missingBodies").asInt()).isEqualTo(1);
        assertThat(paused.path("queue").path("queuedArticles").asInt()).isEqualTo(1);verify(ai,never()).compress(any(),anyString(),any());
        when(ai.compressionAvailable(any())).thenReturn(true);answer("{\"summary\":\"利率为4.00%，不是预测，历史值可能修订。\"}");
        compression.collect();awaitPass();compression.collect();var ready=f.mapper.valueToTree(compression.status());
        assertThat(ready.path("state").asText()).isEqualTo("UP_TO_DATE");assertThat(ready.path("queue").path("completedSummaries").asInt()).isEqualTo(1);
        assertThat(ready.path("queue").path("queuedArticles").asInt()).isZero();verify(ai,times(1)).compress(any(),anyString(),any());
    }
    @Test void preemptionKeepsRawAttemptAndResumesWithoutUpstreamFailureBackoff()throws Exception{
        f.environment.setProperty("ANY2API_MIMO_API_KEY","test-only");body=body(body.text().repeat(15));when(news.selectedEvidence(any(),anyInt())).thenReturn(List.of(article));when(news.compressionCandidates(any())).thenReturn(List.of(article));
        when(ai.compress(any(),anyString(),any())).thenAnswer(call->{Thread.currentThread().interrupt();throw new InterruptedException("research priority");});
        compression.collect();awaitPass();var deferred=f.store.list("compression-result",EvidenceCompression.Result.class,1).getFirst();
        assertThat(deferred.state()).isEqualTo("DEFERRED");assertThat(deferred.error()).isEqualTo("REQUEST_CANCELED");
        assertThat(deferred.nextAttempt()).isEqualTo(f.clock.instant().plusSeconds(10));
        assertThat(compression.detail(deferred.id()).toString()).contains("REQUEST_CANCELED");
        assertThat(compression.status()).containsEntry("consecutiveFailures",0).containsEntry("retryAt","").containsEntry("error","");
        f.clock.advance(11000);answer("{\"summary\":\"利率为4.00%，数据不是预测，历史值可能修订。\"}");compression.collect();awaitPass();
        assertThat(f.store.list("compression-result",EvidenceCompression.Result.class,1).getFirst().state()).isEqualTo("EXTRACTED");
        assertThat(compression.find(deferred.id()).error()).isEqualTo("REQUEST_CANCELED");
    }
    @Test void shortFeedSummaryIsCompressedImmediatelyOnTextArrivalAndNeverPretendsToBeFullBody()throws Exception{
        f.environment.setProperty("ANY2API_MIMO_API_KEY","test-only");
        var text=new NewsService.SourceText("Market headline\nProvider says rates unchanged.","feed-text","FEED_SUMMARY",article.receivedAt(),null,false);
        when(news.textForCompression(eq("a"),any())).thenReturn(text);when(news.compressionCandidates(any())).thenReturn(List.of(article));
        when(news.selectedEvidence(any(),anyInt())).thenReturn(List.of(article));
        when(ai.compress(any(),anyString(),any())).thenAnswer(call->{var packet=f.mapper.valueToTree(call.getArgument(2));
            assertThat(packet.path("sourceFormat").asText()).isEqualTo("FEED_SUMMARY");assertThat(packet.path("text").asText()).isEqualTo(text.text());
            var output=f.mapper.valueToTree(Map.of("summary","RSS摘要称利率未变，未取得全文。"));
            return new Any2ApiClient.Answer("mimo/mimo-v2.6-flash","","NOT_PROVIDED",output.toString(),output,f.mapper.createObjectNode(),"UNKNOWN","stop",null,1);});
        var callback=org.mockito.ArgumentCaptor.forClass(java.util.function.Consumer.class);verify(news).onText(callback.capture());callback.getValue().accept("a");awaitPass();
        var result=f.store.list("compression-result",EvidenceCompression.Result.class,1).getFirst();assertThat(result.sourceCharacters()).isLessThan(600);
        assertThat(result.bodyHash()).isNull();assertThat(result.sourceTextHash()).isEqualTo("feed-text");assertThat(result.sourceFormat()).isEqualTo("FEED_SUMMARY");
        var frozen=new LinkedHashMap<String,Object>();frozen.put("id","a");frozen.put("bodyHash",null);frozen.put("sourceTextHash",text.hash());frozen.put("excerpt",text.text());
        assertThat(compression.apply(List.of(frozen),f.clock.instant(),"short-feed").getFirst().get("excerpt")).isEqualTo(result.excerpt());
    }
    @Test void explicitRecompressionBypassesChunkCacheRetainsPreviousSummaryOnFailureAndArchivesEveryVersion()throws Exception{
        f.environment.setProperty("ANY2API_MIMO_API_KEY","test-only");answer("{\"summary\":\"第一版利率摘要。\"}");var first=compression.process(article,body);
        var frozen=Map.<String,Object>of("id","a","bodyHash",body.hash(),"excerpt","raw must not pass");
        when(ai.compress(any(),anyString(),any())).thenThrow(new IllegalStateException("INVALID_COMPRESSION_OUTPUT"));
        f.clock.advance(1);var failedJob=compression.reprocess("a");awaitPass();var failed=f.store.list("compression-result",EvidenceCompression.Result.class,1).getFirst();
        assertThat(failed.state()).isEqualTo("FAILED");assertThat(failed.generation()).isEqualTo(failedJob.generation());assertThat(failed.id()).isNotEqualTo(first.id());
        assertThat(compression.apply(List.of(frozen),f.clock.instant(),"failed-recompression").getFirst().get("excerpt")).isEqualTo(first.excerpt());
        answer("{\"summary\":\"第二版保留原口径的利率摘要。\"}");f.clock.advance(10);compression.reprocess("a");awaitPass();
        var latest=f.store.list("compression-result",EvidenceCompression.Result.class,1).getFirst();assertThat(latest.state()).isEqualTo("EXTRACTED");
        assertThat(latest.excerpt()).contains("第二版");assertThat(latest.id()).isNotEqualTo(first.id());verify(ai,times(3)).compress(any(),anyString(),any());
        assertThat(compression.find(first.id())).isEqualTo(first);assertThat(compression.find(failed.id())).isEqualTo(failed);
        assertThat(compression.apply(List.of(frozen),first.completedAt(),"before-new-version").getFirst().get("excerpt")).isEqualTo(first.excerpt());
    }
    @Test void changingCompressionProviderDoesNotInheritAnotherProvidersOutageOrUseItsCachedSummary()throws Exception{
        f.environment.setProperty("ANY2API_MIMO_API_KEY","test-only");when(news.compressionCandidates(any())).thenReturn(List.of(article));
        when(ai.compress(any(),anyString(),any())).thenThrow(new java.net.http.HttpTimeoutException("old provider"));compression.collect();awaitPass();
        assertThat(compression.status()).containsEntry("consecutiveFailures",1);
        var replacement=new RuntimeSettings.AiRoute("compressor-mimo","deepseek","deepseek/default","https://localhost/new/v1","TEST_KEY",8000,14000,24000,2,1000,75,false);
        f.environment.setProperty("GOLD_AI_COMPRESSION_ROUTE_JSON",f.mapper.writeValueAsString(replacement));f.environment.setProperty("TEST_KEY","test-new-provider");
        assertThat(compression.status()).containsEntry("provider","deepseek").containsEntry("retryAt","").containsEntry("consecutiveFailures",0);
        f.clock.advance(1);answer("{\"summary\":\"新模型核验后的摘要。\"}");compression.collect();awaitPass();var latest=f.store.list("compression-result",EvidenceCompression.Result.class,1).getFirst();
        assertThat(latest.provider()).isEqualTo("deepseek");assertThat(latest.model()).isEqualTo("deepseek/default");verify(ai,times(2)).compress(any(),anyString(),any());
    }
    @Test void sourceRevisionAfterFreezeCannotReplaceTheArchivedTextOrItsAcceptedSummary()throws Exception{
        answer("{\"summary\":\"冻结版本的4.00%利率摘要。\"}");var first=compression.process(article,body);
        Instant cutoff=f.clock.instant();f.store.save("research-news","frozen-race",Map.of("cutoff",cutoff,"articles",List.of(Map.of("news",article,"sourceText",NewsService.SourceText.fromBody(body)))));
        f.clock.advance(10);var revised=new NewsService.News("a",article.source(),"未来的修订标题","new",article.link(),article.publishedAt(),f.clock.instant(),article.verification(),2,"new-feed");f.store.save("news","a",revised);
        when(news.textForCompression(eq("a"),any())).thenThrow(new AssertionError("Frozen research must use its archived source text"));
        var frozen=Map.<String,Object>of("id","a","bodyHash",body.hash(),"sourceTextHash",body.hash(),"excerpt","archived original","actual","4.00");
        var admitted=compression.apply(List.of(frozen),cutoff,"frozen-race").getFirst();assertThat(admitted).containsEntry("excerpt",first.excerpt()).containsEntry("actual","4.00");
        assertThat(admitted).doesNotContainKey("sourceTextHash");assertThat(f.mapper.valueToTree(compression.status()).path("recent").size()).isEqualTo(1);
    }
}
