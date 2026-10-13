package com.goldcatcher.market;
import static org.assertj.core.api.Assertions.*;
import com.goldcatcher.TestSupport;
import com.goldcatcher.persistence.DocumentStore.MarketFrame;
import java.util.*;
import org.junit.jupiter.api.Test;
class MarketArchiveTest {
    @Test void migrationBackfillsExistingArchiveWithoutChangingEvents(){
        var f=new TestSupport();f.store.marketFrames(List.of(new MarketFrame(0,"XAU_USDT","L1_QUOTE",1,null,f.clock.instant(),"original")));
        var jdbc=(org.springframework.jdbc.core.JdbcTemplate)org.springframework.test.util.ReflectionTestUtils.getField(f.store,"jdbc");
        jdbc.execute("DROP TABLE gc_market_frame_stats");jdbc.execute("DROP INDEX gc_market_frame_symbol_channel_sequence");jdbc.execute("DROP INDEX gc_market_frame_symbol_channel_received");
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("db/migration/V4__market_archive_statistics.sql")).execute(jdbc.getDataSource());
        assertThat(f.store.marketFrameCounts("XAU_USDT").getFirst().get("frames")).isEqualTo(1L);assertThat(f.store.marketFrames("XAU_USDT",0,10).getFirst().body()).isEqualTo("original");
    }
    @Test void incrementalStatisticsMatchCommittedRowsAcrossBatchesRollbackAndExpiry(){
        var f=new TestSupport();var old=f.clock.instant().minusSeconds(120);var current=f.clock.instant();
        f.store.marketFrames(List.of(new MarketFrame(0,"XAU_USDT","L1_QUOTE",1,old,old,"a"),new MarketFrame(0,"XAU_USDT","L1_QUOTE",1,current,current,"b"),new MarketFrame(0,"GATE:XAUUSD","CFD",1,current,current,"c")));
        f.store.marketFrames(List.of(new MarketFrame(0,"XAU_USDT","L1_QUOTE",2,current,current,"d")));
        var counts=f.store.marketFrameCounts("XAU_USDT").getFirst();assertThat(counts.get("frames")).isEqualTo(3L);assertThat(counts.get("firstAt")).isEqualTo(old);assertThat(counts.get("lastAt")).isEqualTo(current);
        assertThatThrownBy(()->f.store.transaction(()->{f.store.marketFrames(List.of(new MarketFrame(0,"XAU_USDT","L1_QUOTE",2,current,current,"rollback")));throw new IllegalStateException("ROLLBACK");})).hasMessage("ROLLBACK");
        assertThat(f.store.marketFrameCounts("XAU_USDT").getFirst().get("frames")).isEqualTo(3L);
        assertThat(f.store.expireMarketFrames(current.minusSeconds(1))).isEqualTo(1);
        counts=f.store.marketFrameCounts("XAU_USDT").getFirst();var remaining=f.store.marketFrames("XAU_USDT",0,10);
        assertThat(counts.get("frames")).isEqualTo(2L);assertThat(counts.get("firstSequence")).isEqualTo(remaining.getFirst().sequence());assertThat(counts.get("lastSequence")).isEqualTo(remaining.getLast().sequence());assertThat(counts.get("firstAt")).isEqualTo(current);
        f.store.expireMarketFrames(current.plusSeconds(1));assertThat(f.store.marketFrameCounts("XAU_USDT")).isEmpty();assertThat(f.store.marketFrameCounts("GATE:XAUUSD")).isEmpty();
    }
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
