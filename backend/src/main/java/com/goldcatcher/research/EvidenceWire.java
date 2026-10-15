package com.goldcatcher.research;

import java.util.*;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.*;

/** Logical evidence remains JSON; choose a lossless text encoding with fewer outer-JSON escapes. */
final class EvidenceWire {
    private EvidenceWire() {}
    static String encode(ObjectMapper mapper,JsonNode packet){
        return encode(mapper,packet,true);
    }
    static String encode(ObjectMapper mapper,JsonNode packet,boolean references){
        packet=com.goldcatcher.CanonicalJson.tree(mapper,packet);
        String json=mapper.writeValueAsString(packet);var options=new DumperOptions();options.setDefaultFlowStyle(DumperOptions.FlowStyle.FLOW);
        options.setExplicitStart(true);options.setAllowUnicode(true);options.setSplitLines(false);options.setWidth(1000000);
        // Equal collection subtrees share YAML anchors. Distinct peer IDs/plans remain distinct.
        String yaml=compactFlow(new Yaml(options).dump(value(packet,references?new HashMap<>():null)).stripTrailing());
        return mapper.writeValueAsString(yaml).length()<mapper.writeValueAsString(json).length()?yaml:json;
    }
    /** Remove optional flow-separator padding, never whitespace belonging to a scalar. */
    private static String compactFlow(String yaml) {
        var out=new StringBuilder(yaml.length());char quote=0;boolean scalarStart=true;
        for(int i=0;i<yaml.length();i++) {
            char c=yaml.charAt(i);out.append(c);
            if(quote=='"') {
                if(c=='\\'&&i+1<yaml.length())out.append(yaml.charAt(++i));
                else if(c=='"')quote=0;
                continue;
            }
            if(quote=='\'') {
                if(c=='\''&&i+1<yaml.length()&&yaml.charAt(i+1)=='\'')out.append(yaml.charAt(++i));
                else if(c=='\'')quote=0;
                continue;
            }
            if(scalarStart&&(c=='\''||c=='"')) {quote=c;scalarStart=false;continue;}
            if(c==',') {
                while(i+1<yaml.length()&&yaml.charAt(i+1)==' ')i++;
                scalarStart=true;
            } else if(c=='{'||c=='['||c==':'&&i+1<yaml.length()&&yaml.charAt(i+1)==' ')scalarStart=true;
            else if(!Character.isWhitespace(c))scalarStart=false;
        }
        return out.toString();
    }
    private static Object value(JsonNode node,Map<Object,Object> shared){
        if(node.isObject()){var result=new LinkedHashMap<String,Object>();node.properties().forEach(field->result.put(field.getKey(),value(field.getValue(),shared)));return shared==null?result:shared.computeIfAbsent(result,key->result);}
        if(node.isArray()){var result=new ArrayList<>();node.forEach(item->result.add(value(item,shared)));return shared==null?result:shared.computeIfAbsent(result,key->result);}
        if(node.isTextual())return node.asText();if(node.isIntegralNumber())return node.bigIntegerValue();if(node.isFloatingPointNumber())return node.decimalValue();
        if(node.isBoolean())return node.asBoolean();if(node.isNull())return null;throw new IllegalArgumentException("UNSUPPORTED_EVIDENCE_VALUE");
    }
}
