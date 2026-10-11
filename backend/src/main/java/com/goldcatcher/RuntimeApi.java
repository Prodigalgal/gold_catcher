package com.goldcatcher;

import com.goldcatcher.information.NewsService;
import com.goldcatcher.analysis.TrendAnalysis;
import com.goldcatcher.analysis.DriverSources;
import com.goldcatcher.analysis.SupplementalSources;
import com.goldcatcher.network.SourceReliability;
import com.goldcatcher.network.SourceProxyPool;
import com.goldcatcher.market.MarketFeed;
import com.goldcatcher.market.MarketArchive;
import com.goldcatcher.persistence.DocumentStore;
import com.goldcatcher.research.*;
import com.goldcatcher.simulation.SimulationLedger;
import com.goldcatcher.simulation.FundingService;
import com.goldcatcher.simulation.ReviewService;
import java.security.Principal;
import java.time.Clock;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api")
public class RuntimeApi {
    private final MarketFeed market;private final NewsService news;private final SimulationLedger ledger;private final ResearchService research;
    private final Any2ApiClient ai;private final RuntimeSettings settings;private final EventHub events;private final DocumentStore store;private final Clock clock;
    private final FundingService funding;
    private final SourceProxyPool proxy;
    private final TrendAnalysis analysis;
    private final SourceReliability reliability;private final DriverSources drivers;private final SupplementalSources supplemental;
    private final MarketArchive archive;
    private final ReviewService reviews;private final EvidenceCompression compression;
    private final AiConfiguration aiConfiguration;
    public RuntimeApi(MarketFeed market,NewsService news,SimulationLedger ledger,ResearchService research,Any2ApiClient ai,
        RuntimeSettings settings,EventHub events,DocumentStore store,Clock clock,FundingService funding,SourceProxyPool proxy,TrendAnalysis analysis,SourceReliability reliability,DriverSources drivers,SupplementalSources supplemental,MarketArchive archive,ReviewService reviews,EvidenceCompression compression,AiConfiguration aiConfiguration) {
        this.market=market;this.news=news;this.ledger=ledger;this.research=research;this.ai=ai;this.settings=settings;this.events=events;this.store=store;this.clock=clock;
        this.funding=funding;this.proxy=proxy;this.analysis=analysis;
        this.reliability=reliability;this.drivers=drivers;this.supplemental=supplemental;
        this.archive=archive;
        this.reviews=reviews;this.compression=compression;
        this.aiConfiguration=aiConfiguration;
    }
    @GetMapping("/session") public Map<String,String> session(Principal principal,CsrfToken token) {
        return Map.of("username",principal.getName(),"csrfHeader",token.getHeaderName(),"csrfToken",token.getToken());
    }
    @GetMapping("/dashboard") public Map<String,Object> dashboard(@RequestParam(defaultValue="XAU_USDT") String symbol) {
        product(symbol);Map<String,Object> data=new LinkedHashMap<>();
        data.put("serverTime",clock.instant());data.put("market",market.snapshot(symbol));data.put("marketProvenance",market.provenance(symbol));data.put("account",ledger.view(symbol));
        var official=news.sources();data.put("news",news.news());data.put("calendar",news.calendar());data.put("sources",official);data.put("models",ai.states());
        data.put("sourceProxy",proxy.status());
        var trend=analysis.view(symbol);data.put("analysis",trend);
        var channels=new ArrayList<SourceReliability.Input>();
        official.forEach(s->channels.add(new SourceReliability.Input(s.id(),s.id(),NewsService.mediaSource(s.id())?"MEDIA":"OFFICIAL",s.url(),s.state(),news.pollInterval(s.id()),s.lastSuccess(),s.nextPoll(),s.error())));
        trend.sources().forEach(s->channels.add(new SourceReliability.Input(s.id(),s.label(),"DRIVER",s.url(),s.state(),drivers.pollInterval(s.id()),s.lastSuccess(),s.nextPoll(),s.error())));
        trend.supplemental().sources().forEach(s->channels.add(new SourceReliability.Input(s.id(),s.label(),"SUPPLEMENTAL",s.url(),s.state(),supplemental.interval(s.id()),s.lastSuccess(),s.nextPoll(),s.error())));
        data.put("sourceReliability",reliability.view(channels));
        data.put("funding",symbol.equals("XAU_USDT")?funding.status():Map.of("state","NOT_APPLICABLE_OR_UNVERIFIED","pricingMethod","NOT_CONFIGURED"));
        data.put("costConfiguration",market.costConfiguration(symbol));
        data.put("marketArchive",archive.status(symbol));
        data.put("liveDepth",symbol.equals("XAU_USDT")?market.depth():Map.of("state","NOT_PROVIDED"));
        data.put("budgets",settings.allRoutes().stream().map(route->Map.ofEntries(Map.entry("id",route.id()),Map.entry("inputEstimateLimit",route.inputEstimateLimit()),Map.entry("characterLimit",route.characterLimit()),
            Map.entry("byteLimit",route.byteLimit()),Map.entry("messageLimit",route.messageLimit()),Map.entry("outputReserve",route.outputReserve()),Map.entry("timeoutSeconds",route.timeoutSeconds()),Map.entry("judge",route.judge()),
            Map.entry("headroomPercent",10),Map.entry("safetyTokens",512),Map.entry("submissionMode",settings.preferJsonText(route)?"JSON_TEXT":"CATALOG_AUTO"))).toList());
        data.put("research",research.latest(symbol));data.put("compression",compression.status());
        data.put("researchHistory",research.history().stream().filter(run->run.symbol.equals(symbol)).map(run->Map.of("id",run.id,"horizon",run.horizon,"status",run.status,"startedAt",run.startedAt,"stage",run.stage)).toList());
        data.put("scheduling",research.scheduling());data.put("review",reviews.view(symbol));data.put("clock",clock instanceof ExchangeClock exchange?exchange.status():Map.of("state","TEST_CLOCK"));return data;
    }
    @GetMapping("/news/{id}") public Map<String,Object> article(@PathVariable String id){return news.detail(id);}
    @GetMapping("/compression/{id}") public Map<String,Object> compression(@PathVariable String id){return compression.detail(id);}
    @PostMapping("/compression/news/{id}/reprocess") public EvidenceCompression.Job reprocess(@PathVariable String id){return compression.reprocess(id);}
    public record ResearchRequest(String symbol,String horizon) {}
    @PostMapping("/research") public ResponseEntity<ResearchService.Run> start(@RequestBody ResearchRequest request) {
        product(request.symbol());return ResponseEntity.accepted().body(research.start(request.symbol(),request.horizon()));
    }
    @GetMapping("/research/{id}") public ResearchService.Run run(@PathVariable String id){return research.find(id);}
    @PostMapping("/research/{id}/cancel") public ResearchService.Run cancelResearch(@PathVariable String id){return research.cancel(id);}
    @PostMapping("/research/scheduling") public Map<String,Object> scheduling(@RequestBody ResearchService.ScheduleConfig config){return research.configure(config);}
    @GetMapping("/ai/configuration") public Map<String,Object> aiConfiguration(){return aiConfiguration.view();}
    @PostMapping("/ai/configuration") public Map<String,Object> configureAi(@RequestBody AiConfiguration.Update config){return aiConfiguration.save(config);}
    @PostMapping("/ai/configuration/models") public Map<String,Object> refreshModels(){return aiConfiguration.refreshCatalogs();}
    @GetMapping("/market/candles") public Map<String,Object> candles(@RequestParam(defaultValue="XAU_USDT") String symbol,@RequestParam(defaultValue="5") String frame){product(symbol);return Map.of("symbol",symbol,"frame",frame,"candles",market.candles(symbol,frame));}
    @GetMapping("/research/{id}/export") public ResponseEntity<ResearchService.Run> exportResearch(@PathVariable String id){var run=research.find(id);return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=research-"+run.id+".json").body(run);}
    @GetMapping("/market/archive") public Map<String,Object> archive(@RequestParam(defaultValue="XAU_USDT") String symbol,@RequestParam(defaultValue="0") long after,@RequestParam(defaultValue="100") int limit) {
        product(symbol);var rows=archive.frames(symbol,after,limit);return Map.of("status",archive.status(symbol),"frames",rows,"nextCursor",rows.isEmpty()?after:rows.getLast().get("sequence"));
    }
    @PostMapping("/simulation/orders/{id}/cancel") public SimulationLedger.Order cancel(@PathVariable String id){return ledger.cancel(id);}
    @PostMapping("/simulation/orders/{id}/command") public SimulationLedger.Order command(@PathVariable String id,@RequestBody tools.jackson.databind.JsonNode plan){return ledger.command(id,plan.path("action").asText(),plan);}
    @GetMapping("/simulation/orders/{id}/events") public List<DocumentStore.Event> orderEvents(@PathVariable String id){return store.events(id,100);}
    @GetMapping(value="/simulation/export",produces="text/csv;charset=UTF-8") public ResponseEntity<String> exportOrders(@RequestParam(defaultValue="XAU_USDT") String symbol){product(symbol);return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=simulation-"+symbol.replace("GATE:","")+".csv").body(reviews.ordersCsv(symbol));}
    @GetMapping(value="/events",produces=MediaType.TEXT_EVENT_STREAM_VALUE) public SseEmitter stream(){return events.connect();}
    private void product(String symbol){if(symbol==null||!List.of("XAU_USDT","GATE:XAUUSD").contains(symbol))throw new IllegalArgumentException("UNKNOWN_PRODUCT");}
}
