package com.goldcatcher;

import java.util.*;
import tools.jackson.databind.*;

/** Lossless column dictionary + RFC 4180 rows; null is an unquoted empty cell. */
public final class EvidenceTable {
    private EvidenceTable() {}
    public static Map<String,Object> packFactored(ObjectMapper mapper,JsonNode records,List<String> columns,List<String> candidates){
        if(records.isEmpty())return pack(mapper,records,columns);
        var common=new LinkedHashMap<String,JsonNode>();var remaining=new ArrayList<>(columns);
        for(var column:candidates){if(!remaining.contains(column))continue;var first=records.iterator().next().path(column);if(first.isMissingNode()||first.isNull())continue;
            boolean equal=true;for(var record:records)if(!record.path(column).equals(first)){equal=false;break;}
            if(equal){common.put(column,first);remaining.remove(column);}
        }
        var result=new LinkedHashMap<String,Object>(pack(mapper,records,remaining));if(!common.isEmpty())result.put("common",common);return result;
    }
    public static Map<String,Object> pack(ObjectMapper mapper,JsonNode records,List<String> columns){
        StringBuilder csv=new StringBuilder();
        for(var record:records){if(!csv.isEmpty())csv.append('\n');
            for(int i=0;i<columns.size();i++){if(i>0)csv.append(',');var cell=record.path(columns.get(i));
                if(cell.isNull()||cell.isMissingNode())continue;
                String value=cell.isTextual()?cell.asText():mapper.writeValueAsString(cell);
                if(value.isEmpty()||value.contains(",")||value.contains("\"")||value.contains("\n")||value.contains("\r"))csv.append('"').append(value.replace("\"","\"\"")).append('"');
                else csv.append(value);
            }
        }
        var result=new LinkedHashMap<String,Object>();result.put("columns",List.copyOf(columns));result.put("csv",csv.toString());return Collections.unmodifiableMap(result);
    }
}
