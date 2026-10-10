package com.goldcatcher.network;

import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/** Bounded diagnostics, separate from the frozen evidence sent to models. Durations use a monotonic clock. */
@Service
public class SourceReliability {
    public record Input(String id,String label,String category,String url,String state,int intervalSeconds,
        Instant lastSuccess,Instant nextPoll,String error) {}
    public record Sample(Instant at,boolean success,long durationMillis,int records,String error) {}
    public record Metrics(Instant windowStart,Instant lastAttempt,int samples,int successes,String successRatePct,
        int consecutiveFailures,Long lastDurationMillis,Long p95DurationMillis,String lastError) {}
    public record Arrival(String evidenceId,Instant sourceTime,Instant receivedAt,String timestampMeaning,
        Long lagMillis,String scope) {}
    public record Health(String id,String label,String category,String url,String state,int intervalSeconds,
        Instant lastSuccess,Instant nextPoll,String error,Metrics collection,Metrics bodies,Arrival arrival) {}
    public record View(Instant calculatedAt,Instant processStartedAt,int channels,int healthy,int degraded,int stale,
        int pending,List<Health> sources,String statisticsScope) {}
    private static final int CAP=128;
    private final Clock clock;private final Instant startedAt;
    private final Map<String,Window> windows=new ConcurrentHashMap<>();
    private final Map<String,Arrival> arrivals=new ConcurrentHashMap<>();
    private static final class Window {
        final Deque<Sample> samples=new ArrayDeque<>();int failures;
        synchronized void add(Sample sample){samples.addLast(sample);while(samples.size()>CAP)samples.removeFirst();failures=sample.success()?0:failures+1;}
        synchronized Metrics metrics(){
            if(samples.isEmpty())return new Metrics(null,null,0,0,null,0,null,null,null);
            var last=samples.getLast();int successes=(int)samples.stream().filter(Sample::success).count();
            var durations=samples.stream().mapToLong(Sample::durationMillis).sorted().toArray();
            String rate=java.math.BigDecimal.valueOf(successes*100L).divide(java.math.BigDecimal.valueOf(samples.size()),1,java.math.RoundingMode.HALF_UP).toPlainString();
            return new Metrics(samples.getFirst().at(),last.at(),samples.size(),successes,rate,failures,last.durationMillis(),
                durations[Math.max(0,(int)Math.ceil(durations.length*.95)-1)],last.error());
        }
    }
    public SourceReliability(Clock clock){this.clock=clock;this.startedAt=clock.instant();}
    public long begin(){return System.nanoTime();}
    public void success(String id,String stage,long start,int records){record(id,stage,true,Math.max(0,(System.nanoTime()-start)/1_000_000),records,null);}
    public void failure(String id,String stage,long start,String error){record(id,stage,false,Math.max(0,(System.nanoTime()-start)/1_000_000),0,error);}
    void record(String id,String stage,boolean success,long duration,int records,String error){
        windows.computeIfAbsent(id+":"+stage,key->new Window()).add(new Sample(clock.instant(),success,duration,records,error));
    }
    public int failures(String id){return metrics(id,"COLLECTION").consecutiveFailures();}
    private Metrics metrics(String id,String stage){return windows.getOrDefault(id+":"+stage,new Window()).metrics();}
    public void arrived(String id,String evidenceId,Instant sourceTime,Instant receivedAt,String meaning){
        boolean precise=Set.of("EXACT_PUBLICATION","DECLARED_RELEASE_TIME").contains(meaning)&&sourceTime!=null;
        boolean future=sourceTime!=null&&sourceTime.isAfter(receivedAt);
        Long lag=precise&&!future?Duration.between(sourceTime,receivedAt).toMillis():null;
        String scope=future?"FUTURE_TIME_EXCLUDED":!precise?"PUBLICATION_UNVERIFIED":sourceTime.isBefore(startedAt)?"STARTUP_OR_HISTORICAL_VERSION":"LIVE_PUBLICATION";
        var next=new Arrival(evidenceId,sourceTime,receivedAt,meaning,lag,scope);
        arrivals.compute(id,(key,previous)->previous!=null&&previous.evidenceId().equals(evidenceId)?previous:previous==null||previous.receivedAt().isBefore(receivedAt)?next:previous);
    }
    public View view(List<Input> inputs){
        Instant now=clock.instant();var sources=inputs.stream().map(input->{
            boolean overdue=input.lastSuccess()!=null&&input.lastSuccess().plusSeconds(Math.max(30,input.intervalSeconds()*3L)).isBefore(now);
            var collection=metrics(input.id(),"COLLECTION");var bodies=metrics(input.id(),"BODY");
            String error=input.error()!=null?input.error():bodies.consecutiveFailures()>0?"BODY_"+bodies.lastError():null;
            String state=input.state().equals("DISABLED")?"DISABLED":overdue?"STALE":error!=null?"DEGRADED":input.lastSuccess()==null?"PENDING":"HEALTHY";
            return new Health(input.id(),input.label(),input.category(),input.url(),state,input.intervalSeconds(),input.lastSuccess(),input.nextPoll(),error,collection,bodies,arrivals.get(input.id()));
        }).sorted(Comparator.comparingInt((Health h)->switch(h.state()){case "STALE"->0;case "DEGRADED"->1;case "PENDING"->2;default->3;}).thenComparing(Health::id)).toList();
        return new View(now,startedAt,sources.size(),count(sources,"HEALTHY"),count(sources,"DEGRADED"),count(sources,"STALE"),count(sources,"PENDING"),sources,
            "CURRENT_PROCESS; LAST_128_COMPLETED_CHECKS_PER_SOURCE_AND_STAGE; P95_INCLUDES_FAILURES; POLL_CADENCE_IS_NOT_PUBLICATION_SLA");
    }
    private static int count(List<Health> sources,String state){return (int)sources.stream().filter(s->s.state().equals(state)).count();}
}
