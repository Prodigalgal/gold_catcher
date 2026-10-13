package com.goldcatcher.research;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.goldcatcher.*;
import com.goldcatcher.information.NewsService;
import com.goldcatcher.market.*;
import static com.goldcatcher.market.MarketTypes.*;
import com.goldcatcher.simulation.SimulationLedger;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.JsonNode;
class ResearchServiceTest {
    @Test void slowDeepAdmissionDoesNotBlockAutomaticShortResearch()throws Exception{
        preparedScheduler();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(ai.selectRoutes(anyList(),anyList(),anyString())).thenAnswer(call->{if(call.getArgument(2).equals("LONG")){entered.countDown();release.await(5,TimeUnit.SECONDS);}return call.getArgument(0);});
        when(ai.complete(any(),anyString(),any(),any(Instant.class))).thenAnswer(call->waiting(call.getArgument(0),f.mapper.valueToTree(call.getArgument(2))));
        research.automatic();try{assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(research.history().stream().noneMatch(r->r.horizon.equals("SHORT"))&&System.nanoTime()<until)Thread.sleep(10);
            var fast=research.history().stream().filter(r->r.horizon.equals("SHORT")).findFirst().orElseThrow();assertThat(await(fast.id).status).isEqualTo("COMPLETED");
            assertThat(((Number)research.scheduling().get("pendingStarts")).intValue()).isEqualTo(1);
        }finally{release.countDown();}
    }
    @Test void internalReplayCoversThreeJudgeDecisionLimitFillFeesProfitAndRecovery()throws Exception{
        research.stop();f.environment.setProperty("GOLD_TAKER_FEE_RATE","0.001");
        var original=market.snapshot("XAU_USDT");
        var replayRules=new Instrument("XAU_USDT","Trading","USDT",d(".01"),d(".001"),d(".001"),d("1000"),d("1000"),d("1"),d("1"),d("100"),d(".01"),List.of(new RiskTier(d("1000000"),d("100"),d(".005"),d("0"))),f.clock.instant());
        when(market.snapshot("XAU_USDT")).thenReturn(new Snapshot("XAU_USDT","LIVE",original.quote(),replayRules,Map.of(),List.of(),f.clock.instant(),null));when(market.instrument()).thenReturn(replayRules);when(market.latestQuote()).thenReturn(original.quote());
        ledger=new SimulationLedger(f.store,market,f.settings,new EventHub(),f.clock);org.springframework.test.util.ReflectionTestUtils.invokeMethod(ledger,"initialize");
        research=new ResearchService(f.settings,f.store,market,new NewsService(f.settings,new RemoteHttp(),f.store,new EventHub(),f.clock),ledger,ai,f.mapper,new EventHub(),f.clock);
        when(ai.complete(any(),anyString(),any(),any(Instant.class))).thenAnswer(call->{
            var route=(RuntimeSettings.AiRoute)call.getArgument(0);var packet=f.mapper.valueToTree(call.getArgument(2));
            var output=f.mapper.createObjectNode().put("title","内部回放").put("output","只验证固定历史情景").put("shortDirection","BULLISH").put("longDirection","UNKNOWN");
            output.putArray("quotedMessages");output.putArray("evidenceRefs");output.putArray("impacts");
            if(packet.path("phase").asInt()==3)output.put("selectedCandidateId",packet.path("peers").path(0).path("id").asText());
            else output.putObject("plan").put("action","CREATE").put("side","BUY").put("type","LIMIT").put("quantity","1").put("leverage","10").put("entryPrice","100").put("stopLoss","99").put("takeProfit","103")
                .put("expiresAt",f.clock.instant().plusSeconds(120).toString()).put("holdUntil",f.clock.instant().plusSeconds(600).toString()).put("riskAssessment","回放固定风险情景").put("invalidate","仅对本次固定回放有效");
            return new Any2ApiClient.Answer(route.model(),"","NOT_PROVIDED",output.toString(),output,f.mapper.createObjectNode(),"UNKNOWN","stop",null,1);
        });
        var completed=await(research.start("XAU_USDT","SHORT").id);assertThat(completed.status).as("failure=%s",completed.error).isEqualTo("COMPLETED");assertThat(completed.adjudication.path("validJudges").asInt()).isEqualTo(3);assertThat(completed.orderId).isNotNull();
        f.clock.advance(300);var entry=new Quote("XAU_USDT","REPLAY",2,f.clock.instant(),f.clock.instant(),d("99.99"),d("100"),d("2"),d("2"),d("100"),d("100"),f.clock.instant(),d("0"),original.quote().nextFundingTime(),true);ledger.acceptQuote(entry);
        f.clock.advance(200);var exit=new Quote("XAU_USDT","REPLAY",3,f.clock.instant(),f.clock.instant(),d("103"),d("103.01"),d("2"),d("2"),d("103"),d("103"),f.clock.instant(),d("0"),original.quote().nextFundingTime(),true);ledger.acceptQuote(exit);
        var account=ledger.view("XAU_USDT",exit);var order=account.orders().getFirst();
        assertThat(order.filled).isEqualByComparingTo("1");assertThat(order.openQuantity).isZero();assertThat(order.entryFees).isEqualByComparingTo("0.1");assertThat(order.exitFees).isEqualByComparingTo("0.103");assertThat(order.netRealized).isEqualByComparingTo("2.797");
        var recovered=new SimulationLedger(f.store,market,f.settings,new EventHub(),f.clock);org.springframework.test.util.ReflectionTestUtils.invokeMethod(recovered,"initialize");assertThat(recovered.view("XAU_USDT",exit).balance()).isEqualTo(account.balance());
        assertThat(recovered.propose(completed.id,"XAU_USDT",completed.verdict.path("plan")).id).isEqualTo(completed.orderId);
    }
    @Test void shortRoundPastHardDeadlineCannotContinueOrCreateAnOrder()throws Exception{
        var advanced=new java.util.concurrent.atomic.AtomicBoolean();when(ai.complete(any(),anyString(),any(),any(Instant.class))).thenAnswer(call->{
            if(advanced.compareAndSet(false,true))f.clock.advance(361000);return waiting(call.getArgument(0),f.mapper.valueToTree(call.getArgument(2)));
        });
        var saved=await(research.start("XAU_USDT","SHORT").id);assertThat(saved.status).isEqualTo("EXECUTION_TIMED_OUT");assertThat(saved.error).isEqualTo("WORKFLOW_DEADLINE");
        assertThat(saved.messages).allMatch(message->message.stage()==0);verify(ledger,never()).propose(anyString(),anyString(),any());
    }
    @Test void shortRoundCanCompleteWhileSameProductDeepRoundRemainsBlocked()throws Exception{
        var deepEntered=new CountDownLatch(1);var releaseDeep=new CountDownLatch(1);
        when(ai.complete(any(),anyString(),any(),any(Instant.class))).thenAnswer(call->{
            var packet=f.mapper.valueToTree(call.getArgument(2));var route=(RuntimeSettings.AiRoute)call.getArgument(0);
            if(packet.path("lane").asText().equals("deep_lane")&&packet.path("phase").asInt()==0){deepEntered.countDown();releaseDeep.await(6,TimeUnit.SECONDS);}
            var output=f.mapper.createObjectNode().put("title","等待").put("output","观察").put("shortDirection","UNKNOWN").put("longDirection","UNKNOWN");
            output.putArray("evidenceRefs");output.putArray("quotedMessages");output.putArray("impacts");
            if(packet.path("phase").asInt()==3)output.put("selectedCandidateId","WAIT");else output.putObject("plan").put("action","WAIT");
            return new Any2ApiClient.Answer(route.model(),"","NOT_PROVIDED",output.toString(),output,f.mapper.createObjectNode(),"UNKNOWN","stop",null,1);
        });
        var deep=research.start("XAU_USDT","LONG");assertThat(deepEntered.await(2,TimeUnit.SECONDS)).isTrue();
        try{
            var fast=research.start("XAU_USDT","SHORT");var completed=await(fast.id);
            assertThat(completed.status).isEqualTo("COMPLETED");assertThat(completed.executionLane).isEqualTo("fast_lane");
            assertThat(completed.adjudication.path("validJudges").asInt()).isEqualTo(3);
            assertThat(research.find(deep.id).status).isEqualTo("RUNNING");
            assertThatThrownBy(()->research.start("XAU_USDT","LONG")).hasMessage("RESEARCH_ALREADY_RUNNING");
            assertThat(Duration.between(completed.startedAt,completed.workflowExpiresAt).toSeconds()).isEqualTo(360);
        }finally{releaseDeep.countDown();}
        assertThat(await(deep.id).status).isEqualTo("COMPLETED");
    }
    private void automaticAndAdmit()throws Exception{
        research.automatic();long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(((Number)research.scheduling().get("pendingStarts")).intValue()>0&&System.nanoTime()<until)Thread.sleep(10);
        assertThat(((Number)research.scheduling().get("pendingStarts")).intValue()).isZero();
    }
    TestSupport f;Any2ApiClient ai;ResearchService research;SimulationLedger ledger;MarketFeed market;
    @BeforeEach void setup() {
        f=new TestSupport();market=mock(MarketFeed.class);ledger=mock(SimulationLedger.class);ai=mock(Any2ApiClient.class);
        var routes=new ArrayList<RuntimeSettings.AiRoute>();for(String id:List.of("p","q","r","j","k","l")){
            String provider=Map.of("j","p","k","q","l","r").getOrDefault(id,id);
            routes.add(new RuntimeSettings.AiRoute(id,provider,provider+"/"+provider+"-model","https://localhost/"+provider+"/v1","TEST_KEY",10000,14000,30000,2,1000,2,List.of("j","k","l").contains(id)));}
        f.environment.setProperty("GOLD_AI_ROUTES_JSON",f.mapper.writeValueAsString(routes));f.environment.setProperty("GOLD_SHORT_STAGE_SECONDS","1");f.environment.setProperty("GOLD_JUDGE_STAGE_SECONDS","1");
        when(ai.selectRoutes(anyList(),anyList(),anyString())).thenAnswer(call->call.getArgument(0));
        when(ai.routeReadiness(anyString())).thenAnswer(call->Map.of("horizon",call.getArgument(0),"state","READY"));
        var quote=new Quote("XAU_USDT","TEST",1,f.clock.instant(),f.clock.instant(),d("100"),d("101"),d("1"),d("1"),d("100"),d("100"),f.clock.instant(),d("0"),f.clock.instant().plusSeconds(3600),true);
        var rules=new Instrument("XAU_USDT","Trading","USDT",d(".01"),d(".001"),d(".001"),d("1000"),d("1000"),d("1"),d("1"),d("100"),d(".01"),List.of(),f.clock.instant());
        when(market.snapshot("XAU_USDT")).thenReturn(new Snapshot("XAU_USDT","LIVE",quote,rules,Map.of(),List.of(),f.clock.instant(),null));
        when(ledger.view("XAU_USDT",quote)).thenReturn(new SimulationLedger.View("XAU_USDT","USDT","VALUED","1000","1000","1000","0","0","0",f.clock.instant(),List.of(),"UNKNOWN","TEST"));
        research=new ResearchService(f.settings,f.store,market,new NewsService(f.settings,new RemoteHttp(),f.store,new EventHub(),f.clock),ledger,ai,f.mapper,new EventHub(),f.clock);
    }
    @AfterEach void cleanup(){research.stop();}
    @Test void sealedBatchesShareEvidenceExcludeLateOutputAndPersistVerdict() throws Exception {
        List<JsonNode> packets=Collections.synchronizedList(new ArrayList<>());
        when(ai.complete(any(),anyString(),any(),any(java.time.Instant.class))).thenAnswer(call->{
            var route=(RuntimeSettings.AiRoute)call.getArgument(0);JsonNode packet=f.mapper.valueToTree(call.getArgument(2));packets.add(packet);
            if(route.id().equals("r")&&packet.path("phase").asInt()==0)Thread.sleep(1500);
            var output=f.mapper.createObjectNode().put("title","观察").put("analysisSummary","摘要").put("output","保持等待").put("shortDirection","UNKNOWN").put("longDirection","UNKNOWN");
            var quotes=output.putArray("quotedMessages");for(var peer:packet.path("peers"))quotes.add(peer.path("id").asText());
            output.putObject("plan").put("action","WAIT");output.putArray("impacts");output.putArray("evidenceRefs");
            if(packet.path("phase").asInt()==3){output.remove("plan");output.put("selectedCandidateId","WAIT");}
            return new Any2ApiClient.Answer(route.model(),"摘要","EXPLICIT_OUTPUT_SUMMARY",output.toString(),output,f.mapper.createObjectNode(),"UNKNOWN","stop",
                new Any2ApiClient.Budget(100,100,34,2,1000,"hash","CONFIG","NOT_SUPPORTED"),1);
        });
        var run=research.start("XAU_USDT","SHORT");
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        while(research.find(run.id).status.equals("RUNNING")&&System.nanoTime()<deadline)Thread.sleep(20);
        while(research.find(run.id).messages.stream().anyMatch(m->m.state().equals("TIMED_OUT"))&&System.nanoTime()<deadline)Thread.sleep(20);
        var saved=research.find(run.id);assertThat(saved.status).isEqualTo("COMPLETED");assertThat(saved.messages).hasSize(12);
        assertThat(saved.modelDiversity.get("researchModels")).isEqualTo(List.of("p-model","q-model","r-model"));
        assertThat((List<String>)saved.modelDiversity.get("judgeModels")).containsExactly("p-model","q-model","r-model");
        assertThat((List<String>)saved.modelDiversity.get("sharedResearchAndJudgeModels")).containsExactly("p-model","q-model","r-model");
        assertThat(saved.adjudication.path("validJudges").asInt()).isEqualTo(3);
        assertThat(packets.stream().filter(packet->packet.path("phase").asInt()==3)
            .map(packet->packet.path("peers").path(0).path("id").asText()).distinct()).hasSize(3);
        assertThat(saved.messages.stream().filter(m->m.stage()==0&&m.state().equals("LATE_ARCHIVED"))).hasSize(1);
        assertThat(packets.stream().map(p->f.mapper.writeValueAsString(p.path("evidence"))).distinct()).containsExactly(f.mapper.writeValueAsString(saved.evidence));
        assertThat(saved.evidenceHash).isEqualTo(com.goldcatcher.information.NewsService.hash(f.mapper.writeValueAsString(saved.evidence)));
        for(var packet:packets) {
            int phase=packet.path("phase").asInt();if(phase==0)assertThat(packet.path("peers")).isEmpty();
            else for(var peer:packet.path("peers"))assertThat(peer.path("id").asText().split("-")[5]).isEqualTo(Integer.toString(phase-1));
            if(phase==1)assertThat(packet.path("peers")).hasSize(2);
            if(phase==3){assertThat(packet.path("peers")).hasSize(3);for(var peer:packet.path("peers"))assertThat(peer.path("actor").asText()).doesNotStartWith("J");}
        }
        verify(ledger,times(1)).propose(eq(run.id),eq("XAU_USDT"),any());
    }
    @Test void insufficientValidModelsNeverCreatesAnOrder() throws Exception {
        when(ai.complete(any(),anyString(),any(),any(java.time.Instant.class))).thenThrow(new IllegalStateException("MODEL_NOT_AVAILABLE"));
        var run=research.start("XAU_USDT","SHORT");long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
        while(research.find(run.id).status.equals("RUNNING")&&System.nanoTime()<deadline)Thread.sleep(20);
        assertThat(research.find(run.id).status).isEqualTo("LOW_QUORUM");verify(ledger,never()).propose(anyString(),anyString(),any());
    }
    @Test void duplicateUnderlyingModelAcrossProvidersCannotCreateExtraVotes(){
        var routes=List.of(new RuntimeSettings.AiRoute("one","vendor-a","vendor-a/Same-Model","https://localhost/v1","TEST",10000,16000,30000,2,1000,2,false),
            new RuntimeSettings.AiRoute("two","vendor-b","vendor-b/same-model","https://localhost/v1","TEST",10000,16000,30000,2,1000,2,false),
            new RuntimeSettings.AiRoute("judge","vendor-c","different-model","https://localhost/v1","TEST",10000,16000,30000,2,1000,2,true));
        f.environment.setProperty("GOLD_AI_ROUTES_JSON",f.mapper.writeValueAsString(routes));
        assertThatThrownBy(()->research.start("XAU_USDT","SHORT")).hasMessage("DUPLICATE_RESEARCH_MODEL");verifyNoInteractions(ai);
    }
    @Test void configuredTrioCanJudgeInFreshContextsAfterResearch(){
        var routes=List.of(new RuntimeSettings.AiRoute("one","a","a/first","https://localhost/v1","TEST",10000,16000,30000,2,1000,2,false),
            new RuntimeSettings.AiRoute("two","b","b/second","https://localhost/v1","TEST",10000,16000,30000,2,1000,2,false),
            new RuntimeSettings.AiRoute("three","c","c/third","https://localhost/v1","TEST",10000,16000,30000,2,1000,2,false),
            new RuntimeSettings.AiRoute("judge","c","c/FIRST","https://localhost/v1","TEST",10000,16000,30000,2,1000,2,true),
            new RuntimeSettings.AiRoute("judge2","d","d/fourth","https://localhost/v1","TEST",10000,16000,30000,2,1000,2,true),
            new RuntimeSettings.AiRoute("judge3","e","e/fifth","https://localhost/v1","TEST",10000,16000,30000,2,1000,2,true));
        f.environment.setProperty("GOLD_AI_ROUTES_JSON",f.mapper.writeValueAsString(routes));
        assertThat(ResearchService.configuredRouteError(routes)).isNull();verifyNoInteractions(ai);
    }
    @Test void twoSuccessfulJudgesCannotReachTheLedger() throws Exception {
        when(ai.complete(any(),anyString(),any(),any(java.time.Instant.class))).thenAnswer(call->{
            var route=(RuntimeSettings.AiRoute)call.getArgument(0);JsonNode packet=f.mapper.valueToTree(call.getArgument(2));
            if(route.id().equals("l"))throw new IllegalStateException("REQUEST_TIMEOUT");
            var output=f.mapper.createObjectNode().put("title","观察").put("output","等待").put("shortDirection","UNKNOWN").put("longDirection","UNKNOWN");
            if(packet.path("phase").asInt()==3)output.put("selectedCandidateId","WAIT");else output.putObject("plan").put("action","WAIT");
            return new Any2ApiClient.Answer(route.model(),"摘要","EXPLICIT_OUTPUT_SUMMARY",output.toString(),output,f.mapper.createObjectNode(),"UNKNOWN","stop",null,1);
        });
        var run=research.start("XAU_USDT","SHORT");long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
        while(research.find(run.id).status.equals("RUNNING")&&System.nanoTime()<deadline)Thread.sleep(20);
        var saved=research.find(run.id);assertThat(saved.stage).isEqualTo(3);assertThat(saved.status).isEqualTo("LOW_QUORUM");
        assertThat(saved.error).isEqualTo("INSUFFICIENT_VALID_JUDGE_OUTPUTS");verify(ledger,never()).propose(anyString(),anyString(),any());
    }
    @Test void fewerThanThreeConfiguredJudgesCannotStart() {
        var routes=f.settings.routes().stream().filter(route->!route.id().equals("l")).toList();
        f.environment.setProperty("GOLD_AI_ROUTES_JSON",f.mapper.writeValueAsString(routes));
        assertThatThrownBy(()->research.start("XAU_USDT","SHORT")).hasMessage("THREE_DISTINCT_JUDGES_REQUIRED");verifyNoInteractions(ai);
    }
    @Test void calendarSelectionPreservesDateRangesAndExcludesEventsOutsideTradingHorizon(){
        var near=new NewsService.CalendarEvent("a","BLS","CPI",f.clock.instant().plusSeconds(3600),"America/New_York","https://www.bls.gov",f.clock.instant());
        var far=new NewsService.CalendarEvent("b","BLS","CPI",f.clock.instant().plusSeconds(172801),"America/New_York","https://www.bls.gov",f.clock.instant());
        var range=new NewsService.CalendarEvent("c","FED","FOMC",null,"America/New_York","https://www.federalreserve.gov",f.clock.instant(),"2026-10-07","2026-10-08","DATE_RANGE");
        assertThat(ResearchService.inTradingWindow(near,f.clock.instant())).isTrue();assertThat(ResearchService.inTradingWindow(far,f.clock.instant())).isFalse();
        assertThat(ResearchService.inTradingWindow(range,f.clock.instant())).isTrue();assertThat(range.scheduledAt()).isNull();
    }
    @Test void cancellationAndExpiredEvidenceCannotReachTheLedger() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(ai.complete(any(),anyString(),any(),any(java.time.Instant.class))).thenAnswer(call->{entered.countDown();release.await(2,TimeUnit.SECONDS);throw new IllegalStateException("TEST_DELAY");});
        var run=research.start("XAU_USDT","SHORT");assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
        assertThat(research.cancel(run.id).status).isEqualTo("CANCELED");release.countDown();Thread.sleep(100);
        verify(ledger,never()).propose(anyString(),anyString(),any());assertThat(research.find(run.id).status).isEqualTo("CANCELED");
    }
    @Test void schedulerConfigurationPersistsAndRejectsUnsupportedProducts(){
        var config=new ResearchService.ScheduleConfig();config.shortIntervalSeconds=60;config.longIntervalSeconds=600;config.automatic=true;
        research.configure(config);assertThat(research.scheduling().get("automatic")).isEqualTo(true);
        assertThat(f.store.find("research-scheduling","config",ResearchService.ScheduleConfig.class).orElseThrow().longIntervalSeconds).isEqualTo(600);
        config.symbols=List.of("BYBIT");assertThatThrownBy(()->research.configure(config)).hasMessage("INVALID_RESEARCH_SCHEDULE");
    }
    @Test void disabledSchedulerReportsItsStateWithoutStartingOrConsumingModels() throws Exception {
        assertThat(research.scheduling()).containsEntry("automatic",false).containsEntry("state","DISABLED")
            .containsEntry("disabledProviders",List.of("qwen"));
        automaticAndAdmit();verify(ai,never()).selectRoutes(anyList(),anyList(),anyString());
        verify(ai,never()).complete(any(),anyString(),any());verifyNoInteractions(ledger);
        assertThat(research.history()).isEmpty();
    }
    private Any2ApiClient.Answer waiting(RuntimeSettings.AiRoute route,JsonNode packet){
        var output=f.mapper.createObjectNode().put("title","等待").put("output","冻结证据观察").put("shortDirection","UNKNOWN").put("longDirection","UNKNOWN");
        output.putArray("quotedMessages");output.putArray("evidenceRefs");output.putArray("impacts");
        if(packet.path("phase").asInt()==3)output.put("selectedCandidateId","WAIT");else output.putObject("plan").put("action","WAIT");
        return new Any2ApiClient.Answer(route.model(),"","NOT_PROVIDED",output.toString(),output,f.mapper.createObjectNode(),"UNKNOWN","stop",null,1);
    }
    private ResearchService.Run await(String id)throws Exception{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);ResearchService.Run run;
        do{run=research.find(id);if(!run.status.equals("RUNNING"))return run;Thread.sleep(20);}while(System.nanoTime()<deadline);
        throw new AssertionError("Research did not complete: "+run.status);
    }
    @Test void timeoutRetriesOnlyFailedActorAndKeepsFrozenPeersAndSuccessfulOutputs()throws Exception{
        f.environment.setProperty("GOLD_SHORT_STAGE_SECONDS","5");f.environment.setProperty("GOLD_RESEARCH_RETRY_BASE_SECONDS","1");
        var count=new ConcurrentHashMap<String,Integer>();var evidence=Collections.synchronizedList(new ArrayList<String>());
        when(ai.complete(any(),anyString(),any(),any(java.time.Instant.class))).thenAnswer(call->{
            var route=(RuntimeSettings.AiRoute)call.getArgument(0);var packet=f.mapper.valueToTree(call.getArgument(2));String key=route.id()+"/"+packet.path("phase").asInt();
            evidence.add(packet.path("evidence").toString());int attempt=count.merge(key,1,Integer::sum);
            if(key.equals("p/0")&&attempt==1)throw new java.net.http.HttpTimeoutException("dummy");
            return waiting(route,packet);
        });
        var run=research.start("XAU_USDT","SHORT");var saved=await(run.id);
        assertThat(saved.status).isEqualTo("COMPLETED");assertThat(count.get("p/0")).isEqualTo(2);
        assertThat(count.get("q/0")).isEqualTo(1);assertThat(count.get("r/0")).isEqualTo(1);
        assertThat(saved.messages.stream().filter(m->m.stage()==0&&m.state().equals("SUBMITTED"))).hasSize(3);
        assertThat(saved.messages.stream().filter(m->m.stage()==0&&m.error()!=null)).anyMatch(m->m.error().equals("REQUEST_TIMEOUT"));
        assertThat(evidence.stream().distinct()).hasSize(1);assertThat(saved.adjudication.path("validJudges").asInt()).isEqualTo(3);
    }
    @Test void invalidReferenceIsReturnedToSameModelForRepairWithoutLeakingSameStageReplies()throws Exception{
        f.environment.setProperty("GOLD_SHORT_STAGE_SECONDS","5");f.environment.setProperty("GOLD_RESEARCH_RETRY_BASE_SECONDS","1");var seen=new ConcurrentHashMap<String,Integer>();
        var routes=f.settings.routes().stream().map(route->new RuntimeSettings.AiRoute(route.id(),route.provider(),route.model(),route.baseUrl(),route.apiKeyEnv(),route.inputEstimateLimit(),route.characterLimit(),route.byteLimit(),route.messageLimit(),route.outputReserve(),route.timeoutSeconds(),route.judge(),
            new RuntimeSettings.AiRoute(route.id()+"-fallback","arena","arena/Max","https://localhost/arena/v1","TEST_KEY",10000,14000,30000,2,1000,2,route.judge()))).toList();
        f.environment.setProperty("GOLD_AI_ROUTES_JSON",f.mapper.writeValueAsString(routes));
        when(ai.complete(any(),anyString(),any(),any(java.time.Instant.class))).thenAnswer(call->{
            var route=(RuntimeSettings.AiRoute)call.getArgument(0);var packet=f.mapper.valueToTree(call.getArgument(2));var answer=waiting(route,packet);
            if(route.id().equals("q")&&packet.path("phase").asInt()==0){int attempt=seen.merge("q",1,Integer::sum);
                assertThat(packet.path("peers")).isEmpty();
                if(attempt==1)((tools.jackson.databind.node.ArrayNode)answer.output().path("quotedMessages")).add("invented-reference");
                else assertThat(packet.path("formatCorrection").path("error").asText()).isEqualTo("INVALID_PREVIOUS_STAGE_REFERENCE");
            }return answer;
        });
        var saved=await(research.start("XAU_USDT","SHORT").id);assertThat(saved.status).isEqualTo("COMPLETED");assertThat(seen.get("q")).isEqualTo(2);
        verify(ai,never()).completeFallback(any(),anyString(),any(),any(Instant.class),anyString());
        assertThat(saved.messages.stream().filter(m->m.error()!=null)).anyMatch(m->m.answer()!=null&&m.answer().output().path("quotedMessages").path(0).asText().equals("invented-reference"));
    }
    @Test void expiredTradingEvidenceStillCompletesAllFourStagesAndThreeJudgeVotes()throws Exception{
        f.environment.setProperty("GOLD_LONG_DECISION_SECONDS","240");var advanced=new java.util.concurrent.atomic.AtomicBoolean();var phases=ConcurrentHashMap.<Integer>newKeySet();
        when(ai.complete(any(),anyString(),any(),any(java.time.Instant.class))).thenAnswer(call->{
            var route=(RuntimeSettings.AiRoute)call.getArgument(0);var packet=f.mapper.valueToTree(call.getArgument(2));phases.add(packet.path("phase").asInt());
            if(advanced.compareAndSet(false,true))f.clock.advance(241000);return waiting(route,packet);
        });
        var saved=await(research.start("XAU_USDT","LONG").id);assertThat(saved.status).isEqualTo("COMPLETED_EXPIRED");
        assertThat(saved.decisionExpired).isTrue();assertThat(saved.verdict).isNotNull();assertThat(saved.adjudication.path("validJudges").asInt()).isEqualTo(3);
        assertThat(phases).containsExactlyInAnyOrder(0,1,2,3);verify(ledger,never()).propose(anyString(),anyString(),any());
    }
    @Test void earlyMissingQuorumDoesNotAbortLaterResearchOrIndependentWaitJudging()throws Exception{
        when(ai.complete(any(),anyString(),any(),any(java.time.Instant.class))).thenAnswer(call->{
            var route=(RuntimeSettings.AiRoute)call.getArgument(0);var packet=f.mapper.valueToTree(call.getArgument(2));
            if(packet.path("phase").asInt()<3&&!route.id().equals("p"))throw new IllegalStateException("MODEL_NOT_AVAILABLE");return waiting(route,packet);
        });
        var saved=await(research.start("XAU_USDT","SHORT").id);assertThat(saved.stage).isEqualTo(3);assertThat(saved.status).isEqualTo("COMPLETED_WAIT");
        assertThat(saved.messages).hasSize(12);assertThat(saved.adjudication.path("validJudges").asInt()).isEqualTo(3);verify(ledger,never()).propose(anyString(),anyString(),any());
    }
    @Test void fatalCredentialErrorsAreNotRetriedButEveryStageIsAttempted()throws Exception{
        when(ai.complete(any(),anyString(),any(),any(java.time.Instant.class))).thenThrow(new IllegalStateException("INFERENCE_HTTP_401"));
        var saved=await(research.start("XAU_USDT","SHORT").id);assertThat(saved.status).isEqualTo("LOW_QUORUM");assertThat(saved.stage).isEqualTo(3);
        verify(ai,times(12)).complete(any(),anyString(),any(),any(java.time.Instant.class));verify(ledger,never()).propose(anyString(),anyString(),any());
    }
    @Test void restartResumesMissingActorsWithoutRepeatingPersistedSuccessfulResponses()throws Exception{
        var entered=new CountDownLatch(1);var submitted=new CountDownLatch(2);var counts=new ConcurrentHashMap<String,Integer>();
        when(ai.complete(any(),anyString(),any(),any(java.time.Instant.class))).thenAnswer(call->{
            var route=(RuntimeSettings.AiRoute)call.getArgument(0);var packet=f.mapper.valueToTree(call.getArgument(2));String key=route.id()+"/"+packet.path("phase").asInt();int attempt=counts.merge(key,1,Integer::sum);
            if(key.equals("r/0")&&attempt==1){entered.countDown();Thread.sleep(5000);}if(packet.path("phase").asInt()==0&&!route.id().equals("r"))submitted.countDown();return waiting(route,packet);
        });
        var run=research.start("XAU_USDT","SHORT");assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();assertThat(submitted.await(2,TimeUnit.SECONDS)).isTrue();
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(research.find(run.id).messages.stream().filter(m->m.state().equals("SUBMITTED")).count()<2&&System.nanoTime()<deadline)Thread.sleep(10);
        research.stop();assertThat(research.find(run.id).status).isEqualTo("RECOVERY_PENDING");
        research=new ResearchService(f.settings,f.store,market,new NewsService(f.settings,new RemoteHttp(),f.store,new EventHub(),f.clock),ledger,ai,f.mapper,new EventHub(),f.clock);
        research.recover();research.resumePending();var saved=await(run.id);assertThat(saved.status).isEqualTo("COMPLETED");assertThat(saved.recoveryCount).isEqualTo(1);
        assertThat(counts.get("p/0")).isEqualTo(1);assertThat(counts.get("q/0")).isEqualTo(1);assertThat(counts.get("r/0")).isEqualTo(2);
        assertThat(saved.adjudication.path("validJudges").asInt()).isEqualTo(3);
    }
    @Test void failedAutomaticStartDoesNotConsumeTheThirtyMinuteLongSchedule()throws Exception{
        var config=new ResearchService.ScheduleConfig();config.automatic=true;config.symbols=List.of("XAU_USDT");research.configure(config);
        when(ai.selectRoutes(anyList(),anyList(),anyString())).thenThrow(new IllegalArgumentException("NO_AVAILABLE_JUDGE_QUORUM"));
        automaticAndAdmit();assertThat((Map<?,?>)research.scheduling().get("lastByProductHorizon")).isEmpty();
        verify(ai,times(2)).selectRoutes(anyList(),anyList(),anyString());automaticAndAdmit();verify(ai,times(2)).selectRoutes(anyList(),anyList(),anyString());
        f.clock.advance(31000);automaticAndAdmit();verify(ai,times(4)).selectRoutes(anyList(),anyList(),anyString());
        assertThat((Map<?,?>)research.scheduling().get("lastByProductHorizon")).isEmpty();verify(ledger,never()).propose(anyString(),anyString(),any());
    }
    private EvidenceCompression preparedScheduler(){
        research.stop();var compression=mock(EvidenceCompression.class);when(compression.configurationKey()).thenReturn("test-compression");
        when(compression.needsPreparation(anyInt())).thenReturn(true);when(compression.preparationSeconds()).thenReturn(75);
        when(compression.apply(anyList(),any(),anyString())).thenAnswer(call->call.getArgument(0));
        research=new ResearchService(f.settings,f.store,market,new NewsService(f.settings,new RemoteHttp(),f.store,new EventHub(),f.clock),ledger,ai,f.mapper,new EventHub(),f.clock,null,compression);
        var config=new ResearchService.ScheduleConfig();config.automatic=true;config.symbols=List.of("XAU_USDT");research.configure(config);
        return compression;
    }
    private void refreshMarket(){
        var old=market.snapshot("XAU_USDT");var quote=new Quote("XAU_USDT","TEST",2,f.clock.instant(),f.clock.instant(),d("102"),d("103"),d("1"),d("1"),d("102"),d("102"),f.clock.instant(),d("0"),f.clock.instant().plusSeconds(3600),true);
        when(market.snapshot("XAU_USDT")).thenReturn(new Snapshot("XAU_USDT","LIVE",quote,old.rules(),Map.of(),List.of(),f.clock.instant(),null));
        when(ledger.view("XAU_USDT",quote)).thenReturn(new SimulationLedger.View("XAU_USDT","USDT","VALUED","1000","1000","1000","0","0","0",f.clock.instant(),List.of(),"UNKNOWN","TEST"));
    }
    @Test void automaticResearchDoesNotWaitForTheCompressionQueue()throws Exception{
        var compression=preparedScheduler();f.clock.advance(2000);refreshMarket();
        when(ai.complete(any(),anyString(),any(),any(Instant.class))).thenAnswer(call->waiting(call.getArgument(0),f.mapper.valueToTree(call.getArgument(2))));
        automaticAndAdmit();var completed=await(research.history().getFirst().id);
        assertThat(completed.status).isEqualTo("COMPLETED");assertThat(completed.evidenceCutoff).isEqualTo(f.clock.instant());
        assertThat(completed.evidence.path("quote").path("bid").asText()).isEqualTo("102");
        assertThat(research.scheduling().get("preparingUntil")).isNull();verify(compression,atLeastOnce()).enqueue();verify(compression,never()).collect();
    }
    @Test void legacyPreparationCannotBlockAutomaticResearchAfterRestart()throws Exception{
        var compression=preparedScheduler();research.stop();
        f.store.save("research-scheduling","preparations",Map.of("XAU_USDT/SHORT/old",Map.of("key","XAU_USDT/SHORT/old","until",f.clock.instant().plusSeconds(600))));
        research=new ResearchService(f.settings,f.store,market,new NewsService(f.settings,new RemoteHttp(),f.store,new EventHub(),f.clock),ledger,ai,f.mapper,new EventHub(),f.clock,null,compression);
        research.recover();when(ai.complete(any(),anyString(),any(),any(Instant.class))).thenAnswer(call->waiting(call.getArgument(0),f.mapper.valueToTree(call.getArgument(2))));
        automaticAndAdmit();assertThat(await(research.history().getFirst().id).status).isEqualTo("COMPLETED");
        assertThat(research.scheduling().get("preparingUntil")).isNull();verify(compression,never()).collect();
    }
    @Test void failedJudgeStopsAfterConfiguredAttemptsWithoutRepeatingOtherJudges()throws Exception{
        f.environment.setProperty("GOLD_JUDGE_STAGE_SECONDS","6");f.environment.setProperty("GOLD_RESEARCH_RETRY_BASE_SECONDS","1");f.environment.setProperty("GOLD_RESEARCH_RETRY_MAX_SECONDS","1");
        var counts=new ConcurrentHashMap<String,Integer>();
        when(ai.complete(any(),anyString(),any(),any(java.time.Instant.class))).thenAnswer(call->{
            var route=(RuntimeSettings.AiRoute)call.getArgument(0);var packet=f.mapper.valueToTree(call.getArgument(2));counts.merge(route.id(),1,Integer::sum);
            if(route.id().equals("j"))throw new java.net.http.HttpTimeoutException("dummy");return waiting(route,packet);
        });
        var saved=await(research.start("XAU_USDT","SHORT").id);assertThat(saved.status).isEqualTo("LOW_QUORUM");assertThat(counts.get("j")).isEqualTo(3);
        assertThat(counts.get("k")).isEqualTo(1);assertThat(counts.get("l")).isEqualTo(1);
        assertThat(saved.messages.stream().filter(m->m.stage()==3&&m.error()!=null).map(ResearchService.Message::attempt)).containsExactly(1,2,3);
        verify(ledger,never()).propose(anyString(),anyString(),any());
    }
    @Test void ledgerChangeRollsBackWhenTerminalResearchCheckpointCannotBeWritten()throws Exception{
        research.stop();var store=spy(f.store);
        doAnswer(call->{Object value=call.getArgument(2);if(value instanceof ResearchService.Run run&&run.status.equals("COMPLETED"))throw new IllegalStateException("CHECKPOINT_SAVE_FAILED");return call.callRealMethod();}).when(store).save(anyString(),anyString(),any());
        research=new ResearchService(f.settings,store,market,new NewsService(f.settings,new RemoteHttp(),store,new EventHub(),f.clock),ledger,ai,f.mapper,new EventHub(),f.clock);
        when(ai.complete(any(),anyString(),any(),any(java.time.Instant.class))).thenAnswer(call->waiting(call.getArgument(0),f.mapper.valueToTree(call.getArgument(2))));
        when(ledger.propose(anyString(),eq("XAU_USDT"),any())).thenAnswer(call->{f.store.save("ledger-checkpoint-test","change",Map.of("amount","100"));var order=new SimulationLedger.Order();order.id="test-order";return order;});
        var saved=await(research.start("XAU_USDT","SHORT").id);assertThat(saved.status).isEqualTo("FAILED");assertThat(saved.error).isEqualTo("CHECKPOINT_SAVE_FAILED");assertThat(saved.orderId).isNull();
        assertThat(f.store.find("ledger-checkpoint-test","change",Map.class)).isEmpty();verify(ledger,times(1)).propose(eq(saved.id),eq("XAU_USDT"),any());
    }
    @Test void persistedPreRetryMessagesRemainReadableWithTheStrictMapper(){
        var legacy=(tools.jackson.databind.node.ObjectNode)f.mapper.valueToTree(new ResearchService.Message("old-id","A","研究",0,"FAILED",f.clock.instant(),f.clock.instant(),"REQUEST_TIMEOUT",List.of(),null));
        legacy.remove("attempt");legacy.remove("retryAt");var decoded=f.mapper.treeToValue(legacy,ResearchService.Message.class);assertThat(decoded.attempt()).isEqualTo(1);assertThat(decoded.retryAt()).isNull();assertThat(decoded.error()).isEqualTo("REQUEST_TIMEOUT");
        legacy.putNull("attempt");assertThat(f.mapper.treeToValue(legacy,ResearchService.Message.class).attempt()).isEqualTo(1);
    }
    private BigDecimal d(String s){return new BigDecimal(s);}
}
