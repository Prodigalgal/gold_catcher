package com.goldcatcher.market;

import static org.assertj.core.api.Assertions.*;
import com.goldcatcher.TestSupport;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class GateFeedTest {
    private final TestSupport f=new TestSupport();
    @Test void cfdPricesAreObservableButDoNotInventLiquidityOrMarks() {
        var body=f.mapper.readTree("{\"timestamp\":1791417600000,\"label\":\"\",\"data\":{\"bid_price\":\"4100.10\",\"ask_price\":\"4100.20\",\"last_price\":\"4100.15\"}}");
        var quote=GateCfdFeed.parseQuote(body,f.clock.instant());
        assertThat(quote.priceFresh(f.clock.instant())).isTrue();assertThat(quote.fresh(f.clock.instant())).isFalse();
        assertThat(quote.symbol()).isEqualTo("GATE:XAUUSD");assertThat(quote.bidSize()).isNull();assertThat(quote.mark()).isNull();assertThat(quote.continuous()).isFalse();
        f.clock.advance(4000);assertThat(quote.priceFresh(f.clock.instant())).isFalse();
    }
    @Test void cfdBarsUseSecondsSkipUnclosedBarsAndPreserveMissingVolume() {
        var body=f.mapper.readTree("{\"data\":{\"list\":[{\"t\":1791417600,\"o\":\"100\",\"h\":\"102\",\"l\":\"99\",\"c\":\"101\"},{\"t\":1791417540,\"o\":\"100\",\"h\":\"102\",\"l\":\"99\",\"c\":\"101\"}]}}");
        var candles=GateCfdFeed.parseCandles(body,"1",f.clock.instant());assertThat(candles).hasSize(1);
        assertThat(candles.getFirst().end()).isEqualTo(f.clock.instant());assertThat(candles.getFirst().volume()).isNull();
        assertThatThrownBy(()->GateCfdFeed.parseCandles(f.mapper.readTree(body.toString().replace("\"h\":\"102\"","\"h\":\"98\"")),"1",f.clock.instant())).hasMessage("GATE_INVALID_OHLC");
    }
    @Test void perpetualRulesBooksAndVolumeConvertContractsToBaseQuantityExactly() {
        var spec=f.mapper.readTree("{\"name\":\"XAU_USDT\",\"type\":\"direct\",\"enable_decimal\":false,\"status\":\"trading\",\"quanto_multiplier\":\"0.0001\",\"order_price_round\":\"0.01\",\"order_size_min\":1,\"market_order_size_max\":\"20000000\",\"order_size_max\":30000000,\"leverage_min\":\"1\",\"leverage_max\":\"100\",\"taker_fee_rate\":\"0.00075\"}");
        var risk=f.mapper.readTree("[{\"risk_limit\":\"50000\",\"leverage_max\":\"100\",\"maintenance_rate\":\"0.005\",\"deduction\":\"0\"}]");
        var rules=MarketFeed.parseRules(spec,risk,f.clock.instant());assertThat(rules.minQty()).isEqualByComparingTo("0.0001");
        assertThat(rules.maxQty()).isEqualByComparingTo("2000");assertThat(rules.takerFeeRate()).isEqualByComparingTo("0.00075");
        var book=f.mapper.readTree("{\"u\":42,\"t\":1791417600000,\"b\":\"4100\",\"a\":\"4100.01\",\"B\":148861,\"A\":\"101629\"}");
        var quote=MarketFeed.parseBook(book,rules,f.mapper.createObjectNode(),null,f.clock.instant().plusSeconds(3600),f.clock.instant(),true);
        assertThat(quote.bidSize()).isEqualByComparingTo("14.8861");assertThat(quote.askSize()).isEqualByComparingTo("10.1629");assertThat(quote.fresh(f.clock.instant())).isTrue();
        var bars=f.mapper.readTree("[{\"t\":1791417540,\"o\":\"100\",\"h\":\"102\",\"l\":\"99\",\"c\":\"101\",\"v\":12345}]");
        assertThat(MarketFeed.parseCandles(bars,"1",new BigDecimal("0.0001"),f.clock.instant()).getFirst().volume()).isEqualByComparingTo("1.2345");
    }
}
