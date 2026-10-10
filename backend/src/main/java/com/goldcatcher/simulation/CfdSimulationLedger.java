package com.goldcatcher.simulation;

import static java.math.BigDecimal.*;
import static com.goldcatcher.market.IndicatorEngine.MC;
import static com.goldcatcher.simulation.SimulationLedger.money;
import com.goldcatcher.*;
import com.goldcatcher.market.*;
import com.goldcatcher.market.MarketTypes.Quote;
import com.goldcatcher.persistence.DocumentStore;
import com.goldcatcher.simulation.SimulationLedger.*;
import jakarta.annotation.PostConstruct;
import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/** Separate CFD virtual account. Fills are quote scenarios, never broker executions or perpetual fills. */
@Service
public class CfdSimulationLedger {
    private final DocumentStore store;private final GateCfdFeed gate;private final RuntimeSettings settings;
    private final EventHub events;private final Clock clock;private volatile long lastSequence=-1;
    public CfdSimulationLedger(DocumentStore store,GateCfdFeed gate,RuntimeSettings settings,EventHub events,Clock clock) {
        this.store=store;this.gate=gate;this.settings=settings;this.events=events;this.clock=clock;gate.onQuote(this::acceptQuote);
    }
    @PostConstruct void initialize(){store.transaction(()->{
        if(store.find("ledger",GateCfdFeed.PRODUCT,Ledger.class).isEmpty()) {
            var balance=settings.decimal("GOLD_CFD_INITIAL_BALANCE","1000");if(balance==null||balance.signum()<=0)fail("CFD_INITIAL_BALANCE_INVALID");
            store.save("ledger",GateCfdFeed.PRODUCT,new Ledger(balance));store.event(GateCfdFeed.PRODUCT,"ACCOUNT_INITIALIZED",Map.of("balance",money(balance),"currency","USD","mode","CFD_QUOTE_SCENARIO"));
        }return null;
    });}
    public synchronized View view(Quote quote) {
        var ledger=load();boolean priced=gate.snapshot().state().equals("LIVE")&&quote!=null&&quote.priceFresh(clock.instant());
        BigDecimal unrealized=ZERO,fees=ZERO;
        for(var order:ledger.orders)if(order.openQuantity.signum()>0&&priced) {
            unrealized=unrealized.add(pnl(order,exit(order,quote),order.openQuantity),MC);
            fees=fees.add(order.openQuantity.multiply(order.commissionPerLot,MC),MC);
        }
        return new View(GateCfdFeed.PRODUCT,"USD",priced?"VALUED":gate.snapshot().state().equals("MARKET_CLOSED")?"MARKET_CLOSED":"STALE_OR_WAITING_QUOTE",
            money(ledger.balance),money(ledger.balance.subtract(reserved(ledger),MC)),priced?money(ledger.balance.add(unrealized,MC)):null,
            money(reserved(ledger)),priced?money(unrealized):null,priced?money(fees):null,quote==null?null:quote.receivedAt(),List.copyOf(ledger.orders),
            "CFD_QUOTE_SCENARIO; COMMISSION_EACH_FILL_SIDE; SWAP_"+settings.text("GOLD_CFD_SWAP_MODE","UNVERIFIED"),"ACCOUNT_MARGIN_LEVEL_50_PERCENT; LARGEST_LOSS_FIRST; GROSS_MARGIN_ASSUMPTION");
    }
    public synchronized Order propose(String decisionId,JsonNode plan) {
        return store.transaction(()->{
            if(plan.path("action").asText().equals("WAIT")){store.event(decisionId,"DECISION_WAIT",Map.of("symbol",GateCfdFeed.PRODUCT,"plan",plan));return null;}
            var ledger=load();var duplicate=ledger.orders.stream().filter(o->decisionId.equals(o.decisionId)).findFirst();if(duplicate.isPresent())return duplicate.get();
            String action=plan.path("action").asText();
            if(List.of("CANCEL","CLOSE","REDUCE","MODIFY").contains(action))return change(ledger,find(ledger,plan.path("orderId").asText()),action,plan);
            if(!action.equals("CREATE"))fail("UNSUPPORTED_PLAN_ACTION");
            var order=new Order();order.id=UUID.randomUUID().toString();order.decisionId=decisionId;order.symbol=GateCfdFeed.PRODUCT;
            order.createdAt=clock.instant();order.activatedAt=order.createdAt.plusMillis(settings.integer("GOLD_EXECUTION_DELAY_MS",250));
            order.side=plan.path("side").asText();order.type=plan.path("type").asText();order.quantityUnit="LOTS";order.evidenceLevel="QUOTE_OBSERVATION";
            order.ruleProvenance="GATE_CFD_SPEC_AND_COMMISSION; OBSERVED_QUOTE_SCENARIO";order.costIncomplete=true;
            try {
                order.riskAssessment=plan.path("riskAssessment").asText();order.invalidate=plan.path("invalidate").asText();
                order.quantity=decimal(plan,"quantity");order.leverage=decimal(plan,"leverage");order.entryPrice=decimal(plan,"entryPrice");
                order.triggerPrice=decimal(plan,"triggerPrice");order.stopLoss=decimal(plan,"stopLoss");order.takeProfit=decimal(plan,"takeProfit");
                order.trailingDistance=decimal(plan,"trailingDistance");order.trailingActivation=decimal(plan,"trailingActivation");
                order.expiresAt=Instant.parse(plan.path("expiresAt").asText());order.holdUntil=Instant.parse(plan.path("holdUntil").asText());
                order.frozenRules=gate.rules().deepCopy();var rules=currentRules();
                order.contractVolume=rules.contractVolume();order.commissionPerLot=rules.commissionPerLot();order.feeRate=ZERO;
                order.swapMode=settings.text("GOLD_CFD_SWAP_MODE","UNVERIFIED");order.swapTimezone=rules.tradeTimezone();
                validate(order,ledger,rules);order.reservation=margin(order,order.entryPrice,order.quantity).add(rules.fee(order.quantity),MC);
            }catch(RuntimeException invalid){order.status="REJECTED";order.rejection=safe(invalid);order.reservation=ZERO;}
            ledger.orders.add(order);persist(ledger,order,"PLAN_RECORDED");return order;
        });
    }
    public synchronized Order cancel(String id){return store.transaction(()->{var ledger=load();return change(ledger,find(ledger,id),"CANCEL",null);});}
    public synchronized Order command(String id,String action,JsonNode plan){return store.transaction(()->{var ledger=load();return change(ledger,find(ledger,id),action,plan);});}
    private Order change(Ledger ledger,Order order,String action,JsonNode plan) {
        if(action.equals("CANCEL")) {
            if(!List.of("PENDING","PARTIALLY_FILLED").contains(order.status))fail("ORDER_NOT_CANCELABLE");order.status="CANCELED";order.reservation=ZERO;
        } else if(action.equals("CLOSE")||action.equals("REDUCE")) {
            if(order.openQuantity.signum()<=0)fail("POSITION_NOT_OPEN");
            var amount=action.equals("REDUCE")?decimal(plan,"quantity"):order.openQuantity;
            if(amount==null||amount.signum()<=0||amount.compareTo(order.openQuantity)>0||!step(amount,currentRules().lotStep()))fail("INVALID_REDUCE_QUANTITY");
            order.exitQuantity=amount;order.exitRequested=true;order.exitReason=action.equals("REDUCE")?"LLM_REDUCE":"REQUESTED_CLOSE";
            order.activatedAt=clock.instant();if(order.status.equals("PARTIALLY_FILLED")){order.status="CANCELED";order.reservation=ZERO;}
        } else if(action.equals("MODIFY")) {
            if(order.closedAt!=null||order.status.equals("REJECTED")||order.openQuantity.signum()==0&&!List.of("PENDING","PARTIALLY_FILLED").contains(order.status))fail("ORDER_NOT_MODIFIABLE");
            var rules=currentRules();
            for(String field:List.of("stopLoss","takeProfit","trailingDistance","trailingActivation"))if(plan.has(field)) {
                var value=decimal(plan,field);if(value!=null&&(value.signum()<=0||!step(value,rules.priceStep())))fail("INVALID_EXIT_PRICE");
            }
            if(plan.has("stopLoss"))order.stopLoss=decimal(plan,"stopLoss");if(plan.has("takeProfit"))order.takeProfit=decimal(plan,"takeProfit");
            if(plan.has("trailingDistance"))order.trailingDistance=decimal(plan,"trailingDistance");if(plan.has("trailingActivation"))order.trailingActivation=decimal(plan,"trailingActivation");
            if(plan.hasNonNull("holdUntil")){var at=Instant.parse(plan.path("holdUntil").asText());if(!at.isAfter(clock.instant()))fail("INVALID_HOLD_UNTIL");order.holdUntil=at;}
            order.activatedAt=clock.instant().plusMillis(settings.integer("GOLD_EXECUTION_DELAY_MS",250));
            order.trailingAnchor=null;
        } else fail("UNSUPPORTED_PLAN_ACTION");
        order.version++;persist(ledger,order,action);return order;
    }
    public synchronized void acceptQuote(Quote quote) {
        if(!quote.symbol().equals(GateCfdFeed.PRODUCT)||quote.sequence()<=lastSequence)return;
        if(clock instanceof ExchangeClock exchange&&!exchange.verified())return;
        var snapshot=gate.snapshot();if(!snapshot.state().equals("LIVE")||!quote.priceFresh(clock.instant())){expire();return;}
        CfdRules rules;try{rules=currentRules();}catch(RuntimeException unavailable){return;}
        store.transaction(()->{
            var ledger=load();if(ledger.orders.isEmpty())return null;
            if(ledger.lastMarketAt!=null&&(quote.receivedAt().isBefore(ledger.lastMarketAt)||quote.receivedAt().equals(ledger.lastMarketAt)&&quote.sequence()==ledger.lastMarketSequence))return null;
            settleSwap(ledger,quote,rules);stopOut(ledger,quote);
            for(var order:ledger.orders)if(order.openQuantity.signum()>0) {
                if(!quote.receivedAt().isBefore(order.activatedAt))updateTrailing(order,exit(order,quote));String reason=reason(order,quote);
                if(reason!=null)close(ledger,order,exit(order,quote),order.exitRequested&&order.exitQuantity!=null?order.openQuantity.min(order.exitQuantity):order.openQuantity,reason,quote);
            }
            for(var order:ledger.orders) {
                if(!List.of("PENDING","PARTIALLY_FILLED").contains(order.status))continue;
                if(!quote.receivedAt().isBefore(order.expiresAt)){order.status="EXPIRED";order.reservation=ZERO;store.event(order.id,"ORDER_EXPIRED",order);continue;}
                if(quote.receivedAt().isBefore(order.activatedAt)||!rules.permits(order.side))continue;
                BigDecimal price=order.side.equals("BUY")?quote.ask():quote.bid();
                if(order.type.startsWith("STOP_")&&!order.triggered) {
                    if(order.side.equals("BUY")?price.compareTo(order.triggerPrice)<0:price.compareTo(order.triggerPrice)>0)continue;
                    order.triggered=true;store.event(order.id,"STOP_OBSERVED",quote);
                }
                if(order.side.equals("BUY")?price.compareTo(order.entryPrice)>0:price.compareTo(order.entryPrice)<0)continue;
                BigDecimal amount=order.quantity.subtract(order.filled,MC),margin=margin(order,price,amount),fee=rules.fee(amount);
                var free=ledger.balance.add(unrealized(ledger,quote),MC).subtract(reserved(ledger).subtract(order.reservation,MC),MC);
                if(margin.add(fee,MC).compareTo(free)>0){order.status="REJECTED";order.rejection="ACCOUNT_CHANGED";order.reservation=ZERO;continue;}
                if(rules.leverages().stream().noneMatch(l->l.compareTo(order.leverage)==0)||amount.compareTo(rules.minimumLots())<0||amount.compareTo(rules.maximumLots())>0||!step(amount,rules.lotStep())||!step(order.entryPrice,rules.priceStep())){
                    order.status="REJECTED";order.rejection="CFD_RULE_CHANGED";order.reservation=ZERO;store.event(order.id,"FILL_REJECTED_RULE_CHANGE",order);continue;}
                order.averageEntry=price;order.filled=order.quantity;order.openQuantity=amount;order.margin=margin;order.reservation=ZERO;
                order.entryFees=order.entryFees.add(fee,MC);ledger.balance=ledger.balance.subtract(fee,MC);order.netRealized=net(order);
                order.status="FILLED";order.positionStatus="OPEN";order.firstFilledAt=quote.receivedAt();order.lastSwapAt=quote.receivedAt();order.lastSequence=quote.sequence();
                store.event(order.id,"ENTRY_QUOTE_SCENARIO",Map.of("quote",quote,"lots",money(amount),"price",money(price),"fee",money(fee),"evidenceLevel","QUOTE_OBSERVATION","visibleSize","NOT_PROVIDED"));
            }
            ledger.lastMarketAt=quote.receivedAt();ledger.lastMarketSequence=quote.sequence();store.save("ledger",GateCfdFeed.PRODUCT,ledger);events.changed();return null;
        });lastSequence=quote.sequence();
    }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=1000) public synchronized void expire(){store.transaction(()->{var ledger=load();boolean changed=false;
        for(var order:ledger.orders)if(List.of("PENDING","PARTIALLY_FILLED").contains(order.status)&&order.expiresAt!=null&&!order.expiresAt.isAfter(clock.instant())) {
            order.status="EXPIRED";order.reservation=ZERO;store.event(order.id,"ORDER_EXPIRED",order);changed=true;
        }if(changed){store.save("ledger",GateCfdFeed.PRODUCT,ledger);events.changed();}return null;
    });}
    private void validate(Order order,Ledger ledger,CfdRules rules) {
        if(!gate.snapshot().state().equals("LIVE")||!rules.permits(order.side))fail("CFD_MARKET_NOT_EXECUTABLE");
        if(!List.of("BUY","SELL").contains(order.side)||!List.of("MARKET","LIMIT","STOP_MARKET","STOP_LIMIT").contains(order.type))fail("UNSUPPORTED_ORDER");
        if(order.quantity==null||order.quantity.compareTo(rules.minimumLots())<0||order.quantity.compareTo(rules.maximumLots())>0||!step(order.quantity,rules.lotStep()))fail("INVALID_CFD_LOTS");
        if(order.leverage==null||rules.leverages().stream().noneMatch(l->l.compareTo(order.leverage)==0))fail("INVALID_CFD_LEVERAGE");
        if(order.entryPrice==null||order.entryPrice.signum()<=0||!step(order.entryPrice,rules.priceStep()))fail("INVALID_ENTRY_BOUND");
        if(order.riskAssessment.isBlank()||order.invalidate.isBlank()||order.riskAssessment.length()>1600||order.invalidate.length()>1600)fail("MISSING_OR_INVALID_RISK_ASSESSMENT");
        if(!order.expiresAt.isAfter(order.activatedAt)||!order.holdUntil.isAfter(order.expiresAt))fail("MISSING_LIFETIME_OR_RISK");
        for(var value:Arrays.asList(order.stopLoss,order.takeProfit,order.triggerPrice,order.trailingDistance,order.trailingActivation))if(value!=null&&(value.signum()<=0||!step(value,rules.priceStep())))fail("INVALID_EXIT_PRICE");
        if(order.stopLoss!=null&&(order.side.equals("BUY")?order.stopLoss.compareTo(order.entryPrice)>=0:order.stopLoss.compareTo(order.entryPrice)<=0))fail("INVALID_STOP_DIRECTION");
        if(order.takeProfit!=null&&(order.side.equals("BUY")?order.takeProfit.compareTo(order.entryPrice)<=0:order.takeProfit.compareTo(order.entryPrice)>=0))fail("INVALID_TARGET_DIRECTION");
        if(order.type.startsWith("STOP_")&&order.triggerPrice==null)fail("MISSING_TRIGGER");
        if(margin(order,order.entryPrice,order.quantity).add(rules.fee(order.quantity),MC).compareTo(ledger.balance.subtract(reserved(ledger),MC))>0)fail("INSUFFICIENT_VIRTUAL_CASH");
    }
    private void stopOut(Ledger ledger,Quote quote) {
        if(!liquidating(ledger,quote))return;
        for(var order:ledger.orders)if(List.of("PENDING","PARTIALLY_FILLED").contains(order.status)) {order.status="CANCELED";order.reservation=ZERO;store.event(order.id,"STOP_OUT_CANCEL",order);}
        var positions=ledger.orders.stream().filter(o->o.openQuantity.signum()>0).sorted(Comparator.comparing(o->pnl(o,exit(o,quote),o.openQuantity))).toList();
        for(var order:positions) {if(!liquidating(ledger,quote))break;close(ledger,order,exit(order,quote),order.openQuantity,"SIMULATED_LIQUIDATION",quote);}
    }
    private boolean liquidating(Ledger ledger,Quote quote) {
        BigDecimal used=ledger.orders.stream().map(o->o.margin).reduce(ZERO,BigDecimal::add);
        return used.signum()>0&&ledger.balance.add(unrealized(ledger,quote),MC).compareTo(used.multiply(new BigDecimal("0.5"),MC))<=0;
    }
    private void close(Ledger ledger,Order order,BigDecimal price,BigDecimal lots,String reason,Quote quote) {
        var gross=pnl(order,price,lots);var fee=lots.multiply(order.commissionPerLot,MC);var released=order.margin.multiply(lots,MC).divide(order.openQuantity,MC);
        ledger.balance=ledger.balance.add(gross,MC).subtract(fee,MC);order.realizedGross=order.realizedGross.add(gross,MC);order.exitFees=order.exitFees.add(fee,MC);
        order.openQuantity=order.openQuantity.subtract(lots,MC);order.margin=order.margin.subtract(released,MC);order.netRealized=net(order);order.exitRequested=false;order.exitQuantity=null;order.exitReason=reason;
        order.positionStatus=order.openQuantity.signum()==0?(reason.equals("SIMULATED_LIQUIDATION")?"LIQUIDATED":"CLOSED"):"PARTIALLY_CLOSED";
        if(order.openQuantity.signum()==0)order.closedAt=quote.receivedAt();
        store.event(order.id,"EXIT_QUOTE_SCENARIO",Map.of("quote",quote,"lots",money(lots),"price",money(price),"gross",money(gross),"fee",money(fee),"reason",reason,"evidenceLevel","QUOTE_OBSERVATION"));
    }
    private String reason(Order order,Quote quote) {
        if(quote.receivedAt().isBefore(order.activatedAt))return null;
        if(order.exitRequested)return order.exitReason;
        if(!quote.receivedAt().isBefore(order.holdUntil))return "HOLD_EXPIRED";
        var price=exit(order,quote);
        if(order.stopLoss!=null&&(order.side.equals("BUY")?price.compareTo(order.stopLoss)<=0:price.compareTo(order.stopLoss)>=0))return "STOP_LOSS";
        if(order.trailingDistance!=null&&order.trailingAnchor!=null&&(order.side.equals("BUY")?price.compareTo(order.trailingAnchor.subtract(order.trailingDistance,MC))<=0:price.compareTo(order.trailingAnchor.add(order.trailingDistance,MC))>=0))return "TRAILING_STOP";
        if(order.takeProfit!=null&&(order.side.equals("BUY")?price.compareTo(order.takeProfit)>=0:price.compareTo(order.takeProfit)<=0))return "TAKE_PROFIT";return null;
    }
    private void updateTrailing(Order order,BigDecimal price) {
        if(order.trailingDistance==null)return;
        if(order.trailingActivation!=null&&(order.side.equals("BUY")?price.compareTo(order.trailingActivation)<0:price.compareTo(order.trailingActivation)>0)&&order.trailingAnchor==null)return;
        if(order.trailingAnchor==null||order.side.equals("BUY")&&price.compareTo(order.trailingAnchor)>0||order.side.equals("SELL")&&price.compareTo(order.trailingAnchor)<0) {
            order.trailingAnchor=price;store.event(order.id,"TRAILING_ANCHOR",Map.of("anchor",money(price),"distance",money(order.trailingDistance)));
        }
    }
    private void settleSwap(Ledger ledger,Quote quote,CfdRules rules) {
        String mode=settings.text("GOLD_CFD_SWAP_MODE","UNVERIFIED"),time=settings.text("GOLD_CFD_SWAP_SETTLEMENT_TIME","");
        if(!List.of("POINTS","SETTLEMENT_CURRENCY","ANNUAL_PERCENT").contains(mode)||time.isBlank())return;
        ZoneOffset offset;LocalTime settlement;try{offset=rules.offset();settlement=LocalTime.parse(time);}catch(RuntimeException invalid){return;}
        for(var order:ledger.orders)if(order.openQuantity.signum()>0&&order.lastSwapAt!=null) {
            var cursor=order.lastSwapAt.atOffset(offset).toLocalDate();var today=quote.receivedAt().atOffset(offset).toLocalDate();int checks=0;
            while(!cursor.isAfter(today)&&++checks<=100) {
                var at=cursor.atTime(settlement).toInstant(offset);
                if(at.isAfter(order.lastSwapAt)&&!at.isAfter(quote.receivedAt())) {
                    BigDecimal rate=order.side.equals("BUY")?rules.buySwap():rules.sellSwap();if(rate==null)break;
                    int day=cursor.getDayOfWeek().getValue()%7,multiplier=day==rules.tripleDay()?3:1;
                    if(day!=0&&day!=6) {
                        var signed=swap(mode,order.openQuantity,order.contractVolume,rules.priceStep(),rate,exit(order,quote),rules.exchangeRate(),multiplier);
                        var charge=signed.negate();order.swapFees=order.swapFees.add(charge,MC);ledger.balance=ledger.balance.subtract(charge,MC);order.netRealized=net(order);
                        store.event(order.id,"SWAP_SCENARIO",Map.of("scheduledAt",at,"mode",mode,"charge",money(charge),"multiplier",multiplier,"rate",money(rate),"source","CURRENT_RULE_OBSERVATION; SETTLEMENT_AND_TYPE_MAPPING_CONFIGURED_ASSUMPTION"));
                    }order.lastSwapAt=at;
                }cursor=cursor.plusDays(1);
            }
        }
    }
    static BigDecimal swap(String mode,BigDecimal lots,BigDecimal contract,BigDecimal tick,BigDecimal rate,BigDecimal price,BigDecimal fx,int multiplier) {
        BigDecimal value=switch(mode) {
            case "POINTS" -> lots.multiply(contract,MC).multiply(tick,MC).multiply(rate,MC);
            case "SETTLEMENT_CURRENCY" -> lots.multiply(rate,MC);
            case "ANNUAL_PERCENT" -> lots.multiply(contract,MC).multiply(price,MC).multiply(rate,MC).divide(new BigDecimal("36000"),MC);
            default -> throw new IllegalArgumentException("CFD_SWAP_MODE_UNKNOWN");
        };return value.multiply(fx,MC).multiply(BigDecimal.valueOf(multiplier),MC);
    }
    private CfdRules currentRules(){if(gate.rulesReceivedAt()==null||gate.rulesReceivedAt().isBefore(clock.instant().minusSeconds(7200)))fail("CFD_RULES_NOT_CURRENT");return CfdRules.parse(gate.rules());}
    private Ledger load(){return store.find("ledger",GateCfdFeed.PRODUCT,Ledger.class).orElseThrow();}
    private Order find(Ledger ledger,String id){return ledger.orders.stream().filter(o->o.id.equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("ORDER_NOT_FOUND"));}
    private void persist(Ledger ledger,Order order,String action){store.save("ledger",GateCfdFeed.PRODUCT,ledger);store.event(order.id,action,order);events.changed();}
    private BigDecimal reserved(Ledger ledger){return ledger.orders.stream().map(o->o.margin.add(o.reservation,MC)).reduce(ZERO,BigDecimal::add);}
    private BigDecimal margin(Order order,BigDecimal price,BigDecimal lots){return lots.multiply(order.contractVolume,MC).multiply(price,MC).divide(order.leverage,MC);}
    private BigDecimal pnl(Order order,BigDecimal price,BigDecimal lots){var change=price.subtract(order.averageEntry,MC);return (order.side.equals("BUY")?change:change.negate()).multiply(lots,MC).multiply(order.contractVolume,MC);}
    private BigDecimal unrealized(Ledger ledger,Quote quote){return ledger.orders.stream().filter(o->o.openQuantity.signum()>0).map(o->pnl(o,exit(o,quote),o.openQuantity)).reduce(ZERO,BigDecimal::add);}
    private BigDecimal exit(Order order,Quote quote){return order.side.equals("BUY")?quote.bid():quote.ask();}
    private BigDecimal net(Order order){return order.realizedGross.subtract(order.entryFees,MC).subtract(order.exitFees,MC).subtract(order.swapFees,MC);}
    private static BigDecimal decimal(JsonNode node,String name){var value=node.path(name);if(value.isMissingNode()||value.isNull()||value.asText().isBlank())return null;if(!value.asText().matches("-?[0-9]{1,18}(?:\\.[0-9]{1,18})?"))throw new IllegalArgumentException("INVALID_NUMERIC_FIELD");return new BigDecimal(value.asText());}
    private static boolean step(BigDecimal value,BigDecimal step){return step!=null&&step.signum()>0&&value.remainder(step).signum()==0;}
    private static String safe(RuntimeException failure){return failure.getMessage()!=null&&failure.getMessage().matches("[A-Z0-9_]+")?failure.getMessage():"INVALID_PLAN_FIELDS";}
    private static void fail(String code){throw new IllegalArgumentException(code);}
}
