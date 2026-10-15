package com.goldcatcher.market;

import static com.goldcatcher.market.MarketTypes.*;
import com.goldcatcher.*;
import com.goldcatcher.persistence.DocumentStore;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.*;

@Service
public class MarketFeed {
    public static final String PRODUCT="XAU_USDT";
    private static final Map<String,String> FRAMES=Map.of("1","1m","5","5m","15","15m","60","1h","240","4h","D","1d");
    private final RuntimeSettings settings;private final RemoteHttp http;private final ObjectMapper mapper;
    private final DocumentStore store;private final IndicatorEngine engine;private final EventHub events;private final Clock clock;private final GateCfdFeed gate;
    private final Map<String,List<Candle>> candles=new ConcurrentHashMap<>();private final Map<String,Indicators> calculated=new ConcurrentHashMap<>();
    private final Map<String,List<IndicatorReplay.Calculation>> indicatorInputs=new ConcurrentHashMap<>();
    private record Inbound(long generation,String raw,Instant receivedAt) {}
    public record StreamFrame(String symbol,String channel,long connectionEpoch,Instant sourceAt,Instant receivedAt,String raw) {}
    private final BlockingQueue<Inbound> incoming=new ArrayBlockingQueue<>(1024);private final AtomicLong generation=new AtomicLong();
    private final Thread processor;private long processedGeneration=-1,lastBookSequence=-1;
    private volatile Quote quote;private volatile Instrument rules;private volatile String error,historyError;
    private volatile WebSocket socket;private volatile Instant lastMessage=Instant.EPOCH,nextFundingTime,lastHistorySuccess;
    private final List<Consumer<Quote>> consumers=new CopyOnWriteArrayList<>();
    private final List<Consumer<StreamFrame>> frameConsumers=new CopyOnWriteArrayList<>();
    private final GateDepthBook depth=new GateDepthBook();private final java.util.concurrent.atomic.AtomicBoolean depthBootstrap=new java.util.concurrent.atomic.AtomicBoolean();
    private JsonNode ticker;private Instant tickerTime;private boolean disconnected=true;
    public MarketFeed(RuntimeSettings settings,RemoteHttp http,ObjectMapper mapper,DocumentStore store,
        IndicatorEngine engine,EventHub events,Clock clock,GateCfdFeed gate) {
        this.settings=settings;this.http=http;this.mapper=mapper;this.store=store;this.engine=engine;this.events=events;this.clock=clock;this.gate=gate;
        gate.onQuote(q->consumers.forEach(consumer->consumer.accept(q)));
        ticker=mapper.createObjectNode();processor=Thread.ofVirtual().name("gold-market-events").start(()->{
            while(!Thread.currentThread().isInterrupted())try{process(incoming.take());}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
            catch(Exception failure){error="INVALID_MARKET_EVENT";disconnect();}
        });
    }
    public void onQuote(Consumer<Quote> consumer){consumers.add(consumer);}
    public void onFrame(Consumer<StreamFrame> consumer){frameConsumers.add(consumer);}
    public Quote latestQuote(){return quote;}
    public Instrument instrument(){return rules;}
    public Map<String,Object> depth(){return depth.view(clock.instant());}
    public List<Candle> candles(String symbol,String frame){if(!FRAMES.containsKey(frame))throw new IllegalArgumentException("UNKNOWN_FRAME");
        if(GateCfdFeed.PRODUCT.equals(symbol))return gate.candles(frame);if(!PRODUCT.equals(symbol))throw new IllegalArgumentException("UNKNOWN_PRODUCT");return candles.getOrDefault(frame,List.of());}
    public List<IndicatorReplay.Calculation> indicatorInputs(String symbol,Map<String,Indicators> frozen){
        return GateCfdFeed.PRODUCT.equals(symbol)?gate.indicatorInputs(frozen):IndicatorReplay.select(Map.copyOf(indicatorInputs),frozen);}
    public BigDecimal feeRate(String symbol){return PRODUCT.equals(symbol)?settings.decimal("GOLD_TAKER_FEE_RATE",null)!=null?
        settings.decimal("GOLD_TAKER_FEE_RATE",null):rules==null?null:rules.takerFeeRate():null;}
    public Map<String,String> costConfiguration(String symbol) {
        if(GateCfdFeed.PRODUCT.equals(symbol))return gate.costConfiguration();
        BigDecimal fee=feeRate(symbol);
        return Map.of("takerFeeRate",fee==null?"UNKNOWN":fee.toPlainString(),"source",PRODUCT.equals(symbol)?
            settings.decimal("GOLD_TAKER_FEE_RATE",null)!=null?"CONFIGURED_OVERRIDE_ASSUMPTION":"GATE_PUBLIC_CONTRACT_TAKER_ASSUMPTION":"PRODUCT_SPECIFIC_COSTS_REQUIRED",
            "reference",PRODUCT.equals(symbol)?settings.text("GOLD_GATE_HTTP_URL","https://api.gateio.ws/api/v4")+"/futures/usdt/contracts/XAU_USDT":"");
    }
    public Snapshot snapshot(String symbol) {
        if(GateCfdFeed.PRODUCT.equals(symbol))return gate.snapshot();
        if(!PRODUCT.equals(symbol))throw new IllegalArgumentException("UNKNOWN_PRODUCT");
        String state=!settings.enabled("GOLD_MARKET_ENABLED")?"DISABLED":clock instanceof ExchangeClock exchange&&!exchange.verified()?"CLOCK_UNVERIFIED":
            quote!=null&&quote.fresh(clock.instant())?"LIVE":"STALE_OR_CONNECTING";
        var presented=new LinkedHashMap<String,Indicators>();calculated.forEach((frame,indicator)->presented.put(frame,engine.present(indicator,clock.instant(),false)));
        return new Snapshot(symbol,state,quote,rules,Map.copyOf(presented),candles.getOrDefault("5",List.of()),clock.instant(),error);
    }
    public Map<String,Object> provenance(String symbol) {
        if(GateCfdFeed.PRODUCT.equals(symbol))return gate.provenance();
        Map<String,Object> info=new LinkedHashMap<>();info.put("venue","GATE");info.put("nativeSymbol",PRODUCT);info.put("transport","WEBSOCKET_L1");
        info.put("quoteTimestampMeaning","BOOK_TICKER_EVENT_TIME");info.put("depthStatus","L1_VISIBLE_SIZE");info.put("rulesState",rules==null?"UNKNOWN":"PUBLIC_RULES_TAKER_ASSUMPTION");
        info.put("simulationReady",rules!=null&&feeRate(symbol)!=null);info.put("historyError",historyError);info.put("lastHistorySuccess",lastHistorySuccess);
        info.put("quantityUnit","XAU base quantity; native contracts = quantity / quantityMultiplier");
        info.put("connectionState",socket!=null&&lastMessage.isAfter(clock.instant().minusSeconds(35))?"CONNECTED":"DISCONNECTED");
        info.put("lastMessageAt",lastMessage);info.put("quoteAgeMillis",quote==null?null:Math.max(0,Duration.between(quote.exchangeTime(),clock.instant()).toMillis()));
        info.put("quoteFreshness",quote==null?"NO_QUOTE":quote.fresh(clock.instant())?"FRESH":"NO_RECENT_PRICE_EVENT");
        info.put("executionFreshnessMillis",3000);
        info.put("leverageStepMethod","WHOLE_INTEGER_LEVERAGE_ASSUMPTION");info.put("historyState",calculated.size()==6?"READY":"WARMING_UP");return info;
    }
    @Scheduled(fixedDelay=15000,initialDelay=1000)
    void connection() {
        if(!settings.enabled("GOLD_MARKET_ENABLED"))return;
        if(socket!=null&&Duration.between(lastMessage,clock.instant()).toSeconds()>35)disconnect();
        if(socket!=null){socket.sendText("{\"time\":"+clock.instant().getEpochSecond()+",\"channel\":\"futures.ping\"}",true);return;}
        try {
            long epoch=generation.incrementAndGet();
            socket=http.client.newWebSocketBuilder().header("X-Gate-Size-Decimal","1").connectTimeout(Duration.ofSeconds(10))
                .buildAsync(URI.create(settings.text("GOLD_GATE_WS_URL","wss://fx-ws.gateio.ws/v4/ws/usdt")),new Listener(epoch)).get(12,TimeUnit.SECONDS);
            lastMessage=clock.instant();
            for(String channel:List.of("futures.book_ticker","futures.tickers","futures.trades"))socket.sendText("{\"time\":"+clock.instant().getEpochSecond()+",\"channel\":\""+channel+"\",\"event\":\"subscribe\",\"payload\":[\"XAU_USDT\"]}",true);
            socket.sendText("{\"time\":"+clock.instant().getEpochSecond()+",\"channel\":\"futures.order_book_update\",\"event\":\"subscribe\",\"payload\":[\"XAU_USDT\",\"100ms\",\"100\"]}",true);
        }catch(Exception failure){error="WEBSOCKET_CONNECT_FAILED";disconnect();}
    }
    @Scheduled(fixedDelay=60000,initialDelay=1000)
    void refreshHistory() {
        if(!settings.enabled("GOLD_MARKET_ENABLED"))return;
        try {
            var spec=get("/futures/usdt/contracts/XAU_USDT");var tiers=get("/futures/usdt/risk_limit_tiers?contract=XAU_USDT");
            rules=parseRules(spec,tiers,clock.instant());nextFundingTime=Instant.ofEpochSecond(spec.path("funding_next_apply").asLong());
            store.save("instrument",PRODUCT,rules);
            for(String frame:List.of("1","5","15","60","240","D")) {
                var body=get("/futures/usdt/candlesticks?contract=XAU_USDT&interval="+FRAMES.get(frame)+"&limit=200");
                var history=parseCandles(body,frame,rules.quantityMultiplier(),clock.instant());candles.put(frame,history);
                var calculation=IndicatorReplay.calculate(engine,frame,history,clock.instant());
                indicatorInputs.compute(frame,(key,old)->IndicatorReplay.append(old,calculation));
                calculated.put(frame,calculation.output());
            }
            lastHistorySuccess=clock.instant();historyError=null;events.changed();
        }catch(Exception failure){historyError="REST_MARKET_REFRESH_FAILED";events.changed();}
    }
    static Instrument parseRules(JsonNode spec,JsonNode risk,Instant receivedAt) {
        if(!spec.path("name").asText().equals(PRODUCT)||!spec.path("type").asText().equals("direct")||spec.path("enable_decimal").asBoolean(true))
            throw new IllegalStateException("UNSUPPORTED_CONTRACT_RULES");
        BigDecimal multiplier=decimal(spec.path("quanto_multiplier"));if(multiplier==null||multiplier.signum()<=0)throw new IllegalStateException("INVALID_CONTRACT_MULTIPLIER");
        var tiers=new ArrayList<RiskTier>();for(var tier:risk)tiers.add(new RiskTier(decimal(tier.path("risk_limit")),decimal(tier.path("leverage_max")),decimal(tier.path("maintenance_rate")),decimal(tier.path("deduction"))));
        tiers.sort(Comparator.comparing(RiskTier::limit));if(tiers.isEmpty())throw new IllegalStateException("MISSING_RISK_TIERS");
        return new Instrument(PRODUCT,spec.path("status").asText().equals("trading")&&!spec.path("in_delisting").asBoolean()?"Trading":"NotTrading","USDT",
            decimal(spec.path("order_price_round")),multiplier,decimal(spec.path("order_size_min")).multiply(multiplier),
            decimal(spec.path("market_order_size_max")).multiply(multiplier),decimal(spec.path("order_size_max")).multiply(multiplier),BigDecimal.ZERO,
            decimal(spec.path("leverage_min")),decimal(spec.path("leverage_max")),BigDecimal.ONE,List.copyOf(tiers),receivedAt,multiplier,decimal(spec.path("taker_fee_rate")));
    }
    static List<Candle> parseCandles(JsonNode body,String frame,BigDecimal multiplier,Instant cutoff) {
        if(!body.isArray())throw new IllegalStateException("INVALID_CANDLES");long seconds=frame.equals("D")?86400:Long.parseLong(frame)*60;
        Map<Instant,Candle> result=new TreeMap<>();
        for(var row:body) {
            long time=row.path("t").asLong(-1);if(time<=0)throw new IllegalStateException("INVALID_CANDLE_TIME");
            Instant start=Instant.ofEpochSecond(time),end=start.plusSeconds(seconds);if(end.isAfter(cutoff))continue;
            var open=decimal(row.path("o"));var high=decimal(row.path("h"));var low=decimal(row.path("l"));var close=decimal(row.path("c"));var volume=decimal(row.path("v"));
            if(open==null||high==null||low==null||close==null||volume==null||low.signum()<=0||volume.signum()<0||high.compareTo(open.max(close))<0||low.compareTo(open.min(close))>0)
                throw new IllegalStateException("INVALID_CANDLE");
            Candle candle=new Candle(start,end,open,high,low,close,volume.multiply(multiplier));
            var previous=result.putIfAbsent(start,candle);if(previous!=null&&!previous.equals(candle))throw new IllegalStateException("CONFLICTING_CANDLE");
        }return List.copyOf(result.values());
    }
    private JsonNode get(String resource) throws Exception {
        var response=http.send(HttpRequest.newBuilder(URI.create(settings.text("GOLD_GATE_HTTP_URL","https://api.gateio.ws/api/v4")+resource))
            .header("X-Gate-Size-Decimal","1").timeout(Duration.ofSeconds(8)).GET().build(),2_000_000);
        if(response.statusCode()!=200)throw new IllegalStateException("GATE_HTTP_"+response.statusCode());return mapper.readTree(response.body());
    }
    private synchronized void process(Inbound inbound) {
        if(inbound.generation()!=generation.get())return;
        if(processedGeneration!=inbound.generation()){processedGeneration=inbound.generation();disconnected=true;lastBookSequence=-1;ticker=mapper.createObjectNode();tickerTime=null;depth.reset();
            frameConsumers.forEach(c->c.accept(new StreamFrame(PRODUCT,"CONNECTION_EPOCH",inbound.generation(),null,inbound.receivedAt(),"{\"continuity\":\"NEW_CONNECTION\"}")));}
        JsonNode payload=mapper.readTree(inbound.raw());lastMessage=clock.instant();if(payload.hasNonNull("error"))throw new IllegalStateException("GATE_WS_ERROR");
        if(!payload.path("event").asText().equals("update"))return;
        String channel=payload.path("channel").asText();var data=payload.path("result");
        {
            var frame=new StreamFrame(PRODUCT,channel,inbound.generation(),payload.path("time_ms").asLong()>0?Instant.ofEpochMilli(payload.path("time_ms").asLong()):null,inbound.receivedAt(),inbound.raw());
            frameConsumers.forEach(consumer->consumer.accept(frame));
        }
        if(channel.equals("futures.order_book_update")){if(depth.update(data,inbound.receivedAt()))bootstrapDepth(inbound.generation());events.changed();return;}
        if(channel.equals("futures.tickers")) {
            for(var row:data)if(row.path("contract").asText().equals(PRODUCT)){ticker=row;tickerTime=Instant.ofEpochMilli(row.path("t").asLong(payload.path("time_ms").asLong()));}
            var previous=quote;
            if(previous!=null)quote=new Quote(previous.symbol(),previous.source(),previous.sequence(),previous.exchangeTime(),previous.receivedAt(),previous.bid(),previous.ask(),previous.bidSize(),previous.askSize(),decimal(ticker.path("last")),decimal(ticker.path("mark_price")),tickerTime,decimal(ticker.path("funding_rate")),nextFundingTime,previous.continuous());
            events.changed();
            return;
        }
        if(!channel.equals("futures.book_ticker")||!data.path("s").asText().equals(PRODUCT)||rules==null)return;
        long sequence=data.path("u").asLong(-1);if(sequence<0)throw new IllegalStateException("INVALID_BOOK_SEQUENCE");if(sequence<=lastBookSequence)return;
        Quote next=parseBook(data,rules,ticker,tickerTime,nextFundingTime,inbound.receivedAt(),!disconnected);
        quote=next;lastBookSequence=sequence;disconnected=false;error=null;consumers.forEach(consumer->consumer.accept(next));events.changed();
    }
    private void bootstrapDepth(long epoch){if(rules==null||!depthBootstrap.compareAndSet(false,true))return;
        Thread.ofVirtual().name("gold-depth-bootstrap").start(()->{try{var body=get("/futures/usdt/order_book?contract=XAU_USDT&limit=100&with_id=true");var received=clock.instant();
            synchronized(this){if(epoch!=generation.get())return;depth.bootstrap(body,rules.quantityMultiplier(),received);
                frameConsumers.forEach(c->c.accept(new StreamFrame(PRODUCT,"DEPTH_REST_BASE",epoch,null,received,mapper.writeValueAsString(body))));}}
            catch(Exception failed){historyError="DEPTH_BOOTSTRAP_FAILED";}finally{depthBootstrap.set(false);}});
    }
    static Quote parseBook(JsonNode book,Instrument rules,JsonNode ticker,Instant tickerTime,Instant nextFundingTime,Instant receivedAt,boolean continuous) {
        return new Quote(PRODUCT,"GATE_WS_BOOK_TICKER",book.path("u").asLong(),Instant.ofEpochMilli(book.path("t").asLong()),receivedAt,
            decimal(book.path("b")),decimal(book.path("a")),decimal(book.path("B")).multiply(rules.quantityMultiplier()),decimal(book.path("A")).multiply(rules.quantityMultiplier()),
            decimal(ticker.path("last")),decimal(ticker.path("mark_price")),tickerTime,decimal(ticker.path("funding_rate")),nextFundingTime,continuous);
    }
    private void disconnect(){generation.incrementAndGet();WebSocket current=socket;socket=null;disconnected=true;if(current!=null)current.abort();}
    private void disconnect(long epoch){if(epoch==generation.get())disconnect();}
    @PreDestroy void stop(){disconnect();processor.interrupt();}
    static BigDecimal decimal(JsonNode node){return node.isMissingNode()||node.isNull()||node.asText().isBlank()?null:new BigDecimal(node.asText());}
    private final class Listener implements WebSocket.Listener {
        private final StringBuilder frame=new StringBuilder();private final long epoch;private Listener(long epoch){this.epoch=epoch;}
        public void onOpen(WebSocket ws){ws.request(1);}
        public CompletionStage<?> onText(WebSocket ws,CharSequence chunk,boolean last) {
            frame.append(chunk);if(epoch!=generation.get()){ws.abort();return null;}
            if(frame.length()>200_000){error="WEBSOCKET_FRAME_TOO_LARGE";disconnect(epoch);return null;}
            if(last){if(!incoming.offer(new Inbound(epoch,frame.toString(),clock.instant()))){error="MARKET_QUEUE_OVERFLOW";disconnect(epoch);}frame.setLength(0);}ws.request(1);return null;
        }
        public CompletionStage<?> onClose(WebSocket ws,int code,String reason){disconnect(epoch);return null;}
        public void onError(WebSocket ws,Throwable failure){if(epoch==generation.get()){error="WEBSOCKET_DISCONNECTED";disconnect(epoch);}}
    }
}
