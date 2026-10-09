package com.goldcatcher.information;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.goldcatcher.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
class NewsServiceTest {
    @Test void newFeedsResolveOfficialRelativeLinksWithoutInventingPublicationTimes() throws Exception {
        var f=new TestSupport();var news=new NewsService(f.settings,new RemoteHttp(),f.store,new EventHub(),f.clock);
        String xml="<rss xmlns:a='http://www.w3.org/2005/Atom'><channel><item><title>EIA release</title><link>/pressroom/releases/press593.php</link><pubDate>Wed, 07 Oct 2026 09:00:00 EST</pubDate></item><item><title>Missing year</title><link>http://www.eia.gov/todayinenergy/detail.php?id=1</link><pubDate>Thu, 08 Oct 09:00:00 EST</pubDate></item></channel></rss>";
        assertThat(news.parseRss("EIA_PRESS",bytes(xml))).isEqualTo(2);assertThat(news.news()).filteredOn(n->n.title().equals("EIA release")).allSatisfy(n->{assertThat(n.link()).isEqualTo("https://www.eia.gov/pressroom/releases/press593.php");assertThat(n.publishedAt()).isEqualTo(Instant.parse("2026-10-07T14:00:00Z"));assertThat(n.publishedPrecision()).isEqualTo("FEED_TIMESTAMP");});assertThat(news.news()).filteredOn(n->n.title().equals("Missing year")).allSatisfy(n->assertThat(n.publishedAt()).isNull());assertThat(news.evidence(f.clock.instant())).isEmpty();
        String boj="<rdf:RDF xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#' xmlns:dc='http://purl.org/dc/elements/1.1/'><item><title>Policy</title><link>https://www.boj.or.jp/en/test.htm</link><dc:date>2026-10-07T01:00:00+09:00</dc:date></item></rdf:RDF>";assertThat(news.parseRss("BOJ_NEWS",bytes(boj))).isEqualTo(1);assertThat(news.news()).filteredOn(n->n.source().equals("BOJ_NEWS")).allMatch(n->n.publishedPrecision().equals("FEED_TIMESTAMP"));
    }
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
    @Test void evidencePrioritizesKnownPublicationAndExcludesFutureReceiptsAndReleases() throws Exception {
        var f=new TestSupport();var news=new NewsService(f.settings,new RemoteHttp(),f.store,new EventHub(),f.clock);
        String template="<rss><channel><item><guid>%s</guid><title>%s</title><link>https://www.bea.gov/news/%s</link>%s</item></channel></rss>";
        news.parseRss("BEA_NEWS",bytes(template.formatted("dated","Current release","dated","<pubDate>Thu, 08 Oct 2026 00:00:00 GMT</pubDate>")));
        f.clock.advance(5000);
        news.parseRss("BEA_NEWS",bytes(template.formatted("archive","2007 archive","archive","")));
        news.parseRss("BEA_NEWS",bytes(template.formatted("future","Future release","future","<pubDate>Fri, 09 Oct 2026 00:00:00 GMT</pubDate>")));
        var cutoff=f.clock.instant();f.clock.advance(5000);
        news.parseRss("BEA_NEWS",bytes(template.formatted("late","Received later","late","")));
        assertThat(news.evidence(cutoff)).extracting(NewsService.News::title).containsExactly("Current release");
        assertThat(news.news()).filteredOn(n->n.title().equals("2007 archive")).allMatch(n->Boolean.TRUE.equals(n.archived()));
    }
    @Test void calendarUsesDeclaredZoneAcrossDaylightSavingAndUnfoldsSummary() {
        var f=new TestSupport();var news=new NewsService(f.settings,new RemoteHttp(),f.store,new EventHub(),f.clock);
        String ics="BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:summer\r\nSUMMARY:Employment\r\n Situation\r\nDTSTART;TZID=America/New_York:20261009T083000\r\nEND:VEVENT\r\nBEGIN:VEVENT\r\nUID:winter\r\nSUMMARY:CPI\r\nDTSTART;TZID=America/New_York:20261112T083000\r\nEND:VEVENT\r\nEND:VCALENDAR";
        assertThat(news.parseCalendar(bytes(ics),"https://www.bls.gov/schedule/news_release/bls.ics")).isEqualTo(2);
        assertThat(news.calendar().get(0).scheduledAt().toString()).isEqualTo("2026-10-09T12:30:00Z");
        assertThat(news.calendar().get(1).scheduledAt().toString()).isEqualTo("2026-11-12T13:30:00Z");
        assertThat(news.calendar().get(0).title()).isEqualTo("EmploymentSituation");
    }
    @Test void calendarsKeepTheirSourceAndIdsSeparate() {
        var f=new TestSupport();var news=new NewsService(f.settings,new RemoteHttp(),f.store,new EventHub(),f.clock);
        var ics=bytes("BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:same\r\nSUMMARY:Release\r\nDTSTART;VALUE=DATE-TIME:20261029T123000Z\r\nEND:VEVENT\r\nEND:VCALENDAR");
        assertThat(news.parseCalendar("BEA_CALENDAR",ics,"https://www.bea.gov/news/schedule")).isEqualTo(1);
        assertThat(news.parseCalendar("BLS_CALENDAR",ics,"https://www.bls.gov/schedule")).isEqualTo(1);
        assertThat(news.calendar()).hasSize(2);assertThat(news.calendar().stream().map(NewsService.CalendarEvent::source)).containsExactlyInAnyOrder("BEA_CALENDAR","BLS_CALENDAR");
        assertThat(news.calendar().stream().map(NewsService.CalendarEvent::id).distinct().count()).isEqualTo(2);
    }
    @Test void beaCurrentListHasPrecisePublicationAndLeavesOldRecordsQuarantined() throws Exception {
        var f=new TestSupport();var news=new NewsService(f.settings,new RemoteHttp(),f.store,new EventHub(),f.clock);
        news.parseRss("BEA_NEWS",bytes("<rss><item><title>2013 archive</title><link>https://www.bea.gov/news/2013/archive</link></item></rss>"));
        String html="<table><tr class='release-row'><td headers='view-title-table-column'><a href='/news/2026/pce'>PCE</a></td><td><time datetime='2026-09-30T08:30:00-04:00'>Sept 30</time></td></tr></table>";
        news.parseBeaCurrent(bytes(html),"https://www.bea.gov/news/current-releases");
        assertThat(news.news().getFirst().publishedAt()).isEqualTo(Instant.parse("2026-09-30T12:30:00Z"));
        assertThat(news.news().getLast().freshness()).isEqualTo("QUARANTINED");
        assertThat(news.parseBeaCurrent(bytes(html),"https://www.bea.gov/news/current-releases")).isEqualTo(1);
        assertThat(news.news()).hasSize(2);
    }
    @Test void blsAtomTimeNeedsBodyVerificationAndFrozenEvidenceCannotSeeLaterBody() throws Exception {
        var f=new TestSupport();var remote=mock(RemoteHttp.class);
        var news=new NewsService(f.settings,remote,f.store,new EventHub(),f.clock);
        f.clock.now=Instant.parse("2026-10-08T14:00:00Z");
        String atom="<feed xmlns='http://www.w3.org/2005/Atom'><entry><id>cpi-202610</id><title>CPI release</title><link rel='alternate' href='https://www.bls.gov/news.release/archives/cpi_10082026.htm'/><published>2026-10-08T07:50:00-04:00</published><updated>2026-10-08T07:50:00-04:00</updated><summary type='html'>&lt;p&gt;Prices &amp;amp; income&lt;/p&gt;</summary></entry></feed>";
        assertThat(news.parseRss("BLS_CPI",bytes(atom))).isEqualTo(1);
        assertThat(news.news().getFirst().excerpt()).isEqualTo("Prices & income");
        assertThat(news.news().getFirst().publishedPrecision()).isEqualTo("FEED_TIMESTAMP");
        var cutoff=f.clock.instant();assertThat(news.evidence(cutoff)).isEmpty();
        f.clock.advance(1000);
        var fetched=response("<div id='bodytext'><pre>Transmission embargoed until\n8:30 a.m. (ET) Thursday, October 8, 2026\nThe Consumer Price Index increased 0.4 percent, with core prices and energy contributing to the increase.</pre></div>");when(remote.send(any(),anyInt())).thenReturn(fetched);
        news.enrich(news.news().getFirst());
        assertThat(news.news().getFirst().publishedAt()).isEqualTo(Instant.parse("2026-10-08T12:30:00Z"));
        assertThat(news.news().getFirst().sourcePublishedAt()).isEqualTo(Instant.parse("2026-10-08T11:50:00Z"));
        assertThat(news.evidence(cutoff)).isEmpty();
        var packet=news.freezeEvidence(f.clock.instant(),"run",4);
        assertThat(packet).hasSize(1);assertThat(packet.getFirst()).containsEntry("publishedPrecision","DECLARED_RELEASE_TIME");
        assertThat(packet.getFirst().get("excerpt").toString()).contains("0.4 percent");
        var saved=f.store.find("research-news","run",Map.class).orElseThrow();assertThat(saved.get("articles")).isNotNull();
        assertThat(news.parseRss("BLS_CPI",bytes(atom))).isZero();
        news.parseRss("BLS_CPI",bytes(atom.replace("07:50:00-04:00</updated>","07:55:00-04:00</updated>")));
        assertThat(news.news().getFirst().bodyHash()).isNull();assertThat(news.evidence(f.clock.instant())).isEmpty();
    }
    @Test void cachedBodyFailureDoesNotMoveItsKnowledgeTimeOrHideFailure() throws Exception {
        var f=new TestSupport();var remote=mock(RemoteHttp.class);var news=new NewsService(f.settings,remote,f.store,new EventHub(),f.clock);
        news.parseRss("FED_PRESS",bytes("<rss><item><title>Decision</title><link>https://www.federalreserve.gov/newsevents/pressreleases/a.htm</link><pubDate>Thu, 08 Oct 2026 00:00:00 GMT</pubDate></item></rss>"));
        var fetched=response("<div id='article'><p>The monetary policy committee decided to hold interest rates unchanged at 4.25 percent after assessing economic conditions.</p></div>");when(remote.send(any(),anyInt())).thenReturn(fetched);
        news.enrich(news.news().getFirst());var received=news.news().getFirst().contentReceivedAt();
        f.clock.advance(5000);when(remote.send(any(),anyInt())).thenThrow(new IllegalStateException("HTTP_403"));news.enrich(news.news().getFirst());
        assertThat(news.news().getFirst().contentReceivedAt()).isEqualTo(received);
        assertThat(news.news().getFirst().contentState()).isEqualTo("CACHED_AFTER_FAILURE");
        assertThat(news.evidence(received).getFirst().excerpt()).contains("4.25 percent");
    }
    @Test void officialFomcDatesNeverInventDecisionHourAndSupportCrossMonthRanges() {
        var f=new TestSupport();var news=new NewsService(f.settings,new RemoteHttp(),f.store,new EventHub(),f.clock);
        String html="<div class='panel'><div class='panel-heading'>2026 FOMC Meetings</div><div class='fomc-meeting'><div class='fomc-meeting__month'>October</div><div class='fomc-meeting__date'>27-28</div></div><div class='fomc-meeting'><div class='fomc-meeting__month'>December</div><div class='fomc-meeting__date'>8-9*</div></div></div><div class='panel'><div class='panel-heading'>2027 FOMC Meetings</div><div class='fomc-meeting'><div class='fomc-meeting__month'>April/May</div><div class='fomc-meeting__date'>30-1</div></div></div>";
        assertThat(news.parseFomcSchedule(bytes(html),"https://www.federalreserve.gov/monetarypolicy/fomccalendars.htm")).isEqualTo(3);
        assertThat(news.calendar()).allMatch(e->e.scheduledAt()==null&&e.timePrecision().equals("DATE_RANGE"));
        assertThat(news.calendar().getLast().endDate()).isEqualTo("2027-05-01");
    }
    @Test void blsCustomTimezoneAliasIsNotSilentlySkippedAndInvalidCalendarFails() {
        var f=new TestSupport();var news=new NewsService(f.settings,new RemoteHttp(),f.store,new EventHub(),f.clock);
        String ics="BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:a\r\nSUMMARY:CPI\r\nDTSTART;TZID=US-Eastern:20261015T083000\r\nEND:VEVENT\r\nEND:VCALENDAR";
        news.parseCalendar(bytes(ics),"https://www.bls.gov/schedule/news_release/bls.ics");
        assertThat(news.calendar().getFirst().scheduledAt()).isEqualTo(Instant.parse("2026-10-15T12:30:00Z"));
        assertThat(news.calendar().getFirst().originalZone()).isEqualTo("US-Eastern");
        var firstReceived=news.calendar().getFirst().receivedAt();f.clock.advance(10000);
        news.parseCalendar(bytes(ics),"https://www.bls.gov/schedule/news_release/bls.ics");
        assertThat(news.calendar().getFirst().receivedAt()).isEqualTo(firstReceived);
        assertThatThrownBy(()->news.parseCalendar(bytes("<html>Access Denied</html>"),"https://www.bls.gov/schedule/")).hasMessage("CALENDAR_FORMAT_CHANGED");
    }
    @SuppressWarnings("unchecked") private HttpResponse<byte[]> response(String body) {
        var response=(HttpResponse<byte[]>)mock(HttpResponse.class);when(response.statusCode()).thenReturn(200);when(response.body()).thenReturn(bytes(body));return response;
    }
    private byte[] bytes(String text){return text.getBytes(StandardCharsets.UTF_8);}
}
