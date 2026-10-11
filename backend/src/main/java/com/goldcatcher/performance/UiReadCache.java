package com.goldcatcher.performance;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.goldcatcher.RuntimeSettings;
import com.goldcatcher.information.NewsService;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Presentation reads only. Matching, research evidence, credentials and sessions never use this cache. */
@Service
public class UiReadCache {
    private static final String SCHEMA="UI_READ_V1";
    private record Entry(JsonNode value,Instant expiresAt,int bytes) {}
    private final Cache<String,Entry> local;
    private final ObjectMapper mapper;private final Clock clock;private final StringRedisTemplate redis;
    private final boolean enabled;private final long maxValueBytes;
    private final AtomicLong localHits=new AtomicLong(),redisHits=new AtomicLong(),loads=new AtomicLong(),redisFailures=new AtomicLong();
    private volatile Instant retryAt=Instant.EPOCH;private volatile boolean redisUsed;
    @Autowired public UiReadCache(RuntimeSettings settings,ObjectMapper mapper,Clock clock,ObjectProvider<StringRedisTemplate> redis){
        this(settings,mapper,clock,redis.getIfAvailable());
    }
    UiReadCache(RuntimeSettings settings,ObjectMapper mapper,Clock clock,StringRedisTemplate redis){
        this.mapper=mapper;this.clock=clock;this.redis=redis;
        enabled=Boolean.parseBoolean(settings.text("GOLD_CACHE_REDIS_ENABLED","false"));
        long memory=Math.max(1,Math.min(32,settings.integer("GOLD_CACHE_LOCAL_MIB",8)))*1024L*1024L;
        maxValueBytes=Math.max(1,Math.min(4,settings.integer("GOLD_CACHE_VALUE_MIB",2)))*1024L*1024L;
        local=Caffeine.newBuilder().maximumWeight(memory).weigher((String key,Entry value)->value.bytes())
            .expireAfterWrite(Duration.ofMinutes(5)).build();
    }
    public JsonNode get(String scope,String version,long ttlMillis,Supplier<?> loader){
        if(ttlMillis<1||ttlMillis>300000)throw new IllegalArgumentException("INVALID_READ_CACHE_TTL");
        String key="gold:ui:"+SCHEMA+":"+scope+":"+ttlMillis+":"+NewsService.hash(version);
        Entry result=local.asMap().compute(key,(unused,old)->{
            Instant now=clock.instant();
            if(old!=null&&now.isBefore(old.expiresAt())){localHits.incrementAndGet();return old;}
            if(enabled&&redis!=null&&!now.isBefore(retryAt)){
                try{
                    String encoded=redis.opsForValue().get(key);redisUsed=true;
                    if(encoded!=null&&encoded.getBytes(StandardCharsets.UTF_8).length<=maxValueBytes){
                        var packet=mapper.readTree(encoded);
                        if(packet.path("schema").asText().equals(SCHEMA)&&packet.path("key").asText().equals(key)&&packet.has("value")){
                            Instant expiry=Instant.parse(packet.path("expiresAt").asText());
                            if(now.isBefore(expiry)&&!expiry.isAfter(now.plusMillis(ttlMillis))){
                                redisHits.incrementAndGet();return new Entry(packet.path("value"),expiry,Math.max(1,encoded.getBytes(StandardCharsets.UTF_8).length*3));
                            }
                        }
                    }
                }catch(RuntimeException unavailable){failed(now);}
            }
            JsonNode value=mapper.valueToTree(loader.get());loads.incrementAndGet();
            // Expiry starts when loading began, so a slow calculation cannot extend a stale view's life.
            var packet=mapper.createObjectNode();packet.put("schema",SCHEMA);packet.put("key",key);packet.put("expiresAt",now.plusMillis(ttlMillis).toString());packet.set("value",value);
            String encoded=mapper.writeValueAsString(packet);int bytes=encoded.getBytes(StandardCharsets.UTF_8).length;
            if(enabled&&redis!=null&&!clock.instant().isBefore(retryAt)&&bytes<=maxValueBytes){
                try{long remaining=Duration.between(clock.instant(),now.plusMillis(ttlMillis)).toMillis();
                    if(remaining>0)redis.opsForValue().set(key,encoded,Duration.ofMillis(remaining));redisUsed=true;
                }catch(RuntimeException unavailable){failed(clock.instant());}
            }
            return new Entry(value,now.plusMillis(ttlMillis),Math.max(1,Math.min(Integer.MAX_VALUE/3,bytes)*3));
        });
        return result.value().deepCopy();
    }
    private void failed(Instant now){redisFailures.incrementAndGet();redisUsed=false;retryAt=now.plusSeconds(30);}
    public Map<String,Object> status(){
        var data=new LinkedHashMap<String,Object>();data.put("redisEnabled",enabled);
        data.put("redisState",!enabled?"DISABLED":clock.instant().isBefore(retryAt)?"BACKOFF":redisUsed?"AVAILABLE":redisFailures.get()>0?"RETRY_PENDING":"NOT_USED");
        data.put("retryAt",clock.instant().isBefore(retryAt)?retryAt:null);data.put("localHits",localHits.get());data.put("redisHits",redisHits.get());
        data.put("loads",loads.get());data.put("redisFailures",redisFailures.get());data.put("entries",local.estimatedSize());
        data.put("scope","UI_READS_ONLY; LIVE_QUOTES_ACCOUNTS_AND_EXECUTION_BYPASS; REDIS_FAILURE_FALLS_BACK_TO_LOCAL_AND_DATABASE");return data;
    }
}
