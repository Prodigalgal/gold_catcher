package com.goldcatcher.research;

import java.util.*;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.*;

/** Logical evidence remains JSON; choose a lossless text encoding with fewer outer-JSON escapes. */
final class EvidenceWire {
    private EvidenceWire() {}
    static String encode(ObjectMapper mapper,JsonNode packet){
        String json=mapper.writeValueAsString(packet);var options=new DumperOptions();options.setDefaultFlowStyle(DumperOptions.FlowStyle.FLOW);
        options.setExplicitStart(true);options.setAllowUnicode(true);options.setSplitLines(false);options.setWidth(1000000);
        String yaml=new Yaml(options).dump(value(packet)).stripTrailing();
        return mapper.writeValueAsString(yaml).length()<mapper.writeValueAsString(json).length()?yaml:json;
    }
    private static Object value(JsonNode node){
        if(node.isObject()){var result=new LinkedHashMap<String,Object>();node.properties().forEach(field->result.put(field.getKey(),value(field.getValue())));return result;}
        if(node.isArray()){var result=new ArrayList<>();node.forEach(item->result.add(value(item)));return result;}
        if(node.isTextual())return node.asText();if(node.isIntegralNumber())return node.bigIntegerValue();if(node.isFloatingPointNumber())return node.decimalValue();
        if(node.isBoolean())return node.asBoolean();if(node.isNull())return null;throw new IllegalArgumentException("UNSUPPORTED_EVIDENCE_VALUE");
    }
}
