package com.goldcatcher;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import tools.jackson.databind.ObjectMapper;

/** Detect a complete error event while receiving, including streams that never send DONE. */
final class SseTerminal {
    private static final ObjectMapper MAPPER=new ObjectMapper();
    private final ByteArrayOutputStream line=new ByteArrayOutputStream();
    private final StringBuilder data=new StringBuilder();
    private String event="";
    boolean accept(byte[] bytes){
        for(byte value:bytes){
            if(value=='\r')continue;
            if(value!='\n'){if(line.size()<1_000_000)line.write(value);continue;}
            String field=line.toString(StandardCharsets.UTF_8);line.reset();
            if(field.isEmpty()){
                boolean failure=event.equals("error");
                if(!failure&&!data.isEmpty())try{failure=MAPPER.readTree(data.toString()).has("error");}catch(RuntimeException malformed){/* The protocol decoder rejects malformed events. */}
                data.setLength(0);event="";if(failure)return true;
            }else if(field.startsWith("event:"))event=field.substring(6).strip();
            else if(field.startsWith("data:")){if(!data.isEmpty())data.append('\n');String fragment=field.substring(5);data.append(fragment.startsWith(" ")?fragment.substring(1):fragment);}
        }
        return false;
    }
}
