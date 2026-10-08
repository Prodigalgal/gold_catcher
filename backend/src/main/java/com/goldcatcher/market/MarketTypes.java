package com.goldcatcher.market;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class MarketTypes {
    private MarketTypes() {}
    public record Candle(Instant start, Instant end, @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal open,
        @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal high, @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal low,
        @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal close, @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal volume) {}
    public record RiskTier(@JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal limit,
        @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal maxLeverage,
        @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal maintenanceRate,
        @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal deduction) {}
    public record Instrument(String symbol,String status,String settlement,
        @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal tickSize,
        @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal qtyStep,
        @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal minQty,
        @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal maxQty,
        @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal maxLimitQty,
        @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal minNotional,
        @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal minLeverage,
        @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal maxLeverage,
        @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal leverageStep,
        List<RiskTier> tiers,Instant receivedAt,
        @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal quantityMultiplier,
        @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal takerFeeRate) {
        public Instrument(String symbol,String status,String settlement,BigDecimal tickSize,BigDecimal qtyStep,BigDecimal minQty,
            BigDecimal maxQty,BigDecimal maxLimitQty,BigDecimal minNotional,BigDecimal minLeverage,BigDecimal maxLeverage,
            BigDecimal leverageStep,List<RiskTier> tiers,Instant receivedAt) {
            this(symbol,status,settlement,tickSize,qtyStep,minQty,maxQty,maxLimitQty,minNotional,minLeverage,maxLeverage,leverageStep,tiers,receivedAt,BigDecimal.ONE,null);
        }
    }
    public record Quote(String symbol,String source,long sequence,Instant exchangeTime,Instant receivedAt,
        @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal bid,
        @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal ask,
        @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal bidSize,
        @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal askSize,
        @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal last,
        @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal mark,
        Instant markTime,@JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal fundingRate,
        Instant nextFundingTime,boolean continuous) {
        public boolean fresh(Instant now) {
            return bidSize!=null&&askSize!=null&&bidSize.signum()>=0&&askSize.signum()>=0&&priceFresh(now);
        }
        /** A fresh displayed price does not establish executable size or tick continuity. */
        public boolean priceFresh(Instant now) {
            return bid!=null&&ask!=null&&bid.signum()>0&&ask.compareTo(bid)>=0
                && !receivedAt.isAfter(now.plusSeconds(2)) && !exchangeTime.isAfter(now.plusSeconds(2))
                && Duration.between(receivedAt,now).compareTo(Duration.ofSeconds(3))<=0
                && Duration.between(exchangeTime,now).compareTo(Duration.ofSeconds(3))<=0;
        }
    }
    public record IndicatorReading(String code,String label,String detail) {}
    public record Indicators(String frame,int samples,Instant closedThrough,String state,Map<String,String> values,
        Instant calculatedAt,String freshness,Map<String,IndicatorReading> readings) {}
    public record Snapshot(String symbol,String state,Quote quote,Instrument rules,
        Map<String,Indicators> indicators,List<Candle> candles,Instant retrievedAt,String error) {}
}
