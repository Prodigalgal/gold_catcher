package com.goldcatcher.market;
import static org.assertj.core.api.Assertions.*;
import static com.goldcatcher.market.MarketTypes.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
class IndicatorEngineTest {
    @Test void flatClosedBarsHaveNeutralRsiAndNoFutureLeakage() {
        Instant first=Instant.parse("2026-10-08T00:00:00Z");List<Candle> bars=new ArrayList<>();
        for(int i=0;i<80;i++)bars.add(new Candle(first.plusSeconds(i*60L),first.plusSeconds((i+1)*60L),d("100"),d("101"),d("99"),d("100"),d("10")));
        var engine=new IndicatorEngine();var cut=first.plusSeconds(70*60);
        var result=engine.calculate("1",bars,cut);
        assertThat(result.samples()).isEqualTo(70);assertThat(result.closedThrough()).isEqualTo(cut);
        assertThat(new BigDecimal(result.values().get("rsi14"))).isEqualByComparingTo("50");
        assertThat(new BigDecimal(result.values().get("atr14"))).isEqualByComparingTo("2");
        assertThat(new BigDecimal(result.values().get("macd"))).isEqualByComparingTo("0");
        assertThat(new BigDecimal(result.values().get("ema20"))).isEqualByComparingTo("100");
        bars.set(79,new Candle(first.plusSeconds(79*60),first.plusSeconds(80*60),d("9000"),d("9001"),d("8999"),d("9000"),d("100")));
        assertThat(engine.calculate("1",bars,cut)).isEqualTo(result);
    }
    @Test void risingSequenceMatchesSeededEmaAndMacdReference() {
        Instant t=Instant.parse("2026-10-08T00:00:00Z");List<Candle> bars=new ArrayList<>();
        for(int i=1;i<=100;i++)bars.add(new Candle(t.plusSeconds((i-1)*60L),t.plusSeconds(i*60L),d(""+i),d(""+(i+1)),d(""+(i-1)),d(""+i),d("1")));
        var result=new IndicatorEngine().calculate("1",bars,t.plusSeconds(6000));
        assertThat(new BigDecimal(result.values().get("sma20"))).isEqualByComparingTo("90.5");
        assertThat(new BigDecimal(result.values().get("ema20"))).isCloseTo(d("90.5"),within(d("0.00000001")));
        assertThat(new BigDecimal(result.values().get("macd"))).isCloseTo(d("7"),within(d("0.00000001")));
        assertThat(new BigDecimal(result.values().get("rsi14"))).isEqualByComparingTo("100");
        assertThat(new IndicatorEngine().calculate("1",bars.subList(0,10),t.plusSeconds(600))).satisfies(r->{assertThat(r.state()).isEqualTo("WARMING_UP");assertThat(r.values().get("ema60")).isNull();});
    }
    private BigDecimal d(String value){return new BigDecimal(value);}
}
