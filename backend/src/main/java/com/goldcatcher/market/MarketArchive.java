package com.goldcatcher.market;

import com.goldcatcher.*;
import com.goldcatcher.persistence.DocumentStore;
import com.goldcatcher.persistence.DocumentStore.MarketFrame;
import jakarta.annotation.PreDestroy;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Bounded asynchronous journal, independent of matching; historical coverage is never inferred. */
@Service
public class MarketArchive {
    private final DocumentStore store;private final RuntimeSettings settings;private final Clock clock;private final ObjectMapper mapper;
    private final BlockingQueue<MarketFrame> queue=new ArrayBlockingQueue<>(1024);private final AtomicLong dropped=new AtomicLong();
    private final Map<String,AtomicLong> missing=new ConcurrentHashMap<>();private volatile boolean running=true;
    private final Thread writer;private volatile String error;private volatile Instant lastPersistedAt;
    private record Cached(Instant at,List<Map<String,Object>> rows) {}
    private final Map<String,Cached> counts=new ConcurrentHashMap<>();
    public MarketArchive(DocumentStore store,RuntimeSettings settings,Clock clock,ObjectMapper mapper,MarketFeed market) {
        this.store=store;this.settings=settings;this.clock=clock;this.mapper=mapper;
        market.onFrame(f->offer(new MarketFrame(0,f.symbol(),f.channel(),f.connectionEpoch(),f.sourceAt(),f.receivedAt(),f.raw())));
        market.onQuote(q->offer(new MarketFrame(0,q.symbol(),q.symbol().equals("XAU_USDT")?"L1_QUOTE":"CFD_REST_OBSERVATION",0,q.exchangeTime(),q.receivedAt(),
            mapper.writeValueAsString(Map.of("quote",q,"marketState",market.snapshot(q.symbol()).state())))));
        writer=Thread.ofVirtual().name("gold-market-archive").start(this::write);
    }
    private void offer(MarketFrame frame) {
        if(!Boolean.parseBoolean(settings.text("GOLD_MARKET_ARCHIVE_ENABLED","true")))return;
        if(frame.body().getBytes(StandardCharsets.UTF_8).length>1000000){loss(frame.symbol(),1,"ARCHIVE_FRAME_TOO_LARGE");return;}
        var encoded=new MarketFrame(0,frame.symbol(),frame.channel(),frame.connectionEpoch(),frame.sourceAt(),frame.receivedAt(),compress(frame.body()));
        if(!queue.offer(encoded))loss(frame.symbol(),1,"ARCHIVE_QUEUE_OVERFLOW");
    }
    private void loss(String symbol,long count,String code){dropped.addAndGet(count);missing.computeIfAbsent(symbol,s->new AtomicLong()).addAndGet(count);error=code;}
    private void write() {
        while(running||!queue.isEmpty()) {
            List<MarketFrame> batch=new ArrayList<>();
            Map<String,Long> gaps=new HashMap<>();
            try {
                var first=queue.poll(1,TimeUnit.SECONDS);if(first==null)continue;
                batch.add(first);queue.drainTo(batch,127);
                for(var entry:missing.entrySet()){long count=entry.getValue().getAndSet(0);if(count>0){gaps.put(entry.getKey(),count);
                    batch.add(new MarketFrame(0,entry.getKey(),"ARCHIVE_GAP",0,null,clock.instant(),compress(mapper.writeValueAsString(Map.of("missingReceivedFrames",count,"reason","ARCHIVE_BUFFER_OR_STORAGE_FAILURE","continuity","UNKNOWN")))));}}
                store.transaction(()->{store.marketFrames(batch);return null;});lastPersistedAt=clock.instant();error=null;
            }catch(InterruptedException stop){if(running)Thread.currentThread().interrupt();}
            catch(Exception failure){for(var frame:batch)if(!frame.channel().equals("ARCHIVE_GAP"))loss(frame.symbol(),1,"ARCHIVE_WRITE_FAILED");
                gaps.forEach((symbol,count)->missing.computeIfAbsent(symbol,s->new AtomicLong()).addAndGet(count));}
        }
    }
    public Map<String,Object> status(String symbol) {
        var cached=counts.get(symbol);Instant now=clock.instant();
        if(cached==null||cached.at().isBefore(now.minusSeconds(10))) {
            cached=new Cached(now,store.marketFrameCounts(symbol));counts.put(symbol,cached);
        }
        var result=new LinkedHashMap<String,Object>();result.put("enabled",Boolean.parseBoolean(settings.text("GOLD_MARKET_ARCHIVE_ENABLED","true")));
        result.put("state",!Boolean.parseBoolean(settings.text("GOLD_MARKET_ARCHIVE_ENABLED","true"))?"DISABLED":error!=null?"DEGRADED":lastPersistedAt==null?"WAITING":"RECORDING");result.put("error",error);
        result.put("queued",queue.size());result.put("droppedSinceStart",dropped.get());result.put("lastPersistedAt",lastPersistedAt);
        result.put("statisticsAt",cached.at());result.put("channels",cached.rows());result.put("retentionDays",retention());
        result.put("coverage","RECEIVED_PUBLIC_EVENTS_ONLY; CONNECTION_EPOCHS_AND_DROPS_MUST_BE_CHECKED; CFD_IS_RESPONSE_SNAPSHOTS_NOT_TICK_CONTINUITY");return result;
    }
    public List<Map<String,Object>> frames(String symbol,long after,int limit) {
        if(after<0)throw new IllegalArgumentException("INVALID_ARCHIVE_CURSOR");
        return store.marketFrames(symbol,after,limit).stream().map(f->{var row=new LinkedHashMap<String,Object>();
            row.put("sequence",f.sequence());row.put("channel",f.channel());row.put("connectionEpoch",f.connectionEpoch());row.put("sourceAt",f.sourceAt());
            row.put("receivedAt",f.receivedAt());row.put("payload",mapper.readTree(expand(f.body())));return (Map<String,Object>)row;}).toList();
    }
    @Scheduled(fixedDelay=3600000,initialDelay=3600000) void expire(){store.expireMarketFrames(clock.instant().minus(Duration.ofDays(retention())));counts.clear();}
    private int retention(){return Math.max(1,Math.min(90,settings.integer("GOLD_MARKET_ARCHIVE_RETENTION_DAYS",7)));}
    static String compress(String value) {
        try{var out=new ByteArrayOutputStream();try(var gzip=new GZIPOutputStream(out)){gzip.write(value.getBytes(StandardCharsets.UTF_8));}return "gz:"+Base64.getEncoder().encodeToString(out.toByteArray());}
        catch(IOException failure){throw new IllegalStateException("ARCHIVE_ENCODING_FAILED");}
    }
    static String expand(String value) {
        if(!value.startsWith("gz:"))return value;
        try(var gzip=new GZIPInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(value.substring(3))))) {
            byte[] bytes=gzip.readNBytes(1000001);if(bytes.length>1000000)throw new IllegalStateException("ARCHIVE_FRAME_TOO_LARGE");
            return new String(bytes,StandardCharsets.UTF_8);
        }catch(IOException failure){throw new IllegalStateException("ARCHIVE_DECODING_FAILED");}
    }
    @PreDestroy void stop(){running=false;writer.interrupt();try{writer.join(3000);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}}
}
