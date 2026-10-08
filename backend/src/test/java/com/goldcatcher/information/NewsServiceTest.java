package com.goldcatcher.information;
import static org.assertj.core.api.Assertions.*;
import com.goldcatcher.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
class NewsServiceTest {
    @Test void deduplicatesAndPreservesRevisionsWithReceiptTime() throws Exception {
        var f=new TestSupport();var news=new NewsService(f.settings,new RemoteHttp(),f.store,new EventHub(),f.clock);
        String xml="<rss><channel><item><guid>same</guid><title>Fed release</title><link>https://www.federalreserve.gov/newsevents/pressreleases.htm</link><description>original</description><pubDate>Thu, 08 Oct 2026 00:00:00 GMT</pubDate></item></channel></rss>";
        assertThat(news.parseRss("FED_PRESS",bytes(xml))).isEqualTo(1);assertThat(news.parseRss("FED_PRESS",bytes(xml))).isZero();
        var first=news.news().getFirst();f.clock.advance(5000);
        assertThat(news.parseRss("FED_PRESS",bytes(xml.replace("original","corrected")))).isEqualTo(1);
        assertThat(news.news().getFirst().version()).isEqualTo(2);assertThat(news.news().getFirst().receivedAt()).isEqualTo(f.clock.instant());
        assertThat(f.store.events(first.id(),100)).hasSize(2);
    }
    @Test void rejectsExternalEntitiesAndUnsafeLinks() throws Exception {
        var f=new TestSupport();var news=new NewsService(f.settings,new RemoteHttp(),f.store,new EventHub(),f.clock);
        assertThatThrownBy(()->news.parseRss("x",bytes("<!DOCTYPE rss [<!ENTITY x SYSTEM 'file:///secret'>]><rss>&x;</rss>")));
        assertThat(news.parseRss("x",bytes("<rss><item><title>x</title><link>javascript:alert(1)</link></item></rss>"))).isZero();
    }
    @Test void calendarUsesDeclaredZoneAcrossDaylightSavingAndUnfoldsSummary() {
        var f=new TestSupport();var news=new NewsService(f.settings,new RemoteHttp(),f.store,new EventHub(),f.clock);
        String ics="BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:summer\r\nSUMMARY:Employment\r\n Situation\r\nDTSTART;TZID=America/New_York:20261009T083000\r\nEND:VEVENT\r\nBEGIN:VEVENT\r\nUID:winter\r\nSUMMARY:CPI\r\nDTSTART;TZID=America/New_York:20261112T083000\r\nEND:VEVENT\r\nEND:VCALENDAR";
        assertThat(news.parseCalendar(bytes(ics),"https://www.bls.gov/schedule/news_release/bls.ics")).isEqualTo(2);
        assertThat(news.calendar().get(0).scheduledAt().toString()).isEqualTo("2026-10-09T12:30:00Z");
        assertThat(news.calendar().get(1).scheduledAt().toString()).isEqualTo("2026-11-12T13:30:00Z");
        assertThat(news.calendar().get(0).title()).isEqualTo("EmploymentSituation");
    }
    private byte[] bytes(String text){return text.getBytes(StandardCharsets.UTF_8);}
}
