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
    TestSupport f;Any2ApiClient ai;ResearchService research;SimulationLedger ledger;
    @BeforeEach void setup() {
        f=new TestSupport();var market=mock(MarketFeed.class);ledger=mock(SimulationLedger.class);ai=mock(Any2ApiClient.class);
        var routes=new ArrayList<RuntimeSettings.AiRoute>();for(String id:List.of("p","q","r","j"))routes.add(new RuntimeSettings.AiRoute(id,id,id+"/"+id+"-model","https://localhost/"+id+"/v1","TEST_KEY",10000,14000,30000,2,1000,2,id.equals("j")));
        f.environment.setProperty("GOLD_AI_ROUTES_JSON",f.mapper.writeValueAsString(routes));f.environment.setProperty("GOLD_SHORT_STAGE_SECONDS","1");f.environment.setProperty("GOLD_JUDGE_STAGE_SECONDS","1");
        var quote=new Quote("XAU_USDT","TEST",1,f.clock.instant(),f.clock.instant(),d("100"),d("101"),d("1"),d("1"),d("100"),d("100"),f.clock.instant(),d("0"),f.clock.instant().plusSeconds(3600),true);
        var rules=new Instrument("XAU_USDT","Trading","USDT",d(".01"),d(".001"),d(".001"),d("1000"),d("1000"),d("1"),d("1"),d("100"),d(".01"),List.of(),f.clock.instant());
        when(market.snapshot("XAU_USDT")).thenReturn(new Snapshot("XAU_USDT","LIVE",quote,rules,Map.of(),List.of(),f.clock.instant(),null));
        when(ledger.view("XAU_USDT",quote)).thenReturn(new SimulationLedger.View("XAU_USDT","USDT","VALUED","1000","1000","1000","0","0","0",f.clock.instant(),List.of(),"UNKNOWN","TEST"));
        research=new ResearchService(f.settings,f.store,market,new NewsService(f.settings,new RemoteHttp(),f.store,new EventHub(),f.clock),ledger,ai,f.mapper,new EventHub(),f.clock);
    }
    @AfterEach void cleanup(){research.stop();}
    @Test void sealedBatchesShareEvidenceExcludeLateOutputAndPersistVerdict() throws Exception {
        List<JsonNode> packets=Collections.synchronizedList(new ArrayList<>());
        when(ai.complete(any(),anyString(),any())).thenAnswer(call->{
            var route=(RuntimeSettings.AiRoute)call.getArgument(0);JsonNode packet=f.mapper.valueToTree(call.getArgument(2));packets.add(packet);
            if(route.id().equals("r")&&packet.path("phase").asInt()==0)Thread.sleep(1500);
            var output=f.mapper.createObjectNode().put("title","观察").put("analysisSummary","摘要").put("output","保持等待").put("shortDirection","UNKNOWN").put("longDirection","UNKNOWN");
            var quotes=output.putArray("quotedMessages");for(var peer:packet.path("peers"))quotes.add(peer.path("id").asText());
            output.putObject("plan").put("action","WAIT");output.putArray("impacts");output.putArray("evidenceRefs");
            return new Any2ApiClient.Answer(route.model(),"摘要","EXPLICIT_OUTPUT_SUMMARY",output.toString(),output,f.mapper.createObjectNode(),"UNKNOWN","stop",
                new Any2ApiClient.Budget(100,100,34,2,1000,"hash","CONFIG","NOT_SUPPORTED"),1);
        });
        var run=research.start("XAU_USDT","SHORT");
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        while(research.find(run.id).status.equals("RUNNING")&&System.nanoTime()<deadline)Thread.sleep(20);
        while(research.find(run.id).messages.stream().anyMatch(m->m.state().equals("TIMED_OUT"))&&System.nanoTime()<deadline)Thread.sleep(20);
        var saved=research.find(run.id);assertThat(saved.status).isEqualTo("COMPLETED");assertThat(saved.messages).hasSize(10);
        assertThat(saved.modelDiversity.get("researchModels")).isEqualTo(List.of("p-model","q-model","r-model"));
        assertThat(saved.modelDiversity.get("judgeModel")).isEqualTo("j-model");
        assertThat(saved.messages.stream().filter(m->m.stage()==0&&m.state().equals("LATE_ARCHIVED"))).hasSize(1);
        assertThat(packets.stream().map(p->p.path("evidenceHash").asText()).distinct()).hasSize(1);
        for(var packet:packets) {
            int phase=packet.path("phase").asInt();if(phase==0)assertThat(packet.path("peers")).isEmpty();
            else for(var peer:packet.path("peers"))assertThat(peer.path("id").asText().split("-")[5]).isEqualTo(Integer.toString(phase-1));
            if(phase==1)assertThat(packet.path("peers")).hasSize(2);
        }
        verify(ledger,times(1)).propose(eq(run.id),eq("XAU_USDT"),any());
    }
    @Test void insufficientValidModelsNeverCreatesAnOrder() throws Exception {
        when(ai.complete(any(),anyString(),any())).thenThrow(new IllegalStateException("MODEL_NOT_AVAILABLE"));
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
    @Test void judgeCannotReuseOneOfTheResearchModels(){
        var routes=List.of(new RuntimeSettings.AiRoute("one","a","a/first","https://localhost/v1","TEST",10000,16000,30000,2,1000,2,false),
            new RuntimeSettings.AiRoute("two","b","b/second","https://localhost/v1","TEST",10000,16000,30000,2,1000,2,false),
            new RuntimeSettings.AiRoute("judge","c","c/FIRST","https://localhost/v1","TEST",10000,16000,30000,2,1000,2,true));
        f.environment.setProperty("GOLD_AI_ROUTES_JSON",f.mapper.writeValueAsString(routes));
        assertThatThrownBy(()->research.start("XAU_USDT","SHORT")).hasMessage("INDEPENDENT_JUDGE_MODEL_REQUIRED");verifyNoInteractions(ai);
    }
    @Test void calendarSelectionPreservesDateRangesAndExcludesEventsOutsideTradingHorizon(){
        var near=new NewsService.CalendarEvent("a","BLS","CPI",f.clock.instant().plusSeconds(3600),"America/New_York","https://www.bls.gov",f.clock.instant());
        var far=new NewsService.CalendarEvent("b","BLS","CPI",f.clock.instant().plusSeconds(172801),"America/New_York","https://www.bls.gov",f.clock.instant());
        var range=new NewsService.CalendarEvent("c","FED","FOMC",null,"America/New_York","https://www.federalreserve.gov",f.clock.instant(),"2026-10-07","2026-10-08","DATE_RANGE");
        assertThat(ResearchService.inTradingWindow(near,f.clock.instant())).isTrue();assertThat(ResearchService.inTradingWindow(far,f.clock.instant())).isFalse();
        assertThat(ResearchService.inTradingWindow(range,f.clock.instant())).isTrue();assertThat(range.scheduledAt()).isNull();
    }
    private BigDecimal d(String s){return new BigDecimal(s);}
}
