package com.goldcatcher.network;

import static org.assertj.core.api.Assertions.*;
import com.goldcatcher.TestSupport;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class SourceReliabilityTest {
    @Test void aStoppedCollectorBecomesStaleAndBodyFailureDoesNotCountAsFeedSuccess(){
        var clock=new TestSupport.TestClock();var service=new SourceReliability(clock);var at=clock.instant();
        service.record("news","COLLECTION",true,120,0,null);service.record("news","BODY",false,800,0,"ARTICLE_FETCH_FAILED");
        var inputs=List.of(new SourceReliability.Input("news","News","OFFICIAL","url","CURRENT",5,at,at.plusSeconds(5),null));
        var report=service.view(inputs);assertThat(report.healthy()).isZero();assertThat(report.degraded()).isEqualTo(1);
        assertThat(report.sources().getFirst().error()).isEqualTo("BODY_ARTICLE_FETCH_FAILED");
        assertThat(report.sources().getFirst().collection().successRatePct()).isEqualTo("100.0");
        assertThat(report.sources().getFirst().bodies().consecutiveFailures()).isEqualTo(1);
        clock.advance(31000);assertThat(service.view(inputs).stale()).isEqualTo(1);
        service.record("news","COLLECTION",false,900,0,"HTTP_429");
        assertThat(service.view(List.of(new SourceReliability.Input("news","News","OFFICIAL","url","FETCH_FAILED",5,clock.instant(),clock.instant().plusSeconds(120),"HTTP_429"))).degraded()).isEqualTo(1);
    }
    @Test void publicationDelayNeverUsesScheduleResponseOrUnknownTimes(){
        var clock=new TestSupport.TestClock();var service=new SourceReliability(clock);clock.advance(10000);
        service.arrived("news","v1",clock.instant().minusSeconds(4),clock.instant(),"DECLARED_RELEASE_TIME");
        var input=new SourceReliability.Input("news","News","OFFICIAL","url","CURRENT",30,clock.instant(),clock.instant().plusSeconds(30),null);
        assertThat(service.view(List.of(input)).sources().getFirst().arrival().lagMillis()).isEqualTo(4000);
        assertThat(service.view(List.of(input)).sources().getFirst().arrival().scope()).isEqualTo("LIVE_PUBLICATION");
        clock.advance(1000);service.arrived("news","v2",clock.instant(),clock.instant(),"SOURCE_RESPONSE_TIME_NOT_TICK");
        assertThat(service.view(List.of(input)).sources().getFirst().arrival().lagMillis()).isNull();
        clock.advance(1000);service.arrived("news","v3",clock.instant().minusSeconds(3600),clock.instant(),"EXACT_PUBLICATION");
        assertThat(service.view(List.of(input)).sources().getFirst().arrival().scope()).isEqualTo("STARTUP_OR_HISTORICAL_VERSION");
    }
    @Test void statisticsHaveABoundedWindowAndKeepSuccessAndRetryCountsSeparate(){
        var clock=new TestSupport.TestClock();var service=new SourceReliability(clock);
        for(int i=0;i<300;i++){clock.advance(1000);service.record("source","COLLECTION",i<298,i,1,i<298?null:"PARSE_INVALID");}
        var row=service.view(List.of(new SourceReliability.Input("source","Source","OFFICIAL","url","FETCH_FAILED",30,clock.instant(),clock.instant().plusSeconds(120),"PARSE_INVALID"))).sources().getFirst();
        assertThat(row.collection().samples()).isEqualTo(128);assertThat(row.collection().successes()).isEqualTo(126);
        assertThat(row.collection().consecutiveFailures()).isEqualTo(2);assertThat(row.collection().p95DurationMillis()).isEqualTo(293);
    }
}
