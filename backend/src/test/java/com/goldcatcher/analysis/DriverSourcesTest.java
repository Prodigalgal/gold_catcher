package com.goldcatcher.analysis;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.goldcatcher.*;
import com.goldcatcher.analysis.AnalysisTypes.*;
import com.goldcatcher.market.GateCfdFeed;
import java.nio.charset.StandardCharsets;
import java.time.*;
import org.junit.jupiter.api.Test;

class DriverSourcesTest {
    @Test void treasuryRejectsFutureDatesAndNullValuesAndCalculatesBasisPoints() throws Exception {
        var xml="""
            <feed xmlns:d="urn:d"><entry><d:NEW_DATE>2026-10-05T00:00:00</d:NEW_DATE><d:TC_10YEAR>-0.05</d:TC_10YEAR></entry>
            <entry><d:NEW_DATE>2026-10-06T00:00:00</d:NEW_DATE><d:TC_10YEAR>-0.12</d:TC_10YEAR></entry>
            <entry><d:NEW_DATE>2026-10-07T00:00:00</d:NEW_DATE><d:TC_10YEAR/></entry>
            <entry><d:NEW_DATE>2026-10-09T00:00:00</d:NEW_DATE><d:TC_10YEAR>9.99</d:TC_10YEAR></entry></feed>
            """;
        var rows=DriverSources.treasuryRows(xml.getBytes(StandardCharsets.UTF_8),true,LocalDate.parse("2026-10-08"));
        var driver=DriverSources.treasuryDrivers(rows,true,"https://home.treasury.gov",Instant.parse("2026-10-08T00:00:00Z")).getFirst();
        assertThat(driver.value()).isEqualTo("-0.12");assertThat(driver.change()).isEqualTo("-7");assertThat(driver.asOfDate()).isEqualTo("2026-10-06");
        assertThat(driver.frequency()).isEqualTo("DAILY");assertThat(driver.responseAt()).isNull();
    }
    @Test void xmlCannotReadExternalEntities(){
        assertThatThrownBy(()->DriverSources.treasuryRows("<!DOCTYPE feed [<!ENTITY ext SYSTEM 'file:///etc/passwd'>]><feed>&ext;</feed>".getBytes(StandardCharsets.UTF_8),true,LocalDate.now())).isInstanceOf(Exception.class);
    }
    @Test void gateOnlyUsesClosedBarsAndDoesNotFabricateIndexIdentity(){
        var f=new TestSupport();long at=f.clock.instant().getEpochSecond();
        var body=f.mapper.readTree("{\"label\":\"\",\"data\":{\"list\":[{\"t\":"+(at-60)+",\"c\":\"100\"},{\"t\":"+at+",\"c\":\"999\"}]}}");
        assertThat(DriverSources.parseHistory(body,f.clock.instant())).hasSize(1);
        var quote=f.mapper.readTree("{\"label\":\"\",\"timestamp\":"+at*1000+",\"data\":{\"bid_price\":\"100.01\",\"ask_price\":\"100.03\",\"last_today_close_price\":\"100\",\"status\":\"open\"}}");
        var driver=DriverSources.parseGate("USIDX",quote,f.clock.instant(),1,java.util.List.of());
        assertThat(driver.value()).isEqualTo("100.02");assertThat(driver.returnsPct()).isEmpty();assertThat(driver.timestampMeaning()).contains("LAST_TICK_TIME_NOT_SUPPLIED");assertThat(driver.note()).contains("不标为 ICE");
        f.clock.advance(12000);assertThatThrownBy(()->DriverSources.parseGate("USIDX",quote,f.clock.instant(),1,java.util.List.of())).hasMessage("GATE_DRIVER_RESPONSE_STALE");
    }
    @Test void forecastVersionsOnlyJoinWhenKnownBeforeActualPublication(){
        var f=new TestSupport();var sources=new DriverSources(f.settings,f.store,new RemoteHttp(),f.mapper,mock(GateCfdFeed.class),new EventHub(),f.clock);
        Instant release=f.clock.instant().plusSeconds(60);
        var rows=f.mapper.readTree("[{\"title\":\"CPI m/m\",\"country\":\"USD\",\"date\":\""+release+"\",\"forecast\":\"0.2%\",\"previous\":\"0.1%\",\"impact\":\"High\"}]");
        sources.ingestExpectations(rows);f.clock.advance(120000);
        ((tools.jackson.databind.node.ObjectNode)rows.path(0)).put("forecast","0.5%");sources.ingestExpectations(rows);
        var selected=sources.preRelease("CPI_MOM","PERCENT_MOM_SA",release);assertThat(selected.forecast()).isEqualTo("0.2");
        assertThat(sources.expectations(release.minusSeconds(1)).getFirst().forecast()).isEqualTo("0.2");
        assertThat(selected.receivedAt()).isBefore(release);assertThat(f.store.list("gold-expectation-version",Expectation.class,10)).hasSize(2);
        assertThat(sources.preRelease("CPI_MOM","PERCENT_MOM_SA",release.minusSeconds(120))).isNull();assertThat(sources.preRelease("CPI_MOM","COUNT",release)).isNull();
    }
    @Test void retainsUnmappedCalendarEventsWithoutPretendingToKnowTheirUnits(){
        var f=new TestSupport();var sources=new DriverSources(f.settings,f.store,new RemoteHttp(),f.mapper,mock(GateCfdFeed.class),new EventHub(),f.clock);
        var rows=f.mapper.readTree("[{\"title\":\"Trade Balance\",\"country\":\"USD\",\"date\":\""+f.clock.instant().plusSeconds(60)+"\",\"forecast\":\"-100.8B\",\"previous\":\"-88.6B\",\"impact\":\"Low\"},{\"title\":\"FOMC Member Speaks\",\"country\":\"USD\",\"date\":\""+f.clock.instant().plusSeconds(120)+"\"}]");
        assertThat(sources.ingestExpectations(rows)).isEqualTo(2);var expectations=sources.expectations(f.clock.instant());
        assertThat(expectations).hasSize(2);assertThat(expectations.getFirst().rawForecast()).isEqualTo("-100.8B");
        assertThat(expectations.getFirst().unit()).isEqualTo("UNMAPPED");assertThat(expectations.getFirst().forecast()).isNull();
        assertThat(sources.preRelease(expectations.getFirst().indicator(),"UNMAPPED",f.clock.instant().plusSeconds(60))).isNull();
    }
}
