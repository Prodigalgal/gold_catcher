package com.goldcatcher.simulation;

import static java.math.BigDecimal.*;
import static com.goldcatcher.market.IndicatorEngine.MC;
import static com.goldcatcher.market.MarketTypes.*;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.goldcatcher.*;
import com.goldcatcher.market.MarketFeed;
import com.goldcatcher.persistence.DocumentStore;
import jakarta.annotation.PostConstruct;
import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

@Service
public class SimulationLedger {
    public static class Order {
        public String id,decisionId,symbol,side,type,status="PENDING",positionStatus="NONE",riskAssessment,invalidate,
            exitReason,ruleProvenance="PUBLIC_RULES_CONFIG_FEES",rejection;
        public int version=1;
        public Instant createdAt,activatedAt,expiresAt,holdUntil,closedAt,nextFundingTime;
        public long lastSequence=-1;
        public boolean costIncomplete=false,quoteGap=false,exitRequested=false,triggered=false;
        @JsonFormat(shape=JsonFormat.Shape.STRING)
        public BigDecimal quantity,leverage,entryPrice,triggerPrice,stopLoss,takeProfit,feeRate,
            filled=ZERO,openQuantity=ZERO,averageEntry=ZERO,margin=ZERO,reservation=ZERO,
            realizedGross=ZERO,entryFees=ZERO,exitFees=ZERO,netRealized=ZERO;
    }
    public static class Ledger {
        @JsonFormat(shape=JsonFormat.Shape.STRING) public BigDecimal balance;
        public List<Order> orders=new ArrayList<>();
        public Ledger() {}
        public Ledger(BigDecimal balance){this.balance=balance;}
    }
    public record View(String symbol,String currency,String state,String balance,String available,String equity,
        String frozenMargin,String unrealized,String estimatedExitFees,Instant valuedAt,List<Order> orders,
        String costStatus,String liquidationMethod) {}
    private final DocumentStore store;private final MarketFeed market;private final RuntimeSettings settings;
    private final EventHub events;private final Clock clock;private volatile long observedSequence=-1;
    private volatile Instant observedTime;
    public SimulationLedger(DocumentStore store,MarketFeed market,RuntimeSettings settings,EventHub events,Clock clock) {
        this.store=store;this.market=market;this.settings=settings;this.events=events;this.clock=clock;
        market.onQuote(this::acceptQuote);
    }
    @PostConstruct void initialize(){store.transaction(()->{if(store.find("ledger","XAUUSDT",Ledger.class).isEmpty()) {
        var initial=settings.decimal("GOLD_INITIAL_BALANCE","1000");
        if(initial==null||initial.signum()<=0)throw new IllegalArgumentException("Initial virtual balance must be positive");
        store.save("ledger","XAUUSDT",new Ledger(initial));store.event("XAUUSDT","ACCOUNT_INITIALIZED",Map.of("balance",initial.toPlainString()));
    }return null;});}
    public synchronized View view(String symbol) {
        return view(symbol,market.latestQuote());
    }
    public synchronized View view(String symbol,Quote quote) {
        if(!symbol.equals("XAUUSDT"))return new View(symbol,"USD","WAITING_MT5",null,null,null,null,null,null,null,List.of(),"UNKNOWN","NOT_CONFIGURED");
        Ledger ledger=load();
        BigDecimal frozen=reserved(ledger),unrealized=ZERO,exitFees=ZERO;
        boolean priced=quote!=null&&quote.fresh(clock.instant());
        for(var order:ledger.orders)if(order.openQuantity.signum()>0&&priced) {
            BigDecimal exit=order.side.equals("BUY")?quote.bid():quote.ask();
            unrealized=unrealized.add(pnl(order,exit,order.openQuantity),MC);
            exitFees=exitFees.add(exit.multiply(order.openQuantity,MC).multiply(order.feeRate,MC),MC);
        }
        return new View(symbol,"USDT",priced?"VALUED":"STALE_OR_WAITING_QUOTE",money(ledger.balance),money(ledger.balance.subtract(frozen,MC)),
            priced?money(ledger.balance.add(unrealized,MC)):null,money(frozen),priced?money(unrealized):null,priced?money(exitFees):null,
            quote==null?null:quote.exchangeTime(),List.copyOf(ledger.orders),
            ledger.orders.stream().anyMatch(o->o.costIncomplete)?"INCOMPLETE_FUNDING_OR_DATA_GAP":settings.decimal("GOLD_TAKER_FEE_RATE",null)==null?"FEE_NOT_CONFIGURED":"CONFIGURED_FEE_ASSUMPTION",
            "SIMPLIFIED_ISOLATED_MARK_MARGIN");
    }
    public synchronized Order propose(String decisionId,String symbol,JsonNode plan) {
        return store.transaction(()->{
            Ledger ledger=load();
            if(!symbol.equals("XAUUSDT"))throw new IllegalArgumentException("PRODUCT_RULES_MISSING");
            var duplicate=ledger.orders.stream().filter(order->decisionId.equals(order.decisionId)).findFirst();
            if(duplicate.isPresent())return duplicate.get();
            String action=plan.path("action").asText("WAIT");
            if(action.equals("WAIT"))return null;
            if(action.equals("CANCEL")||action.equals("CLOSE")) {
                var order=find(ledger,plan.path("orderId").asText());
                if(action.equals("CANCEL")){if(!List.of("PENDING","PARTIALLY_FILLED").contains(order.status))fail("ORDER_NOT_CANCELABLE");order.reservation=ZERO;order.status="CANCELED";}
                else{if(order.openQuantity.signum()<=0)fail("POSITION_NOT_OPEN");order.exitRequested=true;order.exitReason="LLM_CLOSE";}
                order.version++;persist(ledger,order,action,market.snapshot(symbol).quote());return order;
            }
            if(!action.equals("CREATE"))throw new IllegalArgumentException("UNSUPPORTED_PLAN_ACTION");
            Order order=new Order();order.id=UUID.randomUUID().toString();order.decisionId=decisionId;order.symbol=symbol;
            order.createdAt=clock.instant();order.activatedAt=order.createdAt.plusMillis(settings.integer("GOLD_EXECUTION_DELAY_MS",250));
            order.side=plan.path("side").asText();order.type=plan.path("type").asText();
            order.riskAssessment=plan.path("riskAssessment").asText();order.invalidate=plan.path("invalidate").asText();
            try {
                if(!plan.path("riskAssessment").isTextual()||!plan.path("invalidate").isTextual()
                    ||order.riskAssessment.length()>1600||order.invalidate.length()>1600)fail("MISSING_OR_INVALID_RISK_ASSESSMENT");
                order.quantity=decimal(plan,"quantity");order.leverage=decimal(plan,"leverage");order.entryPrice=decimal(plan,"entryPrice");
                order.triggerPrice=decimal(plan,"triggerPrice");order.stopLoss=decimal(plan,"stopLoss");order.takeProfit=decimal(plan,"takeProfit");
                order.expiresAt=Instant.parse(plan.path("expiresAt").asText());order.holdUntil=Instant.parse(plan.path("holdUntil").asText());
                order.feeRate=settings.decimal("GOLD_TAKER_FEE_RATE",null);
                Snapshot snapshot=market.snapshot(symbol);validate(order,snapshot,ledger);
                order.reservation=order.entryPrice.multiply(order.quantity,MC).divide(order.leverage,MC)
                    .add(order.entryPrice.multiply(order.quantity,MC).multiply(order.feeRate,MC),MC);
                order.nextFundingTime=snapshot.quote().nextFundingTime();
                if(order.nextFundingTime==null||order.holdUntil.isAfter(order.nextFundingTime))order.costIncomplete=true;
            }catch(RuntimeException invalid){order.status="REJECTED";order.rejection=invalid.getMessage()!=null&&invalid.getMessage().matches("[A-Z0-9_]+")?invalid.getMessage():"INVALID_PLAN_FIELDS";order.reservation=ZERO;}
            ledger.orders.add(order);persist(ledger,order,"PLAN_RECORDED",null);return order;
        });
    }
    public synchronized Order cancel(String id) {
        return store.transaction(()->{Ledger ledger=load();Order order=find(ledger,id);
            if(!List.of("PENDING","PARTIALLY_FILLED").contains(order.status))throw new IllegalArgumentException("ORDER_NOT_CANCELABLE");
            order.status="CANCELED";order.reservation=ZERO;order.version++;persist(ledger,order,"CANCELED",null);return order;});
    }
    public synchronized void acceptQuote(Quote quote) {
        if(clock instanceof ExchangeClock exchange&&!exchange.verified())return;
        if(!quote.continuous())observedSequence=-1;
        if(quote.sequence()<=observedSequence||!quote.fresh(clock.instant()))return;
        boolean gap=!quote.continuous()||observedTime!=null&&Duration.between(observedTime,quote.exchangeTime()).toSeconds()>3;
        store.transaction(()->{
            Ledger ledger=load();if(ledger.orders.isEmpty())return null;
            BigDecimal bidAvailable=quote.bidSize(),askAvailable=quote.askSize();boolean changed=false;
            var rules=market.instrument();
            if(rules==null)return null;
            for(var order:ledger.orders) {
                if(order.openQuantity.signum()>0) {
                    if(gap&&!order.quoteGap){order.quoteGap=true;order.costIncomplete=true;changed=true;}
                    if(order.nextFundingTime!=null&&!quote.exchangeTime().isBefore(order.nextFundingTime)&&!order.costIncomplete){order.costIncomplete=true;changed=true;}
                    BigDecimal exit=order.side.equals("BUY")?quote.bid():quote.ask();
                    String reason=exitReason(order,quote,rules,ledger);
                    if(reason!=null) {
                        BigDecimal liquidity=order.side.equals("BUY")?bidAvailable:askAvailable;
                        BigDecimal amount=order.openQuantity.min(liquidity);
                        if(amount.signum()>0) {
                            close(ledger,order,exit,amount,reason,quote);
                            if(order.side.equals("BUY"))bidAvailable=bidAvailable.subtract(amount,MC);else askAvailable=askAvailable.subtract(amount,MC);
                            changed=true;
                        }
                    }
                }
            }
            for(var order:ledger.orders) {
                if(!List.of("PENDING","PARTIALLY_FILLED").contains(order.status))continue;
                if(!quote.receivedAt().isBefore(order.expiresAt)) {order.status="EXPIRED";order.reservation=ZERO;store.event(order.id,"ORDER_EXPIRED",order);changed=true;continue;}
                if(quote.receivedAt().isBefore(order.activatedAt)||quote.exchangeTime().isBefore(order.activatedAt)||!quote.continuous())continue;
                if(!rules.status().equals("Trading")||rules.receivedAt().isBefore(clock.instant().minusSeconds(7200)))continue;
                BigDecimal price=order.side.equals("BUY")?quote.ask():quote.bid();
                if(order.type.startsWith("STOP_")&&order.triggerPrice!=null&&!order.triggered) {
                    boolean triggered=order.side.equals("BUY")?quote.ask().compareTo(order.triggerPrice)>=0:quote.bid().compareTo(order.triggerPrice)<=0;
                    if(!triggered)continue;
                    order.triggered=true;store.event(order.id,"STOP_TRIGGERED",quote);changed=true;
                }
                boolean marketable=order.side.equals("BUY")?price.compareTo(order.entryPrice)<=0:price.compareTo(order.entryPrice)>=0;
                if(!marketable)continue;
                var liquidity=order.side.equals("BUY")?askAvailable:bidAvailable;
                BigDecimal amount=order.quantity.subtract(order.filled,MC).min(liquidity);
                if(amount.signum()<=0)continue;
                amount=amount.divide(rules.qtyStep(),0,RoundingMode.DOWN).multiply(rules.qtyStep());
                if(amount.signum()<=0)continue;
                BigDecimal margin=price.multiply(amount,MC).divide(order.leverage,MC),fee=price.multiply(amount,MC).multiply(order.feeRate,MC);
                BigDecimal otherFrozen=reserved(ledger).subtract(order.reservation,MC);
                if(margin.add(fee,MC).compareTo(ledger.balance.subtract(otherFrozen,MC))>0) {
                    order.status="REJECTED";order.rejection="ACCOUNT_CHANGED";order.reservation=ZERO;changed=true;continue;
                }
                var tier=tier(rules,price.multiply(sideQuantity(ledger,order.side).add(amount,MC),MC));
                if(tier==null||order.leverage.compareTo(tier.maxLeverage())>0||ledger.orders.stream().anyMatch(existing->order.side.equals(existing.side)&&existing.openQuantity.signum()>0&&existing.leverage.compareTo(tier.maxLeverage())>0)){
                    order.status="REJECTED";order.rejection="RISK_TIER_CHANGED";order.reservation=ZERO;store.event(order.id,"FILL_REJECTED_RISK_TIER",order);changed=true;continue;}
                order.averageEntry=order.averageEntry.multiply(order.openQuantity,MC).add(price.multiply(amount,MC),MC)
                    .divide(order.openQuantity.add(amount,MC),MC);
                order.openQuantity=order.openQuantity.add(amount,MC);order.filled=order.filled.add(amount,MC);order.margin=order.margin.add(margin,MC);
                order.entryFees=order.entryFees.add(fee,MC);ledger.balance=ledger.balance.subtract(fee,MC);
                order.netRealized=order.realizedGross.subtract(order.entryFees,MC).subtract(order.exitFees,MC);
                order.positionStatus="OPEN";order.status=order.filled.compareTo(order.quantity)==0?"FILLED":"PARTIALLY_FILLED";
                order.reservation=order.entryPrice.multiply(order.quantity.subtract(order.filled,MC),MC).divide(order.leverage,MC)
                    .add(order.entryPrice.multiply(order.quantity.subtract(order.filled,MC),MC).multiply(order.feeRate,MC),MC);
                order.lastSequence=quote.sequence();
                store.event(order.id,"ENTRY_FILL_L1_ASSUMPTION",Map.of("quote",quote,"quantity",money(amount),"price",money(price),"fee",money(fee)));
                if(order.side.equals("BUY"))askAvailable=askAvailable.subtract(amount,MC);else bidAvailable=bidAvailable.subtract(amount,MC);
                changed=true;
            }
            if(changed){store.save("ledger","XAUUSDT",ledger);events.changed();}return null;
        });
        observedSequence=quote.sequence();observedTime=quote.exchangeTime();
    }
    private void validate(Order order,Snapshot snapshot,Ledger ledger) {
        if(!order.symbol.equals("XAUUSDT")||snapshot.rules()==null)fail("PRODUCT_RULES_MISSING");
        var rules=snapshot.rules();var quote=snapshot.quote();
        if(quote==null||!quote.fresh(clock.instant())||!quote.continuous())fail("QUOTE_NOT_EXECUTABLE");
        if(!rules.status().equals("Trading")||rules.receivedAt().isBefore(clock.instant().minusSeconds(7200)))fail("RULES_NOT_CURRENT");
        if(!List.of("BUY","SELL").contains(order.side)||!List.of("MARKET","LIMIT","STOP_MARKET","STOP_LIMIT").contains(order.type))fail("UNSUPPORTED_ORDER");
        if(order.quantity==null||order.entryPrice==null||order.leverage==null||order.feeRate==null)fail("COST_OR_PLAN_UNKNOWN");
        var maxQuantity=List.of("MARKET","STOP_MARKET").contains(order.type)?rules.maxQty():rules.maxLimitQty();
        if(maxQuantity==null||order.quantity.compareTo(rules.minQty())<0||order.quantity.compareTo(maxQuantity)>0||!step(order.quantity,rules.qtyStep()))fail("INVALID_QUANTITY");
        if(order.entryPrice.signum()<=0||!step(order.entryPrice,rules.tickSize()))fail("INVALID_ENTRY_BOUND");
        if(order.leverage.compareTo(rules.minLeverage())<0||order.leverage.compareTo(rules.maxLeverage())>0||!step(order.leverage.subtract(rules.minLeverage()),rules.leverageStep()))fail("INVALID_LEVERAGE");
        if(order.feeRate.signum()<0||order.feeRate.compareTo(ONE)>=0)fail("INVALID_FEE_RATE");
        var notional=order.quantity.multiply(order.entryPrice,MC);var pending=ledger.orders.stream()
            .filter(existing->order.side.equals(existing.side)&&List.of("PENDING","PARTIALLY_FILLED").contains(existing.status))
            .map(existing->existing.quantity.subtract(existing.filled,MC)).reduce(ZERO,(a,b)->a.add(b,MC));
        var tier=tier(rules,order.entryPrice.multiply(sideQuantity(ledger,order.side).add(pending,MC).add(order.quantity,MC),MC));
        if(notional.compareTo(rules.minNotional())<0||tier==null||order.leverage.compareTo(tier.maxLeverage())>0
            ||ledger.orders.stream().anyMatch(existing->order.side.equals(existing.side)&&existing.openQuantity.signum()>0&&existing.leverage.compareTo(tier.maxLeverage())>0))fail("INVALID_RISK_TIER");
        if(!order.expiresAt.isAfter(order.activatedAt)||!order.holdUntil.isAfter(order.activatedAt)||order.holdUntil.isBefore(order.expiresAt)||order.riskAssessment.isBlank()||order.invalidate.isBlank())fail("MISSING_LIFETIME_OR_RISK");
        for(var exit:Arrays.asList(order.stopLoss,order.takeProfit,order.triggerPrice))if(exit!=null&&(exit.signum()<=0||!step(exit,rules.tickSize())))fail("INVALID_EXIT_PRICE");
        if(order.type.startsWith("STOP_")&&order.triggerPrice==null)fail("MISSING_TRIGGER");
        if(order.stopLoss!=null&&(order.side.equals("BUY")?order.stopLoss.compareTo(order.entryPrice)>=0:order.stopLoss.compareTo(order.entryPrice)<=0))fail("INVALID_STOP_DIRECTION");
        if(order.takeProfit!=null&&(order.side.equals("BUY")?order.takeProfit.compareTo(order.entryPrice)<=0:order.takeProfit.compareTo(order.entryPrice)>=0))fail("INVALID_TARGET_DIRECTION");
        BigDecimal reservation=notional.divide(order.leverage,MC).add(notional.multiply(order.feeRate,MC),MC);
        if(reservation.compareTo(ledger.balance.subtract(reserved(ledger),MC))>0)fail("INSUFFICIENT_VIRTUAL_CASH");
    }
    private String exitReason(Order order,Quote quote,Instrument rules,Ledger ledger) {
        if(order.exitRequested)return order.exitReason==null?"LLM_CLOSE":order.exitReason;
        if(!quote.receivedAt().isBefore(order.holdUntil))return "HOLD_EXPIRED";
        BigDecimal price=order.side.equals("BUY")?quote.bid():quote.ask();
        if(order.stopLoss!=null&&(order.side.equals("BUY")?price.compareTo(order.stopLoss)<=0:price.compareTo(order.stopLoss)>=0))return "STOP_LOSS";
        if(order.takeProfit!=null&&(order.side.equals("BUY")?price.compareTo(order.takeProfit)>=0:price.compareTo(order.takeProfit)<=0))return "TAKE_PROFIT";
        if(rules!=null&&quote.mark()!=null&&quote.mark().signum()>0&&quote.markTime()!=null&&quote.markTime().isAfter(clock.instant().minusSeconds(3))&&!quote.markTime().isAfter(clock.instant().plusSeconds(2))) {
            var quantity=sideQuantity(ledger,order.side);var notional=quote.mark().multiply(quantity,MC);var tier=tier(rules,notional);
            if(tier!=null&&tier.maintenanceRate()!=null&&tier.deduction()!=null) {
                var maintenance=notional.multiply(tier.maintenanceRate(),MC).subtract(tier.deduction(),MC).max(ZERO)
                    .multiply(order.openQuantity,MC).divide(quantity,MC)
                    .add(quote.mark().multiply(order.openQuantity,MC).multiply(order.feeRate,MC),MC);
                if(order.margin.add(pnl(order,quote.mark(),order.openQuantity),MC).compareTo(maintenance)<=0)return "SIMULATED_LIQUIDATION";
            }
        }
        return null;
    }
    private void close(Ledger ledger,Order order,BigDecimal price,BigDecimal amount,String reason,Quote quote) {
        BigDecimal gross=pnl(order,price,amount),fee=price.multiply(amount,MC).multiply(order.feeRate,MC);
        BigDecimal released=order.margin.multiply(amount,MC).divide(order.openQuantity,MC);
        ledger.balance=ledger.balance.add(gross,MC).subtract(fee,MC);order.realizedGross=order.realizedGross.add(gross,MC);
        order.exitFees=order.exitFees.add(fee,MC);order.openQuantity=order.openQuantity.subtract(amount,MC);order.margin=order.margin.subtract(released,MC);
        order.netRealized=order.realizedGross.subtract(order.entryFees,MC).subtract(order.exitFees,MC);
        order.exitReason=reason;order.exitRequested=order.openQuantity.signum()>0;order.reservation=ZERO;
        if(order.status.equals("PARTIALLY_FILLED"))order.status="CANCELED";
        order.positionStatus=order.openQuantity.signum()==0?(reason.equals("SIMULATED_LIQUIDATION")?"LIQUIDATED":"CLOSED"):"PARTIALLY_CLOSED";
        if(order.openQuantity.signum()==0)order.closedAt=quote.receivedAt();
        store.event(order.id,"EXIT_FILL_L1_ASSUMPTION",Map.of("quote",quote,"quantity",money(amount),"price",money(price),"gross",money(gross),"fee",money(fee),"reason",reason));
    }
    private void persist(Ledger ledger,Order order,String event,Quote quote){store.save("ledger","XAUUSDT",ledger);store.event(order.id,event,order);if(quote!=null)store.event(order.id,"DECISION_QUOTE",quote);events.changed();}
    private Ledger load(){return store.find("ledger","XAUUSDT",Ledger.class).orElseThrow();}
    private Order find(Ledger ledger,String id){return ledger.orders.stream().filter(order->order.id.equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("ORDER_NOT_FOUND"));}
    private BigDecimal reserved(Ledger ledger){return ledger.orders.stream().map(order->order.margin.add(order.reservation,MC)).reduce(ZERO,(a,b)->a.add(b,MC));}
    private BigDecimal sideQuantity(Ledger ledger,String side){return ledger.orders.stream().filter(order->side.equals(order.side)).map(order->order.openQuantity).reduce(ZERO,(a,b)->a.add(b,MC));}
    private BigDecimal pnl(Order order,BigDecimal exit,BigDecimal quantity){var change=exit.subtract(order.averageEntry,MC);return (order.side.equals("BUY")?change:change.negate()).multiply(quantity,MC);}
    private RiskTier tier(Instrument rules,BigDecimal notional){return rules.tiers().stream().filter(tier->tier.limit()!=null&&tier.maxLeverage()!=null&&notional.compareTo(tier.limit())<=0).findFirst().orElse(null);}
    private boolean step(BigDecimal value,BigDecimal step){return step!=null&&step.signum()>0&&value.remainder(step).signum()==0;}
    private BigDecimal decimal(JsonNode node,String field){var value=node.path(field);return value.isMissingNode()||value.isNull()||value.asText().isBlank()?null:new BigDecimal(value.asText());}
    private void fail(String code){throw new IllegalArgumentException(code);}
    public static String money(BigDecimal value){return value==null?null:value.stripTrailingZeros().toPlainString();}
}
