package com.goldcatcher.market;

import static java.math.BigDecimal.*;
import static com.goldcatcher.market.MarketTypes.*;
import java.math.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class IndicatorEngine {
    public static final MathContext MC=MathContext.DECIMAL128;
    public Indicators calculate(String frame,List<Candle> history,Instant cutoff) {
        var candles=history.stream().filter(c->!c.end().isAfter(cutoff)).sorted(Comparator.comparing(Candle::start)).toList();
        Map<String,String> values=new LinkedHashMap<>();
        var closes=candles.stream().map(Candle::close).toList();
        put(values,"sma20",sma(closes,20));put(values,"ema20",ema(closes,20));put(values,"ema60",ema(closes,60));
        put(values,"rsi14",rsi(closes,14));put(values,"atr14",atr(candles,14));
        if(closes.size()>=35) {
            List<BigDecimal> macd=new ArrayList<>();
            for(int i=26;i<=closes.size();i++) macd.add(ema(closes.subList(0,i),12).subtract(ema(closes.subList(0,i),26),MC));
            var signal=ema(macd,9);var line=macd.getLast();
            put(values,"macd",line);put(values,"macdSignal",signal);put(values,"macdHistogram",line.subtract(signal,MC));
        }
        if(!candles.isEmpty()) {
            var last=candles.getLast();put(values,"close",last.close());put(values,"volume",last.volume());
            if(closes.size()>1) put(values,"changePct",last.close().subtract(closes.get(closes.size()-2),MC)
                .divide(closes.get(closes.size()-2),MC).multiply(valueOf(100),MC));
        }
        return new Indicators(frame,candles.size(),candles.isEmpty()?null:candles.getLast().end(),
            candles.size()>=60?"READY":"WARMING_UP",Map.copyOf(values));
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
