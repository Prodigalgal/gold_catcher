package com.goldcatcher.research;

import com.goldcatcher.RuntimeSettings.AiRoute;
import java.nio.charset.StandardCharsets;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Counts the serialized request, including instructions and tool schema, before any HTTP call. */
final class ContextWindow {
    static final int SAFETY_TOKENS=512, TARGET_PERCENT=90;
    record Limits(long bytes,long characters,long inputEstimate,long declaredInput,long declaredContext,
        long declaredOutput,int messageLimit,int safetyTokens,int targetPercent) {}
    record Size(int bytes,int characters,long inputEstimate,int messages) {}
    record Change(String path,int originalCodePoints,int sentCodePoints) {}
    record Prepared(String body,JsonNode packet,Size original,Size sent,List<Change> changes) {}

    static Limits limits(AiRoute route,JsonNode catalog) {
        JsonNode capabilities=catalog.path("capabilities");
        long input=positive(capabilities,"max_input_tokens"),context=positive(capabilities,"max_context_tokens"),
            output=positive(capabilities,"max_output_tokens");
        long effectiveInput=route.inputEstimateLimit();
        if(input>0)effectiveInput=Math.min(effectiveInput,input);
        if(context>0)effectiveInput=Math.min(effectiveInput,Math.max(0,context-route.outputReserve()-SAFETY_TOKENS));
        return new Limits(route.byteLimit(),Math.min(route.characterLimit(),characterLimit(catalog)),effectiveInput,
            input,context,output,route.messageLimit(),SAFETY_TOKENS,TARGET_PERCENT);
    }
    static Size size(String body,int messages) {
        int bytes=body.getBytes(StandardCharsets.UTF_8).length;
        return new Size(bytes,body.codePointCount(0,body.length()),(bytes+2L)/3L,messages);
    }
    static boolean fits(Size size,Limits limits,boolean headroom) {
        int percent=headroom?limits.targetPercent():100;
        return size.bytes()<=limits.bytes()*percent/100&&size.characters()<=limits.characters()*percent/100
            &&size.inputEstimate()<=limits.inputEstimate()*percent/100&&size.messages()<=limits.messageLimit();
    }
    static int utilization(Size size,Limits limits) {
        double ratio=Math.max(size.bytes()/(double)Math.max(1,limits.bytes()),
            Math.max(size.characters()/(double)Math.max(1,limits.characters()),size.inputEstimate()/(double)Math.max(1,limits.inputEstimate())));
        return (int)Math.min(Integer.MAX_VALUE,Math.ceil(ratio*100));
    }
    static Prepared prepare(ObjectMapper mapper,Map<String,Object> request,Object packet,Limits limits) {
        return prepare(mapper,request,packet,limits,true);
    }
    static Prepared prepareModel(ObjectMapper mapper,Map<String,Object> request,Object packet,Limits limits) {
        return prepare(mapper,request,packet,limits,false);
    }
    private static Prepared prepare(ObjectMapper mapper,Map<String,Object> request,Object packet,Limits limits,boolean references) {
        JsonNode originalPacket=mapper.valueToTree(packet);String originalBody=body(mapper,request,originalPacket,references);
        int messages=((List<?>)request.get("messages")).size();Size original=size(originalBody,messages);
        Prepared last=new Prepared(originalBody,originalPacket,original,original,List.of());
        if(!references||fits(original,limits,true))return last;
        // Only optional prose is shortened. Already compressed news strings and financial facts stay intact.
        for(int level=1;level<=3;level++) {
            JsonNode selected=originalPacket.deepCopy();List<Change> changes=new ArrayList<>();
            int newsLimit=level==1?120:level==2?60:0,peerLimit=level==1?240:level==2?160:40;
            int changeLimit=level==1?80:level==2?40:0,impactLimit=level==1?140:level==2?80:40;
            int index=0;
            for(JsonNode article:selected.path("evidence").path("news"))
                shorten(article,"excerpt",newsLimit,"evidence.news["+(index++)+"].excerpt",changes);
            index=0;
            for(JsonNode release:selected.path("evidence").path("analysis").path("releases")) {
                String path="evidence.analysis.releases["+(index++)+"].";
                shorten(release,"excerpt",newsLimit,path+"excerpt",changes);
                int revision=0;for(JsonNode row:release.path("revisions"))shorten(row,"excerpt",newsLimit,path+"revisions["+(revision++)+"].excerpt",changes);
                int metricIndex=0;for(JsonNode metric:release.path("metrics")){
                    String metricPath=path+"metrics["+(metricIndex++)+"].";shorten(metric,"excerpt",newsLimit,metricPath+"excerpt",changes);
                    int revisionIndex=0;for(JsonNode row:metric.path("revisions"))shorten(row,"excerpt",newsLimit,metricPath+"revisions["+(revisionIndex++)+"].excerpt",changes);
                }
            }
            index=0;
            for(JsonNode driver:selected.path("evidence").path("analysis").path("drivers"))shorten(driver,"note",impactLimit,"evidence.analysis.drivers["+(index++)+"].note",changes);
            index=0;
            for(JsonNode peer:selected.path("peers")) {
                String path="peers["+(index++)+"].";JsonNode output=peer.path("output").isObject()?peer.path("output"):peer;
                shorten(output,"analysisSummary",peerLimit,path+"analysisSummary",changes);
                shorten(output,"output",peerLimit,path+"output",changes);
                shorten(output,"changes",changeLimit,path+"changes",changes);
                int impactIndex=0;
                for(JsonNode impact:output.path("impacts")) {
                    String impactPath=path+"impacts["+(impactIndex++)+"].";
                    shorten(impact,"reason",impactLimit,impactPath+"reason",changes);
                    shorten(impact,"counter",impactLimit,impactPath+"counter",changes);
                }
            }
            String serialized=body(mapper,request,selected,references);
            last=new Prepared(serialized,selected,original,size(serialized,messages),List.copyOf(changes));
            if(fits(last.sent(),limits,true))return last;
        }
        return last; // The caller records this failed preflight and does not send it.
    }
    private static String body(ObjectMapper mapper,Map<String,Object> request,JsonNode packet,boolean references) {
        Map<String,Object> selected=new LinkedHashMap<>(request);
        var messages=new ArrayList<Map<String,Object>>();
        for(Object message:(List<?>)request.get("messages")) {
            var node=mapper.valueToTree(message);Map<String,Object> copy=new LinkedHashMap<>();
            node.properties().forEach(field->copy.put(field.getKey(),field.getValue()));
            if(node.path("role").asText().equals("user"))copy.put("content",EvidenceWire.encode(mapper,packet,references));
            messages.add(copy);
        }
        selected.put("messages",messages);return com.goldcatcher.CanonicalJson.text(mapper,selected);
    }
    private static void shorten(JsonNode node,String field,int limit,String path,List<Change> changes) {
        if(!(node instanceof ObjectNode object)||!node.path(field).isTextual())return;
        String text=node.path(field).asText();int count=text.codePointCount(0,text.length());
        if(count<=limit)return;
        String excerpt=limit==0?"[摘要已省略，全文归档，不得补造内容]":
            text.substring(0,text.offsetByCodePoints(0,limit))+" [摘录，全文归档]";
        // Small text is left intact when a marker would be larger than the original.
        if(excerpt.getBytes(StandardCharsets.UTF_8).length>=text.getBytes(StandardCharsets.UTF_8).length)return;
        object.put(field,excerpt);changes.add(new Change(path,count,excerpt.codePointCount(0,excerpt.length())));
    }
    private static long positive(JsonNode node,String field){return Math.max(0,node.path(field).asLong(0));}
    private static long characterLimit(JsonNode node) {
        long own=positive(node,"input_character_limit"),limit=own>0?own:Long.MAX_VALUE;
        if(node.path("mode").asText().equals("declared_character_limit")&&positive(node,"limit")>0)limit=Math.min(limit,positive(node,"limit"));
        if(node.isObject()||node.isArray())for(JsonNode child:node)if(child.isObject()||child.isArray())limit=Math.min(limit,characterLimit(child));
        return limit;
    }
}
