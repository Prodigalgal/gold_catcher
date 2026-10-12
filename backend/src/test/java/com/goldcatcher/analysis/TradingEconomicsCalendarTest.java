package com.goldcatcher.analysis;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.goldcatcher.*;
import com.goldcatcher.market.GateCfdFeed;
import java.nio.charset.StandardCharsets;
import java.time.*;
import org.junit.jupiter.api.Test;

class TradingEconomicsCalendarTest {
    static byte[] page(String selected,String consensus,String own){return ("<select id='DropDownListTimezone'><option selected value='"+selected+"'>zone</option></select><table id='calendar'><thead><tr><th>date</th><th>country</th><th>event</th><th>Actual</th><th>Previous</th><th>Consensus</th><th>Forecast</th></tr></thead><tr data-country='united states' data-id='123'><td class='2026-10-14'><span class='calendar-date-3'>12:30 PM</span></td><td>US</td><td><a class='calendar-event'>Core Inflation Rate MoM</a><span class='calendar-reference'>SEP</span></td><td><span id='actual'></span></td><td><span id='previous'>0.3%</span></td><td><a id='consensus'>"+consensus+"</a></td><td><a id='forecast'>"+own+"</a></td></tr></table>").getBytes(StandardCharsets.UTF_8);}
    @Test void missingOwnPredictionRemainsUnknownInsteadOfReplacingSurveyConsensus(){
        var f=new TestSupport();String html=new String(page("0","0.2%","0.9%"),StandardCharsets.UTF_8).replace("<a id='forecast'>0.9%</a>","");
        var row=TradingEconomicsCalendar.parse(html.getBytes(StandardCharsets.UTF_8),f.mapper).get(0);assertThat(row.path("rawProviderForecast").asText()).isEmpty();assertThat(row.path("forecast").asText()).isEqualTo("0.2%");
    }
    @Test void unlinkedCalendarEventTitlesRemainVisibleWithExplicitSourceFields(){
        var f=new TestSupport();String html=new String(page("0","0.2%","0.9%"),StandardCharsets.UTF_8).replace("<a class='calendar-event'>Core Inflation Rate MoM</a>","<span>Core Inflation Rate MoM</span>");
        assertThat(TradingEconomicsCalendar.parse(html.getBytes(StandardCharsets.UTF_8),f.mapper).get(0).path("title").asText()).isEqualTo("Core Inflation Rate MoM");
    }
    @Test void parsesSurveyAndOwnForecastSeparatelyAndHonorsExplicitOffset(){
        var f=new TestSupport();var rows=TradingEconomicsCalendar.parse(page("0","0.2%","0.9%"),f.mapper);var row=rows.get(0);
        assertThat(row.path("forecast").asText()).isEqualTo("0.2%");assertThat(row.path("rawProviderForecast").asText()).isEqualTo("0.9%");
        assertThat(row.path("date").asText()).isEqualTo("2026-10-14T12:30:00Z");assertThat(row.path("impact").asText()).isEqualTo("High");
        assertThat(TradingEconomicsCalendar.parse(page("480","0.2%","0.9%"),f.mapper).get(0).path("date").asText()).isEqualTo("2026-10-14T04:30:00Z");
        assertThatThrownBy(()->TradingEconomicsCalendar.parse(page("bad","0.2%","0.9%"),f.mapper)).hasMessage("TE_CALENDAR_TIMEZONE_UNKNOWN");
        assertThatThrownBy(()->TradingEconomicsCalendar.parse("<html>captcha</html>".getBytes(StandardCharsets.UTF_8),f.mapper)).hasMessage("TE_CALENDAR_TIMEZONE_UNKNOWN");
    }
    @Test void surveyHasExplicitPriorityWithoutAveragingProvidersOrUsingPostReleaseUpdates(){
        var f=new TestSupport();f.clock.advance(Duration.between(f.clock.instant(),Instant.parse("2026-10-14T12:29:00Z")).toMillis());
        var sources=new DriverSources(f.settings,f.store,new RemoteHttp(),f.mapper,mock(GateCfdFeed.class),new EventHub(),f.clock);
        sources.ingestExpectations(f.mapper.readTree("[{\"country\":\"USD\",\"title\":\"Core CPI m/m\",\"date\":\"2026-10-14T12:30:00Z\",\"forecast\":\"0.4%\",\"previous\":\"0.3%\"}]"));
        assertThat(sources.ingestTradingEconomics(page("0","0.2%","0.9%"))).isEqualTo(1);var release=Instant.parse("2026-10-14T12:30:00Z");
        var selected=sources.preRelease("CORE_CPI_MOM","PERCENT_MOM_SA",release);assertThat(selected.forecast()).isEqualTo("0.2");assertThat(selected.rawProviderForecast()).isEqualTo("0.9%");assertThat(selected.source()).isEqualTo("TRADING_ECONOMICS_SURVEY_CONSENSUS");
        f.clock.advance(120000);sources.ingestTradingEconomics(page("0","0.8%","0.9%"));assertThat(sources.preRelease("CORE_CPI_MOM","PERCENT_MOM_SA",release).forecast()).isEqualTo("0.2");
        assertThat(sources.expectations(release.minusSeconds(1))).hasSize(2);
    }
}
