package com.goldcatcher.market;

import java.math.*;
import java.time.*;
import java.util.*;
import tools.jackson.databind.JsonNode;

/** Public 100-level book, bootstrapped with a matching REST id and gap-checked U/u updates. */
final class GateDepthBook {
    private final NavigableMap<BigDecimal,BigDecimal> bids=new TreeMap<>(Comparator.reverseOrder()),asks=new TreeMap<>();
    private final Deque<JsonNode> waiting=new ArrayDeque<>();private long id=-1;private String state="WAITING_SNAPSHOT",error;
    private Instant sourceAt,receivedAt;private BigDecimal multiplier=BigDecimal.ONE;
    synchronized void reset(){bids.clear();asks.clear();waiting.clear();id=-1;state="WAITING_SNAPSHOT";sourceAt=null;receivedAt=null;error=null;}
    synchronized boolean update(JsonNode update,Instant received) {
        if(!update.path("s").asText().equals("XAU_USDT"))return false;
        long first=update.path("U").asLong(-1),last=update.path("u").asLong(-1);
        if(first<0||last<first)throw new IllegalStateException("DEPTH_SEQUENCE_INVALID");
        if(update.path("full").asBoolean(false)){bids.clear();asks.clear();levels(bids,update.path("b"));levels(asks,update.path("a"));id=last;state="SEQUENCED";error=null;}
        else if(id<0){if(waiting.size()>=4096){waiting.clear();error="DEPTH_BUFFER_OVERFLOW";}waiting.addLast(update.deepCopy());return true;}
        else if(last<=id)return false;
        else if(first>id+1){id=-1;state="SEQUENCE_GAP";error="DEPTH_SEQUENCE_GAP";waiting.clear();waiting.addLast(update.deepCopy());return true;}
        else {levels(bids,update.path("b"));levels(asks,update.path("a"));id=last;}
        sourceAt=Instant.ofEpochMilli(update.path("t").asLong());receivedAt=received;
        if(!bids.isEmpty()&&!asks.isEmpty()&&bids.firstKey().compareTo(asks.firstKey())>=0){reset();error="DEPTH_CROSSED";return true;}
        return false;
    }
    synchronized void bootstrap(JsonNode snapshot,BigDecimal quantityMultiplier,Instant received) {
        long base=snapshot.path("id").asLong(-1);if(base<0)throw new IllegalStateException("DEPTH_SNAPSHOT_ID_MISSING");
        multiplier=quantityMultiplier;bids.clear();asks.clear();levels(bids,snapshot.path("bids"));levels(asks,snapshot.path("asks"));id=base;state="SNAPSHOT_ONLY";error=null;
        sourceAt=null;receivedAt=received;var buffered=new ArrayList<>(waiting);waiting.clear();
        for(var update:buffered){if(update(update,received))break;if(id>=0)state="SEQUENCED";}
    }
    synchronized Map<String,Object> view(Instant now){var result=new LinkedHashMap<String,Object>();result.put("state",state);result.put("error",error);result.put("sequence",id);
        result.put("subscribedLevels",100);result.put("sourceAt",sourceAt);result.put("receivedAt",receivedAt);result.put("fresh",state.equals("SEQUENCED")&&sourceAt!=null&&sourceAt.isAfter(now.minusSeconds(3))&&!sourceAt.isAfter(now.plusSeconds(2)));
        result.put("bids",rows(bids));result.put("asks",rows(asks));result.put("quantityUnit","XAU");result.put("executionUse","OBSERVATION_ONLY; L1_SIMULATION_STAYS_CONSERVATIVE");return result;}
    private List<Map<String,String>> rows(NavigableMap<BigDecimal,BigDecimal> levels){return levels.entrySet().stream().limit(10).map(e->Map.of("price",e.getKey().toPlainString(),"quantity",e.getValue().multiply(multiplier,MathContext.DECIMAL128).toPlainString())).toList();}
    private void levels(NavigableMap<BigDecimal,BigDecimal> book,JsonNode rows){if(!rows.isArray())throw new IllegalStateException("DEPTH_LEVELS_INVALID");
        for(var row:rows){var p=new BigDecimal(row.path("p").asText());var s=new BigDecimal(row.path("s").asText());if(p.signum()<=0||s.signum()<0)throw new IllegalStateException("DEPTH_PRICE_SIZE_INVALID");if(s.signum()==0)book.remove(p);else book.put(p,s);}
        while(book.size()>100)book.pollLastEntry();}
}
