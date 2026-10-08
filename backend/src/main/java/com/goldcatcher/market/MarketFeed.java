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
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicLong;
import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;

@Service
public class MarketFeed {
    private final RuntimeSettings settings;private final RemoteHttp http;private final ObjectMapper mapper;
    private final DocumentStore store;private final IndicatorEngine engine;private final EventHub events;private final Clock clock;
    private final Map<String,List<Candle>> candles=new ConcurrentHashMap<>();
    private final Map<String,Indicators> calculated=new ConcurrentHashMap<>();
    private record Inbound(long generation,String raw) {}
    private final BlockingQueue<Inbound> incoming=new ArrayBlockingQueue<>(1024);
    private final AtomicLong generation=new AtomicLong();
    private final Thread processor;
    private long processedGeneration=-1;
    private volatile Quote quote;private volatile Instrument rules;private volatile String error;
    private volatile WebSocket socket;private volatile Instant lastMessage=Instant.EPOCH;
    private volatile Consumer<Quote> consumer=q->{};
    private ObjectNode ticker;private long lastBookSequence=-1;private boolean disconnected=true;
    public MarketFeed(RuntimeSettings settings,RemoteHttp http,ObjectMapper mapper,DocumentStore store,
        IndicatorEngine engine,EventHub events,Clock clock) {
        this.settings=settings;this.http=http;this.mapper=mapper;this.store=store;this.engine=engine;this.events=events;this.clock=clock;
        ticker=mapper.createObjectNode();
        processor=Thread.ofVirtual().name("gold-market-events").start(()->{
            while(!Thread.currentThread().isInterrupted())try{process(incoming.take());}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
            catch(Exception failure){error="INVALID_MARKET_EVENT";disconnect();}
        });
    }
    public void onQuote(Consumer<Quote> consumer) {this.consumer=consumer;}
    public Quote latestQuote(){return quote;}
    public Instrument instrument(){return rules;}
    public Snapshot snapshot(String symbol) {
        if(!"XAUUSDT".equals(symbol))return new Snapshot(symbol,"WAITING_MT5",null,null,Map.of(),List.of(),clock.instant(),null);
        String state=!settings.enabled("GOLD_MARKET_ENABLED")?"DISABLED":clock instanceof ExchangeClock exchange&&!exchange.verified()?"CLOCK_UNVERIFIED":quote!=null&&quote.fresh(clock.instant())?"LIVE":"STALE_OR_CONNECTING";
        return new Snapshot(symbol,state,quote,rules,Map.copyOf(calculated),candles.getOrDefault("5",List.of()),clock.instant(),error);
    }
    @Scheduled(fixedDelay=15000,initialDelay=1000)
    void connection() {
        if(!settings.enabled("GOLD_MARKET_ENABLED"))return;
        if(socket!=null&&Duration.between(lastMessage,clock.instant()).toSeconds()>35)disconnect();
        if(socket!=null){socket.sendText("{\"op\":\"ping\"}",true);return;}
        try {
            long epoch=generation.incrementAndGet();
            socket=http.client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10))
                .buildAsync(URI.create(settings.text("GOLD_BYBIT_WS_URL","wss://stream.bybit.com/v5/public/linear")),new Listener(epoch)).get(12,TimeUnit.SECONDS);
            lastMessage=clock.instant();
            socket.sendText("{\"op\":\"subscribe\",\"args\":[\"orderbook.1.XAUUSDT\",\"tickers.XAUUSDT\"]}",true);
        } catch(Exception failure){error="WEBSOCKET_CONNECT_FAILED";disconnect();}
    }
    @Scheduled(fixedDelay=60000,initialDelay=1000)
    void refreshHistory() {
        if(!settings.enabled("GOLD_MARKET_ENABLED"))return;
        try {
            if(rules==null||Duration.between(rules.receivedAt(),clock.instant()).toMinutes()>=60)refreshRules();
            for(String frame:List.of("1","5","15","60","240","D")) {
                JsonNode response=get("kline","&interval="+frame+"&limit=200");
                long seconds=frame.equals("D")?86400:Long.parseLong(frame)*60;
                List<Candle> history=new ArrayList<>();
                Instant cutoff=Instant.ofEpochMilli(response.path("time").asLong()).isBefore(clock.instant())?
                    Instant.ofEpochMilli(response.path("time").asLong()):clock.instant();
                for(var row:response.path("result").path("list")) {
                    Instant start=Instant.ofEpochMilli(Long.parseLong(row.path(0).asText())),end=start.plusSeconds(seconds);
                    if(!end.isAfter(cutoff))history.add(new Candle(start,end,decimal(row.path(1)),decimal(row.path(2)),decimal(row.path(3)),decimal(row.path(4)),decimal(row.path(5))));
                }
                history.sort(Comparator.comparing(Candle::start));candles.put(frame,List.copyOf(history));
                calculated.put(frame,engine.calculate(frame,history,cutoff));
            }
            error=null;events.changed();
        }catch(Exception failure){error="REST_MARKET_REFRESH_FAILED";events.changed();}
    }
    private void refreshRules() throws Exception {
        var spec=get("instruments-info","").path("result").path("list").path(0);
        if(!spec.path("symbol").asText().equals("XAUUSDT"))throw new IllegalStateException("SYMBOL_MISMATCH");
        List<RiskTier> tiers=new ArrayList<>();
        for(var tier:get("risk-limit","").path("result").path("list"))tiers.add(new RiskTier(decimal(tier.path("riskLimitValue")),
            decimal(tier.path("maxLeverage")),decimal(tier.path("maintenanceMargin")),decimal(tier.path("mmDeduction"))));
        tiers.sort(Comparator.comparing(RiskTier::limit));
        var lot=spec.path("lotSizeFilter");var leverage=spec.path("leverageFilter");
        rules=new Instrument("XAUUSDT",spec.path("status").asText(),spec.path("settleCoin").asText(),decimal(spec.path("priceFilter").path("tickSize")),
            decimal(lot.path("qtyStep")),decimal(lot.path("minOrderQty")),decimal(lot.path("maxMktOrderQty")),decimal(lot.path("maxOrderQty")),decimal(lot.path("minNotionalValue")),
            decimal(leverage.path("minLeverage")),decimal(leverage.path("maxLeverage")),decimal(leverage.path("leverageStep")),List.copyOf(tiers),clock.instant());
        store.save("instrument","XAUUSDT",rules);
    }
    private JsonNode get(String resource,String query) throws Exception {
        String url=settings.text("GOLD_BYBIT_HTTP_URL","https://api.bybit.com")+"/v5/market/"+resource+"?category=linear&symbol=XAUUSDT"+query;
        var response=http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(12)).GET().build(),2_000_000);
        if(response.statusCode()!=200)throw new IllegalStateException("BYBIT_HTTP_"+response.statusCode());
        JsonNode body=mapper.readTree(response.body());
        if(body.path("retCode").asInt(-1)!=0)throw new IllegalStateException("BYBIT_ERROR");
        return body;
    }
    private synchronized void process(Inbound inbound) {
        if(inbound.generation()!=generation.get())return;
        if(processedGeneration!=inbound.generation()) {processedGeneration=inbound.generation();disconnected=true;lastBookSequence=-1;ticker=mapper.createObjectNode();}
        JsonNode payload=mapper.readTree(inbound.raw());lastMessage=clock.instant();
        String topic=payload.path("topic").asText();
        if(topic.equals("tickers.XAUUSDT")) {
            if(payload.path("type").asText().equals("snapshot"))ticker=mapper.createObjectNode();
            payload.path("data").properties().forEach(entry->ticker.set(entry.getKey(),entry.getValue()));
            ticker.put("_ts",payload.path("ts").asLong());return;
        }
        if(!topic.equals("orderbook.1.XAUUSDT"))return;
        var book=payload.path("data");long sequence=book.path("seq").asLong(-1);
        if(sequence<lastBookSequence&&book.path("u").asLong()!=1)return;
        boolean restart=book.path("u").asLong()==1&&lastBookSequence>=0;
        if(restart)disconnected=true;
        if(!payload.path("type").asText().equals("snapshot"))throw new IllegalStateException("EXPECTED_L1_SNAPSHOT");
        long ts=payload.path("ts").asLong();
        Quote next=new Quote("XAUUSDT","BYBIT_WS_L1",sequence,Instant.ofEpochMilli(ts),clock.instant(),
            decimal(book.path("b").path(0).path(0)),decimal(book.path("a").path(0).path(0)),
            decimal(book.path("b").path(0).path(1)),decimal(book.path("a").path(0).path(1)),
            decimal(ticker.path("lastPrice")),decimal(ticker.path("markPrice")),
            ticker.has("_ts")?Instant.ofEpochMilli(ticker.path("_ts").asLong()):null,
            decimal(ticker.path("fundingRate")),ticker.path("nextFundingTime").asText().isBlank()?null:
                Instant.ofEpochMilli(Long.parseLong(ticker.path("nextFundingTime").asText())),!disconnected);
        quote=next;lastBookSequence=sequence;disconnected=false;error=null;
        consumer.accept(next);events.changed();
    }
    private void disconnect() {generation.incrementAndGet();WebSocket current=socket;socket=null;disconnected=true;if(current!=null)current.abort();}
    private void disconnect(long epoch) {if(epoch==generation.get())disconnect();}
    @PreDestroy void stop(){disconnect();processor.interrupt();}
    static BigDecimal decimal(JsonNode node) {return node.isMissingNode()||node.isNull()||node.asText().isBlank()?null:new BigDecimal(node.asText());}
    private final class Listener implements WebSocket.Listener {
        private final StringBuilder frame=new StringBuilder();
        private final long epoch;
        private Listener(long epoch){this.epoch=epoch;}
        public void onOpen(WebSocket ws){ws.request(1);}
        public CompletionStage<?> onText(WebSocket ws,CharSequence chunk,boolean last) {
            frame.append(chunk);
            if(epoch!=generation.get()){ws.abort();return null;}
            if(frame.length()>200_000){error="WEBSOCKET_FRAME_TOO_LARGE";disconnect(epoch);return null;}
            if(last){if(!incoming.offer(new Inbound(epoch,frame.toString()))){error="MARKET_QUEUE_OVERFLOW";disconnect(epoch);}frame.setLength(0);}
            ws.request(1);return null;
        }
        public CompletionStage<?> onClose(WebSocket ws,int code,String reason){disconnect(epoch);return null;}
        public void onError(WebSocket ws,Throwable failure){if(epoch==generation.get()){error="WEBSOCKET_DISCONNECTED";disconnect(epoch);}}
    }
}
