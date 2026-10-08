package com.goldcatcher.market;

import static java.math.BigDecimal.*;
import static com.goldcatcher.market.MarketTypes.*;
import java.math.*;
import java.time.Instant;
import java.time.Duration;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class IndicatorEngine {
    public static final MathContext MC=MathContext.DECIMAL128;
    public Indicators calculate(String frame,List<Candle> history,Instant cutoff) {
        var candles=history.stream().filter(c->!c.end().isAfter(cutoff)).sorted(Comparator.comparing(Candle::start)).toList();
        Map<String,String> values=new LinkedHashMap<>();
        var closes=candles.stream().map(Candle::close).toList();
        var sma20=sma(closes,20);var ema20=ema(closes,20);var ema60=ema(closes,60);
        var rsi14=rsi(closes,14);var atr14=atr(candles,14);
        put(values,"sma20",sma20);put(values,"ema20",ema20);put(values,"ema60",ema60);
        put(values,"rsi14",rsi14);put(values,"atr14",atr14);
        BigDecimal deviation=null,upper=null,lower=null;
        if(sma20!=null) {
            BigDecimal variance=ZERO;
            for(var price:closes.subList(closes.size()-20,closes.size()))variance=variance.add(price.subtract(sma20,MC).pow(2,MC),MC);
            deviation=variance.divide(valueOf(20),MC).sqrt(MC);
            upper=sma20.add(deviation.multiply(valueOf(2),MC),MC);lower=sma20.subtract(deviation.multiply(valueOf(2),MC),MC);
            put(values,"bbMiddle",sma20);put(values,"bbUpper",upper);put(values,"bbLower",lower);
            put(values,"bbWidthPct",percent(upper.subtract(lower,MC),sma20));
        }
        if(closes.size()>=35) {
            List<BigDecimal> macd=new ArrayList<>();
            for(int i=26;i<=closes.size();i++) macd.add(ema(closes.subList(0,i),12).subtract(ema(closes.subList(0,i),26),MC));
            var signal=ema(macd,9);var line=macd.getLast();
            put(values,"macd",line);put(values,"macdSignal",signal);put(values,"macdHistogram",line.subtract(signal,MC));
        }
        if(!candles.isEmpty()) {
            var last=candles.getLast();put(values,"close",last.close());put(values,"volume",last.volume());
            put(values,"atrPct",percent(atr14,last.close()));
            put(values,"ema20DistancePct",percent(ema20==null?null:last.close().subtract(ema20,MC),ema20));
            if(upper!=null&&upper.compareTo(lower)>0)put(values,"bbPercentB",last.close().subtract(lower,MC).divide(upper.subtract(lower,MC),MC));
            if(closes.size()>12)put(values,"roc12Pct",percent(last.close().subtract(closes.get(closes.size()-13),MC),closes.get(closes.size()-13)));
            if(candles.size()>=20&&candles.subList(candles.size()-20,candles.size()).stream().allMatch(c->c.volume()!=null)) {
                BigDecimal total=ZERO,weighted=ZERO;
                for(var candle:candles.subList(candles.size()-20,candles.size())) {
                    total=total.add(candle.volume(),MC);
                    var typical=candle.high().add(candle.low(),MC).add(candle.close(),MC).divide(valueOf(3),MC);
                    weighted=weighted.add(typical.multiply(candle.volume(),MC),MC);
                }
                if(total.signum()>0) {
                    put(values,"vwap20",weighted.divide(total,MC));
                    put(values,"volumeRatio20",last.volume().divide(total.divide(valueOf(20),MC),MC));
                }
            }
            if(closes.size()>1) put(values,"changePct",last.close().subtract(closes.get(closes.size()-2),MC)
                .divide(closes.get(closes.size()-2),MC).multiply(valueOf(100),MC));
        }
        var readings=new LinkedHashMap<String,IndicatorReading>();
        var close=closes.isEmpty()?null:closes.getLast();
        readings.put("trend",trend(close,ema20,ema60));
        readings.put("rsi",rsi14==null?missing("RSI14 需要至少15根已收盘K线"):
            rsi14.compareTo(valueOf(70))>=0?new IndicatorReading("OVERBOUGHT","动量偏强 / 超买区","RSI≥70；强趋势可持续超买，不能单独作为做空依据"):
            rsi14.compareTo(valueOf(30))<=0?new IndicatorReading("OVERSOLD","动量偏弱 / 超卖区","RSI≤30；弱趋势可持续超卖，不能单独作为做多依据"):
            new IndicatorReading("NEUTRAL","动量中性","RSI介于30与70之间"));
        String histogram=values.get("macdHistogram");
        readings.put("macd",histogram==null?missing("MACD(12,26,9) 当前实现需要至少35根已收盘K线"):
            new BigDecimal(histogram).signum()>0?new IndicatorReading("POSITIVE","柱值为正","MACD线高于信号线；不等于MACD线位于零轴上方"):
            new BigDecimal(histogram).signum()<0?new IndicatorReading("NEGATIVE","柱值为负","MACD线低于信号线；不等于MACD线位于零轴下方"):
            new IndicatorReading("FLAT","柱值为零","MACD线与信号线相同"));
        readings.put("bollinger",upper==null?missing("布林带(20,2)需要至少20根已收盘K线"):
            upper.compareTo(lower)==0?new IndicatorReading("ZERO_WIDTH","带宽为零","最近20根收盘价相同，%B无定义"):
            close.compareTo(upper)>0?new IndicatorReading("ABOVE_BAND","收盘位于上轨外","超出20周期、2倍总体标准差区间；不代表必然回落"):
            close.compareTo(lower)<0?new IndicatorReading("BELOW_BAND","收盘位于下轨外","低于20周期、2倍总体标准差区间；不代表必然反弹"):
            new IndicatorReading("INSIDE_BAND","收盘位于带内","最近收盘在布林带上下轨之间"));
        var result=new Indicators(frame,candles.size(),candles.isEmpty()?null:candles.getLast().end(),
            candles.size()>=60?"READY":"WARMING_UP",Map.copyOf(values),cutoff,"CURRENT",Map.copyOf(readings));
        return present(result,cutoff,false);
    }
    public Indicators present(Indicators indicator,Instant now,boolean marketClosed) {
        long minutes=indicator.frame().equals("D")?1440:Long.parseLong(indicator.frame());
        String freshness=indicator.closedThrough()==null?"NO_HISTORY":marketClosed?"MARKET_CLOSED":
            indicator.calculatedAt()==null||Duration.between(indicator.calculatedAt(),now).toSeconds()>150?"REFRESH_DELAYED":
            Duration.between(indicator.closedThrough(),now).toSeconds()>minutes*120+120?"BAR_GAP":"CURRENT";
        return new Indicators(indicator.frame(),indicator.samples(),indicator.closedThrough(),indicator.state(),indicator.values(),indicator.calculatedAt(),freshness,indicator.readings());
    }
    private BigDecimal percent(BigDecimal numerator,BigDecimal denominator) {
        return numerator==null||denominator==null||denominator.signum()==0?null:numerator.divide(denominator,MC).multiply(valueOf(100),MC);
    }
    private IndicatorReading missing(String detail){return new IndicatorReading("INSUFFICIENT","样本不足",detail);}
    private IndicatorReading trend(BigDecimal close,BigDecimal fast,BigDecimal slow) {
        if(close==null||fast==null||slow==null)return missing("EMA60需要至少60根已收盘K线");
        if(close.compareTo(fast)>0&&fast.compareTo(slow)>0)return new IndicatorReading("UPTREND","均线向上排列","收盘价 > EMA20 > EMA60；仅描述当前结构");
        if(close.compareTo(fast)<0&&fast.compareTo(slow)<0)return new IndicatorReading("DOWNTREND","均线向下排列","收盘价 < EMA20 < EMA60；仅描述当前结构");
        if(close.compareTo(fast)==0&&fast.compareTo(slow)==0)return new IndicatorReading("RANGE","均线重合","收盘价、EMA20与EMA60相同");
        return new IndicatorReading("MIXED","价格与均线分歧","价格、EMA20与EMA60未形成一致排列");
    }
    public BigDecimal sma(List<BigDecimal> values,int period) {
        if(values.size()<period) return null;
        return values.subList(values.size()-period,values.size()).stream().reduce(ZERO,(a,b)->a.add(b,MC)).divide(valueOf(period),MC);
    }
    public BigDecimal ema(List<BigDecimal> values,int period) {
        if(values.size()<period) return null;
        BigDecimal result=sma(values.subList(0,period),period),alpha=valueOf(2).divide(valueOf(period+1),MC);
        for(int i=period;i<values.size();i++) result=result.add(alpha.multiply(values.get(i).subtract(result,MC),MC),MC);
        return result;
    }
    public BigDecimal rsi(List<BigDecimal> prices,int period) {
        if(prices.size()<=period) return null;
        BigDecimal gain=ZERO,loss=ZERO;
        for(int i=1;i<=period;i++) {var delta=prices.get(i).subtract(prices.get(i-1),MC);gain=gain.add(delta.max(ZERO),MC);loss=loss.add(delta.negate().max(ZERO),MC);}
        gain=gain.divide(valueOf(period),MC);loss=loss.divide(valueOf(period),MC);
        for(int i=period+1;i<prices.size();i++) {
            var delta=prices.get(i).subtract(prices.get(i-1),MC);
            gain=gain.multiply(valueOf(period-1),MC).add(delta.max(ZERO),MC).divide(valueOf(period),MC);
            loss=loss.multiply(valueOf(period-1),MC).add(delta.negate().max(ZERO),MC).divide(valueOf(period),MC);
        }
        if(loss.signum()==0) return gain.signum()==0?valueOf(50):valueOf(100);
        return valueOf(100).subtract(valueOf(100).divide(ONE.add(gain.divide(loss,MC),MC),MC),MC);
    }
    public BigDecimal atr(List<Candle> candles,int period) {
        if(candles.size()<=period) return null;
        BigDecimal average=ZERO;
        for(int i=1;i<=period;i++) average=average.add(trueRange(candles.get(i),candles.get(i-1).close()),MC);
        average=average.divide(valueOf(period),MC);
        for(int i=period+1;i<candles.size();i++) average=average.multiply(valueOf(period-1),MC)
            .add(trueRange(candles.get(i),candles.get(i-1).close()),MC).divide(valueOf(period),MC);
        return average;
    }
    private BigDecimal trueRange(Candle c,BigDecimal previous) {
        return c.high().subtract(c.low(),MC).max(c.high().subtract(previous,MC).abs()).max(c.low().subtract(previous,MC).abs());
    }
    private void put(Map<String,String> values,String name,BigDecimal value) {if(value!=null)values.put(name,value.stripTrailingZeros().toPlainString());}
}
