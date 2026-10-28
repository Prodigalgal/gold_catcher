package com.goldcatcher.external;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.JsonNode;

/** Closed, bounded schemas shared by every external transport. No coercion of financial inputs. */
final class ExternalSchemas {
    private ExternalSchemas() {}
    static Map<String,Object> object(Map<String,?> fields,String... required){return Map.of("type","object","properties",new TreeMap<>(fields),"required",List.of(required),"additionalProperties",false);}
    static Map<String,Object> text(String description,int max){return Map.of("type","string","description",description,"maxLength",max);}
    static Map<String,Object> choice(String description,List<String> values){return Map.of("type","string","description",description,"enum",values,"maxLength",160);}
    static Map<String,Object> integer(String description,int min,int max){return Map.of("type","integer","description",description,"minimum",min,"maximum",max);}
    static Map<String,Object> bool(){return Map.of("type","boolean");}
    static Map<String,Object> time(){return Map.of("type","string","format","date-time","maxLength",60);}
    static Map<String,Object> decimal(boolean nullable){return Map.of("type",nullable?List.of("string","null"):"string","format","decimal","description","Exact decimal string, never a floating point JSON number","maxLength",80);}
    static Map<String,Object> array(Map<String,Object> items,int max){return Map.of("type","array","items",items,"maxItems",max);}
    @SuppressWarnings("unchecked") static void validate(Map<String,Object> spec,JsonNode value){
        Object type=spec.get("type");
        if(value!=null&&value.isNull()&&type instanceof List<?> types&&types.contains("null"))return;
        String kind=type instanceof List<?> types?(String)types.getFirst():(String)type;
        if(value==null)throw new IllegalArgumentException("MISSING_REQUIRED_ARGUMENT");
        switch(kind){
            case "object" -> {
                if(!value.isObject())fail("INVALID_OBJECT_ARGUMENT");
                var fields=(Map<String,Map<String,Object>>)spec.get("properties");
                for(String name:value.propertyNames()){var field=fields.get(name);if(field==null)fail("UNKNOWN_ARGUMENT");validate(field,value.get(name));}
                for(String name:(List<String>)spec.getOrDefault("required",List.of()))if(!value.has(name))fail("MISSING_REQUIRED_ARGUMENT");
            }
            case "array" -> {if(!value.isArray()||value.size()>(int)spec.get("maxItems"))fail("INVALID_ARRAY_ARGUMENT");for(var item:value)validate((Map<String,Object>)spec.get("items"),item);}
            case "string" -> {
                if(!value.isTextual()||value.asText().length()>(int)spec.getOrDefault("maxLength",200))fail("INVALID_STRING_ARGUMENT");
                if(spec.containsKey("enum")&&!((List<String>)spec.get("enum")).contains(value.asText()))fail("INVALID_ENUM_ARGUMENT");
                if("date-time".equals(spec.get("format")))try{Instant.parse(value.asText());}catch(RuntimeException invalid){fail("INVALID_TIME_ARGUMENT");}
                if("decimal".equals(spec.get("format")))try{if(!value.asText().matches("-?[0-9]+(?:\\.[0-9]+)?"))fail("INVALID_DECIMAL_ARGUMENT");new BigDecimal(value.asText());}catch(RuntimeException invalid){fail("INVALID_DECIMAL_ARGUMENT");}
            }
            case "integer" -> {if(!value.isIntegralNumber()||!value.canConvertToInt()||value.asInt()<(int)spec.get("minimum")||value.asInt()>(int)spec.get("maximum"))fail("INVALID_INTEGER_ARGUMENT");}
            case "boolean" -> {if(!value.isBoolean())fail("INVALID_BOOLEAN_ARGUMENT");}
            default -> fail("INVALID_ARGUMENT_SCHEMA");
        }
    }
    private static void fail(String error){throw new IllegalArgumentException(error);}
}
