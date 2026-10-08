package com.goldcatcher;

import com.goldcatcher.information.NewsService;
import com.goldcatcher.network.SourceProxyPool;
import com.goldcatcher.market.MarketFeed;
import com.goldcatcher.persistence.DocumentStore;
import com.goldcatcher.research.*;
import com.goldcatcher.simulation.SimulationLedger;
import com.goldcatcher.simulation.FundingService;
import java.math.BigDecimal;
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
    public RuntimeApi(MarketFeed market,NewsService news,SimulationLedger ledger,ResearchService research,Any2ApiClient ai,
        RuntimeSettings settings,EventHub events,DocumentStore store,Clock clock,FundingService funding,SourceProxyPool proxy) {
        this.market=market;this.news=news;this.ledger=ledger;this.research=research;this.ai=ai;this.settings=settings;this.events=events;this.store=store;this.clock=clock;
        this.funding=funding;this.proxy=proxy;
    }
    @GetMapping("/session") public Map<String,String> session(Principal principal,CsrfToken token) {
        return Map.of("username",principal.getName(),"csrfHeader",token.getHeaderName(),"csrfToken",token.getToken());
    }
    @GetMapping("/dashboard") public Map<String,Object> dashboard(@RequestParam(defaultValue="XAU_USDT") String symbol) {
        product(symbol);Map<String,Object> data=new LinkedHashMap<>();
        data.put("serverTime",clock.instant());data.put("market",market.snapshot(symbol));data.put("marketProvenance",market.provenance(symbol));data.put("account",ledger.view(symbol));
        data.put("news",news.news());data.put("calendar",news.calendar());data.put("sources",news.sources());data.put("models",ai.states());
        data.put("sourceProxy",proxy.status());
        data.put("funding",symbol.equals("XAU_USDT")?funding.status():Map.of("state","NOT_APPLICABLE_OR_UNVERIFIED","pricingMethod","NOT_CONFIGURED"));
        data.put("costConfiguration",market.costConfiguration(symbol));
        data.put("budgets",settings.routes().stream().map(route->Map.ofEntries(Map.entry("id",route.id()),Map.entry("inputEstimateLimit",route.inputEstimateLimit()),Map.entry("characterLimit",route.characterLimit()),
            Map.entry("byteLimit",route.byteLimit()),Map.entry("messageLimit",route.messageLimit()),Map.entry("outputReserve",route.outputReserve()),Map.entry("timeoutSeconds",route.timeoutSeconds()),Map.entry("judge",route.judge()),
            Map.entry("headroomPercent",10),Map.entry("safetyTokens",512),Map.entry("submissionMode",settings.preferJsonText(route)?"JSON_TEXT":"CATALOG_AUTO"))).toList());
        data.put("research",research.latest(symbol));
        data.put("researchHistory",research.history().stream().filter(run->run.symbol.equals(symbol)).map(run->Map.of("id",run.id,"horizon",run.horizon,"status",run.status,"startedAt",run.startedAt,"stage",run.stage)).toList());
        data.put("scheduling",research.scheduling());data.put("review",review(symbol));data.put("clock",clock instanceof ExchangeClock exchange?exchange.status():Map.of("state","TEST_CLOCK"));return data;
    }
    @GetMapping("/news/{id}") public Map<String,Object> article(@PathVariable String id){return news.detail(id);}
    public record ResearchRequest(String symbol,String horizon) {}
    @PostMapping("/research") public ResponseEntity<ResearchService.Run> start(@RequestBody ResearchRequest request) {
        product(request.symbol());return ResponseEntity.accepted().body(research.start(request.symbol(),request.horizon()));
    }
    @GetMapping("/research/{id}") public ResearchService.Run run(@PathVariable String id){return research.find(id);}
    @PostMapping("/simulation/orders/{id}/cancel") public SimulationLedger.Order cancel(@PathVariable String id){return ledger.cancel(id);}
    @GetMapping("/simulation/orders/{id}/events") public List<DocumentStore.Event> orderEvents(@PathVariable String id){return store.events(id,100);}
    @GetMapping(value="/events",produces=MediaType.TEXT_EVENT_STREAM_VALUE) public SseEmitter stream(){return events.connect();}
    private Map<String,Object> review(String symbol) {
        var account=ledger.view(symbol);var completed=account.orders().stream().filter(order->order.closedAt!=null).toList();
        var gross=completed.stream().map(order->order.realizedGross).reduce(BigDecimal.ZERO,BigDecimal::add);
        var fees=completed.stream().map(order->order.entryFees.add(order.exitFees).add(order.fundingFees)).reduce(BigDecimal.ZERO,BigDecimal::add);
        return Map.of("closedOrders",completed.size(),"gross",SimulationLedger.money(gross),"fees",SimulationLedger.money(fees),"netBeforeUnknownCosts",SimulationLedger.money(gross.subtract(fees)),
            "fullyCostedOrders",completed.stream().filter(order->!order.costIncomplete).count(),"positiveRecordedNet",completed.stream().filter(order->order.netRealized.signum()>0).count(),
            "status",completed.isEmpty()?"NO_CLOSED_SAMPLE":completed.stream().anyMatch(o->o.costIncomplete)?"RECORDED_COSTS_INCOMPLETE":"PUBLIC_COST_ASSUMPTIONS");
    }
    private void product(String symbol){if(symbol==null||!List.of("XAU_USDT","GATE:XAUUSD").contains(symbol))throw new IllegalArgumentException("UNKNOWN_PRODUCT");}
}
