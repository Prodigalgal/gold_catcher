package com.goldcatcher.simulation;

import java.math.*;
import java.time.*;
import java.util.*;
import tools.jackson.databind.JsonNode;

/** Gate contract observations and separately labelled internal execution assumptions. */
public record CfdRules(BigDecimal contractVolume,BigDecimal minimumLots,BigDecimal maximumLots,BigDecimal lotStep,
    BigDecimal priceStep,BigDecimal maximumLeverage,List<BigDecimal> leverages,BigDecimal commissionPerLot,BigDecimal exchangeRate,
    BigDecimal buySwap,BigDecimal sellSwap,String rawSwapType,int tripleDay,String tradeTimezone,String tradeMode) {
    public static final String MARGIN_REFERENCE="https://www.gate.com/pt-br/help/futures/tradfi/47532";
    public static final String COST_REFERENCE="https://www.gate.com/pt-br/help/tradfi/cfd/47533/cfd-fee-calculation";
    public static CfdRules parse(JsonNode node) {
        if(node==null||!node.path("symbol").asText().equals("XAUUSD")||!node.path("settlement_currency").asText().equals("USD"))throw new IllegalArgumentException("CFD_RULES_NOT_SUPPORTED");
        int precision=node.path("price_precision").asInt(-1);if(precision<0||precision>8)throw new IllegalArgumentException("CFD_PRICE_PRECISION_UNKNOWN");
        BigDecimal max=positive(node,"leverage"),step=positive(node,"step_order_volume");
        var weekly=optional(node,"weekly_limit_leverage");if(weekly!=null&&weekly.signum()>0)max=max.min(weekly);
        BigDecimal minLots=positive(node,"min_order_volume"),maxLots=positive(node,"max_order_volume");
        if(minLots.compareTo(maxLots)>0||minLots.remainder(step).signum()!=0)throw new IllegalArgumentException("CFD_LOT_LIMITS_INVALID");
        var allowed=new ArrayList<BigDecimal>();for(var value:node.path("leverages")){var leverage=new BigDecimal(value.asText());if(leverage.signum()>0&&leverage.compareTo(max)<=0)allowed.add(leverage);}
        if(allowed.isEmpty())throw new IllegalArgumentException("CFD_LEVERAGES_UNKNOWN");
        var fee=new BigDecimal(node.path("commission_per_lot").asText());if(fee.signum()<0)throw new IllegalArgumentException("CFD_COMMISSION_INVALID");
        int triple=node.path("swap_cost_3day").asInt(-1);
        return new CfdRules(positive(node,"contract_volume"),minLots,maxLots,step,
            BigDecimal.ONE.scaleByPowerOfTen(-precision),max,List.copyOf(allowed),fee,positive(node,"exchange_rate"),
            optional(node,"buy_swap_cost_rate"),optional(node,"sell_swap_cost_rate"),node.path("swap_cost_type").asText(),triple,node.path("trade_timezone").asText(),node.path("trade_mode").asText());
    }
    public static boolean available(JsonNode node){try{parse(node);return true;}catch(RuntimeException invalid){return false;}}
    public boolean permits(String side){return tradeMode.equals("4")||side.equals("BUY")&&tradeMode.equals("1")||side.equals("SELL")&&tradeMode.equals("2");}
    public BigDecimal fee(BigDecimal lots){return lots.multiply(commissionPerLot,MathContext.DECIMAL128);}
    public ZoneOffset offset(){return ZoneOffset.of(tradeTimezone.replace("GMT",""));}
    private static BigDecimal positive(JsonNode node,String name){var value=new BigDecimal(node.path(name).asText());if(value.signum()<=0)throw new IllegalArgumentException("CFD_RULE_"+name.toUpperCase(Locale.ROOT));return value;}
    private static BigDecimal optional(JsonNode node,String name){return node.path(name).isMissingNode()||node.path(name).isNull()?null:new BigDecimal(node.path(name).asText());}
}
