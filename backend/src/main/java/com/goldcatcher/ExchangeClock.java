package com.goldcatcher;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.*;
import java.util.*;
import org.springframework.scheduling.annotation.Scheduled;
import tools.jackson.databind.ObjectMapper;

/** Exchange UTC anchored to monotonic elapsed time; the host clock is never changed. */
public class ExchangeClock extends Clock {
    private record Anchor(Instant utc,long nano,Instant calibratedAt,long offsetMs,long uncertaintyMs,String state,String error) {}
    private final RuntimeSettings settings;private final RemoteHttp http;private final ObjectMapper mapper;
    private volatile Anchor anchor=new Anchor(Instant.now(),System.nanoTime(),null,0,Long.MAX_VALUE,"UNCALIBRATED",null);
    public ExchangeClock(RuntimeSettings settings,RemoteHttp http,ObjectMapper mapper){this.settings=settings;this.http=http;this.mapper=mapper;calibrate();}
    @Override public ZoneId getZone(){return ZoneOffset.UTC;}
    @Override public Clock withZone(ZoneId zone){if(!ZoneOffset.UTC.equals(zone))throw new IllegalArgumentException("UTC_CLOCK_ONLY");return this;}
    @Override public Instant instant(){var current=anchor;return current.utc().plusNanos(System.nanoTime()-current.nano());}
    public boolean verified(){var current=anchor;return current.calibratedAt()!=null&&current.state().equals("EXCHANGE_CALIBRATED")
        &&Duration.between(current.calibratedAt(),instant()).compareTo(Duration.ofMinutes(6))<0;}
    public Map<String,Object> status(){var current=anchor;return Map.of("state",verified()?current.state():"CLOCK_UNVERIFIED","source","GATE_API_V4_TIME",
        "offsetMillis",current.offsetMs(),"uncertaintyMillis",current.uncertaintyMs(),"calibratedAt",current.calibratedAt()==null?"":current.calibratedAt(),"hostTime",Instant.now(),"error",current.error()==null?"":current.error());}
    @Scheduled(fixedDelay=15000,initialDelay=15000) public synchronized void calibrate() {
        if(!settings.enabled("GOLD_MARKET_ENABLED"))return;
        var previous=anchor;
        if(previous.calibratedAt()!=null&&verified()
            &&Duration.between(previous.calibratedAt(),instant()).compareTo(Duration.ofMinutes(3))<0)return;
        try {
            long start=System.nanoTime();Instant hostStart=Instant.now();
            var response=http.send(HttpRequest.newBuilder(URI.create(settings.text("GOLD_GATE_HTTP_URL","https://api.gateio.ws/api/v4")+"/spot/time"))
                .header("Cache-Control","no-cache").timeout(Duration.ofSeconds(6)).GET().build(),10000);
            long end=System.nanoTime(),roundTrip=end-start;
            if(response.statusCode()!=200||roundTrip>800_000_000L)throw new IllegalStateException("CLOCK_UNCERTAINTY_TOO_LARGE");
            var body=mapper.readTree(response.body());long millis=body.path("server_time").asLong(-1);
            if(millis<=0)throw new IllegalStateException("CLOCK_SOURCE_FAILED");
            Instant estimated=Instant.ofEpochMilli(millis).plusNanos(roundTrip/2);
            long offset=Duration.between(hostStart.plusNanos(roundTrip),estimated).toMillis();
            anchor=new Anchor(estimated,end,estimated,offset,roundTrip/2_000_000L+100,"EXCHANGE_CALIBRATED",null);
        }catch(Exception failure){var current=anchor;anchor=new Anchor(current.utc(),current.nano(),current.calibratedAt(),current.offsetMs(),current.uncertaintyMs(),current.state(),"CLOCK_CALIBRATION_FAILED");}
    }
}
