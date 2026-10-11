package com.goldcatcher.analysis;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.goldcatcher.*;
import com.goldcatcher.market.GateCfdFeed;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class CnbcRatesTest {
    static byte[] page(TestSupport f,String symbol,String value,String previous,String timestamp){
        var row=new LinkedHashMap<String,Object>();row.put("symbol",symbol);row.put("code","0");row.put("type","BOND");
        row.put("last",value);row.put("previous_day_closing",previous);row.put("last_time",timestamp);row.put("last_timedate","5:01 PM EDT");
        row.put("change_pct","-0.0655%");row.put("bond_last_price","95.2969");row.put("realTime","true");row.put("provider","CNBC Quote Cache");
        row.put("name","Escaped } ; window.fake=true; \\\" string");
        return ("<script>window.__s_data="+f.mapper.writeValueAsString(Map.of("quote",Map.of("data",List.of(row))))+";window.other={};</script>").getBytes(StandardCharsets.UTF_8);
    }
    @Test void datedQuotePreservesOffsetAndUsesYieldDifferenceRatherThanBondPricePercentage(){
        var f=new TestSupport();f.clock.now=Instant.parse("2026-10-09T21:02:00Z");
        var quote=CnbcRates.parse("US10Y",page(f,"US10Y","5.242%","5.233%","2026-10-09T17:01:34.000-0400"),f.mapper,f.clock.instant());
        var d=CnbcRates.driver(quote,f.clock.instant(),1,List.of());
        assertThat(d.responseAt()).isEqualTo(Instant.parse("2026-10-09T21:01:34Z"));
        assertThat(d.value()).isEqualTo("5.242");assertThat(d.change()).isEqualTo("0.9");
        assertThat(d.returnsPct()).isEmpty();assertThat(d.marketStatus()).isEqualTo("RECENT_SOURCE_QUOTE");
        assertThat(quote.rawQuote()).contains("95.2969","window.fake");
    }
    @Test void missingDateForeignSymbolWrongUnitAndFutureQuotesAreRejected(){
        var f=new TestSupport();f.clock.now=Instant.parse("2026-10-09T21:02:00Z");
        assertThatThrownBy(()->CnbcRates.parse("US10Y",page(f,"US10Y","5.242%","5.233%","5:01 PM EDT"),f.mapper,f.clock.instant())).hasMessage("CNBC_RATE_DATED_TIMESTAMP_REQUIRED");
        assertThatThrownBy(()->CnbcRates.parse("US10Y",page(f,"US2Y","5.242%","5.233%","2026-10-09T17:01:34.000-0400"),f.mapper,f.clock.instant())).hasMessage("CNBC_RATE_SYMBOL_MISMATCH");
        assertThatThrownBy(()->CnbcRates.parse("US10Y",page(f,"US10Y","95.2969","5.233%","2026-10-09T17:01:34.000-0400"),f.mapper,f.clock.instant())).hasMessage("CNBC_RATE_PERCENT_UNIT_REQUIRED");
        assertThatThrownBy(()->CnbcRates.parse("US10Y",page(f,"US10Y","5.242%","5.233%","2026-10-09T17:05:34.000-0400"),f.mapper,f.clock.instant())).hasMessage("CNBC_RATE_FUTURE_TIMESTAMP");
    }
    @Test void negativeRealYieldsAndObservedWindowsUseExactBasisPointsAndRejectMissingBaselines(){
        var f=new TestSupport();f.clock.now=Instant.parse("2026-10-09T19:00:00Z");
        var q=CnbcRates.parse("US10YTIPS",page(f,"US10YTIPS","-0.1%","0%","2026-10-09T15:00:00.000-0400"),f.mapper,f.clock.instant());
        var history=List.of(new CnbcRates.Point(f.clock.instant().minusSeconds(300),f.clock.instant().minusSeconds(290),new BigDecimal("-0.12")),
            new CnbcRates.Point(f.clock.instant().minusSeconds(1000),f.clock.instant().minusSeconds(990),new BigDecimal("1")),
            new CnbcRates.Point(f.clock.instant().minusSeconds(3600),f.clock.instant().plusSeconds(1),new BigDecimal("4")));
        var d=CnbcRates.driver(q,f.clock.instant(),1,history);
        assertThat(d.changesBp()).containsOnly(entry("5","2"));assertThat(d.change()).isEqualTo("-10");
        assertThat(CnbcRates.driver(q,f.clock.instant().plusSeconds(86400),1,history).changesBp()).isEmpty();
    }
    @Test void pollingAndRestartDoNotMakeAnUnchangedClosedMarketQuoteFresh(){
        var f=new TestSupport();f.clock.now=Instant.parse("2026-10-09T21:02:00Z");var gate=mock(GateCfdFeed.class);var http=mock(RemoteHttp.class);
        var sources=new DriverSources(f.settings,f.store,http,f.mapper,gate,new EventHub(),f.clock);
        var bytes=page(f,"US10Y","5.242%","5.233%","2026-10-09T17:01:34.000-0400");sources.ingestCnbc("US10Y",bytes);
        var first=sources.drivers(f.clock.instant()).getFirst();f.clock.advance(172800000);
        var restarted=new DriverSources(f.settings,f.store,http,f.mapper,gate,new EventHub(),f.clock);restarted.recover();restarted.ingestCnbc("US10Y",bytes);
        var current=restarted.drivers(f.clock.instant()).getFirst();assertThat(current.receivedAt()).isEqualTo(first.receivedAt());assertThat(current.version()).isEqualTo(1);
        assertThat(TrendAnalysis.rateFresh(current,f.clock.instant())).isFalse();
        assertThat(current.marketStatus()).isEqualTo("STALE_OR_MARKET_CLOSED");
        assertThat(f.store.list("cnbc-rate-quote-version",CnbcRates.Observed.class,20)).hasSize(1);
        assertThat(restarted.pollInterval("CNBC_US10Y")).isEqualTo(60);
    }
    @Test void olderCachedPublisherPagesCannotOverwriteANewerObservation(){
        var f=new TestSupport();f.clock.now=Instant.parse("2026-10-09T21:02:00Z");
        var sources=new DriverSources(f.settings,f.store,mock(RemoteHttp.class),f.mapper,mock(GateCfdFeed.class),new EventHub(),f.clock);
        sources.ingestCnbc("US10Y",page(f,"US10Y","5.242%","5.233%","2026-10-09T17:01:34.000-0400"));
        assertThatThrownBy(()->sources.ingestCnbc("US10Y",page(f,"US10Y","5.239%","5.233%","2026-10-09T17:00:34.000-0400")))
            .hasMessage("CNBC_RATE_TIMESTAMP_REGRESSION");
        assertThat(sources.drivers(f.clock.instant()).getFirst().value()).isEqualTo("5.242");
        assertThat(f.store.list("cnbc-rate-quote-version",CnbcRates.Observed.class,20)).hasSize(1);
    }
    @Test void nonQuoteJavascriptFieldsAndNestedLookalikesCannotChangeTheDatedJsonQuote(){
        var f=new TestSupport();f.clock.now=Instant.parse("2026-10-09T21:06:00Z");
        String valid=new String(page(f,"US2Y","4.791%","4.756%","2026-10-09T17:05:00.000-0400"),StandardCharsets.UTF_8);
        String withUndefined=valid.replace("window.__s_data={","window.__s_data={\"nested\":{\"quote\":{\"data\":[]}},\"video\":undefined,")
            .replace("\"quote\":{\"data\":","\"quote\":{\"latestVideo\":undefined,\"data\":");
        var q=CnbcRates.parse("US2Y",withUndefined.getBytes(StandardCharsets.UTF_8),f.mapper,f.clock.instant());
        assertThat(q.value()).isEqualByComparingTo("4.791");assertThat(q.sourceAt()).isEqualTo(Instant.parse("2026-10-09T21:05:00Z"));
        assertThatThrownBy(()->CnbcRates.propertyContainer("{\"quote\":{},\"quote\":{}}","quote")).hasMessage("CNBC_RATE_DUPLICATE_PROPERTY");
    }
}
