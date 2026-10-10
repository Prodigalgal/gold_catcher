package com.goldcatcher.network;

import static org.assertj.core.api.Assertions.*;
import com.goldcatcher.TestSupport;
import com.goldcatcher.information.NewsService.CalendarEvent;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ReleasePollingTest {
    @Test void exactOfficialWindowsAreSourceSpecificAndDateRangesDoNotInventHours(){
        var now=Instant.parse("2026-11-05T13:25:00Z");
        var cpi=new CalendarEvent("cpi","BLS_CALENDAR","Consumer Price Index",now.plusSeconds(300),"America/New_York","url",now);
        var bea=new CalendarEvent("bea","BEA_CALENDAR","Personal Income and Outlays",now.plusSeconds(1200),"America/New_York","url",now);
        var window=ReleasePolling.windows(List.of(cpi,bea),now);
        assertThat(window).contains("BLS_CPI","BLS_CALENDAR","FF_EXPECTATIONS").doesNotContain("BLS_EMPLOYMENT","BEA_NEWS");
        assertThat(ReleasePolling.windows(List.of(cpi),now.plusSeconds(1400))).isEmpty();
        var fomc=new CalendarEvent("fomc","FOMC_CALENDAR","Meeting",null,"America/New_York","url",now,"2026-11-04","2026-11-05","DATE_RANGE");
        assertThat(ReleasePolling.windows(List.of(fomc),now)).containsExactly("FED_PRESS");
        assertThat(ReleasePolling.windows(List.of(fomc),now.minusSeconds(86400))).isEmpty();
    }
    @Test void springAndWinterUtcSchedulesKeepTheSameNewYorkReleaseWindow(){
        for(String timestamp:List.of("2026-03-12T12:25:00Z","2026-11-05T13:25:00Z")){
            var f=new TestSupport();f.clock.now=Instant.parse(timestamp);var event=new CalendarEvent("cpi","BLS_CALENDAR","Consumer Price Index",f.clock.now.plusSeconds(300),"America/New_York","url",f.clock.now);
            f.store.save("calendar",event.id(),event);var policy=new ReleasePolling(f.clock,f.store);
            assertThat(policy.interval("BLS_CPI",30)).isEqualTo(5);assertThat(policy.interval("FF_EXPECTATIONS",1800)).isEqualTo(60);
            assertThat(policy.interval("FED_SPEECH",30)).isEqualTo(30);
        }
    }
}
