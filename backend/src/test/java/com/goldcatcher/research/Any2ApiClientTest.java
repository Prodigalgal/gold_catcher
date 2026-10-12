package com.goldcatcher.research;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.goldcatcher.*;
import com.goldcatcher.RuntimeSettings.AiRoute;
import org.junit.jupiter.api.Test;
class Any2ApiClientTest {
    @Test void minmaxResearchUsesOneSeatWithoutLaunchingANativeTeam()throws Exception{
        var f=new TestSupport();var remote=mock(RemoteHttp.class);f.environment.setProperty("TEST_KEY","test-credential");
        var route=new AiRoute("minmax","minmax","minmax/model","https://localhost/minmax/v1","TEST_KEY",10000,16000,30000,2,1000,300,false);
        var readyCatalog=response(200,"{\"available\":true,\"capabilities\":{\"provider_options\":{\"enable_team\":\"boolean\"}}}");
        when(remote.send(any(),eq(500_000))).thenReturn(readyCatalog);
        String output="protocol=GOLD_RESEARCH_V1\ntitle=等待\noutput=观察\nshortDirection=UNKNOWN\nlongDirection=UNKNOWN\nplan.action=WAIT\n";
        when(remote.send(any(),eq(8_000_000))).thenAnswer(call->{
            var request=(java.net.http.HttpRequest)call.getArgument(0);var bytes=new java.io.ByteArrayOutputStream();
            request.bodyPublisher().orElseThrow().subscribe(new java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer>(){
                public void onSubscribe(java.util.concurrent.Flow.Subscription s){s.request(Long.MAX_VALUE);}public void onNext(java.nio.ByteBuffer b){byte[] chunk=new byte[b.remaining()];b.get(chunk);bytes.writeBytes(chunk);}public void onError(Throwable t){throw new AssertionError(t);}public void onComplete(){}});
            var body=f.mapper.readTree(bytes.toByteArray());assertThat(body.path("provider_options").path("minmax").path("enable_team").asBoolean(true)).isFalse();
            assertThat(body.has("max_tokens")).isFalse();assertThat(body.path("messages").size()).isEqualTo(2);
            return response(200,f.mapper.writeValueAsString(java.util.Map.of("choices",java.util.List.of(java.util.Map.of("finish_reason","stop","message",java.util.Map.of("content",output))))));
        });
        assertThat(new Any2ApiClient(f.settings,remote,f.mapper,f.clock).complete(route,"观察",java.util.Map.of("protocol",ResearchProtocol.VERSION,"phase",0),f.clock.instant().plusSeconds(300)).output().path("plan").path("action").asText()).isEqualTo("WAIT");
    }
    @Test void requestsStreamingAndOnlySubmitsACompletedStream()throws Exception{
        var f=new TestSupport();var remote=mock(RemoteHttp.class);var route=new AiRoute("custom","custom","custom/model","https://localhost/v1","UI",10000,16000,30000,2,1000,300,false);
        f.settings.registerConfiguredRoute(route,"test-credential","OPENAI_COMPATIBLE","MANUAL");var client=new Any2ApiClient(f.settings,remote,f.mapper,f.clock);
        String output="protocol=GOLD_RESEARCH_V1\ntitle=等待\noutput=观察\nshortDirection=UNKNOWN\nlongDirection=UNKNOWN\nplan.action=WAIT\n";
        String delta="data: "+f.mapper.writeValueAsString(java.util.Map.of("choices",java.util.List.of(java.util.Map.of("index",0,"delta",java.util.Map.of("content",output)))))+"\n\n";
        String ending="data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n";
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        when(remote.send(any(),eq(8_000_000))).thenAnswer(call->{
            var request=(java.net.http.HttpRequest)call.getArgument(0);var bytes=new java.io.ByteArrayOutputStream();
            request.bodyPublisher().orElseThrow().subscribe(new java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer>(){
                public void onSubscribe(java.util.concurrent.Flow.Subscription s){s.request(Long.MAX_VALUE);}public void onNext(java.nio.ByteBuffer b){byte[] chunk=new byte[b.remaining()];b.get(chunk);bytes.writeBytes(chunk);}public void onError(Throwable t){throw new AssertionError(t);}public void onComplete(){}});
            assertThat(f.mapper.readTree(bytes.toByteArray()).path("stream").asBoolean()).isTrue();
            assertThat(request.headers().firstValue("Accept")).contains("text/event-stream");
            assertThat(request.timeout().orElseThrow()).isEqualTo(java.time.Duration.ofSeconds(300));
            return response(200,delta+(calls.incrementAndGet()==1?"":ending));
        });
        assertThatThrownBy(()->client.complete(route,"观察",java.util.Map.of("phase",0),f.clock.instant().plusSeconds(300))).hasMessage("STREAM_INCOMPLETE");
        assertThat(client.complete(route,"观察",java.util.Map.of("phase",0),f.clock.instant().plusSeconds(300)).output().path("plan").path("action").asText()).isEqualTo("WAIT");
    }
    @Test void openAny2apiCircuitCanAttemptRecoveryWithoutTreatingItAsConfirmedHealthy()throws Exception{
        var f=new TestSupport();f.environment.setProperty("GOLD_AI_ROUTES_JSON",java.nio.file.Files.readString(java.nio.file.Path.of("../config/ai-routes.fast.example.json")));
        for(var route:f.settings.allRoutes())f.environment.setProperty(route.apiKeyEnv(),"test-only");var remote=mock(RemoteHttp.class);
        String recovering="{\"cataloged\":true,\"available\":false,\"runtime\":{\"circuit_state\":\"OPEN\",\"available_account_count\":2,\"probe_status\":\"READY\"}}";
        when(remote.send(any(),eq(500_000))).thenAnswer(call->{var request=(java.net.http.HttpRequest)call.getArgument(0);return response(200,request.uri().getPath().contains("mimo")?recovering:"{\"available\":true}");});
        var client=new Any2ApiClient(f.settings,remote,f.mapper,f.clock);var selected=client.selectRoutes(f.settings.routes(),java.util.List.of());
        assertThat(selected).hasSize(6);var readiness=f.mapper.valueToTree(client.routeReadiness("SHORT"));
        var checkStates=new java.util.ArrayList<String>();for(var check:readiness.path("checks"))checkStates.add(check.path("state").asText());
        assertThat(checkStates).contains("MODEL_RECOVERY_READY");
        assertThat(client.states().stream().filter(state->state.provider().equals("mimo")).findFirst().orElseThrow().catalog().path("available").asBoolean()).isFalse();
        String output="{\"title\":\"等待\",\"output\":\"等待条件\",\"shortDirection\":\"UNKNOWN\",\"longDirection\":\"UNKNOWN\",\"plan\":{\"action\":\"WAIT\"}}";
        var valid=response(200,f.mapper.writeValueAsString(java.util.Map.of("choices",java.util.List.of(java.util.Map.of("finish_reason","stop","message",java.util.Map.of("content",output))))));
        when(remote.send(any(),eq(8_000_000))).thenReturn(valid);
        var mimo=selected.stream().filter(route->route.provider().equals("mimo")&&!route.judge()).findFirst().orElseThrow();
        assertThat(client.complete(mimo,"冻结证据",java.util.Map.of("phase",0),f.clock.instant().plusSeconds(2)).output().path("plan").path("action").asText()).isEqualTo("WAIT");
        verify(remote,times(1)).send(any(),eq(8_000_000));
    }
    @Test void forcedOpenMissingAccountsAndUnverifiedModelsNeverUseTheRecoveryException()throws Exception{
        for(String blocked:java.util.List.of(
            "{\"cataloged\":true,\"available\":false,\"runtime\":{\"circuit_state\":\"FORCED_OPEN\",\"available_account_count\":2,\"probe_status\":\"READY\"}}",
            "{\"cataloged\":true,\"available\":false,\"runtime\":{\"circuit_state\":\"OPEN\",\"available_account_count\":0,\"probe_status\":\"READY\"}}",
            "{\"cataloged\":true,\"available\":false,\"runtime\":{\"circuit_state\":\"OPEN\",\"available_account_count\":2,\"probe_status\":\"FAILED\"}}",
            "{\"available\":false,\"runtime\":{\"circuit_state\":\"OPEN\",\"available_account_count\":2,\"probe_status\":\"READY\"}}")){
            var f=new TestSupport();f.environment.setProperty("TEST","test-only");var remote=mock(RemoteHttp.class);
            var catalog=response(200,blocked);when(remote.send(any(),eq(500_000))).thenReturn(catalog);var client=new Any2ApiClient(f.settings,remote,f.mapper,f.clock);
            var route=new AiRoute("blocked","mimo","mimo/test","https://localhost/mimo/v1","TEST",10000,16000,30000,2,1000,2,false);
            assertThatThrownBy(()->client.complete(route,"观察",java.util.Map.of(),f.clock.instant().plusSeconds(2))).hasMessage("MODEL_NOT_AVAILABLE");
            verify(remote,never()).send(any(),eq(8_000_000));
        }
    }
    @Test void aResearchRequestPreemptsOnlyItsProvidersCompressorAndReleasesTheLease()throws Exception{
        var f=new TestSupport();f.environment.setProperty("ANY2API_MIMO_API_KEY","test-only");f.environment.setProperty("TEST","test-only");
        var remote=mock(RemoteHttp.class);var catalog=response(200,"{\"available\":true}");when(remote.send(any(),eq(500_000))).thenReturn(catalog);
        var started=new java.util.concurrent.CountDownLatch(1);var compressor=new java.util.concurrent.atomic.AtomicReference<Thread>();
        String output="{\"title\":\"等待\",\"output\":\"观察\",\"shortDirection\":\"UNKNOWN\",\"longDirection\":\"UNKNOWN\",\"plan\":{\"action\":\"WAIT\"}}";
        when(remote.send(any(),eq(8_000_000))).thenAnswer(call->{
            if(Thread.currentThread()==compressor.get()){started.countDown();new java.util.concurrent.CountDownLatch(1).await(10,java.util.concurrent.TimeUnit.SECONDS);throw new AssertionError("compressor was not preempted");}
            return response(200,f.mapper.writeValueAsString(java.util.Map.of("choices",java.util.List.of(java.util.Map.of("finish_reason","stop","message",java.util.Map.of("content",output))))));
        });
        var client=new Any2ApiClient(f.settings,remote,f.mapper,f.clock);var mimo=f.settings.compressionRoute();
        var research=new AiRoute("research","mimo","mimo/pro","https://localhost/mimo/v1","TEST",10000,16000,30000,2,1000,2,false);
        var other=new AiRoute("other","deepseek","deepseek/default","https://localhost/deepseek/v1","TEST",10000,16000,30000,2,1000,2,false);
        try(var workers=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()){
            var pending=workers.submit(()->{compressor.set(Thread.currentThread());try{client.compress(mimo,"摘要",java.util.Map.of("text","官方事实"));return "UNEXPECTED_SUCCESS";}catch(Exception failure){return Any2ApiClient.safeError(failure);}});
            assertThat(started.await(2,java.util.concurrent.TimeUnit.SECONDS)).isTrue();assertThat(client.compressionAvailable(mimo)).isFalse();
            assertThat(client.complete(other,"观察",java.util.Map.of("phase",0),f.clock.instant().plusSeconds(2)).output().path("plan").path("action").asText()).isEqualTo("WAIT");
            assertThat(pending.isDone()).isFalse();
            assertThat(client.complete(research,"观察",java.util.Map.of("phase",0),f.clock.instant().plusSeconds(2)).output().path("plan").path("action").asText()).isEqualTo("WAIT");
            assertThat(pending.get(2,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo("REQUEST_CANCELED");assertThat(client.compressionAvailable(mimo)).isTrue();
        }
    }
    @SuppressWarnings("unchecked") private java.net.http.HttpResponse<byte[]> response(int status,String body){var result=(java.net.http.HttpResponse<byte[]>)mock(java.net.http.HttpResponse.class);when(result.statusCode()).thenReturn(status);when(result.headers()).thenReturn(java.net.http.HttpHeaders.of(java.util.Map.of(),(a,b)->true));when(result.body()).thenReturn(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));return result;}
    @Test void withdrawnProviderNeverReachesCatalogOrInferenceEvenThroughDirectCalls() {
        var f=new TestSupport();f.environment.setProperty("TEST","test-only");var remote=mock(RemoteHttp.class);
        var withdrawn=new AiRoute("withdrawn","QWEN","qwen/old","https://localhost/qwen/v1","TEST",10000,16000,30000,2,1000,2,false);
        var client=new Any2ApiClient(f.settings,remote,f.mapper,f.clock);
        assertThatThrownBy(()->client.complete(withdrawn,"instruction",java.util.Map.of())).hasMessage("AI_PROVIDER_DISABLED");
        assertThatThrownBy(()->client.selectRoutes(java.util.List.of(withdrawn),java.util.List.of())).hasMessage("NO_AVAILABLE_JUDGE_QUORUM");
        verifyNoInteractions(remote);
    }
    @Test void readinessUsesCachedCatalogOnlyAndTrioModelsCanReturnAsDistinctJudges() throws Exception {
        var f=new TestSupport();f.environment.setProperty("TEST","test-only");var remote=mock(RemoteHttp.class);
        var first=new AiRoute("a","a","a/one","https://localhost/a/v1","TEST",10000,16000,30000,2,1000,2,false);
        var second=new AiRoute("b","b","b/two","https://localhost/b/v1","TEST",10000,16000,30000,2,1000,2,false);
        var unavailable=new AiRoute("j","arena","arena/offline","https://localhost/arena/v1","TEST",10000,16000,30000,2,1000,2,true);
        var sharedResearcher=new AiRoute("d","deepseek","deepseek/default","https://localhost/deepseek/v1","TEST",10000,16000,30000,2,1000,2,false);
        var backup=new AiRoute("backup","deepseek","deepseek/default","https://localhost/deepseek/v1","TEST",10000,16000,30000,2,1000,2,true);
        var judge2=new AiRoute("j2","glm","glm/four","https://localhost/glm/v1","TEST",10000,16000,30000,2,1000,2,true);
        var judge3=new AiRoute("j3","grok","grok/five","https://localhost/grok/v1","TEST",10000,16000,30000,2,1000,2,true);
        var preferred=java.util.List.of(first,second,sharedResearcher,unavailable,judge2,judge3);
        f.environment.setProperty("GOLD_AI_ROUTES_JSON",f.mapper.writeValueAsString(preferred));
        f.environment.setProperty("GOLD_AI_FALLBACK_ROUTES_JSON",f.mapper.writeValueAsString(java.util.List.of(backup)));
        var client=new Any2ApiClient(f.settings,remote,f.mapper,f.clock);
        assertThat(client.routeReadiness("LONG")).containsEntry("state","NO_AVAILABLE_JUDGE_QUORUM");verifyNoInteractions(remote);
        when(remote.send(any(),eq(500_000))).thenAnswer(call->{var request=(java.net.http.HttpRequest)call.getArgument(0);
            return response(200,"{\"available\":"+!request.uri().getPath().contains("offline")+"}");});
        client.warmCatalogs();clearInvocations(remote);
        var preview=f.mapper.valueToTree(client.routeReadiness("LONG"));
        assertThat(preview.path("state").asText()).isEqualTo("READY");
        var selectedIds=new java.util.ArrayList<String>();for(var route:preview.path("selected"))selectedIds.add(route.path("routeId").asText());
        var checkStates=new java.util.ArrayList<String>();for(var check:preview.path("checks"))checkStates.add(check.path("state").asText());
        assertThat(selectedIds).containsExactly("a","b","d","j2","j3","backup");
        assertThat(checkStates).contains("MODEL_NOT_AVAILABLE");verifyNoInteractions(remote);
        assertThat(client.selectRoutes(preferred,java.util.List.of(backup))).extracting(AiRoute::id).containsExactly("a","b","d","j2","j3","backup");
        verifyNoInteractions(remote);
        f.clock.advance(300001);assertThat(client.routeReadiness("LONG")).containsEntry("state","NO_AVAILABLE_JUDGE_QUORUM");verifyNoInteractions(remote);
    }
    @Test void availableFallbacksAreFixedDistinctModelsAndEmulatedToolsUseJsonText() throws Exception {
        var f=new TestSupport();f.environment.setProperty("TEST","test-only");var remote=mock(RemoteHttp.class);
        when(remote.send(any(),eq(500_000))).thenAnswer(call->{var request=(java.net.http.HttpRequest)call.getArgument(0);return response(200,"{\"available\":"+!request.uri().getPath().contains("offline")+",\"capabilities\":{\"structured_output\":{\"supported\":true},\"tools\":{\"function_calling\":true,\"strict\":true,\"bridge\":\"emulated_function_bridge\"},\"supported_parameters\":{\"chat_completions\":[\"response_format\"]}}}");});
        var first=new AiRoute("a","a","a/offline","https://localhost/a/v1","TEST",10000,16000,30000,2,2200,2,false);
        var second=new AiRoute("b","b","b/second","https://localhost/b/v1","TEST",10000,16000,30000,2,2200,2,false);
        var judge=new AiRoute("j","j","j/judge","https://localhost/j/v1","TEST",10000,16000,30000,2,2200,2,true);
        var fallback=new AiRoute("c","c","c/third","https://localhost/c/v1","TEST",10000,16000,30000,2,2200,2,false);
        var participatingMimo=new AiRoute("m","mimo","mimo/mimo-v2.6-pro","https://localhost/mimo/v1","TEST",10000,16000,30000,2,2200,2,false);
        var judge2=new AiRoute("j2","mimo","mimo/mimo-v2.6-pro","https://localhost/mimo/v1","TEST",10000,16000,30000,2,2200,2,true);
        var judge3=new AiRoute("j3","b","b/second","https://localhost/b/v1","TEST",10000,16000,30000,2,2200,2,true);
        var client=new Any2ApiClient(f.settings,remote,f.mapper,f.clock);
        assertThat(client.selectRoutes(java.util.List.of(first,second,participatingMimo,judge,judge2,judge3),java.util.List.of(f.settings.compressionRoute(),fallback))).extracting(AiRoute::id).containsExactly("b","m","c","j","j2","j3");
        java.util.List<String> bodies=new java.util.ArrayList<>();
        when(remote.send(any(),eq(8_000_000))).thenAnswer(call->{var request=(java.net.http.HttpRequest)call.getArgument(0);var bytes=new java.io.ByteArrayOutputStream();var publisher=request.bodyPublisher().orElseThrow();
            publisher.subscribe(new java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer>(){public void onSubscribe(java.util.concurrent.Flow.Subscription s){s.request(Long.MAX_VALUE);}public void onNext(java.nio.ByteBuffer b){var chunk=new byte[b.remaining()];b.get(chunk);bytes.writeBytes(chunk);}public void onError(Throwable t){throw new AssertionError(t);}public void onComplete(){}});
            bodies.add(bytes.toString(java.nio.charset.StandardCharsets.UTF_8));if(bodies.size()==1)return response(503,"{\"error\":{\"code\":\"UPSTREAM_BUSY\"}}");
            String output="{\"title\":\"等待\",\"analysisSummary\":\"摘要\",\"output\":\"保持等待\",\"shortDirection\":\"UNKNOWN\",\"longDirection\":\"UNKNOWN\",\"plan\":{\"action\":\"WAIT\"}}";
            return response(200,f.mapper.writeValueAsString(java.util.Map.of("model",second.model(),"choices",java.util.List.of(java.util.Map.of("finish_reason","stop","message",java.util.Map.of("content",output))))));});
        var answer=client.complete(second,"只返回JSON",java.util.Map.of("evidence",java.util.Map.of("decisionExpiresAt",f.clock.instant().plusSeconds(30))));
        assertThat(answer.attempts()).hasSize(2);assertThat(answer.attempts().getFirst().httpStatus()).isEqualTo(503);
        for(var body:bodies){assertThat(f.mapper.readTree(body).has("tools")).isFalse();assertThat(f.mapper.readTree(body).path("response_format").path("type").asText()).isEqualTo("json_object");}
    }
    @Test void anUnavailableMemberCannotReduceTheFixedTrioOrStartInference() throws Exception {
        var f=new TestSupport();f.environment.setProperty("GOLD_AI_ROUTES_JSON",java.nio.file.Files.readString(java.nio.file.Path.of("../config/ai-routes.fast.example.json")));
        for(var route:f.settings.allRoutes())f.environment.setProperty(route.apiKeyEnv(),"test-only");
        var remote=mock(RemoteHttp.class);when(remote.send(any(),eq(500_000))).thenAnswer(call->{
            var request=(java.net.http.HttpRequest)call.getArgument(0);return response(200,"{\"available\":"+!request.uri().getPath().contains("mimo")+"}");});
        var client=new Any2ApiClient(f.settings,remote,f.mapper,f.clock);client.warmCatalogs();
        assertThat(client.routeReadiness("SHORT")).containsEntry("state","NO_AVAILABLE_JUDGE_QUORUM");
        assertThatThrownBy(()->client.selectRoutes(f.settings.routes(),java.util.List.of())).hasMessage("NO_AVAILABLE_JUDGE_QUORUM");
        verify(remote,never()).send(any(),eq(8_000_000));
    }
    @Test void mimoPreprocessingChunksHaveIndependentBudgetsAndCannotSubmitTradingPlans() throws Exception {
        var f=new TestSupport();f.environment.setProperty("ANY2API_MIMO_API_KEY","test-only");var remote=mock(RemoteHttp.class);
        var catalog=response(200,"{\"available\":true,\"capabilities\":{\"provider_options\":{\"thinking\":\"boolean\",\"web_search_status\":\"string\"},\"parameter_adaptation\":{\"search\":{\"mode\":\"mapped\"}},\"supported_parameters\":{\"chat_completions\":[\"response_format\"]}}}");when(remote.send(any(),eq(500_000))).thenReturn(catalog);
        String output="{\"summary\":\"价格4.00未变，不保证未来增长。\"}";
        var selectedResponse=response(200,f.mapper.writeValueAsString(java.util.Map.of("choices",java.util.List.of(java.util.Map.of("finish_reason","stop","message",java.util.Map.of("content",output))))));when(remote.send(any(),eq(8_000_000))).thenReturn(selectedResponse);
        var client=new Any2ApiClient(f.settings,remote,f.mapper,f.clock);var route=f.settings.compressionRoute();
        String text=("价格4.00未变化，不保证未来增长，发布时间仅为官方声明。😄 ").repeat(1200);
        var chunks=EvidenceCompression.chunks(text,"large",route);assertThat(chunks).hasSizeGreaterThan(2);
        for(var chunk:chunks){var packet=java.util.Map.of("task","SUMMARIZE","title","官方资料","chunkIndex",chunk.index(),"totalChunks",chunks.size(),"evidence",java.util.Map.of("decisionExpiresAt",f.clock.instant().plusSeconds(45)),"text",chunk.text());
            var answer=client.compress(route,EvidenceCompression.INSTRUCTION,packet);assertThat(answer.budget().utilizationPercent()).isLessThanOrEqualTo(90);
            assertThat(answer.output().has("plan")).isFalse();assertThat(answer.output().path("summary").asText()).isEqualTo("价格4.00未变，不保证未来增长。");}
        var captured=org.mockito.ArgumentCaptor.forClass(java.net.http.HttpRequest.class);verify(remote,atLeastOnce()).send(captured.capture(),eq(8_000_000));
        for(var request:captured.getAllValues()){
            var bytes=new java.io.ByteArrayOutputStream();request.bodyPublisher().orElseThrow().subscribe(new java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer>(){public void onSubscribe(java.util.concurrent.Flow.Subscription s){s.request(Long.MAX_VALUE);}public void onNext(java.nio.ByteBuffer b){var chunk=new byte[b.remaining()];b.get(chunk);bytes.writeBytes(chunk);}public void onError(Throwable t){throw new AssertionError(t);}public void onComplete(){}});
            var wire=f.mapper.readTree(bytes.toString(java.nio.charset.StandardCharsets.UTF_8));assertThat(wire.has("response_format")).isFalse();assertThat(wire.path("provider_options").size()).isEqualTo(1);
            assertThat(wire.path("provider_options").path("mimo").path("thinking").asBoolean(true)).isFalse();assertThat(wire.path("provider_options").path("mimo").path("web_search_status").asText()).isEqualTo("disabled");}
        String invalid="{\"summary\":\"价格4.00未变\",\"shortDirection\":\"BULLISH\"}";
        var invalidResponse=response(200,f.mapper.writeValueAsString(java.util.Map.of("choices",java.util.List.of(java.util.Map.of("finish_reason","stop","message",java.util.Map.of("content",invalid))))));when(remote.send(any(),eq(8_000_000))).thenReturn(invalidResponse);
        assertThatThrownBy(()->client.compress(route,EvidenceCompression.INSTRUCTION,java.util.Map.of("text",chunks.getFirst().text()))).hasMessage("INVALID_COMPRESSION_OUTPUT");
    }
    @Test void historicalEightFieldBudgetRemainsReadableAfterUpgrade() {
        var f=new TestSupport();var budget=f.mapper.readValue("{\"bytes\":1500,\"characters\":500,\"gatewayInputEstimate\":500,\"messages\":2,\"outputReserve\":1000,\"inputHash\":\"previous\",\"limitSource\":\"CONFIG\",\"outputEnforcement\":\"NOT_SUPPORTED\"}",Any2ApiClient.Budget.class);
        assertThat(budget.preparation()).isEqualTo("LEGACY_NOT_RECORDED");assertThat(budget.limits()).isNull();
        assertThat(budget.characters()).isEqualTo(500);assertThat(budget.utilizationPercent()).isNull();assertThat(budget.changes()).isEmpty();
    }
    @Test void oversizedAccountBlocksHttpAndPreservesAuditableBudget() throws Exception {
        var f=new TestSupport();f.environment.setProperty("TEST_KEY","test-only");var remote=mock(RemoteHttp.class);
        @SuppressWarnings("unchecked") var response=(java.net.http.HttpResponse<byte[]>)mock(java.net.http.HttpResponse.class);
        when(response.statusCode()).thenReturn(200);when(response.body()).thenReturn("{\"available\":true,\"capabilities\":{}}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        when(remote.send(any(),eq(500_000))).thenReturn(response);
        var client=new Any2ApiClient(f.settings,remote,f.mapper,f.clock);
        var route=new AiRoute("a","arena","test","https://localhost/v1","TEST_KEY",2000,2000,6000,2,1000,1,false);
        f.environment.setProperty("GOLD_AI_ROUTES_JSON",f.mapper.writeValueAsString(java.util.List.of(route)));
        var packet=f.mapper.createObjectNode();packet.putObject("evidence").putObject("account").put("unshortenedOrderState","账户状态".repeat(2000));
        assertThatThrownBy(()->client.complete(route,"不能修改的研究指令",packet)).isInstanceOfSatisfying(Any2ApiClient.InferenceFailure.class,failure->{
            assertThat(failure.getMessage()).isEqualTo("CONTEXT_INSUFFICIENT");assertThat(failure.partial().finishReason()).isEqualTo("NOT_SENT");
            assertThat(failure.partial().budget().preparation()).isEqualTo("BLOCKED_BY_BUDGET");
            assertThat(failure.partial().budget().changes()).isEmpty();
            assertThat(f.mapper.writeValueAsString(failure.partial().budget())).contains("\"targetPercent\":90","\"safetyTokens\":512");
            var reread=f.mapper.readValue(f.mapper.writeValueAsString(failure.partial().budget()),Any2ApiClient.Budget.class);
            assertThat(reread.limits().safetyTokens()).isEqualTo(512);assertThat(reread.preparation()).isEqualTo("BLOCKED_BY_BUDGET");
        });
        verify(remote,never()).send(any(),eq(8_000_000));
        assertThat(client.states().getFirst().lastBudget().preparation()).isEqualTo("BLOCKED_BY_BUDGET");
        assertThat(client.states().getFirst().lastInference()).isEqualTo("FAILED");
    }
    @Test void countsUtf8AndEnforcesIndependentLimitsWithoutInventingTokens() {
        var f=new TestSupport();var client=new Any2ApiClient(f.settings,new RemoteHttp(),f.mapper,f.clock);
        var route=new AiRoute("a","arena","arena/test","https://localhost/arena/v1","TEST_KEY",600,1500,1800,2,128,1,false);
        var empty=f.mapper.createObjectNode();var budget=client.verify(route,"金".repeat(500),empty,false);
        assertThat(budget.bytes()).isEqualTo(1500);assertThat(budget.characters()).isEqualTo(500);assertThat(budget.gatewayInputEstimate()).isEqualTo(500);
        assertThat(budget.limitSource()).isEqualTo("CONFIG_CONSERVATIVE_UNKNOWN_CAPACITY");assertThat(budget.outputEnforcement()).isEqualTo("NOT_SUPPORTED");
        assertThatThrownBy(()->client.verify(route,"金".repeat(601),empty,false)).hasMessage("CONTEXT_INSUFFICIENT");
        assertThatThrownBy(()->client.verify(route,"a".repeat(1501),empty,false)).hasMessage("CONTEXT_INSUFFICIENT");
        assertThatThrownBy(()->client.verify(route,"金".repeat(400),f.mapper.readTree("{\"capabilities\":{\"max_context_tokens\":1000}}"),false)).hasMessage("CONTEXT_INSUFFICIENT");
        assertThatThrownBy(()->client.verify(route,"a".repeat(101),f.mapper.readTree("{\"parameter_adaptation\":{\"input\":{\"mode\":\"declared_character_limit\",\"limit\":100}}}"),false)).hasMessage("CONTEXT_INSUFFICIENT");
    }
    @Test void rejectsAmbiguousOutputInsteadOfRepairingOrInventingIt() {
        var f=new TestSupport();var client=new Any2ApiClient(f.settings,new RemoteHttp(),f.mapper,f.clock);
        assertThat(client.parseOutput("```json\n{\"ok\":true}\n```").path("ok").asBoolean()).isTrue();
        assertThatThrownBy(()->client.parseOutput("Some explanation then {\"ok\":true}"));
        assertThatThrownBy(()->client.parseOutput("[1,2]"));
    }
    @Test void allowsEveryReferenceInTheFourNewsSixCalendarAndFourAnchorPacket() {
        var f=new TestSupport();var client=new Any2ApiClient(f.settings,new RemoteHttp(),f.mapper,f.clock);
        var output=f.mapper.createObjectNode();output.put("title","等待确认");output.put("output","根据冻结证据等待");
        output.put("shortDirection","UNKNOWN");output.put("longDirection","UNKNOWN");
        var refs=output.putArray("evidenceRefs");
        for(String id:java.util.List.of("market:quote","market:rules","account","indicators"))refs.add(id);
        for(int i=0;i<4;i++)refs.add("news-"+i);
        for(int i=0;i<6;i++)refs.add("calendar-"+i);
        assertThatCode(()->client.validate(output)).doesNotThrowAnyException();
        refs.add("extra");assertThatThrownBy(()->client.validate(output)).hasMessage("INVALID_EVIDENCE_ARRAY");
    }
    @Test void researchDeadlineBoundsOneHttpAttemptAndPreservesRateLimitRetryAfter()throws Exception{
        var f=new TestSupport();var remote=mock(RemoteHttp.class);var route=new AiRoute("custom","custom","custom/model","https://localhost/v1","UI",10000,16000,30000,2,1000,180,false);
        f.settings.registerConfiguredRoute(route,"test-credential","OPENAI_COMPATIBLE","MANUAL");var client=new Any2ApiClient(f.settings,remote,f.mapper,f.clock);
        var limited=response(429,"{\"error\":{\"code\":\"RATE_LIMITED\"}}");when(limited.headers()).thenReturn(java.net.http.HttpHeaders.of(java.util.Map.of("Retry-After",java.util.List.of("7")),(a,b)->true));
        when(remote.send(any(),eq(8_000_000))).thenAnswer(call->{var request=(java.net.http.HttpRequest)call.getArgument(0);assertThat(request.timeout().orElseThrow()).isEqualTo(java.time.Duration.ofSeconds(2));return limited;});
        assertThatThrownBy(()->client.complete(route,"观察",java.util.Map.of("phase",0,"evidence",java.util.Map.of("decisionExpiresAt",f.clock.instant().minusSeconds(1))),f.clock.instant().plusSeconds(2)))
            .isInstanceOfSatisfying(Any2ApiClient.InferenceFailure.class,error->{assertThat(error.getMessage()).isEqualTo("INFERENCE_HTTP_429_RATE_LIMITED");assertThat(error.retryAfterMillis()).isEqualTo(7000);assertThat(error.partial().attempts()).hasSize(1);});
        verify(remote,times(1)).send(any(),eq(8_000_000));
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void nativeAndEmulatedAdaptersUseOneLogicalProtocolWithoutForcingTextModelsToWriteJson(boolean nativeTool)throws Exception{
        var f=new TestSupport();f.environment.setProperty("TEST","test-only");var remote=mock(RemoteHttp.class);var route=new AiRoute("vendor","vendor","vendor/model","https://localhost/vendor/v1","TEST",10000,16000,30000,2,1000,2,true);
        var catalog=response(200,"{\"available\":true,\"capabilities\":{\"structured_output\":true,\"tools\":{\"function_calling\":true,\"strict\":true,\"bridge\":\""+(nativeTool?"native":"emulated_function_bridge")+"\"},\"supported_parameters\":{\"chat_completions\":[\"response_format\"]}}}");when(remote.send(any(),eq(500_000))).thenReturn(catalog);
        var canonical=f.mapper.readTree("{\"title\":\"裁决\",\"output\":\"等待确认\",\"shortDirection\":\"UNKNOWN\",\"longDirection\":\"MIXED\",\"selectedCandidateId\":\"WAIT\",\"quotedMessages\":[],\"evidenceRefs\":[],\"impacts\":[]}");
        when(remote.send(any(),eq(8_000_000))).thenAnswer(call->{
            var request=(java.net.http.HttpRequest)call.getArgument(0);var bytes=new java.io.ByteArrayOutputStream();request.bodyPublisher().orElseThrow().subscribe(new java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer>(){public void onSubscribe(java.util.concurrent.Flow.Subscription s){s.request(Long.MAX_VALUE);}public void onNext(java.nio.ByteBuffer b){var chunk=new byte[b.remaining()];b.get(chunk);bytes.writeBytes(chunk);}public void onError(Throwable t){throw new AssertionError(t);}public void onComplete(){}});
            var wire=f.mapper.readTree(bytes.toByteArray());assertThat(wire.has("response_format")).isFalse();assertThat(wire.has("tools")).isEqualTo(nativeTool);
            assertThat(wire.path("messages").path(0).path("content").asText()).contains(nativeTool?"只调用 submit_research":"每行一个 key=value");
            var message=f.mapper.createObjectNode();if(nativeTool){var function=message.putArray("tool_calls").addObject().putObject("function");function.put("name","submit_research").put("arguments",canonical.toString());}
            else message.put("content","protocol=GOLD_RESEARCH_V1\ntitle=裁决\noutput=等待确认\nshortDirection=UNKNOWN\nlongDirection=MIXED\nselectedCandidateId=WAIT\n");
            return response(200,f.mapper.writeValueAsString(java.util.Map.of("choices",java.util.List.of(java.util.Map.of("finish_reason",nativeTool?"tool_calls":"stop","message",message)))));
        });
        var client=new Any2ApiClient(f.settings,remote,f.mapper,f.clock);var packet=java.util.Map.of("protocol",ResearchProtocol.VERSION,"phase",3,"evidence",java.util.Map.of());
        assertThat(client.complete(route,"冻结证据。",packet,f.clock.instant().plusSeconds(2)).output()).isEqualTo(canonical);
    }
    @Test void oversizedLocalPacketDoesNotCoolDownAProviderThatWasNeverCalled()throws Exception{
        var f=new TestSupport();var remote=mock(RemoteHttp.class);var routes=new java.util.ArrayList<AiRoute>();
        for(String provider:java.util.List.of("aa","bb","cc"))for(boolean judge:java.util.List.of(false,true)){
            var route=new AiRoute(provider+(judge?"-judge":"-research"),provider,provider+"/model-"+provider,"https://localhost/"+provider+"/v1","UI",10000,16000,30000,2,1000,2,judge);routes.add(route);
            f.settings.registerConfiguredRoute(route,"test-credential","OPENAI_COMPATIBLE","MANUAL");
        }
        var client=new Any2ApiClient(f.settings,remote,f.mapper,f.clock);
        assertThatThrownBy(()->client.complete(routes.getFirst(),"冻结证据",java.util.Map.of("protectedFinancialData","1".repeat(40000)),f.clock.instant().plusSeconds(2)))
            .hasMessage("CONTEXT_INSUFFICIENT");
        assertThat(client.selectRoutes(routes,java.util.List.of())).hasSize(6);verifyNoInteractions(remote);
    }
    @Test void transportTimeoutReleasesProviderLeaseForTheNextBoundedResearchAttempt()throws Exception{
        var f=new TestSupport();var remote=mock(RemoteHttp.class);var route=new AiRoute("custom","custom","custom/model","https://localhost/v1","UI",10000,16000,30000,2,1000,180,false);
        f.settings.registerConfiguredRoute(route,"test-credential","OPENAI_COMPATIBLE","MANUAL");var client=new Any2ApiClient(f.settings,remote,f.mapper,f.clock);
        String output="{\"title\":\"等待\",\"output\":\"观察\",\"shortDirection\":\"UNKNOWN\",\"longDirection\":\"UNKNOWN\",\"plan\":{\"action\":\"WAIT\"}}";
        var valid=response(200,f.mapper.writeValueAsString(java.util.Map.of("choices",java.util.List.of(java.util.Map.of("finish_reason","stop","message",java.util.Map.of("content",output))))));
        when(remote.send(any(),eq(8_000_000))).thenThrow(new java.net.http.HttpTimeoutException("dummy")).thenReturn(valid);
        var packet=java.util.Map.of("phase",0,"evidence",java.util.Map.of());
        assertThatThrownBy(()->client.complete(route,"观察",packet,f.clock.instant().plusSeconds(2))).hasMessage("REQUEST_TIMEOUT");
        assertThat(client.complete(route,"观察",packet,f.clock.instant().plusSeconds(2)).output().path("plan").path("action").asText()).isEqualTo("WAIT");
        verify(remote,times(2)).send(any(),eq(8_000_000));
    }
}
