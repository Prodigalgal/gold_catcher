package com.goldcatcher.information;
import static org.assertj.core.api.Assertions.*;
import com.goldcatcher.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
class BeaScheduleTest {
    @Test void parsesPublishedScheduleWithNewYorkDaylightSaving() {
        var f=new TestSupport();var service=new NewsService(f.settings,new RemoteHttp(),f.store,new EventHub(),f.clock);
        String html="<th>Year 2026</th><tr class=\"scheduled-releases-type-press\"><td><div class=\"release-date\">October 29</div><small class=\"text-muted\">8:30 AM</small></td><td class=\"release-title views-field\">GDP (Advance Estimate), 3rd Quarter 2026</td></tr>";
        assertThat(service.parseBeaSchedule(html.getBytes(StandardCharsets.UTF_8),"https://www.bea.gov/news/schedule")).isEqualTo(1);
        assertThat(service.calendar().getFirst().scheduledAt().toString()).isEqualTo("2026-10-29T12:30:00Z");
        assertThatThrownBy(()->service.parseBeaSchedule("broken".getBytes(StandardCharsets.UTF_8),"https://www.bea.gov/news/schedule")).hasMessage("BEA_CALENDAR_FORMAT_CHANGED");
    }
}
