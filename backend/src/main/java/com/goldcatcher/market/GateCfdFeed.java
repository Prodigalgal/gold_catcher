package com.goldcatcher.market;

import static com.goldcatcher.market.MarketTypes.*;
import com.goldcatcher.*;
import com.goldcatcher.persistence.DocumentStore;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.*;

/** Gate TradFi observations and two signed read-only product endpoints; no trading calls. */
@Service
public class GateCfdFeed {
    public static final String PRODUCT="GATE:XAUUSD";
    private static final Map<String,String> FRAMES=Map.of("1","1m","5","5m","15","15m","60","1h","240","4h","D","1d");
    private final RuntimeSettings settings;private final RemoteHttp http;private final ObjectMapper mapper;
    private final IndicatorEngine engine;private final DocumentStore store;private final EventHub events;private final Clock clock;
    private final Map<String,List<Candle>> candles=new ConcurrentHashMap<>();
    private final Map<String,Indicators> calculated=new ConcurrentHashMap<>();
    private volatile Quote quote;private volatile JsonNode symbolSummary,cfdRules;
    private volatile String ruleState="CREDENTIAL_NOT_CONFIGURED",ruleError;
    private volatile Instant rulesReceivedAt;
    private volatile BigDecimal commissionPerLot;
    private volatile String marketStatus="unknown",quoteError,historyError;
    private volatile Instant lastQuoteSuccess,lastHistorySuccess,nextPoll=Instant.EPOCH;
    private volatile Instant lastSymbolSuccess;
    private int failures;
    private long lastKlineRequest;
    private long nextRulesAttempt;
    public GateCfdFeed(RuntimeSettings settings,RemoteHttp http,ObjectMapper mapper,IndicatorEngine engine,
        DocumentStore store,EventHub events,Clock clock) {
        this.settings=settings;this.http=http;this.mapper=mapper;this.engine=engine;this.store=store;this.events=events;this.clock=clock;
    }
    private boolean enabled(){return settings.enabled("GOLD_MARKET_ENABLED")&&Boolean.parseBoolean(settings.text("GOLD_GATE_ENABLED","true"));}
    public Snapshot snapshot() {
        String state=!enabled()?"DISABLED":clock instanceof ExchangeClock exchange&&!exchange.verified()?"CLOCK_UNVERIFIED":
            !marketStatus.equals("open")&&quote!=null?"MARKET_CLOSED":quote!=null&&quote.priceFresh(clock.instant())?"LIVE":"STALE_OR_CONNECTING";
        return new Snapshot(PRODUCT,state,quote,null,Map.copyOf(calculated),candles.getOrDefault("5",List.of()),clock.instant(),quoteError);
    }
    public Map<String,Object> provenance() {
        Map<String,Object> info=new LinkedHashMap<>();
        info.put("venue","GATE");info.put("nativeSymbol","XAUUSD");info.put("transport","REST_POLL");info.put("pollSeconds",1);
        info.put("quoteTimestampMeaning","SERVER_RESPONSE_TIME; last quote tick time is not supplied");
        info.put("depthStatus","NOT_PROVIDED");info.put("rulesState",ruleState);info.put("simulationReady",false);
        info.put("rulesError",ruleError);info.put("rulesReceivedAt",rulesReceivedAt);info.put("cfdRules",cfdRules);
        info.put("simulationPending","CFD execution semantics and swap type/time conventions still require verification; authenticated product data alone does not establish these.");
        info.put("marketStatus",marketStatus);info.put("lastQuoteSuccess",lastQuoteSuccess);info.put("nextPoll",nextPoll);
        info.put("lastHistorySuccess",lastHistorySuccess);info.put("historyError",historyError);
        info.put("historyState",calculated.size()==6&&calculated.values().stream().allMatch(i->i.state().equals("READY"))?"READY":"WARMING_UP");
        info.put("publicSymbolSummary",symbolSummary);
        return info;
    }
    public Map<String,String> costConfiguration() {
        return Map.of("takerFeeRate","NOT_APPLICABLE","feePerLot",commissionPerLot==null?"UNKNOWN":commissionPerLot.toPlainString(),
            "source",commissionPerLot==null?"PRODUCT_SPECIFIC_COSTS_REQUIRED":"GATE_AUTHENTICATED_COMMISSION_PER_LOT",
            "reference","https://www.gate.com/docs/developers/apiv4/en/cfd/","feeUnit","USD_PER_LOT","swapStatus","CONVENTION_UNVERIFIED");
    }
    @Scheduled(fixedDelay=10000,initialDelay=3500)
    void refreshAuthenticatedRules() {
        if(!enabled())return;
        if(settings.text("GOLD_GATE_API_KEY","").isBlank()||settings.text("GOLD_GATE_API_SECRET","").isBlank())return;
        if(clock instanceof ExchangeClock exchange&&!exchange.verified()){ruleState="CLOCK_UNVERIFIED";return;}
        if(nextRulesAttempt!=0&&System.nanoTime()-nextRulesAttempt<0)return;
        try {
            JsonNode detail=null,commission=null;
            for(var item:getAuthenticated("detail").path("data").path("list"))if(item.path("symbol").asText().equals("XAUUSD"))detail=item;
            for(var item:getAuthenticated("commissions").path("data").path("list"))if(item.path("symbol").asText().equals("XAUUSD"))commission=item;
            if(detail==null||commission==null)throw new IllegalStateException("GATE_CFD_RULES_MISSING");
            BigDecimal fee=new BigDecimal(commission.path("fee_per_lot").asText());if(fee.signum()<0)throw new IllegalStateException("GATE_INVALID_COMMISSION");
            var selected=mapper.createObjectNode();
            for(String key:List.of("symbol","contract_volume","min_order_volume","max_order_volume","step_order_volume","leverage","leverages","price_precision","settlement_currency","exchange_rate",
                "price_sl_level","hedged_margin","buy_swap_cost_rate","sell_swap_cost_rate","swap_cost_type","swap_cost_3day","trade_timezone","trade_time","weekly_limit_leverage","trade_mode"))
                if(detail.has(key))selected.set(key,detail.path(key));
            selected.put("commission_per_lot",fee.toPlainString());selected.put("swapConvention","UNVERIFIED");
            selected.put("executionConvention","UNVERIFIED_NO_DEPTH_OR_TICK_TIMESTAMP");
            cfdRules=selected;commissionPerLot=fee;rulesReceivedAt=clock.instant();ruleState="SPEC_AND_COMMISSION_VERIFIED";ruleError=null;
            store.save("instrument-observation",PRODUCT,Map.of("rules",selected,"receivedAt",rulesReceivedAt,"source","GATE_AUTHENTICATED_GET"));events.changed();
            nextRulesAttempt=System.nanoTime()+Duration.ofMinutes(5).toNanos();
        }catch(Exception failure){ruleState="RULE_QUERY_FAILED";ruleError=safe(failure);events.changed();
            nextRulesAttempt=System.nanoTime()+Duration.ofSeconds(30).toNanos();}
    }
    private JsonNode getAuthenticated(String resource) throws Exception {
        if(!Set.of("detail","commissions").contains(resource))throw new IllegalArgumentException("READ_ONLY_ENDPOINT_REQUIRED");
        URI base=URI.create(settings.text("GOLD_GATE_HTTP_URL","https://api.gateio.ws/api/v4"));
        if(!"https".equals(base.getScheme())||!"api.gateio.ws".equals(base.getHost())||base.getUserInfo()!=null||base.getQuery()!=null||base.getFragment()!=null)
            throw new IllegalStateException("GATE_PRIVATE_ENDPOINT_REJECTED");
        String path="/api/v4/tradfi/symbols/"+resource,query="symbols=XAUUSD",timestamp=Long.toString(clock.instant().getEpochSecond());
        String signature=sign(settings.text("GOLD_GATE_API_SECRET",""),path,query,timestamp);
        var response=http.send(HttpRequest.newBuilder(URI.create("https://api.gateio.ws"+path+"?"+query))
            .header("KEY",settings.text("GOLD_GATE_API_KEY","")).header("Timestamp",timestamp).header("SIGN",signature)
            .header("Accept","application/json").timeout(Duration.ofSeconds(8)).GET().build(),100_000);
        if(response.statusCode()!=200)throw new IllegalStateException("GATE_RULE_HTTP_"+response.statusCode());
        var body=mapper.readTree(response.body());requireSuccess(body);return body;
    }
    static String sign(String secret,String path,String query,String timestamp) throws Exception {
        String emptyHash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-512").digest(new byte[0]));
        String payload="GET\n"+path+"\n"+query+"\n"+emptyHash+"\n"+timestamp;
        var mac=javax.crypto.Mac.getInstance("HmacSHA512");mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8),"HmacSHA512"));
        return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    @Scheduled(fixedDelay=1000,initialDelay=1200)
    void pollQuote() {
        if(!enabled()||clock.instant().isBefore(nextPoll))return;
        try {
            var body=get("/tradfi/symbols/XAUUSD/tickers",Duration.ofSeconds(3));
            Quote next=parseQuote(body,clock.instant());
            marketStatus=body.path("data").path("status").asText("unknown");quote=next;
            lastQuoteSuccess=clock.instant();quoteError=null;failures=0;nextPoll=clock.instant();events.changed();
        }catch(Exception failure){quoteError=safe(failure);failures=Math.min(6,failures+1);nextPoll=clock.instant().plusSeconds(1L<<failures);events.changed();}
    }
    @Scheduled(fixedDelay=60000,initialDelay=2200)
    void refreshHistory() {
        if(!enabled())return;
        try {
            if(symbolSummary==null||lastSymbolSuccess==null||Duration.between(lastSymbolSuccess,clock.instant()).toMinutes()>=60) {
                var body=get("/tradfi/symbols",Duration.ofSeconds(8));
                JsonNode found=null;
                for(var row:body.path("data").path("list"))if(row.path("symbol").asText().equals("XAUUSD")){found=row;break;}
                if(found==null)throw new IllegalStateException("GATE_SYMBOL_MISSING");
                // Icons and other irrelevant fields never enter the research context.
                var selected=mapper.createObjectNode();
                for(String key:List.of("symbol","leverages","price_precision","settlement_currency","status","trade_mode","open_time","close_time","next_open_time"))
                    if(found.has(key))selected.set(key,found.path(key));
                symbolSummary=selected;lastSymbolSuccess=clock.instant();
            }
            for(String frame:List.of("1","5","15","60","240","D")) {
                var body=get("/tradfi/symbols/XAUUSD/klines?kline_type="+FRAMES.get(frame)+"&limit=200",Duration.ofSeconds(8));
                Instant serverTime=timestamp(body),cutoff=serverTime.isBefore(clock.instant())?serverTime:clock.instant();
                var history=parseCandles(body,frame,cutoff);candles.put(frame,history);
                calculated.put(frame,engine.calculate(frame,history,cutoff));
            }
            lastHistorySuccess=clock.instant();historyError=null;
            store.save("market-indicators",PRODUCT,Map.copyOf(calculated));events.changed();
        }catch(Exception failure){historyError=safe(failure);events.changed();}
    }
    static Quote parseQuote(JsonNode body,Instant receivedAt) {
        requireSuccess(body);Instant at=timestamp(body);var data=body.path("data");
        BigDecimal bid=positive(data.path("bid_price")),ask=positive(data.path("ask_price")),last=positive(data.path("last_price"));
        if(ask.compareTo(bid)<0)throw new IllegalStateException("GATE_CROSSED_QUOTE");
        return new Quote(PRODUCT,"GATE_REST_CFD",at.toEpochMilli(),at,receivedAt,bid,ask,null,null,last,null,null,null,null,false);
    }
    static List<Candle> parseCandles(JsonNode body,String frame,Instant cutoff) {
        requireSuccess(body);long seconds=frame.equals("D")?86400:Long.parseLong(frame)*60;
        if(!body.path("data").path("list").isArray())throw new IllegalStateException("GATE_INVALID_KLINES");
        Map<Instant,Candle> history=new TreeMap<>();
        for(var row:body.path("data").path("list")) {
            long t=row.path("t").asLong(-1);if(t<=0)throw new IllegalStateException("GATE_INVALID_KLINE_TIME");
            Instant start=Instant.ofEpochSecond(t),end=start.plusSeconds(seconds);
            if(end.isAfter(cutoff))continue;
            BigDecimal open=positive(row.path("o")),high=positive(row.path("h")),low=positive(row.path("l")),close=positive(row.path("c"));
            if(high.compareTo(low)<0||high.compareTo(open.max(close))<0||low.compareTo(open.min(close))>0)
                throw new IllegalStateException("GATE_INVALID_OHLC");
            var candle=new Candle(start,end,open,high,low,close,null); // CFD API supplies no traded volume.
            var previous=history.putIfAbsent(start,candle);
            if(previous!=null&&!previous.equals(candle))throw new IllegalStateException("GATE_CONFLICTING_KLINE");
        }
        return List.copyOf(history.values());
    }
    private JsonNode get(String resource,Duration timeout) throws Exception {
        if(resource.contains("/klines?"))paceKlines();
        var response=http.send(HttpRequest.newBuilder(URI.create(settings.text("GOLD_GATE_HTTP_URL","https://api.gateio.ws/api/v4")+resource))
            .header("Accept","application/json").header("Cache-Control","no-cache").timeout(timeout).GET().build(),1_000_000);
        if(response.statusCode()!=200)throw new IllegalStateException("GATE_HTTP_"+response.statusCode());
        var body=mapper.readTree(response.body());requireSuccess(body);return body;
    }
    private synchronized void paceKlines() throws InterruptedException {
        long remaining=300_000_000L-(System.nanoTime()-lastKlineRequest);
        if(remaining>0)Thread.sleep(Duration.ofNanos(remaining));
        lastKlineRequest=System.nanoTime();
    }
    private static void requireSuccess(JsonNode body) {
        if(!body.path("label").asText().isBlank()||!body.path("data").isObject())throw new IllegalStateException("GATE_INVALID_RESPONSE");
    }
    private static Instant timestamp(JsonNode body) {
        long millis=body.path("timestamp").asLong(-1);if(millis<=0)throw new IllegalStateException("GATE_MISSING_TIMESTAMP");return Instant.ofEpochMilli(millis);
    }
    private static BigDecimal positive(JsonNode node) {
        if(!node.isTextual()||node.asText().length()>48)throw new IllegalStateException("GATE_INVALID_PRICE");
        BigDecimal value=new BigDecimal(node.asText());if(value.signum()<=0)throw new IllegalStateException("GATE_INVALID_PRICE");return value;
    }
    private static String safe(Exception failure){String code=failure.getMessage();return code!=null&&code.matches("GATE_[A-Z0-9_]+")?code:"GATE_REQUEST_FAILED";}
}
