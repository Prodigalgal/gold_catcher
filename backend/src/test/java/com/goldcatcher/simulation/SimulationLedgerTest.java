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
        rules=new Instrument("XAU_USDT","Trading","USDT",d("0.01"),d("0.001"),d("0.001"),d("1000"),d("1000"),d("1"),d("1"),d("100"),d("0.01"),
            List.of(new RiskTier(d("1000000"),d("100"),d("0.005"),BigDecimal.ZERO)),f.clock.instant());
        quote.set(q(1,"100","101","0.5"));when(market.instrument()).thenReturn(rules);when(market.latestQuote()).thenAnswer(call->quote.get());
        when(market.snapshot("XAU_USDT")).thenAnswer(call->new Snapshot("XAU_USDT","LIVE",quote.get(),rules,Map.of(),List.of(),f.clock.instant(),null));
        ledger=new SimulationLedger(f.store,market,f.settings,new EventHub(),f.clock);ledger.initialize();
    }
    @Test void activationPartialLiquidityIdempotenceAndSpreadAccountingSurviveRestart() {
        var order=ledger.propose("decision-1","XAU_USDT",plan("1"));
        assertThat(ledger.propose("decision-1","XAU_USDT",plan("1")).id).isEqualTo(order.id);
        f.clock.advance(100);send(2,"100","101","0.5");assertThat(current().filled).isEqualByComparingTo("0");
        f.clock.advance(200);send(3,"100","101","0.5");assertThat(current().filled).isEqualByComparingTo("0.5");
        send(3,"100","101","0.5");assertThat(current().filled).isEqualByComparingTo("0.5");
        f.clock.advance(10);send(4,"100","101","0.5");assertThat(current().filled).isEqualByComparingTo("1");
        assertThat(ledger.view("XAU_USDT").balance()).isEqualTo("999.899");
        assertThat(ledger.view("XAU_USDT").unrealized()).isEqualTo("-1");
        assertThat(current().entryFees).isEqualByComparingTo("0.101");
        var close=f.mapper.readTree("{\"action\":\"CLOSE\",\"orderId\":\""+order.id+"\"}");ledger.propose("exit-decision","XAU_USDT",close);
        f.clock.advance(10);send(5,"102","103","0.4");assertThat(current().openQuantity).isEqualByComparingTo("0.6");
        f.clock.advance(10);send(6,"102","103","1");
        assertThat(current().netRealized).isEqualByComparingTo("0.797");assertThat(ledger.view("XAU_USDT").balance()).isEqualTo("1000.797");
        assertThat(current().positionStatus).isEqualTo("CLOSED");assertThat(current().costIncomplete).isFalse();
        var restarted=new SimulationLedger(f.store,market,f.settings,new EventHub(),f.clock);restarted.initialize();
        assertThat(restarted.view("XAU_USDT").balance()).isEqualTo("1000.797");
        assertThat(f.store.events(order.id,100).stream().filter(e->e.type().equals("ENTRY_FILL_L1_ASSUMPTION")).count()).isEqualTo(2);
    }
    @Test void cancelKeepsPartialPositionAndMissingFeePreventsCreation() {
        var order=ledger.propose("d","XAU_USDT",plan("1"));f.clock.advance(300);send(2,"100","101","0.5");ledger.cancel(order.id);
        f.clock.advance(10);send(3,"100","101","1");assertThat(current().openQuantity).isEqualByComparingTo("0.5");assertThat(current().status).isEqualTo("CANCELED");
        f.environment.setProperty("GOLD_TAKER_FEE_RATE","");var rejected=ledger.propose("missing-fee","XAU_USDT",plan("1"));
        assertThat(rejected.status).isEqualTo("REJECTED");assertThat(rejected.rejection).isEqualTo("COST_OR_PLAN_UNKNOWN");
        assertThat(rejected.reservation).isEqualByComparingTo("0");
    }
    @Test void bookSizeIsSharedAcrossOrdersAndGapIsRecorded() {
        ledger.propose("a","XAU_USDT",plan("1"));ledger.propose("b","XAU_USDT",plan("1"));
        f.clock.advance(300);send(2,"100","101","0.5");
        assertThat(ledger.view("XAU_USDT").orders().stream().map(o->o.filled).reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo("0.5");
        f.clock.advance(4000);send(3,"100","101","0.5");assertThat(current().quoteGap).isTrue();assertThat(current().costIncomplete).isTrue();
        assertThat(ledger.view("GATE:XAUUSD").balance()).isNull();
    }
    @Test void isolatedMarginLiquidationUsesFreshMarkAndImmutableEntryFee() {
        ledger.propose("a","XAU_USDT",plan("1"));f.clock.advance(300);send(2,"100","101","1");
        f.clock.advance(10);send(3,"99.9","100","1");
        assertThat(current().positionStatus).isEqualTo("LIQUIDATED");assertThat(current().entryFees).isEqualByComparingTo("0.101");
        assertThat(current().netRealized).isEqualByComparingTo("-1.3009");
    }
    @Test void fundingUsesHeldQuantityAndSettlesOnceAfterRestart() {
        var boundary=f.clock.instant().plusSeconds(2);quote.set(fundingQuote(1,boundary));
        var order=ledger.propose("funding-long","XAU_USDT",plan("1"));f.clock.advance(300);
        quote.set(fundingQuote(2,boundary));ledger.acceptQuote(quote.get());
        f.clock.advance(1700);quote.set(fundingQuote(3,boundary.plusSeconds(3600)));ledger.acceptQuote(quote.get());
        assertThat(current().funding).hasSize(1);assertThat(current().funding.getFirst().quantity).isEqualByComparingTo("1");
        assertThat(current().costIncomplete).isTrue();
        ledger.propose("exit","XAU_USDT",f.mapper.valueToTree(Map.of("action","CLOSE","orderId",order.id)));
        f.clock.advance(100);quote.set(fundingQuote(4,boundary.plusSeconds(3600)));ledger.acceptQuote(quote.get());
        var rates=List.of(new SimulationLedger.FundingRate(boundary,d("0.01"),f.clock.instant()));
        ledger.acceptFundingHistory(rates);assertThat(current().fundingFees).isEqualByComparingTo("1.01");
        assertThat(current().costIncomplete).isFalse();assertThat(current().netRealized).isEqualByComparingTo("-2.211");
        var balance=ledger.view("XAU_USDT").balance();
        var restarted=new SimulationLedger(f.store,market,f.settings,new EventHub(),f.clock);restarted.initialize();restarted.acceptFundingHistory(rates);
        assertThat(restarted.view("XAU_USDT").balance()).isEqualTo(balance);
        assertThat(f.store.events(order.id,100).stream().filter(e->e.type().equals("FUNDING_SETTLED_BOUNDARY_MARK_ASSUMPTION")).count()).isEqualTo(1);
    }
    @Test void positiveFundingCreditsShortAndNegativeFundingDebitsShort() {
        var boundary=f.clock.instant().plusSeconds(2);quote.set(fundingQuote(1,boundary));
        var plan=(tools.jackson.databind.node.ObjectNode)plan("1");plan.put("side","SELL");plan.put("entryPrice","100");plan.put("leverage","10");
        ledger.propose("short-funding","XAU_USDT",plan);f.clock.advance(300);quote.set(fundingQuote(2,boundary));ledger.acceptQuote(quote.get());
        f.clock.advance(1700);quote.set(fundingQuote(3,boundary.plusSeconds(2)));ledger.acceptQuote(quote.get());
        ledger.acceptFundingHistory(List.of(new SimulationLedger.FundingRate(boundary,d("0.01"),f.clock.instant())));
        assertThat(current().fundingFees).isEqualByComparingTo("-1.01");assertThat(ledger.view("XAU_USDT").balance()).isEqualTo("1000.91");
        f.clock.advance(2000);quote.set(fundingQuote(4,boundary.plusSeconds(3600)));ledger.acceptQuote(quote.get());
        ledger.acceptFundingHistory(List.of(new SimulationLedger.FundingRate(boundary.plusSeconds(2),d("-0.01"),f.clock.instant())));
        assertThat(current().fundingFees).isEqualByComparingTo("0");assertThat(ledger.view("XAU_USDT").balance()).isEqualTo("999.9");
    }
    @Test void missingBoundaryMarkStaysUnknownInsteadOfInventingZeroCost() {
        var boundary=f.clock.instant().plusSeconds(2);quote.set(fundingQuote(1,boundary));ledger.propose("gap-funding","XAU_USDT",plan("1"));
        f.clock.advance(300);quote.set(fundingQuote(2,boundary));ledger.acceptQuote(quote.get());
        f.clock.advance(6000);quote.set(fundingQuote(3,boundary.plusSeconds(3600)));ledger.acceptQuote(quote.get());
        ledger.acceptFundingHistory(List.of(new SimulationLedger.FundingRate(boundary,d("0.01"),f.clock.instant())));
        assertThat(current().funding.getFirst().state).isEqualTo("MISSING_BOUNDARY_MARK");assertThat(current().funding.getFirst().charge).isNull();
        assertThat(current().costIncomplete).isTrue();assertThat(current().fundingUnknown).isTrue();
    }
    private Quote fundingQuote(long seq,Instant next){return new Quote("XAU_USDT","TEST",seq,f.clock.instant(),f.clock.instant(),d("100"),d("101"),d("1"),d("1"),d("101"),d("101"),f.clock.instant(),d("0.01"),next,true);}
    private tools.jackson.databind.JsonNode plan(String qty){return f.mapper.valueToTree(Map.of("action","CREATE","side","BUY","type","LIMIT","quantity",qty,"leverage","100","entryPrice","101",
        "expiresAt",f.clock.instant().plusSeconds(60).toString(),"holdUntil",f.clock.instant().plusSeconds(120).toString(),"riskAssessment","风险由模型评估","invalidate","证据失效"));}
    private Quote q(long seq,String bid,String ask,String size){return new Quote("XAU_USDT","TEST",seq,f.clock.instant(),f.clock.instant(),d(bid),d(ask),d(size),d(size),d(ask),d(ask),f.clock.instant(),d("0.001"),f.clock.instant().plusSeconds(3600),true);}
    private void send(long seq,String bid,String ask,String size){quote.set(q(seq,bid,ask,size));ledger.acceptQuote(quote.get());}
    private SimulationLedger.Order current(){return ledger.view("XAU_USDT").orders().getFirst();}
    private BigDecimal d(String v){return new BigDecimal(v);}
}
