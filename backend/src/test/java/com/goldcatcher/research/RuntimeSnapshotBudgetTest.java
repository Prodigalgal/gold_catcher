package com.goldcatcher.research;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.goldcatcher.*;
import com.goldcatcher.analysis.*;
import com.goldcatcher.analysis.AnalysisTypes.*;
import com.goldcatcher.information.NewsService;
import com.goldcatcher.market.*;
import com.goldcatcher.market.MarketTypes.*;
import com.goldcatcher.simulation.SimulationLedger;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
class RuntimeSnapshotBudgetTest {
 @ParameterizedTest @ValueSource(strings={"WAIT","CREATE","ACTIVE_CREATE"}) void currentPublicSnapshotFitsEveryFrozenDiscussionStageWithoutModelHttpCalls(String scenario) throws Exception {
  String path=System.getenv("GOLD_TEST_SNAPSHOT");org.junit.jupiter.api.Assumptions.assumeTrue(path!=null&&!path.isBlank(),"Optional frozen public runtime snapshot");
  var f=new TestSupport();var dashboard=f.mapper.readTree(Files.readString(Path.of(path)));
  f.clock.now=Instant.parse(dashboard.path("analysis").path("calculatedAt").asText());
  f.environment.setProperty("GOLD_AI_ROUTES_JSON",Files.readString(Path.of("../config/ai-routes.balanced.example.json")));
  f.environment.setProperty("GOLD_SHORT_STAGE_SECONDS","1");f.environment.setProperty("GOLD_JUDGE_STAGE_SECONDS","1");
  var events=new EventHub();var market=mock(MarketFeed.class);var ledger=mock(SimulationLedger.class);var ai=mock(Any2ApiClient.class);
  var snapshot=f.mapper.treeToValue(dashboard.path("market"),Snapshot.class);
  when(market.snapshot("XAU_USDT")).thenReturn(snapshot);
  when(market.costConfiguration("XAU_USDT")).thenReturn(f.mapper.convertValue(dashboard.path("costConfiguration"),Map.class));
  when(market.provenance("XAU_USDT")).thenReturn(f.mapper.convertValue(dashboard.path("marketProvenance"),Map.class));
  var account=f.mapper.treeToValue(dashboard.path("account"),SimulationLedger.View.class);
  if(scenario.equals("ACTIVE_CREATE")){var o=new SimulationLedger.Order();o.id="active-order";o.symbol="XAU_USDT";o.side="BUY";o.type="LIMIT";o.status="FILLED";o.positionStatus="OPEN";o.quantity=new java.math.BigDecimal("0.01");o.openQuantity=o.quantity;o.filled=o.quantity;o.leverage=new java.math.BigDecimal("50");o.entryPrice=snapshot.quote().ask();o.averageEntry=o.entryPrice;o.margin=new java.math.BigDecimal("1");o.feeRate=new java.math.BigDecimal("0.0005");o.expiresAt=f.clock.instant().plusSeconds(300);o.holdUntil=f.clock.instant().plusSeconds(3600);o.riskAssessment="风险".repeat(60);o.invalidate="条件".repeat(40);
   account=new SimulationLedger.View(account.symbol(),account.currency(),account.state(),account.balance(),account.available(),account.equity(),"1",account.unrealized(),account.estimatedExitFees(),account.valuedAt(),List.of(o),account.costStatus(),account.liquidationMethod());}
  when(ledger.view("XAU_USDT",snapshot.quote())).thenReturn(account);
  for(var row:dashboard.path("news")){var n=f.mapper.treeToValue(row,NewsService.News.class);f.store.save("news",n.id(),n);}
  for(var row:dashboard.path("calendar")){var e=f.mapper.treeToValue(row,NewsService.CalendarEvent.class);f.store.save("calendar",e.id(),e);}
  var bodyPath=Path.of(path).resolveSibling("bodies.json");if(Files.exists(bodyPath))for(var row:f.mapper.readTree(Files.readString(bodyPath))){var body=f.mapper.treeToValue(row,NewsService.ArticleBody.class);f.store.save("news-body",body.id(),body);}
  var news=new NewsService(f.settings,new RemoteHttp(),f.store,events,f.clock);ReflectionTestUtils.invokeMethod(news,"recover");
  for(var row:dashboard.path("analysis").path("drivers")){var d=f.mapper.treeToValue(row,Driver.class);f.store.save("gold-driver",d.id(),d);}
  for(var row:dashboard.path("analysis").path("releases")){var r=f.mapper.treeToValue(row,Release.class);f.store.save("economic-release-version",r.id()+":"+r.version(),r);}
  for(var row:dashboard.path("analysis").path("expectations")){var e=f.mapper.treeToValue(row,Expectation.class);f.store.save("gold-expectation-version",e.id()+":"+e.version(),e);}
  var sources=new DriverSources(f.settings,f.store,new RemoteHttp(),f.mapper,mock(GateCfdFeed.class),events,f.clock);ReflectionTestUtils.invokeMethod(sources,"recover");
  var supplemental=new SupplementalSources(f.settings,new RemoteHttp(),f.mapper,f.store,market,f.clock,events);
  for(var row:dashboard.path("analysis").path("supplemental").path("observations")){var o=f.mapper.treeToValue(row,SupplementalSources.Observation.class);f.store.save("public-observation-version",NewsService.hash(o.id()),o);}
  var savedExtra=dashboard.path("analysis").path("supplemental");f.store.save("public-depth","XAU_USDT",f.mapper.treeToValue(savedExtra.path("depth"),SupplementalSources.Depth.class));f.store.save("public-trades","XAU_USDT",f.mapper.treeToValue(savedExtra.path("trades"),SupplementalSources.Trades.class));
  var analysis=new TrendAnalysis(sources,news,market,f.store,f.mapper,f.clock,events,supplemental);
  List<ContextWindow.Prepared> prepared=Collections.synchronizedList(new ArrayList<>()),repaired=Collections.synchronizedList(new ArrayList<>());
  when(ai.selectRoutes(anyList(),anyList())).thenAnswer(call->call.getArgument(0));
  when(ai.complete(any(),anyString(),any(),any(java.time.Instant.class))).thenAnswer(call->{
   var route=(RuntimeSettings.AiRoute)call.getArgument(0);String instruction=call.getArgument(1);var packet=f.mapper.valueToTree(call.getArgument(2));
   Map<String,Object> request=new LinkedHashMap<>();request.put("model",route.model());
   request.put("messages",List.of(Map.of("role","system","content",instruction+ResearchProtocol.instruction(packet.path("phase").asInt()==3,false)),Map.of("role","user","content","")));request.put("max_tokens",route.outputReserve());
   var limits=ContextWindow.limits(route,f.mapper.createObjectNode());var result=ContextWindow.prepare(f.mapper,request,packet,limits);prepared.add(result);
   var retryPacket=(tools.jackson.databind.node.ObjectNode)packet.deepCopy();retryPacket.putObject("formatCorrection").put("error","INVALID_EVIDENCE_REFERENCE").put("instruction","只修复所报格式/引用错误，保持冻结证据；方向仅BULLISH/BEARISH/UNKNOWN/MIXED；riskAssessment≤120字，invalidate≤80字，详情写output；不得补造数值或引用。");
   var repair=ContextWindow.prepare(f.mapper,request,retryPacket,limits);repaired.add(repair);assertThat(ContextWindow.fits(repair.sent(),limits,true)).isTrue();
   Files.writeString(Path.of("../artifacts/offline-request-"+route.id()+"-"+packet.path("phase").asInt()+".json"),result.body());
   System.out.println(route.id()+" phase="+packet.path("phase").asInt()+" chars="+result.sent().characters()+" bytes="+result.sent().bytes()+" utilization="+ContextWindow.utilization(result.sent(),limits));
   var output=f.mapper.createObjectNode().put("title","离线预算验证").put("analysisSummary","研究观点".repeat(90)).put("output","解释与反证".repeat(110)).put("shortDirection","UNKNOWN").put("longDirection","UNKNOWN").put("changes","保留修订".repeat(20));
   var peers=output.putArray("quotedMessages");for(var peer:packet.path("peers"))peers.add(peer.path("id").asText());output.putArray("evidenceRefs").add("analysis:drivers").add("market:quote");output.putArray("impacts");var plan=output.putObject("plan").put("action",scenario.equals("WAIT")?"WAIT":"CREATE");
   plan.put("riskAssessment","风险".repeat(60)).put("invalidate","条件".repeat(40));
   if(!scenario.equals("WAIT")){plan.put("side","BUY").put("type","LIMIT").put("quantity","0.01").put("leverage","50").put("entryPrice",snapshot.quote().ask().toPlainString()).put("stopLoss",snapshot.quote().ask().subtract(new java.math.BigDecimal("1")).toPlainString()).put("takeProfit",snapshot.quote().ask().add(new java.math.BigDecimal("1")).toPlainString()).put("trailingDistance","1").put("expiresAt",f.clock.instant().plusSeconds(300).toString()).put("holdUntil",f.clock.instant().plusSeconds(3600).toString());}
   if(packet.path("phase").asInt()==3){output.remove("plan");output.put("selectedCandidateId",scenario.equals("WAIT")?"WAIT":packet.path("peers").path(0).path("id").asText());}
   return new Any2ApiClient.Answer(route.model(),"离线验证","EXPLICIT_OUTPUT_SUMMARY",output.toString(),output,f.mapper.createObjectNode(),"UNKNOWN","stop",new Any2ApiClient.Budget(result.sent().bytes(),result.sent().characters(),result.sent().inputEstimate(),2,route.outputReserve(),"offline","CONFIG","OFFLINE"),1);
  });
  var compression=new EvidenceCompression(f.settings,news,ai,f.store,f.mapper,f.clock,events);
  // Optional genuine preprocessing records exercise the exact cache/cutoff path, still without model HTTP.
  try(var files=Files.list(Path.of(path).getParent())){var cached=new HashMap<String,EvidenceCompression.Result>();
   for(var file:files.filter(p->p.getFileName().toString().matches("compression-[a-f0-9]{64}\\.json")).toList()){
    var result=f.mapper.treeToValue(f.mapper.readTree(Files.readString(file)).path("result"),EvidenceCompression.Result.class);
    cached.merge(result.cacheKey(),result,(left,right)->left.completedAt().isAfter(right.completedAt())?left:right);
    f.store.save("compression-result",result.id(),result);
   }cached.forEach((key,result)->f.store.save("compression-cache",key,result));}
  var research=new ResearchService(f.settings,f.store,market,news,ledger,ai,f.mapper,events,f.clock,analysis,compression);
  try{
   var run=research.start("XAU_USDT","SHORT");long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
   while(research.find(run.id).status.equals("RUNNING")&&System.nanoTime()<deadline)Thread.sleep(20);
   assertThat(research.find(run.id).status).isEqualTo("COMPLETED");assertThat(prepared).hasSize(12);assertThat(repaired).hasSize(12);
   var limits=ContextWindow.limits(f.settings.routes().getFirst(),f.mapper.createObjectNode());for(var packet:prepared)assertThat(ContextWindow.fits(packet.sent(),limits,true)).isTrue();
   assertThat(run.evidence.path("analysis").path("drivers").path("csv").asText().split("\n")).hasSize(dashboard.path("analysis").path("drivers").size());
   assertThat(run.evidence.path("analysis").path("horizons").isMissingNode()).isTrue();
   assertThat(run.evidence.path("analysis").path("publicFacts").path("csv").asText().split("\n").length).isGreaterThanOrEqualTo(8);
   assertThat(f.store.find("research-analysis",run.id,View.class).orElseThrow().horizons()).hasSize(4);
   assertThat(f.store.find("research-analysis",run.id,View.class)).isPresent();
  }finally{research.stop();compression.stop();}
 }
}
