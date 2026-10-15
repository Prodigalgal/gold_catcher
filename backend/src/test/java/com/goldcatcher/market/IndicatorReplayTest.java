package com.goldcatcher.market;

import static org.assertj.core.api.Assertions.*;
import static com.goldcatcher.market.MarketTypes.*;
import java.math.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class IndicatorReplayTest {
    @Test void rawCandlesAndExplicitCutoffReproduceAllValuesWithoutReadingLiveMarket(){
        Instant cutoff=Instant.parse("2026-10-15T07:00:00Z");var candles=new ArrayList<Candle>();
        for(int i=0;i<80;i++){Instant start=cutoff.minusSeconds((80-i)*60L);var close=new BigDecimal("4000.123456789012345678").add(BigDecimal.valueOf(i));candles.add(new Candle(start,start.plusSeconds(60),close,close.add(BigDecimal.ONE),close.subtract(BigDecimal.ONE),close,new BigDecimal("0.123456789012345678")));}
        var original=List.copyOf(candles);var saved=IndicatorReplay.calculate(new IndicatorEngine(),"1",candles,cutoff);
        assertThat(IndicatorReplay.replay(saved).matches()).isTrue();assertThat(candles).isEqualTo(original);
        candles.clear();assertThat(saved.candles()).hasSize(80);assertThat(IndicatorReplay.replay(saved).matches()).isTrue();
        var changed=new ArrayList<>(saved.candles());var last=changed.getLast();changed.set(changed.size()-1,new Candle(last.start(),last.end(),last.open(),last.high(),last.low(),new BigDecimal("4001"),last.volume()));
        assertThat(IndicatorReplay.replay(new IndicatorReplay.Calculation(saved.version(),"1",cutoff,changed,saved.output())).matches()).isFalse();
        assertThat(IndicatorReplay.select(Map.of("1",List.of(saved)),Map.of("1",saved.output()))).containsExactly(saved);
    }
    @Test void boundedHistoryKeepsSameTimeCorrectionsAndDoesNotBindDifferentValues(){
        Instant cutoff=Instant.parse("2026-10-15T07:00:00Z");var a=IndicatorReplay.calculate(new IndicatorEngine(),"1",List.of(),cutoff);
        var b=IndicatorReplay.calculate(new IndicatorEngine(),"5",List.of(),cutoff);var previous=List.of(a);
        assertThat(IndicatorReplay.append(previous,b)).containsExactly(b,a);assertThat(previous).containsExactly(a);
        assertThat(IndicatorReplay.select(Map.of("1",List.of(a)),Map.of("1",b.output()))).isEmpty();
    }
}
