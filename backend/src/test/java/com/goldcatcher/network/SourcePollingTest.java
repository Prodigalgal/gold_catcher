package com.goldcatcher.network;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class SourcePollingTest {
    @Test void enteringReleaseWindowAcceleratesSuccessButNeverAnErrorBackoff(){
        var now=Instant.parse("2026-10-08T12:29:00Z");
        assertThat(SourcePolling.due(now,now.minusSeconds(10),now.plusSeconds(20),null,30,5)).isTrue();
        assertThat(SourcePolling.due(now,now.minusSeconds(10),now.plusSeconds(120),"HTTP_429",30,5)).isFalse();
        assertThat(SourcePolling.due(now,null,now.plusSeconds(60),"FETCH_FAILED",30,5)).isFalse();
        assertThat(SourcePolling.due(now,now,now,null,5,5)).isTrue();
    }
    @Test void retryAfterSupportsSecondsAndHttpDateAndCannotShortenBackoff(){
        var now=Instant.parse("2026-10-08T12:30:00Z");
        assertThat(SourcePolling.retrySeconds(failure(429,"120",now),now,60)).isBetween(120,121);
        assertThat(SourcePolling.retrySeconds(failure(503,"Thu, 08 Oct 2026 12:35:00 GMT",now),now,60)).isBetween(300,301);
        assertThat(SourcePolling.retrySeconds(failure(429,"1",now),now,60)).isEqualTo(60);
        assertThat(SourcePolling.retrySeconds(failure(429,"invalid",now),now,60)).isEqualTo(60);
        assertThat(SourcePolling.retrySeconds(failure(403,"120",now),now,60)).isEqualTo(60);
        assertThat(SourcePolling.backoff(3,60,300)).isEqualTo(240);
        assertThat(SourcePolling.backoff(90,60,300)).isEqualTo(300);
    }
    @SuppressWarnings("unchecked") private Exception failure(int status,String retry,Instant now){
        HttpResponse<byte[]> response=mock(HttpResponse.class);when(response.statusCode()).thenReturn(status);
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of("Retry-After",List.of(retry)),(k,v)->true));
        return SourcePolling.failure("HTTP_",response,now);
    }
}
