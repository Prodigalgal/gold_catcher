package com.goldcatcher.research;

import java.util.*;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;

/** Archived v1 grammar and shared text/compression framing. Live research uses ModelProtocol v2. */
final class ResearchProtocol {
    static final String VERSION="GOLD_RESEARCH_V1",COMPRESSION_VERSION="GOLD_COMPRESSION_V1";
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
        String common="GOLD_RESEARCH_V1，方向BULLISH/BEARISH/UNKNOWN/MIXED。字数目标：title80/analysisSummary300/output400/changes120；硬上限plan.riskAssessment120/plan.invalidate80，详情写output。";
        if(nativeTool)return common+"只调用 submit_research，用函数 arguments 按声明字段提交，不输出其他正文。";
        return common+"\n每行一个 key=value，值单行不加引号，不写JSON/Markdown。首行protocol=GOLD_RESEARCH_V1。必填title/output/shortDirection/longDirection，可选analysisSummary/changes；quotedMessages/evidenceRefs用英文逗号隔开id，无项留空。\n"+
            (judging?"必填selectedCandidateId=WAIT或原始peers.id；禁止plan字段。\n":waitOnly?"必填plan.action=WAIT，可选plan.riskAssessment/plan.invalidate，禁止订单或价格字段。\n":"必填plan.action=WAIT或allowedActions动作，WAIT说明写plan.riskAssessment。其他plan字段按需："+String.join(",",PLAN_FIELDS.subList(1,PLAN_FIELDS.size()))+"；加plan.前缀。数值十进制无单位，不适用省略/null，0保留。\n")+
            "新闻判断：impact.1.newsId/horizon/direction/reason/counter各一行，组号1至4，horizon=SHORT/LONG，newsId抄news.id，无判断省略。quotedMessages≤6，evidenceRefs≤14，不猜补数值/id。\n"+
            "结构示例（按证据填写自己的结论；无机会可等待）：\nprotocol=GOLD_RESEARCH_V1\ntitle=形势观察\noutput=说明依据、反证与缺口\nshortDirection=UNKNOWN\nlongDirection=UNKNOWN\nquotedMessages=\nevidenceRefs=\n"+
            (judging?"selectedCandidateId=WAIT\n":"plan.action=WAIT\nplan.riskAssessment=当前缺乏可靠的执行依据\n");
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
    static boolean text(String raw){return framedText(raw).matches("(?s)^(?:protocol|title|analysisSummary|output|shortDirection|longDirection)\\s*=.*");}
    static String unfence(String raw){
        String content=raw.strip();if(content.startsWith("```")){int newline=content.indexOf('\n'),end=content.lastIndexOf("```");
            if(newline<0||end<=newline||!content.substring(end+3).isBlank())throw invalid();content=content.substring(newline+1,end).strip();}
        return content;
    }
    private static String framedText(String raw){
        String content=unfence(raw);var marker=java.util.regex.Pattern.compile("(?m)^protocol[\\t ]*=").matcher(content);
        if(!marker.find()||marker.start()==0)return content;
        String prefix=content.substring(0,marker.start());
        // A bounded prose preface may surround a result, but must never hide earlier structured fields or JSON.
        if(prefix.length()>2048||prefix.contains("```")||prefix.contains("{")||prefix.contains("}")
            ||java.util.regex.Pattern.compile("(?m)^[\\t ]*[A-Za-z_][A-Za-z0-9_.-]*[\\t ]*=").matcher(prefix).find())throw invalid("PREFIX");
        return content.substring(marker.start());
    }
    static JsonNode parse(ObjectMapper mapper,String raw,boolean compression,boolean judging){
        return parse(mapper,raw,compression,judging,null);
    }
    static JsonNode parse(ObjectMapper mapper,String raw,boolean compression,boolean judging,Integer phase){
        var fields=new LinkedHashMap<String,String>();
        for(String line:framedText(raw).split("\\R")){if(line.isBlank())continue;int split=line.indexOf('=');if(split<1)throw invalid("LINE");
            String key=line.substring(0,split).strip(),value=line.substring(split+1).strip();
            if(key.startsWith("impacts."))key="impact."+key.substring(8);
            if(key.matches("(?:newsImpact|news|impact)\\.[1-4]\\.(?:newsId|horizon|direction|reason|counter)"))key=key.replaceFirst("^[^.]+","impact");
            if(key.matches("[1-4]\\.(?:newsId|horizon|direction|reason|counter)"))key="impact."+key;
            String previous=fields.putIfAbsent(key,value);if(previous!=null&&!previous.equals(value))throw invalid("CONFLICT_"+fieldCode(key));}
        String marker=fields.remove("protocol");
        if(marker!=null&&!Objects.equals(marker,compression?COMPRESSION_VERSION:VERSION)||compression&&marker==null)throw invalid();
        var result=mapper.createObjectNode();
        if(compression){if(fields.size()!=1||!fields.containsKey("summary"))throw invalid();return result.put("summary",fields.get("summary"));}
        String echoed=fields.remove("phase");if(echoed!=null&&(phase==null||!echoed.equals(phase.toString())))throw invalid("PHASE");
        String reason=fields.remove("plan.reason");
        if(reason!=null&&judging)throw invalid("JUDGE_PLAN");
        result.putArray("quotedMessages");result.putArray("evidenceRefs");var impacts=result.putArray("impacts");
        var groups=new TreeMap<Integer,ObjectNode>();
        for(var entry:fields.entrySet()){
            String key=entry.getKey(),value=entry.getValue();
            if(TEXT_FIELDS.contains(key)){if(!judging&&key.equals("selectedCandidateId"))throw invalid("RESEARCH_VOTE");result.put(key,value);}
            else if(ARRAY_FIELDS.contains(key))readIds(mapper,result,key,value);
            else if(key.equals("impacts")&&(value.equals("[]")||value.equals("null")||value.isBlank())){}
            else if(key.startsWith("plan.")&&!judging&&PLAN_FIELDS.contains(key.substring(5))){
                var plan=result.withObject("plan");if(value.isBlank()||value.equals("null"))plan.putNull(key.substring(5));else plan.put(key.substring(5),value);}
            else if(key.matches("impact\\.[1-4]\\.(?:newsId|horizon|direction|reason|counter)")){
                var parts=key.split("\\.");groups.computeIfAbsent(Integer.parseInt(parts[1]),index->mapper.createObjectNode()).put(parts[2],value);}
            else throw invalid(key.startsWith("plan.")&&judging?"JUDGE_PLAN":"UNKNOWN_FIELD_"+fieldCode(key));
        }
        if(fields.containsKey("impacts")&&!groups.isEmpty())throw invalid("CONFLICT_IMPACTS");
        for(var group:groups.values()){
            if(emptyImpact(group))continue;
            if(IMPACT_FIELDS.stream().anyMatch(field->!group.has(field)))throw new IllegalStateException("INVALID_IMPACT");impacts.add(group);
        }
        applyReason(result,reason);
        if(!judging&&(!result.path("plan").isObject()||result.path("plan").path("action").asText().isBlank()))throw new IllegalStateException("MISSING_REQUIRED_OUTPUT_PLAN_ACTION");
        if(judging&&result.path("selectedCandidateId").asText().isBlank())throw new IllegalStateException("MISSING_JUDGE_VOTE");
        return result;
    }
    static JsonNode normalize(JsonNode output,boolean compression,boolean judging,Integer phase){
        if(compression||!output.isObject())return output;
        var result=(ObjectNode)output.deepCopy();var echoed=result.remove("phase");
        if(echoed!=null&&(phase==null||!echoed.asText().equals(phase.toString())))throw invalid("PHASE");
        if(judging&&result.has("plan"))throw invalid("JUDGE_PLAN");
        if(result.path("impacts").isArray()){
            var original=result.path("impacts");var impacts=result.putArray("impacts");for(var group:original)if(!emptyImpact(group))impacts.add(group);
        }
        if(result.path("plan") instanceof ObjectNode plan){var reason=plan.remove("reason");if(reason!=null){
            if(!reason.isTextual())throw invalid("UNKNOWN_FIELD");applyReason(result,reason.asText());}}
        if(!judging&&result.path("plan").path("action").asText().isBlank())throw new IllegalStateException("MISSING_REQUIRED_OUTPUT_PLAN_ACTION");
        return result;
    }
    private static boolean emptyImpact(JsonNode group){return group.isObject()&&group.properties().stream().allMatch(entry->
        IMPACT_FIELDS.contains(entry.getKey())&&(entry.getValue().isNull()||entry.getValue().isTextual()&&entry.getValue().asText().isBlank()));}
    private static void applyReason(ObjectNode result,String reason){
        if(reason==null||reason.isBlank())return;
        var plan=result.withObject("plan");String risk=plan.path("riskAssessment").asText();
        if(risk.isBlank())plan.put("riskAssessment",reason);
        else if(!risk.equals(reason)){
            if(result.path("output").asText().isBlank())throw new IllegalStateException("MISSING_REQUIRED_OUTPUT_OUTPUT");
            result.put("output",result.path("output").asText()+"；补充理由："+reason);}
    }
    private static void readIds(ObjectMapper mapper,ObjectNode result,String key,String value){
        var array=result.putArray(key);if(value.isBlank()||value.equals("null"))return;
        if(value.startsWith("[")){JsonNode ids;try{ids=mapper.readTree(value);}catch(RuntimeException failure){throw invalid("LIST");}
            if(!ids.isArray())throw invalid("LIST");for(var id:ids){if(!id.isTextual()||id.asText().isBlank())throw invalid("LIST");array.add(id.asText());}return;}
        for(String id:value.split(",",-1)){if(id.isBlank())throw invalid("LIST");array.add(id.strip());}
    }
    private static String fieldCode(String key){
        if(TEXT_FIELDS.contains(key)||ARRAY_FIELDS.contains(key)||key.equals("protocol")||key.equals("phase")||key.equals("summary"))return key.toUpperCase(Locale.ROOT);
        if(key.startsWith("plan.")&&PLAN_FIELDS.contains(key.substring(5)))return key.replace('.','_').toUpperCase(Locale.ROOT);
        return key.matches("[A-Za-z][A-Za-z0-9_.]{0,15}")?key.replace('.','_').toUpperCase(Locale.ROOT):"FIELD";
    }
    static String repairInstruction(String error){
        String detail;
        if(error.startsWith("PLAN_NARRATIVE_TOO_LONG"))detail="plan.riskAssessment≤120字，plan.invalidate≤80字；详情写output。";
        else if(error.startsWith("MISSING_REQUIRED_OUTPUT_PLAN_ACTION"))detail="必须明确提交plan.action=allowedActions内动作。";
        else if(error.startsWith("INVALID_DIRECTION"))detail="两方向只用BULLISH/BEARISH/UNKNOWN/MIXED。";
        else if(error.equals("INVALID_EVIDENCE_ARRAY"))detail="quotedMessages最多6个原id，evidenceRefs最多14个原id，impact最多4组；只选相关引用，无项留空。";
        else if(error.equals("INVALID_IMPACT"))detail="impact每组完整提交newsId/horizon/direction/reason/counter；newsId只抄news.id，horizon=SHORT/LONG，direction按协议；无判断省略整组。";
        else if(error.equals("INVALID_EVIDENCE_REFERENCE")||error.equals("INVALID_NEWS_REFERENCE")||error.equals("INVALID_PREVIOUS_STAGE_REFERENCE"))detail="引用只选submissionIds中对应列表的完整原id，不用YAML锚点id001等，不确定留空。";
        else if(error.equals("INVALID_JUDGE_CANDIDATE")||error.equals("MISSING_JUDGE_VOTE"))detail="selectedCandidateId=WAIT或修订peers.id，禁止plan。";
        else if(error.startsWith("INVALID_STRUCTURED_OUTPUT"))detail="只用协议列出的key=value，不回显输入，同字段不可冲突。";
        else detail="按协议完整重交，方向、数值/id不猜补。";
        return detail;
    }
    private static IllegalStateException invalid(String detail){return new IllegalStateException("INVALID_STRUCTURED_OUTPUT_"+detail);}
    private static IllegalStateException invalid(){return new IllegalStateException("INVALID_STRUCTURED_OUTPUT");}
}
