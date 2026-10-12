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
    @Test void disabledPublicConsensusIsExcludedWhileHistoryAndForexFactoryRemainAvailable(){
        var f=new TestSupport();var selection=new com.goldcatcher.information.SourceConfiguration(f.settings,f.store,f.clock,new EventHub());
        var sources=new DriverSources(f.settings,f.store,mock(RemoteHttp.class),f.mapper,mock(GateCfdFeed.class),new EventHub(),f.clock);
        Instant release=f.clock.instant().plusSeconds(60);
        var ff=new Expectation("ff","CPI_MOM","CPI m/m",release,"PROVIDER_DECLARED","High","PERCENT_MOM_SA","0.2","0.1","FOREX_FACTORY_PROVIDER","https://nfs.faireconomy.media/ff_calendar_thisweek.json",f.clock.instant(),1,"ff-hash","0.2%","0.1%");
        var te=new Expectation("te","CPI_MOM","Inflation Rate MoM",release,"PROVIDER_DECLARED","High","PERCENT_MOM_SA","0.3","0.1","TRADING_ECONOMICS_SURVEY_CONSENSUS",TradingEconomicsCalendar.URL,f.clock.instant(),1,"te-hash","0.3%","0.1%");
        f.store.save("gold-expectation-version","ff",ff);f.store.save("gold-expectation-version","te",te);
        assertThat(sources.sources()).extracting(SourceCheck::id).doesNotContain("TE_EXPECTATIONS");
        assertThat(sources.expectations(f.clock.instant())).containsExactly(ff);assertThat(sources.preRelease("CPI_MOM","PERCENT_MOM_SA",release)).isEqualTo(ff);
        assertThat(f.store.find("gold-expectation-version","te",Expectation.class)).contains(te);
        selection.save(new com.goldcatcher.information.SourceConfiguration.Draft(1,java.util.List.of()));
        assertThat(sources.sources()).extracting(SourceCheck::id).contains("TE_EXPECTATIONS");
        assertThat(sources.expectations(f.clock.instant())).containsExactlyInAnyOrder(ff,te);assertThat(sources.preRelease("CPI_MOM","PERCENT_MOM_SA",release)).isEqualTo(te);
    }
    @Test void rateLimitedHistoryWaitsForRetryAfterWhileFreshQuotesContinue() throws Exception {
        var f=new TestSupport();var gate=mock(GateCfdFeed.class);
        @SuppressWarnings("unchecked") java.net.http.HttpResponse<byte[]> limited=mock(java.net.http.HttpResponse.class);
        when(limited.statusCode()).thenReturn(429);when(limited.headers()).thenReturn(java.net.http.HttpHeaders.of(java.util.Map.of("Retry-After",java.util.List.of("120")),(k,v)->true));
        var rateLimit=com.goldcatcher.network.SourcePolling.failure("GATE_HTTP_",limited,f.clock.instant());
        when(gate.driverObservation("USIDX",true)).thenThrow(rateLimit);
        when(gate.driverObservation("USIDX",false)).thenAnswer(call->{var json=f.mapper.createObjectNode().put("timestamp",f.clock.instant().toEpochMilli());json.putObject("data").put("bid_price","100").put("ask_price","101").put("status","open");return json;});
        var sources=new DriverSources(f.settings,f.store,new RemoteHttp(),f.mapper,gate,new EventHub(),f.clock);
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(sources,"collectGate","USIDX");f.clock.advance(30000);
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(sources,"collectGate","USIDX");verify(gate,times(1)).driverObservation("USIDX",true);
        assertThat(sources.drivers(f.clock.instant())).hasSize(1);assertThat(sources.drivers(f.clock.instant()).getFirst().note()).contains("关联1分钟K线未刷新");
        f.clock.advance(100000);org.springframework.test.util.ReflectionTestUtils.invokeMethod(sources,"collectGate","USIDX");verify(gate,times(2)).driverObservation("USIDX",true);verify(gate,times(3)).driverObservation("USIDX",false);
    }
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
