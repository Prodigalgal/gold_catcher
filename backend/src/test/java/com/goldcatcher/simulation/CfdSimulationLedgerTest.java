package com.goldcatcher.simulation;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.goldcatcher.*;
import com.goldcatcher.market.*;
import com.goldcatcher.market.MarketTypes.*;
import java.math.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import tools.jackson.databind.node.ObjectNode;

class CfdSimulationLedgerTest {
    TestSupport f;GateCfdFeed feed;CfdSimulationLedger ledger;AtomicReference<Quote> quote;ObjectNode rules;
    @BeforeEach void setup(){
        f=new TestSupport();feed=mock(GateCfdFeed.class);quote=new AtomicReference<>();
        rules=(ObjectNode)f.mapper.readTree("""
          {"symbol":"XAUUSD","settlement_currency":"USD","contract_volume":"100","min_order_volume":"0.01","max_order_volume":"10",
           "step_order_volume":"0.01","leverage":"500","leverages":["5","50","100","500","1000"],"price_precision":2,
           "commission_per_lot":"6","exchange_rate":"1","buy_swap_cost_rate":"-72.5","sell_swap_cost_rate":"30.72",
           "swap_cost_type":"1","swap_cost_3day":"3","trade_timezone":"GMT+3","trade_mode":"4"}
          """);
        when(feed.rules()).thenReturn(rules);when(feed.rulesReceivedAt()).thenAnswer(c->f.clock.instant());
        when(feed.snapshot()).thenAnswer(c->new Snapshot(GateCfdFeed.PRODUCT,"LIVE",quote.get(),null,Map.of(),List.of(),f.clock.instant(),null));
        ledger=new CfdSimulationLedger(f.store,feed,f.settings,new EventHub(),f.clock);ledger.initialize();send(1,"99.99","100");
    }
    @Test void lotsSpreadCommissionReductionAndRestartAreIndependentFromPerpetuals(){
        var order=ledger.propose("entry",plan("0.1"));f.clock.advance(300);send(2,"99.99","100");
        assertThat(current().openQuantity).isEqualByComparingTo("0.1");assertThat(ledger.view(quote.get()).balance()).isEqualTo("999.4");
        ledger.command(order.id,"REDUCE",f.mapper.readTree("{\"quantity\":\"0.04\"}"));f.clock.advance(10);send(3,"101","101.01");
        assertThat(current().openQuantity).isEqualByComparingTo("0.06");assertThat(current().exitRequested).isFalse();
        assertThat(current().realizedGross).isEqualByComparingTo("4");assertThat(current().exitFees).isEqualByComparingTo("0.24");
        ledger.command(order.id,"CLOSE",f.mapper.createObjectNode());f.clock.advance(10);send(4,"102","102.01");
        assertThat(current().netRealized).isEqualByComparingTo("14.8");assertThat(ledger.view(quote.get()).balance()).isEqualTo("1014.8");
        assertThat(current().evidenceLevel).isEqualTo("QUOTE_OBSERVATION");assertThat(current().costIncomplete).isTrue();
        var restarted=new CfdSimulationLedger(f.store,feed,f.settings,new EventHub(),f.clock);restarted.initialize();restarted.acceptQuote(quote.get());
        assertThat(restarted.view(quote.get()).balance()).isEqualTo("1014.8");assertThat(f.store.list("ledger",SimulationLedger.Ledger.class,10)).hasSize(1);
    }
    @Test void stopOutUsesAccountEquityAndAllowsNegativeBalanceAfterPriceGap(){
        ledger.propose("large",plan("2"));f.clock.advance(300);send(2,"99.99","100");
        var waiting=plan("0.01");waiting.put("entryPrice","90");ledger.propose("pending",waiting);
        f.clock.advance(10);send(3,"95","95.01");
        var orders=ledger.view(quote.get()).orders();assertThat(orders.getFirst().positionStatus).isEqualTo("LIQUIDATED");
        assertThat(orders.get(1).status).isEqualTo("CANCELED");assertThat(ledger.view(quote.get()).balance()).isEqualTo("-24");
    }
    @Test void changedRulesExpiryAndTrailingDoNotInventBetweenQuoteFills(){
        var order=ledger.propose("trailing",plan("0.1"));f.clock.advance(300);send(2,"99.99","100");
        ledger.command(order.id,"MODIFY",f.mapper.readTree("{\"trailingDistance\":\"0.50\",\"trailingActivation\":\"101\"}"));
        f.clock.advance(300);send(3,"101","101.01");f.clock.advance(10);send(4,"102","102.01");f.clock.advance(10);send(5,"101.50","101.51");
        assertThat(current().exitReason).isEqualTo("TRAILING_STOP");assertThat(current().realizedGross).isEqualByComparingTo("15");
        var pending=plan("0.1");pending.put("entryPrice","90");ledger.propose("expiry",pending);f.clock.advance(61000);ledger.expire();
        assertThat(ledger.view(quote.get()).orders().get(1).status).isEqualTo("EXPIRED");
        var invalid=plan("0.1");invalid.put("leverage","1000");assertThat(ledger.propose("invalid",invalid).rejection).isEqualTo("INVALID_CFD_LEVERAGE");
    }
    @Test void swapFormulaSignedUnitsAndWeeklyLeverageCapAreExact(){
        assertThat(CfdSimulationLedger.swap("POINTS",d(".1"),d("100"),d(".01"),d("-72.5"),d("4000"),d("1"),3)).isEqualByComparingTo("-21.75");
        assertThat(CfdSimulationLedger.swap("SETTLEMENT_CURRENCY",d(".1"),d("100"),d(".01"),d("30.72"),d("4000"),d("1"),1)).isEqualByComparingTo("3.072");
        assertThat(CfdSimulationLedger.swap("ANNUAL_PERCENT",d(".1"),d("100"),d(".01"),d("-3.6"),d("4000"),d("1"),1)).isEqualByComparingTo("-4");
        rules.put("weekly_limit_leverage","100");assertThat(CfdRules.parse(rules).leverages()).noneMatch(l->l.compareTo(d("100"))>0);
    }
    private SimulationLedger.Order current(){return ledger.view(quote.get()).orders().getFirst();}
    private ObjectNode plan(String lots){return (ObjectNode)f.mapper.valueToTree(Map.of("action","CREATE","side","BUY","type","LIMIT","quantity",lots,"leverage","500","entryPrice","100",
        "expiresAt",f.clock.instant().plusSeconds(60).toString(),"holdUntil",f.clock.instant().plusSeconds(120).toString(),"riskAssessment","情景模拟，掉期成本未知","invalidate","证据失效"));}
    private void send(long seq,String bid,String ask){quote.set(new Quote(GateCfdFeed.PRODUCT,"CFD_TEST",seq,f.clock.instant(),f.clock.instant(),d(bid),d(ask),null,null,d(ask),null,null,null,null,true));ledger.acceptQuote(quote.get());}
    private BigDecimal d(String value){return new BigDecimal(value);}
}
