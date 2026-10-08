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
        assertThat(new BigDecimal(result.values().get("bbUpper"))).isCloseTo(d("102.032562594670796"),within(d("0.00000001")));
        assertThat(new BigDecimal(result.values().get("bbLower"))).isCloseTo(d("78.967437405329204"),within(d("0.00000001")));
        assertThat(new BigDecimal(result.values().get("vwap20"))).isEqualByComparingTo("90.5");
        assertThat(new BigDecimal(result.values().get("volumeRatio20"))).isEqualByComparingTo("1");
        assertThat(new BigDecimal(result.values().get("roc12Pct"))).isCloseTo(d("13.636363636363636"),within(d("0.00000001")));
        assertThat(result.readings().get("trend").code()).isEqualTo("UPTREND");
        assertThat(new IndicatorEngine().calculate("1",bars.subList(0,10),t.plusSeconds(600))).satisfies(r->{assertThat(r.state()).isEqualTo("WARMING_UP");assertThat(r.values().get("ema60")).isNull();});
    }
    @Test void cfdDoesNotInventVolumeAndFlatBandHasNoPercentB() {
        var time=Instant.parse("2026-10-08T00:00:00Z");var bars=new ArrayList<Candle>();
        for(int i=0;i<80;i++)bars.add(new Candle(time.plusSeconds(i*60L),time.plusSeconds((i+1)*60L),d("100"),d("101"),d("99"),d("100"),null));
        var result=new IndicatorEngine().calculate("1",bars,time.plusSeconds(4800));
        assertThat(result.values()).doesNotContainKeys("volume","vwap20","volumeRatio20","bbPercentB");
        assertThat(result.values().get("atrPct")).isEqualTo("2");assertThat(result.values().get("bbWidthPct")).isEqualTo("0");
        assertThat(result.readings().get("bollinger").code()).isEqualTo("ZERO_WIDTH");assertThat(result.readings().get("trend").code()).isEqualTo("RANGE");
    }
    @Test void reportsRefreshDelayBarGapAndClosedMarketSeparately() {
        var engine=new IndicatorEngine();var time=Instant.parse("2026-10-08T00:00:00Z");
        var bar=new Candle(time.minusSeconds(60),time,d("100"),d("101"),d("99"),d("100"),d("0"));
        var current=engine.calculate("1",List.of(bar),time);assertThat(current.freshness()).isEqualTo("CURRENT");
        assertThat(engine.present(current,time.plusSeconds(151),false).freshness()).isEqualTo("REFRESH_DELAYED");
        assertThat(engine.present(current,time.plusSeconds(10000),true).freshness()).isEqualTo("MARKET_CLOSED");
        assertThat(engine.calculate("1",List.of(bar),time.plusSeconds(300)).freshness()).isEqualTo("BAR_GAP");
        assertThat(engine.calculate("1",List.of(),time).freshness()).isEqualTo("NO_HISTORY");
    }
    private BigDecimal d(String value){return new BigDecimal(value);}
}
