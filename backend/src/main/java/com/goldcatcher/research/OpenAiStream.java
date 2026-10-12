package com.goldcatcher.research;

import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;

/** Fold bounded OpenAI SSE frames into the same envelope used by non-streaming adapters. */
final class OpenAiStream {
    private final ObjectMapper mapper;
    private final StringBuilder content=new StringBuilder(),reasoning=new StringBuilder();
    private final Map<Integer,ObjectNode> tools=new TreeMap<>();
    private final ObjectNode envelope;
    private String finish,model;
    private boolean done;
    private int frames;
    private long outputBytes;
    private static final long MAX_OUTPUT_BYTES=1_000_000;
    private OpenAiStream(ObjectMapper mapper){this.mapper=mapper;envelope=mapper.createObjectNode();}

    static JsonNode decode(ObjectMapper mapper,byte[] bytes){
        String text;
        try{text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}
        catch(CharacterCodingException bad){throw new IllegalStateException("INVALID_STREAM_UTF8");}
        String trimmed=text.stripLeading();
        // Some compatible providers ignore stream:true and return one complete JSON envelope.
        if(trimmed.startsWith("{")){
            var envelope=mapper.readTree(text);var guard=new OpenAiStream(mapper);
            for(var choice:envelope.path("choices")){
                var message=choice.path("message");guard.account(message.path("content").asText());guard.account(message.path("reasoning_content").asText());
                for(var call:message.path("tool_calls")){guard.account(call.path("function").path("name").asText());guard.account(call.path("function").path("arguments").asText());}
            }
            return envelope;
        }
        var stream=new OpenAiStream(mapper);var data=new StringBuilder();String event="";
        for(String line:text.replace("\r\n","\n").replace('\r','\n').split("\n",-1)){
            if(line.isEmpty()){if(event.equals("error"))throw streamFailure(mapper,data.toString());if(!data.isEmpty()){stream.frame(data.toString());data.setLength(0);}event="";continue;}
            if(line.startsWith(":"))continue;
            int colon=line.indexOf(':');String field=colon<0?line:line.substring(0,colon);
            String value=colon<0?"":line.substring(colon+1);if(value.startsWith(" "))value=value.substring(1);
            if(field.equals("data")){if(!data.isEmpty())data.append('\n');data.append(value);}
            else if(field.equals("event"))event=value.strip();
        }
        // EOF does not dispatch an unterminated SSE event; accepting it could turn a broken reply into a vote.
        if(!data.isEmpty()||!stream.done||stream.finish==null)throw new IllegalStateException("STREAM_INCOMPLETE");
        var message=mapper.createObjectNode();message.put("content",stream.content.toString());message.put("reasoning_content",stream.reasoning.toString());
        if(!stream.tools.isEmpty()){var calls=message.putArray("tool_calls");stream.tools.values().forEach(calls::add);}
        var choice=stream.envelope.putArray("choices").addObject();choice.put("index",0);choice.put("finish_reason",stream.finish);choice.set("message",message);
        if(stream.model!=null)stream.envelope.put("model",stream.model);
        return stream.envelope;
    }
    private static IllegalStateException streamFailure(ObjectMapper mapper,String data){
        String code="";try{var item=mapper.readTree(data);code=(item.has("error")?item.path("error"):item).path("code").asText().toUpperCase(Locale.ROOT);}catch(RuntimeException malformed){}
        return new IllegalStateException("INFERENCE_STREAM_ERROR"+(code.matches("[A-Z0-9_]{1,60}")?"_"+code:""));
    }
    private void frame(String data){
        if(++frames>10000||data.length()>262144)throw new IllegalStateException("STREAM_FRAME_LIMIT");
        if(data.equals("[DONE]")){if(done)throw new IllegalStateException("INVALID_STREAM_SEQUENCE");done=true;return;}
        if(done)throw new IllegalStateException("INVALID_STREAM_SEQUENCE");
        JsonNode item;
        try{item=mapper.readTree(data);}catch(RuntimeException malformed){throw new IllegalStateException("INVALID_STREAM_JSON");}
        if(!item.isObject())throw new IllegalStateException("INVALID_STREAM_JSON");
        if(item.has("error")){
            String code=item.path("error").path("code").asText().toUpperCase(Locale.ROOT);
            throw new IllegalStateException("INFERENCE_STREAM_ERROR"+(code.matches("[A-Z0-9_]{1,60}")?"_"+code:""));
        }
        String declared=item.path("model").asText();
        if(!declared.isBlank()){if(model!=null&&!model.equals(declared))throw new IllegalStateException("INVALID_STREAM_MODEL");model=declared;}
        for(String key:List.of("usage","usage_source"))if(item.hasNonNull(key))envelope.set(key,item.get(key));
        var choices=item.path("choices");if(choices.isMissingNode())return;
        if(!choices.isArray()||choices.size()>1)throw new IllegalStateException("INVALID_STREAM_CHOICES");
        if(choices.isEmpty())return;var choice=choices.get(0);
        if(choice.path("index").asInt(0)!=0)throw new IllegalStateException("INVALID_STREAM_CHOICES");
        var delta=choice.path("delta");
        if(finish!=null&&(!delta.path("content").asText().isEmpty()||!delta.path("reasoning_content").asText().isEmpty()||delta.has("tool_calls")))
            throw new IllegalStateException("INVALID_STREAM_SEQUENCE");
        append(content,delta,"content");append(reasoning,delta,"reasoning_content");
        var calls=delta.path("tool_calls");
        if(!calls.isMissingNode()&&!calls.isNull()){
            if(!calls.isArray())throw new IllegalStateException("INVALID_STREAM_TOOL_CALL");
            for(var part:calls){
                int index=part.path("index").asInt(-1);if(index<0||index>7)throw new IllegalStateException("INVALID_STREAM_TOOL_CALL");
                var target=tools.computeIfAbsent(index,key->{var node=mapper.createObjectNode();node.putObject("function");return node;});
                for(String key:List.of("id","type"))if(part.hasNonNull(key)){
                    if(!part.path(key).isTextual()||target.has(key)&&!target.path(key).equals(part.path(key)))throw new IllegalStateException("INVALID_STREAM_TOOL_CALL");
                    target.set(key,part.get(key));
                }
                var function=(ObjectNode)target.path("function");
                for(String key:List.of("name","arguments"))if(part.path("function").hasNonNull(key)){
                    if(!part.path("function").path(key).isTextual())throw new IllegalStateException("INVALID_STREAM_TOOL_CALL");
                    String fragment=part.path("function").path(key).asText();account(fragment);
                    function.put(key,function.path(key).asText()+fragment);
                }
            }
        }
        if(choice.hasNonNull("finish_reason")){
            String next=choice.path("finish_reason").asText();
            if(next.isBlank()||finish!=null&&!finish.equals(next))throw new IllegalStateException("INVALID_STREAM_SEQUENCE");finish=next;
        }
    }
    private void account(String fragment){outputBytes+=fragment.getBytes(StandardCharsets.UTF_8).length;if(outputBytes>MAX_OUTPUT_BYTES)throw new IllegalStateException("STREAM_OUTPUT_LIMIT");}
    private void append(StringBuilder target,JsonNode delta,String key){
        if(delta.hasNonNull(key)){if(!delta.path(key).isTextual())throw new IllegalStateException("INVALID_STREAM_DELTA");String fragment=delta.path(key).asText();account(fragment);target.append(fragment);}
    }
}
