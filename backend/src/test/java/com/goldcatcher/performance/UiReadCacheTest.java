package com.goldcatcher.performance;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.goldcatcher.TestSupport;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.*;
import tools.jackson.databind.node.ObjectNode;

class UiReadCacheTest {
    @Test void expiresByOriginalTimeAndReturnsIndependentValues(){
        var f=new TestSupport();var cache=new UiReadCache(f.settings,f.mapper,f.clock,(StringRedisTemplate)null);var calls=new AtomicInteger();
        var first=(ObjectNode)cache.get("news","v1",1000,()->Map.of("count",calls.incrementAndGet()));first.put("count",999);
        assertThat(cache.get("news","v1",1000,()->Map.of("count",calls.incrementAndGet())).path("count").asInt()).isEqualTo(1);
        f.clock.advance(1000);assertThat(cache.get("news","v1",1000,()->Map.of("count",calls.incrementAndGet())).path("count").asInt()).isEqualTo(2);
        assertThat(cache.get("news","v2",1000,()->Map.of("count",calls.incrementAndGet())).path("count").asInt()).isEqualTo(3);
    }
    @Test void concurrentReadersOnlyLoadOnce()throws Exception{
        var f=new TestSupport();var cache=new UiReadCache(f.settings,f.mapper,f.clock,(StringRedisTemplate)null);var calls=new AtomicInteger();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var threads=Executors.newFixedThreadPool(8)){
            var futures=new ArrayList<Future<?>>();for(int i=0;i<8;i++)futures.add(threads.submit(()->cache.get("detail","immutable",300000,()->{
                calls.incrementAndGet();entered.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("TEST_WAIT");}catch(InterruptedException e){throw new RuntimeException(e);}return Map.of("state","EXTRACTED");})));
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();release.countDown();for(var future:futures)future.get(5,TimeUnit.SECONDS);
        }
        assertThat(calls).hasValue(1);assertThat(cache.status()).containsEntry("localHits",7L);
    }
    @Test void redisFailureFallsBackAndDoesNotDelayEveryRequest(){
        var f=new TestSupport();f.environment.setProperty("GOLD_CACHE_REDIS_ENABLED","true");var redis=mock(StringRedisTemplate.class);ValueOperations<String,String> values=mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);when(values.get(anyString())).thenThrow(new IllegalStateException("UNAVAILABLE"));
        var cache=new UiReadCache(f.settings,f.mapper,f.clock,redis);
        assertThat(cache.get("detail","one",1000,()->Map.of("value",1)).path("value").asInt()).isEqualTo(1);
        assertThat(cache.get("detail","two",1000,()->Map.of("value",2)).path("value").asInt()).isEqualTo(2);
        verify(values,times(1)).get(anyString());assertThat(cache.status()).containsEntry("redisState","BACKOFF");
        f.clock.advance(30001);doReturn(null).when(values).get(anyString());
        cache.get("detail","three",1000,()->Map.of("value",3));verify(values).set(anyString(),anyString(),eq(Duration.ofMillis(1000)));
        assertThat(cache.status()).containsEntry("redisState","AVAILABLE");
    }
    @Test void failuresNeverBecomeCachedSuccess(){
        var f=new TestSupport();var cache=new UiReadCache(f.settings,f.mapper,f.clock,(StringRedisTemplate)null);var calls=new AtomicInteger();
        for(int i=0;i<2;i++)assertThatThrownBy(()->cache.get("news","missing",1000,()->{calls.incrementAndGet();throw new IllegalArgumentException("NEWS_NOT_FOUND");})).hasMessage("NEWS_NOT_FOUND");
        assertThat(calls).hasValue(2);
    }
    @Test void diagnosticsExposeTypesAndTimeWithoutCredentialBearingMessages(){
        var f=new TestSupport();f.environment.setProperty("GOLD_CACHE_REDIS_ENABLED","true");var redis=mock(StringRedisTemplate.class);ValueOperations<String,String> values=mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);when(values.get(anyString())).thenThrow(new IllegalStateException("redis://user:private-password@host",new java.net.ConnectException("sensitive-url")));
        var cache=new UiReadCache(f.settings,f.mapper,f.clock,redis);
        assertThat(cache.get("news","version",1000,()->Map.of("ready",true)).path("ready").asBoolean()).isTrue();
        assertThat(cache.status()).containsEntry("lastFailure","IllegalStateException:ConnectException").containsEntry("lastFailureAt",f.clock.instant());
        assertThat(f.mapper.writeValueAsString(cache.status())).doesNotContain("private-password","sensitive-url");
    }
    @Test void documentVersionOnlyAdvancesAfterCommitAndDoesNotPublishRollback(){
        var f=new TestSupport();String before=f.store.revision("news");
        f.store.transaction(()->{f.store.save("news","one",Map.of("value",1));assertThat(f.store.revision("news")).isEqualTo(before);return null;});
        String committed=f.store.revision("news");assertThat(committed).isNotEqualTo(before);
        assertThatThrownBy(()->f.store.transaction(()->{f.store.save("news","one",Map.of("value",2));throw new IllegalStateException("ROLLBACK");}));
        assertThat(f.store.revision("news")).isEqualTo(committed);assertThat(f.store.find("news","one",tools.jackson.databind.JsonNode.class).orElseThrow().path("value").asInt()).isEqualTo(1);
    }
}
