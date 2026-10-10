package com.goldcatcher.market;
import static org.assertj.core.api.Assertions.*;
import com.goldcatcher.TestSupport;
import com.goldcatcher.persistence.DocumentStore.MarketFrame;
import java.util.*;
import org.junit.jupiter.api.Test;
class MarketArchiveTest {
    @Test void exactPublicPayloadSurvivesCompressionDatabaseCursorAndRetention(){
        var f=new TestSupport();String payload="{\"p\":\"4096.123456789012345678\",\"s\":\"0\",\"中文\":\"来源\"}";
        var compressed=MarketArchive.compress(payload);assertThat(MarketArchive.expand(compressed)).isEqualTo(payload);
        f.store.marketFrames(List.of(new MarketFrame(0,"XAU_USDT","futures.trades",7,f.clock.instant(),f.clock.instant(),compressed),
            new MarketFrame(0,"GATE:XAUUSD","CFD_REST_OBSERVATION",0,null,f.clock.instant(),compressed)));
        var first=f.store.marketFrames("XAU_USDT",0,10);assertThat(first).hasSize(1);assertThat(first.getFirst().connectionEpoch()).isEqualTo(7);
        assertThat(MarketArchive.expand(first.getFirst().body())).isEqualTo(payload);assertThat(f.store.marketFrames("XAU_USDT",first.getFirst().sequence(),10)).isEmpty();
        f.store.expireMarketFrames(f.clock.instant().plusSeconds(1));assertThat(f.store.marketFrames("XAU_USDT",0,10)).isEmpty();
    }
}
