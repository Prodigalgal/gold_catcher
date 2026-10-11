package com.goldcatcher.research;

import java.util.*;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;

/** One logical result schema; text adapters never infer financial values or evidence identities. */
final class ResearchProtocol {
    static final String VERSION="GOLD_RESEARCH_V1",COMPRESSION_VERSION="GOLD_COMPRESSION_V1";
    static final String REPAIR_INSTRUCTION="按统一协议重新提交，保持冻结证据与原ID，不补造数值。";
    static final List<String> PLAN_FIELDS=List.of("action","orderId","side","type","quantity","leverage","entryPrice","triggerPrice","stopLoss","takeProfit","trailingDistance","trailingActivation","expiresAt","holdUntil","riskAssessment","invalidate");
    private static final Set<String> TEXT_FIELDS=Set.of("title","analysisSummary","output","shortDirection","longDirection","changes","selectedCandidateId");
    private static final Set<String> ARRAY_FIELDS=Set.of("quotedMessages","evidenceRefs");
    private static final List<String> IMPACT_FIELDS=List.of("newsId","horizon","direction","reason","counter");
    private ResearchProtocol(){}
    static String instruction(boolean judging,boolean nativeTool){
        return instruction(judging,nativeTool,false);
    }
    static boolean onlyWait(JsonNode packet){var allowed=packet.path("evidence").path("allowedActions");return allowed.isArray()&&allowed.size()==1&&allowed.path(0).asText().equals("WAIT");}
    static String instruction(boolean judging,boolean nativeTool,boolean waitOnly){
        String common="GOLD_RESEARCH_V1，字段名区分大小写，方向BULLISH/BEARISH/UNKNOWN/MIXED。字数上限：title80/analysisSummary300/output400/changes120/plan.riskAssessment120/plan.invalidate80，详情写output。";
        if(nativeTool)return common+"只调用 submit_research，用函数 arguments 按声明字段提交，不输出其他正文。";
        return common+"\n每行一个 key=value，值单行不加引号，不写JSON/Markdown。首行protocol=GOLD_RESEARCH_V1。必填title/output/shortDirection/longDirection，可选analysisSummary/changes；quotedMessages/evidenceRefs用英文逗号隔开id，无项留空。\n"+
            (judging?"必填selectedCandidateId=WAIT或原始peers.id；禁止plan字段。\n":waitOnly?"本轮只允许plan.action=WAIT，可选plan.riskAssessment/plan.invalidate，禁止订单或价格字段。\n":"必填plan.action=WAIT或allowedActions动作，WAIT仅action与原因。其他plan字段按需："+String.join(",",PLAN_FIELDS.subList(1,PLAN_FIELDS.size()))+"；字段加plan.前缀，例plan.entryPrice=4096.123。数值十进制无单位，不适用省略/null，0保留。\n")+
            "新闻判断：impact.1.newsId/horizon/direction/reason/counter各一行，组号1至4，horizon=SHORT/LONG，newsId抄news.id，无判断省略。quotedMessages≤6，evidenceRefs≤14，不猜补数值/id。";
    }
    static String compressionInstruction(){return "只返回两行，不用JSON或Markdown，每个value单行：\nprotocol=GOLD_COMPRESSION_V1\nsummary=中文精华摘要\n";}
    static Map<String,Object> schema(boolean adjudication){
        Map<String,Object> properties=new LinkedHashMap<>();
        for(var field:List.of("title","analysisSummary","output","changes"))properties.put(field,Map.of("type","string"));
        for(var field:List.of("shortDirection","longDirection"))properties.put(field,Map.of("type","string","enum",List.of("BULLISH","BEARISH","UNKNOWN","MIXED")));
        for(var field:ARRAY_FIELDS)properties.put(field,Map.of("type","array","items",Map.of("type","string")));
        properties.put("impacts",Map.of("type","array","items",Map.of("type","object","properties",Map.of(
            "newsId",Map.of("type","string"),"horizon",Map.of("type","string","enum",List.of("SHORT","LONG")),"direction",properties.get("shortDirection"),
            "reason",Map.of("type","string"),"counter",Map.of("type","string")),"required",IMPACT_FIELDS,"additionalProperties",false)));
        if(adjudication)properties.put("selectedCandidateId",Map.of("type","string"));
        else{
            Map<String,Object> plan=new LinkedHashMap<>();plan.put("action",Map.of("type","string","enum",List.of("WAIT","CREATE","CANCEL","CLOSE","MODIFY","REDUCE")));
            for(var field:PLAN_FIELDS)if(!field.equals("action"))plan.put(field,
                field.equals("riskAssessment")||field.equals("invalidate")?Map.of("type",List.of("string","null"),"maxLength",field.equals("riskAssessment")?120:80):Map.of("type",List.of("string","null")));
            properties.put("plan",Map.of("type","object","properties",plan,"required",PLAN_FIELDS,"additionalProperties",false));
        }
        return Map.of("type","object","properties",properties,"required",List.copyOf(properties.keySet()),"additionalProperties",false);
    }
    static boolean text(String raw){return unfence(raw).matches("(?s)^protocol\\s*=.*");}
    static String unfence(String raw){
        String content=raw.strip();if(content.startsWith("```")){int newline=content.indexOf('\n'),end=content.lastIndexOf("```");
            if(newline<0||end<=newline||!content.substring(end+3).isBlank())throw invalid();content=content.substring(newline+1,end).strip();}
        return content;
    }
    static JsonNode parse(ObjectMapper mapper,String raw,boolean compression,boolean judging){
        var fields=new LinkedHashMap<String,String>();
        for(String line:unfence(raw).split("\\R")){if(line.isBlank())continue;int split=line.indexOf('=');if(split<1)throw invalid();
            String key=line.substring(0,split).strip(),value=line.substring(split+1).strip();if(fields.putIfAbsent(key,value)!=null)throw invalid();}
        if(!Objects.equals(fields.remove("protocol"),compression?COMPRESSION_VERSION:VERSION))throw invalid();
        var result=mapper.createObjectNode();
        if(compression){if(fields.size()!=1||!fields.containsKey("summary"))throw invalid();return result.put("summary",fields.get("summary"));}
        result.putArray("quotedMessages");result.putArray("evidenceRefs");var impacts=result.putArray("impacts");
        var groups=new TreeMap<Integer,ObjectNode>();
        for(var entry:fields.entrySet()){
            String key=entry.getKey(),value=entry.getValue();
            if(TEXT_FIELDS.contains(key)){if(!judging&&key.equals("selectedCandidateId"))throw invalid();result.put(key,value);}
            else if(ARRAY_FIELDS.contains(key)){var array=result.putArray(key);if(!value.isBlank())for(String id:value.split(",",-1)){if(id.isBlank())throw invalid();array.add(id.strip());}}
            else if(key.startsWith("plan.")&&!judging&&PLAN_FIELDS.contains(key.substring(5))){
                var plan=result.withObject("plan");if(value.isBlank()||value.equals("null"))plan.putNull(key.substring(5));else plan.put(key.substring(5),value);}
            else if(key.matches("impact\\.[1-4]\\.(?:newsId|horizon|direction|reason|counter)")){
                var parts=key.split("\\.");groups.computeIfAbsent(Integer.parseInt(parts[1]),index->mapper.createObjectNode()).put(parts[2],value);}
            else throw invalid();
        }
        for(var group:groups.values()){if(IMPACT_FIELDS.stream().anyMatch(field->!group.has(field)))throw new IllegalStateException("INVALID_IMPACT");impacts.add(group);}
        if(!judging&&(!result.path("plan").isObject()||result.path("plan").path("action").asText().isBlank()))throw new IllegalStateException("MISSING_REQUIRED_OUTPUT");
        if(judging&&result.path("selectedCandidateId").asText().isBlank())throw new IllegalStateException("MISSING_JUDGE_VOTE");
        return result;
    }
    private static IllegalStateException invalid(){return new IllegalStateException("INVALID_STRUCTURED_OUTPUT");}
}
