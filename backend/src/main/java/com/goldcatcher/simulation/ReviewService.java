package com.goldcatcher.simulation;

import static java.math.BigDecimal.ZERO;
import static com.goldcatcher.market.IndicatorEngine.MC;
import static com.goldcatcher.simulation.SimulationLedger.money;
import com.goldcatcher.persistence.DocumentStore;
import com.goldcatcher.persistence.DocumentStore.AccountSample;
import com.goldcatcher.research.ResearchService;
import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class ReviewService {
    private final SimulationLedger ledger;private final DocumentStore store;private final ResearchService research;private final Clock clock;
    private final Map<String,Cached> curves=new java.util.concurrent.ConcurrentHashMap<>();
    private record Cached(Instant at,Map<String,Object> statistics) {}
    public ReviewService(SimulationLedger ledger,DocumentStore store,ResearchService research,Clock clock){this.ledger=ledger;this.store=store;this.research=research;this.clock=clock;}
    @Scheduled(fixedDelay=60000,initialDelay=5000) public void sample(){
        for(String symbol:List.of("XAU_USDT","GATE:XAUUSD")){var account=ledger.view(symbol);if(account.balance()!=null)store.accountSample(new AccountSample(symbol,clock.instant(),account.balance(),account.equity(),account.unrealized(),account.state()));}
        curves.clear();store.expireAccountSamples(clock.instant().minus(Duration.ofDays(7)));
    }
    public Map<String,Object> view(String symbol){
        var account=ledger.view(symbol);var closed=account.orders().stream().filter(o->o.closedAt!=null).toList();var complete=closed.stream().filter(o->!o.costIncomplete&&!o.evidenceLevel.equals("QUOTE_OBSERVATION")).toList();
        var result=new LinkedHashMap<String,Object>();result.put("closedOrders",closed.size());result.put("fullyCostedOrders",complete.size());
        result.put("gross",money(closed.stream().map(o->o.realizedGross).reduce(ZERO,BigDecimal::add)));
        var entry=closed.stream().map(o->o.entryFees).reduce(ZERO,BigDecimal::add);var exit=closed.stream().map(o->o.exitFees).reduce(ZERO,BigDecimal::add);
        var funding=closed.stream().map(o->o.fundingFees).reduce(ZERO,BigDecimal::add);var swaps=closed.stream().map(o->o.swapFees).reduce(ZERO,BigDecimal::add);
        result.put("fees",money(entry.add(exit).add(funding).add(swaps)));result.put("costBreakdown",Map.of("entry",money(entry),"exit",money(exit),"funding",money(funding),"swap",money(swaps)));
        result.put("netBeforeUnknownCosts",money(closed.stream().map(o->o.netRealized).reduce(ZERO,BigDecimal::add)));
        result.put("positiveRecordedNet",closed.stream().filter(o->o.netRealized.signum()>0).count());
        result.put("completeNet",money(complete.stream().map(o->o.netRealized).reduce(ZERO,BigDecimal::add)));
        result.put("completeWinRatePct",complete.isEmpty()?null:money(BigDecimal.valueOf(complete.stream().filter(o->o.netRealized.signum()>0).count()).multiply(new BigDecimal("100")).divide(BigDecimal.valueOf(complete.size()),MC)));
        var gains=complete.stream().map(o->o.netRealized.max(ZERO)).reduce(ZERO,BigDecimal::add);var losses=complete.stream().map(o->o.netRealized.min(ZERO).negate()).reduce(ZERO,BigDecimal::add);
        result.put("profitFactor",losses.signum()==0?null:money(gains.divide(losses,MC)));
        result.put("incompleteOrders",closed.stream().filter(o->o.costIncomplete).count());result.put("quoteGapOrders",closed.stream().filter(o->o.quoteGap).count());
        result.put("liquidatedOrders",closed.stream().filter(o->o.positionStatus.equals("LIQUIDATED")).count());
        result.put("status",closed.isEmpty()?"NO_CLOSED_SAMPLE":complete.size()!=closed.size()?"RECORDED_COSTS_INCOMPLETE":"PUBLIC_COST_ASSUMPTIONS");
        var curve=curves.get(symbol);if(curve==null||curve.at().isBefore(clock.instant().minusSeconds(15))){curve=new Cached(clock.instant(),curve(store.accountSamples(symbol)));curves.put(symbol,curve);}
        result.putAll(curve.statistics());result.put("statisticsAt",curve.at());
        var runs=research.history().stream().filter(r->r.symbol.equals(symbol)).toList();
        result.put("researchStatistics",Map.of("scope","LATEST_40_RESEARCH_RUNS","runs",runs.size(),"completed",runs.stream().filter(r->r.status.equals("COMPLETED")).count(),"lowQuorum",runs.stream().filter(r->r.status.equals("LOW_QUORUM")).count(),"failed",runs.stream().filter(r->List.of("FAILED","EXPIRED","COMPLETED_ORDER_REJECTED").contains(r.status)).count()));
        var models=new LinkedHashMap<String,Map<String,Object>>();
        for(var run:runs)for(var actor:run.actors){var messages=run.messages.stream().filter(m->m.actorId().equals(actor.id())).toList();
            var row=models.computeIfAbsent(actor.provider()+"/"+actor.model(),k->new LinkedHashMap<>(Map.of("provider",actor.provider(),"model",actor.model(),"calls",0L,"submitted",0L,"failed",0L,"late",0L,"durationMillis",0L)));
            for(var message:messages){increment(row,"calls",1);if(message.state().equals("SUBMITTED"))increment(row,"submitted",1);if(List.of("FAILED","TIMED_OUT").contains(message.state()))increment(row,"failed",1);if(message.state().equals("LATE_ARCHIVED"))increment(row,"late",1);if(message.answer()!=null)increment(row,"durationMillis",message.answer().durationMs());}}
        result.put("modelStatistics",List.copyOf(models.values()));result.put("primaryStatisticsScope","CLOSED_PERPETUAL_ORDERS_WITH_RECORDED_COSTS; CFD_QUOTE_SCENARIOS_AND_UNKNOWN_COSTS_EXCLUDED");
        return result;
    }
    static Map<String,Object> curve(List<AccountSample> samples){
        BigDecimal peak=null,max=ZERO,maxPct=ZERO;int unknown=0;Instant peakAt=null,troughAt=null;var rows=new ArrayList<Map<String,Object>>();
        for(var sample:samples){if(sample.equity()==null){unknown++;continue;}var equity=new BigDecimal(sample.equity());if(peak==null||equity.compareTo(peak)>0){peak=equity;peakAt=sample.at();}
            var drawdown=peak.subtract(equity,MC);if(drawdown.compareTo(max)>0){max=drawdown;troughAt=sample.at();}
            if(peak.signum()>0)maxPct=maxPct.max(drawdown.multiply(new BigDecimal("100"),MC).divide(peak,MC));}
        int stride=Math.max(1,(samples.size()+479)/480);for(int i=0;i<samples.size();i++)if(i%stride==0||i==samples.size()-1||samples.get(i).at().equals(troughAt)||samples.get(i).at().equals(peakAt)){
            var sample=samples.get(i);var row=new LinkedHashMap<String,Object>();row.put("at",sample.at());row.put("balance",sample.balance());row.put("equity",sample.equity());row.put("state",sample.state());rows.add(row);}
        var result=new LinkedHashMap<String,Object>();result.put("equityCurve",rows);result.put("equitySamples",samples.size());result.put("unvaluedSamples",unknown);
        result.put("sampledMaximumDrawdown",samples.size()==unknown?null:money(max));result.put("sampledMaximumDrawdownPct",samples.size()==unknown?null:money(maxPct));
        result.put("curveScope","LAST_7_DAYS; MINUTE_OBSERVATIONS; DRAWNDOWN_IS_SAMPLED_LOWER_BOUND; NULL_EQUITY_REMAINS_UNKNOWN");return result;
    }
    private static void increment(Map<String,Object> row,String key,long amount){row.put(key,(long)row.get(key)+amount);}
    public String ordersCsv(String symbol){
        var result=new StringBuilder("\uFEFFid,symbol,quantityUnit,side,type,status,positionStatus,quantity,filled,openQuantity,averageEntry,leverage,realizedGross,entryFees,exitFees,fundingFees,swapFees,recordedNet,costIncomplete,evidenceLevel,createdAt,closedAt,riskAssessment,invalidate\r\n");
        for(var o:ledger.view(symbol).orders()){var cells=Arrays.asList(o.id,o.symbol,o.quantityUnit,o.side,o.type,o.status,o.positionStatus,money(o.quantity),money(o.filled),money(o.openQuantity),money(o.averageEntry),money(o.leverage),money(o.realizedGross),money(o.entryFees),money(o.exitFees),money(o.fundingFees),money(o.swapFees),money(o.netRealized),Boolean.toString(o.costIncomplete),o.evidenceLevel,o.createdAt==null?null:o.createdAt.toString(),o.closedAt==null?null:o.closedAt.toString(),safeCsvText(o.riskAssessment),safeCsvText(o.invalidate));
            result.append(cells.stream().map(v->"\""+(v==null?"":v.replace("\"","\"\""))+"\"").collect(java.util.stream.Collectors.joining(","))).append("\r\n");}return result.toString();
    }
    private String safeCsvText(String text){if(text!=null&&!text.stripLeading().isEmpty()&&"=+-@".indexOf(text.stripLeading().charAt(0))>=0)return "'"+text;return text;}
}
