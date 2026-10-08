package com.goldcatcher.simulation;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.goldcatcher.market.MarketTypes.*;
import com.goldcatcher.*;
import com.goldcatcher.market.MarketFeed;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
class SimulationLedgerTest {
    TestSupport f;MarketFeed market;SimulationLedger ledger;Instrument rules;AtomicReference<Quote> quote;
    @BeforeEach void setup() {
        f=new TestSupport();f.environment.setProperty("GOLD_TAKER_FEE_RATE","0.001");market=mock(MarketFeed.class);quote=new AtomicReference<>();
        rules=new Instrument("XAUUSDT","Trading","USDT",d("0.01"),d("0.001"),d("0.001"),d("1000"),d("1000"),d("1"),d("1"),d("100"),d("0.01"),
            List.of(new RiskTier(d("1000000"),d("100"),d("0.005"),BigDecimal.ZERO)),f.clock.instant());
        quote.set(q(1,"100","101","0.5"));when(market.instrument()).thenReturn(rules);when(market.latestQuote()).thenAnswer(call->quote.get());
        when(market.snapshot("XAUUSDT")).thenAnswer(call->new Snapshot("XAUUSDT","LIVE",quote.get(),rules,Map.of(),List.of(),f.clock.instant(),null));
        ledger=new SimulationLedger(f.store,market,f.settings,new EventHub(),f.clock);ledger.initialize();
    }
    @Test void activationPartialLiquidityIdempotenceAndSpreadAccountingSurviveRestart() {
        var order=ledger.propose("decision-1","XAUUSDT",plan("1"));
        assertThat(ledger.propose("decision-1","XAUUSDT",plan("1")).id).isEqualTo(order.id);
        f.clock.advance(100);send(2,"100","101","0.5");assertThat(current().filled).isEqualByComparingTo("0");
        f.clock.advance(200);send(3,"100","101","0.5");assertThat(current().filled).isEqualByComparingTo("0.5");
        send(3,"100","101","0.5");assertThat(current().filled).isEqualByComparingTo("0.5");
        f.clock.advance(10);send(4,"100","101","0.5");assertThat(current().filled).isEqualByComparingTo("1");
        assertThat(ledger.view("XAUUSDT").balance()).isEqualTo("999.899");
        assertThat(ledger.view("XAUUSDT").unrealized()).isEqualTo("-1");
        assertThat(current().entryFees).isEqualByComparingTo("0.101");
        var close=f.mapper.readTree("{\"action\":\"CLOSE\",\"orderId\":\""+order.id+"\"}");ledger.propose("exit-decision","XAUUSDT",close);
        f.clock.advance(10);send(5,"102","103","0.4");assertThat(current().openQuantity).isEqualByComparingTo("0.6");
        f.clock.advance(10);send(6,"102","103","1");
        assertThat(current().netRealized).isEqualByComparingTo("0.797");assertThat(ledger.view("XAUUSDT").balance()).isEqualTo("1000.797");
        assertThat(current().positionStatus).isEqualTo("CLOSED");assertThat(current().costIncomplete).isFalse();
        var restarted=new SimulationLedger(f.store,market,f.settings,new EventHub(),f.clock);restarted.initialize();
        assertThat(restarted.view("XAUUSDT").balance()).isEqualTo("1000.797");
        assertThat(f.store.events(order.id,100).stream().filter(e->e.type().equals("ENTRY_FILL_L1_ASSUMPTION")).count()).isEqualTo(2);
    }
    @Test void cancelKeepsPartialPositionAndMissingFeePreventsCreation() {
        var order=ledger.propose("d","XAUUSDT",plan("1"));f.clock.advance(300);send(2,"100","101","0.5");ledger.cancel(order.id);
        f.clock.advance(10);send(3,"100","101","1");assertThat(current().openQuantity).isEqualByComparingTo("0.5");assertThat(current().status).isEqualTo("CANCELED");
        f.environment.setProperty("GOLD_TAKER_FEE_RATE","");var rejected=ledger.propose("missing-fee","XAUUSDT",plan("1"));
        assertThat(rejected.status).isEqualTo("REJECTED");assertThat(rejected.rejection).isEqualTo("COST_OR_PLAN_UNKNOWN");
        assertThat(rejected.reservation).isEqualByComparingTo("0");
    }
    @Test void bookSizeIsSharedAcrossOrdersAndGapIsRecorded() {
        ledger.propose("a","XAUUSDT",plan("1"));ledger.propose("b","XAUUSDT",plan("1"));
        f.clock.advance(300);send(2,"100","101","0.5");
        assertThat(ledger.view("XAUUSDT").orders().stream().map(o->o.filled).reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo("0.5");
        f.clock.advance(4000);send(3,"100","101","0.5");assertThat(current().quoteGap).isTrue();assertThat(current().costIncomplete).isTrue();
        assertThat(ledger.view("XAUUSD.s").balance()).isNull();
    }
    @Test void isolatedMarginLiquidationUsesFreshMarkAndImmutableEntryFee() {
        ledger.propose("a","XAUUSDT",plan("1"));f.clock.advance(300);send(2,"100","101","1");
        f.clock.advance(10);send(3,"99.9","100","1");
        assertThat(current().positionStatus).isEqualTo("LIQUIDATED");assertThat(current().entryFees).isEqualByComparingTo("0.101");
        assertThat(current().netRealized).isEqualByComparingTo("-1.3009");
    }
    private tools.jackson.databind.JsonNode plan(String qty){return f.mapper.valueToTree(Map.of("action","CREATE","side","BUY","type","LIMIT","quantity",qty,"leverage","100","entryPrice","101",
        "expiresAt",f.clock.instant().plusSeconds(60).toString(),"holdUntil",f.clock.instant().plusSeconds(120).toString(),"riskAssessment","风险由模型评估","invalidate","证据失效"));}
    private Quote q(long seq,String bid,String ask,String size){return new Quote("XAUUSDT","TEST",seq,f.clock.instant(),f.clock.instant(),d(bid),d(ask),d(size),d(size),d(ask),d(ask),f.clock.instant(),d("0.001"),f.clock.instant().plusSeconds(3600),true);}
    private void send(long seq,String bid,String ask,String size){quote.set(q(seq,bid,ask,size));ledger.acceptQuote(quote.get());}
    private SimulationLedger.Order current(){return ledger.view("XAUUSDT").orders().getFirst();}
    private BigDecimal d(String v){return new BigDecimal(v);}
}
