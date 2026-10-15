package com.goldcatcher.research;

import java.util.*;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;

/** Current submission grammar. No reference, candidate, actor or order identifiers. */
final class ModelProtocol {
    static final String VERSION="GOLD_RESEARCH_V2";
    static final List<String> PLAN_FIELDS=List.of("action","side","type","quantity","leverage","entryPrice","triggerPrice","stopLoss","takeProfit",
        "trailingDistance","trailingActivation","expiresAt","holdUntil","riskAssessment","invalidate");
    static final List<String> TARGET_FIELDS=List.of("side","type","quantity","leverage","entryPrice");
    private static final List<String> IMPACT_FIELDS=List.of("topic","horizon","direction","reason","counter");
    private static final Set<String> TEXT_FIELDS=Set.of("title","analysisSummary","output","shortDirection","longDirection","changes");
    private ModelProtocol(){}

    static String instruction(boolean judge,boolean nativeTool,boolean waitOnly){
        String common="方向BULLISH/BEARISH/UNKNOWN/MIXED；title≤80字，analysisSummary≤300，output≤400，changes≤120；plan.riskAssessment≤120，plan.invalidate≤80。无需任何编号或引用。";
        if(nativeTool)return common+"只调用 submit_research，用函数 arguments 提交，不输出其他正文。";
        return common+"\n每行一个 key=value，值单行，不加引号，不写JSON/Markdown。首行protocol=GOLD_RESEARCH_V2。必填title/output/shortDirection/longDirection/plan.action，可选analysisSummary/changes。\n"+
            (waitOnly?"plan.action=WAIT，只需风险说明。":judge?"选择等待则plan.action=WAIT；采纳候选则逐项复制一个完整候选plan，含风险、失效条件，不可改值、拼接或新增。":
                "plan.action=WAIT/CREATE/CANCEL/CLOSE/MODIFY/REDUCE。其他plan字段按需："+String.join(",",PLAN_FIELDS.subList(1,PLAN_FIELDS.size()))+"。")+
            "数值为无单位十进制字符串，不适用省略/null，0保留。管理已有仓位时复制其财务特征：plan.target.side/type/quantity/leverage/entryPrice；与拟修改字段分开。\n"+
            "可选impact.1.topic/horizon/direction/reason/counter各一行，最多4组，topic用事件名称，horizon=SHORT/LONG；没有判断省略整组。不猜补缺失值。\n";
    }

    static Map<String,Object> schema(){
        var properties=new LinkedHashMap<String,Object>();
        for(String key:List.of("title","analysisSummary","output","changes"))properties.put(key,Map.of("type","string"));
        var direction=Map.of("type","string","enum",List.of("BULLISH","BEARISH","UNKNOWN","MIXED"));
        properties.put("shortDirection",direction);properties.put("longDirection",direction);
        var impact=new LinkedHashMap<String,Object>();for(String key:IMPACT_FIELDS)impact.put(key,Map.of("type","string"));
        impact.put("direction",direction);impact.put("horizon",Map.of("type","string","enum",List.of("SHORT","LONG")));
        properties.put("impacts",Map.of("type","array","maxItems",4,"items",Map.of("type","object","properties",impact,"required",IMPACT_FIELDS,"additionalProperties",false)));
        var plan=new LinkedHashMap<String,Object>();for(String key:PLAN_FIELDS)plan.put(key,Map.of("type",List.of("string","null")));
        plan.put("action",Map.of("type","string","enum",List.of("WAIT","CREATE","CANCEL","CLOSE","MODIFY","REDUCE")));
        plan.put("riskAssessment",Map.of("type",List.of("string","null"),"maxLength",120));plan.put("invalidate",Map.of("type",List.of("string","null"),"maxLength",80));
        var target=new LinkedHashMap<String,Object>();TARGET_FIELDS.forEach(key->target.put(key,Map.of("type","string")));
        plan.put("target",Map.of("type",List.of("object","null"),"properties",target,"required",TARGET_FIELDS,"additionalProperties",false));
        properties.put("plan",Map.of("type","object","properties",plan,"required",List.copyOf(plan.keySet()),"additionalProperties",false));
        return Map.of("type","object","properties",properties,"required",List.copyOf(properties.keySet()),"additionalProperties",false);
    }

    static JsonNode parse(ObjectMapper mapper,String raw){
        String content=ResearchProtocol.unfence(raw);var marker=java.util.regex.Pattern.compile("(?m)^protocol[\\t ]*=").matcher(content);
        if(marker.find()&&marker.start()>0){String prefix=content.substring(0,marker.start());
            if(prefix.length()>2048||prefix.contains("```")||prefix.contains("{")||prefix.contains("}")||java.util.regex.Pattern.compile("(?m)^[\\t ]*[A-Za-z_][A-Za-z0-9_.-]*[\\t ]*=").matcher(prefix).find())throw invalid("PREFIX");
            content=content.substring(marker.start());}
        var fields=new LinkedHashMap<String,String>();for(String line:content.split("\\R")){
            if(line.isBlank())continue;int at=line.indexOf('=');if(at<1)throw invalid("LINE");String key=line.substring(0,at).strip(),value=line.substring(at+1).strip();
            if(key.startsWith("impacts."))key="impact."+key.substring(8);
            String previous=fields.putIfAbsent(key,value);if(previous!=null&&!previous.equals(value))throw invalid("CONFLICT");
        }
        String version=fields.remove("protocol");
        // A legacy marker alone is harmless; legacy identity fields are rejected below.
        if(version!=null&&!Set.of(VERSION,ResearchProtocol.VERSION).contains(version))throw invalid("PROTOCOL");
        var result=mapper.createObjectNode();var groups=new TreeMap<Integer,ObjectNode>();
        for(var field:fields.entrySet()){
            String key=field.getKey(),value=field.getValue();
            if(TEXT_FIELDS.contains(key))result.put(key,value);
            else if(key.startsWith("plan.target.")&&TARGET_FIELDS.contains(key.substring(12)))put(result.withObject("plan").withObject("target"),key.substring(12),value);
            else if(key.startsWith("plan.")&&(PLAN_FIELDS.contains(key.substring(5))||key.equals("plan.reason")))put(result.withObject("plan"),key.substring(5),value);
            else if(key.matches("impact\\.[1-4]\\.(?:topic|horizon|direction|reason|counter)")){
                var parts=key.split("\\.");put(groups.computeIfAbsent(Integer.parseInt(parts[1]),n->mapper.createObjectNode()),parts[2],value);
            }else if(key.equals("impacts")&&(value.isBlank()||value.equals("[]")||value.equals("null"))){}
            else throw invalid("UNKNOWN_FIELD");
        }
        if(fields.containsKey("impacts")&&!groups.isEmpty())throw invalid("CONFLICT");
        var impacts=result.putArray("impacts");groups.values().forEach(impacts::add);
        return normalize(result);
    }
    private static void put(ObjectNode node,String key,String value){if(value.isBlank()||value.equals("null"))node.putNull(key);else node.put(key,value);}
    static JsonNode normalize(JsonNode output){
        if(!output.isObject())throw invalid("OBJECT");var result=(ObjectNode)output.deepCopy();
        for(var field:result.properties()){
            if(!TEXT_FIELDS.contains(field.getKey())&&!Set.of("impacts","plan").contains(field.getKey()))throw invalid("UNKNOWN_FIELD");
            if(TEXT_FIELDS.contains(field.getKey())&&!field.getValue().isTextual())throw invalid("VALUE");
        }
        if(!(result.path("plan") instanceof ObjectNode plan)||plan.path("action").asText().isBlank())throw new IllegalStateException("MISSING_REQUIRED_OUTPUT_PLAN_ACTION");
        for(var field:plan.properties())if(!PLAN_FIELDS.contains(field.getKey())&&!Set.of("target","reason").contains(field.getKey()))throw invalid("UNKNOWN_FIELD");
        var reason=plan.remove("reason");if(reason!=null&&!reason.isNull()){
            if(!reason.isTextual())throw invalid("VALUE");if(plan.path("riskAssessment").asText().isBlank())plan.set("riskAssessment",reason);
            else if(!plan.path("riskAssessment").equals(reason))result.put("output",result.path("output").asText()+"；补充理由："+reason.asText());
        }
        for(String key:PLAN_FIELDS)if(plan.has(key)&&!plan.path(key).isNull()&&!plan.path(key).isTextual())throw invalid("VALUE");
        if(plan.hasNonNull("target")){
            if(!plan.path("target").isObject())throw invalid("TARGET");
            for(var field:plan.path("target").properties())if(!TARGET_FIELDS.contains(field.getKey())||!field.getValue().isTextual())throw invalid("TARGET");
            if(TARGET_FIELDS.stream().anyMatch(key->!plan.path("target").hasNonNull(key)))throw invalid("TARGET");
        }
        if(result.has("impacts")&&!result.path("impacts").isArray())throw new IllegalStateException("INVALID_IMPACT");
        var original=result.path("impacts");var impacts=result.putArray("impacts");for(var impact:original){
            if(impact.isObject()&&impact.properties().stream().allMatch(f->IMPACT_FIELDS.contains(f.getKey())&&(f.getValue().isNull()||f.getValue().asText().isBlank())))continue;
            if(!impact.isObject()||impact.properties().stream().anyMatch(f->!IMPACT_FIELDS.contains(f.getKey()))||IMPACT_FIELDS.stream().anyMatch(k->!impact.path(k).isTextual())||impact.path("topic").asText().isBlank())throw new IllegalStateException("INVALID_IMPACT");
            impacts.add(impact);
        }
        return result;
    }
    static String repairInstruction(String error){
        if(Set.of("MISSING_ORDER_TARGET","ORDER_TARGET_NOT_FOUND","AMBIGUOUS_ORDER_TARGET").contains(error))return "管理仓位须复制同一仓位的target.side/type/quantity/leverage/entryPrice；无法唯一定位时WAIT，不猜选。";
        if(error.equals("INVALID_JUDGE_CANDIDATE")||error.equals("MISSING_JUDGE_VOTE"))return "复制一个候选的完整plan，数值、风险与失效条件均须相同；无法采纳则plan.action=WAIT。";
        if(error.equals("INVALID_IMPACT"))return "impact每组完整填写topic/horizon/direction/reason/counter，无判断省略整组。";
        if(error.startsWith("INVALID_STRUCTURED_OUTPUT"))return "只按声明字段提交，不使用编号、引用或额外字段；数值不要猜补。";
        if(error.startsWith("PLAN_NARRATIVE_TOO_LONG"))return "plan.riskAssessment≤120字，plan.invalidate≤80字；详情写output。";
        if(error.startsWith("MISSING_REQUIRED_OUTPUT_PLAN_ACTION"))return "必须明确提交plan.action。";
        if(error.startsWith("INVALID_DIRECTION"))return "方向只用BULLISH/BEARISH/UNKNOWN/MIXED。";
        return "按当前协议完整重交，不加编号或引用，不猜补数值。";
    }
    private static IllegalStateException invalid(String code){return new IllegalStateException("INVALID_STRUCTURED_OUTPUT_"+code);}
}
