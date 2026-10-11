package com.goldcatcher.research;

import java.util.*;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;

/** One logical result schema; text adapters never infer financial values or evidence identities. */
final class ResearchProtocol {
    static final String VERSION="GOLD_RESEARCH_V1",COMPRESSION_VERSION="GOLD_COMPRESSION_V1";
    static final List<String> PLAN_FIELDS=List.of("action","orderId","side","type","quantity","leverage","entryPrice","triggerPrice","stopLoss","takeProfit","trailingDistance","trailingActivation","expiresAt","holdUntil","riskAssessment","invalidate");
    private static final Set<String> TEXT_FIELDS=Set.of("title","analysisSummary","output","shortDirection","longDirection","changes","selectedCandidateId");
    private static final Set<String> ARRAY_FIELDS=Set.of("quotedMessages","evidenceRefs");
    private static final List<String> IMPACT_FIELDS=List.of("newsId","horizon","direction","reason","counter");
    private ResearchProtocol(){}
    static String instruction(boolean judging,boolean nativeTool){
        String common="统一逻辑协议 GOLD_RESEARCH_V1，字段名区分大小写，方向仅 BULLISH/BEARISH/UNKNOWN/MIXED。title≤80字，analysisSummary≤300，output≤400，changes≤120；plan.riskAssessment≤120字，invalidate≤80字，详情写output。";
        if(nativeTool)return common+"只调用 submit_research，用函数 arguments 按声明字段提交，不输出其他正文。";
        return common+"""

            文本输出每行一个 key=value，不用JSON括号、引号、转义，不写解释或Markdown；每个value单行，等号后原文保留。首行protocol=GOLD_RESEARCH_V1。
            必填title/output/shortDirection/longDirection，其余叙述可省略；列表直接用英文逗号隔开的原始id，空列表留空。参考：
            protocol=GOLD_RESEARCH_V1
            title=形势观察
            analysisSummary=依据摘要
            output=依据、反证和采纳理由
            shortDirection=UNKNOWN
            longDirection=UNKNOWN
            quotedMessages=
            evidenceRefs=market:quote
            changes=修订说明
            """+(judging?"裁决必填 selectedCandidateId=WAIT 或一个原始 peers.id，不输出任何 plan 字段。\n":"研究必填 plan.action=WAIT 或allowedActions中的动作；WAIT只需action和原因。CREATE/管理动作按需输出 plan."+String.join(" / plan.",PLAN_FIELDS)+"。价格、数量、杠杆原样写十进制，不加单位，不适用字段省略或写null，0不能写成空。参考：\nplan.action=WAIT\nplan.riskAssessment=费用或证据不足\nplan.invalidate=取得新证据后复核\n")+
            "新闻判断最多4组，每组五行 impact.1.newsId=原始news.id、impact.1.horizon=SHORT或LONG、impact.1.direction=方向、impact.1.reason=依据、impact.1.counter=反证；其他组将1改为2/3/4，没有判断则全部省略。quotedMessages≤6，evidenceRefs≤14；不得猜补id或数值。";
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
