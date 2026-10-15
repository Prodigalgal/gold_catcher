package com.goldcatcher.research;

import com.goldcatcher.EvidenceTable;
import java.math.BigDecimal;
import java.util.*;
import tools.jackson.databind.*;
import tools.jackson.databind.node.*;

/** Model-facing facts only. Archive identities and workflow state never cross this boundary. */
final class ModelInput {
    private static final Set<String> ARCHIVE_FIELDS=Set.of("id","orderId","sourceId","metricId","newsId","actorId","actor",
        "evidenceIds","evidenceRefs","quotedMessages","selectedCandidateId","bodyHash","sourceTextHash","hash","version",
        "extractionVersion","extractedAt","calculatedAt","sequence","receivedAt","expectationReceivedAt","method","reference","url","link");
    private static final List<String> TARGET_FIELDS=List.of("side","type","quantity","leverage","entryPrice");
    private static final Set<String> PROSE_FIELDS=Set.of("title","output","changes","riskAssessment","invalidate","reason","counter","note");
    private ModelInput() {}

    static JsonNode research(ObjectMapper mapper,JsonNode packet) {
        var result=mapper.createObjectNode();var evidence=packet.path("evidence");var facts=result.putObject("evidence");
        for(String key:List.of("symbol","horizon","quote","indicators","indicatorPrecision","rules","marketProvenance","account",
            "costConfiguration","quantityUnit","marginReference","costLimitations","calendar"))
            if(evidence.has(key))facts.set(key,clean(mapper,evidence.path(key)));
        if(evidence.has("evidenceCutoff"))facts.set("asOf",evidence.path("evidenceCutoff"));
        // Received time is relevant to quote freshness when the source does not supply a tick time.
        if(evidence.path("quote").has("receivedAt"))facts.withObject("quote").set("observedAt",evidence.path("quote").path("receivedAt"));
        var news=facts.putArray("news");for(var article:evidence.path("news")) {
            String summary=article.isTextual()?article.asText():article.path("excerpt").asText();
            if(!summary.isBlank())news.add(summary);
        }
        if(evidence.path("analysis").isObject())facts.set("analysis",analysis(mapper,evidence.path("analysis")));
        var peers=result.putArray("peers");for(var peer:packet.path("peers")) {
            var output=peer.has("output")?peer.path("output"):peer;
            var view=mapper.createObjectNode();
            for(String key:List.of("title","shortDirection","longDirection","output","changes"))if(output.has(key))view.put(key,prose(output.path(key).asText()));
            if(output.path("plan").isObject())view.set("plan",plan(mapper,output.path("plan"),evidence));
            // Legacy article-linked judgments are prose, without an identifier or a fabricated association.
            if(output.path("impacts").isArray())view.set("impacts",clean(mapper,output.path("impacts")));
            peers.add(view);
        }
        return result;
    }

    static JsonNode compression(ObjectMapper mapper,JsonNode packet) {
        var result=mapper.createObjectNode();for(String key:List.of("title","text","summaries"))if(packet.has(key))result.set(key,packet.path(key));
        return result;
    }

    static String instruction(JsonNode packet,boolean compression) {
        if(compression)return (packet.path("task").asText().equals("MERGE")?"\n合并同一原文的分块摘要，去重并保留不同条件与反证。":"")+
            (Set.of("FEED_SUMMARY","HEADLINE_ONLY").contains(packet.path("sourceFormat").asText())?"\n材料仅为来源摘要或标题，注明范围，不冒充全文。":"");
        String phase=switch(packet.path("phase").asInt(-1)){case 0->"独立初审";case 1->"交叉质疑";case 2->"修订完整方案";case 3->"独立裁决";default->"研究";};
        String result="\n当前任务："+phase+"。";
        if(!packet.path("role").asText().isBlank())result+="研究重点："+packet.path("role").asText()+"。";
        // Policies stay in Java. Only the applicable financial task is stated as a trusted instruction.
        if(ResearchProtocol.onlyWait(packet))result+="当前只进行形势观察，返回WAIT。";
        String correction=packet.path("formatCorrection").path("instruction").asText();
        if(!correction.isBlank())result+="\n修正上次格式："+correction;
        return result;
    }

    static JsonNode plan(ObjectMapper mapper,JsonNode original,JsonNode evidence) {
        var result=(ObjectNode)clean(mapper,original);
        for(String key:List.of("riskAssessment","invalidate"))if(result.path(key).isTextual())result.put(key,prose(result.path(key).asText()));
        if(original.hasNonNull("orderId")) {
            var orders=records(mapper,evidence.path("account").path("orders"));
            for(var order:orders)if(order.path("id").asText().equals(original.path("orderId").asText()))result.set("target",target(mapper,order));
        }
        return result;
    }

    /** Bind only an unambiguous frozen financial tuple. A model never supplies an order identity. */
    static JsonNode bindOrder(ObjectMapper mapper,JsonNode plan,JsonNode evidence) {
        if(!Set.of("CANCEL","CLOSE","REDUCE","MODIFY").contains(plan.path("action").asText()))return plan;
        if(plan.hasNonNull("orderId"))return plan; // Previously sealed, archived plans remain readable.
        var target=plan.path("target");
        if(!target.isObject()||TARGET_FIELDS.stream().anyMatch(key->!target.hasNonNull(key)))throw new IllegalStateException("MISSING_ORDER_TARGET");
        var matches=new ArrayList<JsonNode>();for(var order:records(mapper,evidence.path("account").path("orders"))) {
            boolean same=true;for(String key:TARGET_FIELDS)if(!equal(key,target.path(key),order.path(key)))same=false;
            if(same)matches.add(order);
        }
        if(matches.size()!=1)throw new IllegalStateException(matches.isEmpty()?"ORDER_TARGET_NOT_FOUND":"AMBIGUOUS_ORDER_TARGET");
        var result=(ObjectNode)plan.deepCopy();result.put("orderId",matches.getFirst().path("id").asText());return result;
    }
    private static ObjectNode target(ObjectMapper mapper,JsonNode order){var target=mapper.createObjectNode();for(String key:TARGET_FIELDS)target.set(key,order.path(key));return target;}
    private static boolean equal(String field,JsonNode a,JsonNode b){
        if(Set.of("side","type").contains(field))return a.equals(b);
        try{return new BigDecimal(a.asText()).compareTo(new BigDecimal(b.asText()))==0;}catch(NumberFormatException invalid){return false;}
    }

    private static JsonNode analysis(ObjectMapper mapper,JsonNode source) {
        var result=mapper.createObjectNode();
        for(String key:List.of("drivers","driverTimeMeaning","expectations","expectationMeaning","precision","expectationSelection","publicFacts","publicScope","gaps"))
            if(source.has(key))result.set(key,clean(mapper,source.path(key)));
        // Resolve the archive's joins in Java, then discard every join key.
        var metadata=new HashMap<String,JsonNode>();for(var row:records(mapper,source.path("releaseSources")))metadata.put(row.path("id").asText(),row);
        var releases=result.putArray("releases");for(var group:source.path("releases")) {
            if(!group.has("csv")){releases.add(clean(mapper,group));continue;}
            var row=mapper.createObjectNode();var meta=metadata.get(group.path("sourceId").asText());
            if(meta==null)throw new IllegalStateException("MISSING_RELEASE_SOURCE");
            for(String key:List.of("source","period","publishedAt","precision"))if(meta.has(key))row.set(key,meta.path(key));
            var table=mapper.createObjectNode();table.set("columns",source.path("metricColumns"));table.set("csv",group.path("csv"));
            row.set("metrics",clean(mapper,table));releases.add(row);
        }
        if(source.has("revisions"))result.set("revisions",clean(mapper,source.path("revisions")));
        return result;
    }

    private static JsonNode clean(ObjectMapper mapper,JsonNode node) {
        if(node.isObject()&&node.path("columns").isArray()&&node.path("csv").isTextual()) {
            var rows=mapper.createArrayNode();var columns=new LinkedHashSet<String>();
            for(var record:records(mapper,node)) {
                var row=mapper.createObjectNode();for(var field:record.properties()) {
                    String key=field.getKey();var value=field.getValue();
                    if((key.equals("id")||key.equals("metricId"))&&(value.asText().startsWith("econ:")||value.asText().startsWith("US_")||value.asText().startsWith("GATE_"))) {
                        row.put("name",name(value.asText()));continue;
                    }
                    if(archiveField(key))continue;
                    if(key.equals("indicator"))row.put(key,value.asText().startsWith("UNMAPPED:")?"未映射指标，仅使用标题及原文值":name(value.asText()));
                    else row.set(key,field(mapper,key,value));
                }
                columns.addAll(row.propertyNames());rows.add(row);
            }
            return mapper.valueToTree(EvidenceTable.pack(mapper,rows,List.copyOf(columns)));
        }
        if(node.isObject()){var result=mapper.createObjectNode();node.properties().forEach(field->{if(!archiveField(field.getKey()))result.set(field.getKey(),field(mapper,field.getKey(),field.getValue()));});return result;}
        if(node.isArray()){var result=mapper.createArrayNode();node.forEach(value->result.add(clean(mapper,value)));return result;}
        if(node.isTextual())return mapper.getNodeFactory().stringNode(names(node.asText()));
        return node.deepCopy();
    }
    private static JsonNode field(ObjectMapper mapper,String key,JsonNode value){return value.isTextual()&&PROSE_FIELDS.contains(key)?mapper.getNodeFactory().stringNode(prose(value.asText())):clean(mapper,value);}
    private static boolean archiveField(String key){return ARCHIVE_FIELDS.contains(key)||key.endsWith("Id")||key.endsWith("Ids")||key.endsWith("Hash")||key.endsWith("_id")||key.endsWith("_ids");}
    private static String names(String text){return java.util.regex.Pattern.compile("econ:[A-Z][A-Z0-9_]*|US_(?:INTRADAY_)?(?:NOMINAL_(?:2Y|10Y)|REAL_10Y)|GATE_(?:EURUSD|USDJPY|USIDX)").matcher(text).replaceAll(match->name(match.group()));}
    private static String prose(String text){return names(text).replaceAll("(?i)(?<![a-z0-9_])(?:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}(?:-[0-3]-[A-Z][0-9]?-\\d+)?|(?=[0-9a-f]*[a-f])[0-9a-f]{32,64}|(?:market|analysis):[a-z_]+|(?:pub|ff):\\d+|s\\d+)(?![a-z0-9_])", "");}

    static String name(String code){return switch(code.replace("econ:","")){
        case "GATE_EURUSD"->"欧元/美元";case "GATE_USDJPY"->"美元/日元";case "GATE_USIDX"->"Gate美元指数CFD";
        case "US_INTRADAY_NOMINAL_2Y"->"美债2年名义收益率（公开盘中）";case "US_INTRADAY_NOMINAL_10Y"->"美债10年名义收益率（公开盘中）";case "US_INTRADAY_REAL_10Y"->"美债10年实际收益率（公开盘中）";
        case "US_NOMINAL_2Y"->"美债2年名义收益率（日频）";case "US_NOMINAL_10Y"->"美债10年名义收益率（日频）";case "US_REAL_10Y"->"美债10年实际收益率（日频）";
        case "CPI_YOY"->"CPI同比";case "CORE_CPI_YOY"->"核心CPI同比";case "CPI_MOM"->"CPI环比";case "CORE_CPI_MOM"->"核心CPI环比";
        case "WAGES_MOM"->"平均时薪环比";case "UNEMPLOYMENT"->"失业率";case "NFP"->"非农就业变动";case "PPI_MOM"->"PPI环比";case "JOLTS"->"职位空缺";
        case "GDP_ADVANCE"->"GDP初值";case "GDP_SECOND"->"GDP第二次估计";case "GDP_THIRD"->"GDP第三次估计";
        case "PCE_YOY"->"PCE同比";case "CORE_PCE_YOY"->"核心PCE同比";case "PCE_MOM"->"PCE环比";case "CORE_PCE_MOM"->"核心PCE环比";
        default->code.replace("econ:","").replace('_',' ');
    };}

    static ArrayNode records(ObjectMapper mapper,JsonNode table) {
        if(table.isArray())return (ArrayNode)table.deepCopy();
        var result=mapper.createArrayNode();if(!table.path("csv").isTextual()||table.path("csv").asText().isEmpty())return result;
        var rows=csv(table.path("csv").asText());for(var cells:rows) {
            if(cells.size()!=table.path("columns").size())throw new IllegalStateException("INVALID_EVIDENCE_TABLE");
            var record=result.addObject();if(table.path("common").isObject())record.setAll((ObjectNode)table.path("common"));
            for(int i=0;i<cells.size();i++){String key=table.path("columns").path(i).asText(),value=cells.get(i);if(value==null)record.putNull(key);else record.put(key,value);}
        }
        return result;
    }
    private static List<List<String>> csv(String text){
        var rows=new ArrayList<List<String>>();var row=new ArrayList<String>();var cell=new StringBuilder();boolean quoted=false,wasQuoted=false;
        for(int i=0;i<text.length();i++){char c=text.charAt(i);
            if(quoted){if(c=='"'&&i+1<text.length()&&text.charAt(i+1)=='"'){cell.append('"');i++;}else if(c=='"')quoted=false;else cell.append(c);}
            else if(c=='"'){quoted=true;wasQuoted=true;}
            else if(c==','||c=='\n'){row.add(cell.isEmpty()&&!wasQuoted?null:cell.toString());cell.setLength(0);wasQuoted=false;if(c=='\n'){rows.add(row);row=new ArrayList<>();}}
            else cell.append(c);
        }
        if(quoted)throw new IllegalStateException("INVALID_EVIDENCE_TABLE");row.add(cell.isEmpty()&&!wasQuoted?null:cell.toString());rows.add(row);return rows;
    }
}
