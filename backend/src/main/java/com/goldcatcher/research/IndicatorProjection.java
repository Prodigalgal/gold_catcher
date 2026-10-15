package com.goldcatcher.research;

import com.goldcatcher.EvidenceTable;
import com.goldcatcher.market.MarketTypes.Indicators;
import java.math.*;
import java.util.*;
import tools.jackson.databind.*;

/** Full precision values remain in the calculation archive. Raw close/volume never use derived-value rounding. */
final class IndicatorProjection {
    private IndicatorProjection() {}
    static JsonNode project(ObjectMapper mapper,Map<String,Indicators> input){
        var rows=mapper.createArrayNode();var columns=new LinkedHashSet<String>(List.of("frame","samples","closedThrough","state","calculatedAt","freshness"));
        for(String frame:List.of("1","5","15","60","240","D")){
            var indicator=input.get(frame);if(indicator==null)continue;
            var row=rows.addObject().put("frame",frame).put("samples",indicator.samples()).put("state",indicator.state()).put("freshness",indicator.freshness());
            row.put("closedThrough",indicator.closedThrough()==null?null:indicator.closedThrough().toString());row.put("calculatedAt",indicator.calculatedAt()==null?null:indicator.calculatedAt().toString());
            for(String key:new TreeSet<>(indicator.values().keySet())){
                if(Set.of("ema20DistancePct","vwap20","volumeRatio20","bbWidthPct","bbPercentB").contains(key))continue;
                columns.add(key);String value=indicator.values().get(key);
                if(value==null)row.putNull(key);else row.put(key,Set.of("close","volume").contains(key)?value:new BigDecimal(value).setScale(8,RoundingMode.HALF_EVEN).stripTrailingZeros().toPlainString());
            }
        }
        return mapper.valueToTree(EvidenceTable.packFactored(mapper,rows,List.copyOf(columns),List.of("state","freshness","samples","calculatedAt")));
    }
}
