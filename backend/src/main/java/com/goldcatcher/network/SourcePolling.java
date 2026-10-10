package com.goldcatcher.network;

import java.net.http.HttpResponse;
import java.time.*;
import java.time.format.DateTimeFormatter;

/** Poll cadence and retry delays are separate; acceleration never overrides a failed request. */
public final class SourcePolling {
    private SourcePolling() {}
    public static boolean due(Instant now,Instant lastSuccess,Instant nextPoll,String error,int previousInterval,int interval) {
        if(nextPoll==null||!nextPoll.isAfter(now))return true;
        return error==null&&lastSuccess!=null&&interval<previousInterval&&!lastSuccess.plusSeconds(interval).isAfter(now);
    }
    public static int backoff(int failures,int base,int maximum) {
        return (int)Math.min(maximum,base*(1L<<Math.min(16,Math.max(0,failures-1))));
    }
    public static int retrySeconds(Exception failure,Instant now,int fallback) {
        return failure instanceof HttpFailure http&&http.retryAt!=null?
            (int)Math.min(Integer.MAX_VALUE,Math.max(fallback,Math.max(0,Duration.between(now,http.retryAt).getSeconds()+1))):fallback;
    }
    public static HttpFailure failure(String prefix,HttpResponse<?> response,Instant now) {
        Instant retry=null;
        if(response.statusCode()==429||response.statusCode()==503) {
            var value=response.headers().firstValue("Retry-After").orElse("").strip();
            try {retry=value.matches("[0-9]{1,9}")?now.plusSeconds(Long.parseLong(value)):
                ZonedDateTime.parse(value,DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();}catch(RuntimeException ignored){}
        }
        return new HttpFailure(prefix+response.statusCode(),retry);
    }
    public static final class HttpFailure extends IllegalStateException {
        public final Instant retryAt;
        HttpFailure(String message,Instant retryAt){super(message);this.retryAt=retryAt;}
    }
}
