package com.goldcatcher;

import java.util.*;
import tools.jackson.databind.*;

/** Lossless column dictionary + RFC 4180 rows; null is an unquoted empty cell. */
public final class EvidenceTable {
    private EvidenceTable() {}
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
        return Map.of("columns",List.copyOf(columns),"csv",csv.toString());
    }
}
