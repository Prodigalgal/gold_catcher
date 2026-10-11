package com.goldcatcher.research;

import static org.assertj.core.api.Assertions.*;
import com.goldcatcher.TestSupport;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

class OpenAiStreamTest {
    private final TestSupport f=new TestSupport();
    private String frame(Object delta,String finish){var choice=new LinkedHashMap<String,Object>();choice.put("index",0);choice.put("delta",delta);choice.put("finish_reason",finish);
        return "data: "+f.mapper.writeValueAsString(Map.of("model","test","choices",List.of(choice)))+"\n\n";}
    private tools.jackson.databind.JsonNode decode(String text){return OpenAiStream.decode(f.mapper,text.getBytes(StandardCharsets.UTF_8));}
    @Test void combinesThinkingContentUsageAndHeartbeatsWithoutMixingThinkingIntoTheAnswer(){
        var envelope=decode(": request_id=opaque\r\n\r\n"+frame(Map.of("role","assistant"),null)+frame(Map.of("reasoning_content","先核对价格"),null)
            +": heartbeat\n\n"+frame(Map.of("content","protocol=GOLD_COMPRESSION_V1\n"),null)+frame(Map.of("content","summary=价格未变。"),null)
            +frame(Map.of(),"stop")+"data: {\"choices\":[],\"usage\":{\"total_tokens\":123,\"usage_source\":\"REPORTED\"}}\n\ndata: [DONE]\n\n");
        assertThat(envelope.path("choices").path(0).path("message").path("reasoning_content").asText()).isEqualTo("先核对价格");
        assertThat(envelope.path("choices").path(0).path("message").path("content").asText()).isEqualTo("protocol=GOLD_COMPRESSION_V1\nsummary=价格未变。");
        assertThat(envelope.path("usage").path("total_tokens").asInt()).isEqualTo(123);
    }
    @Test void joinsNativeToolArgumentsExactly(){
        String start=frame(Map.of("tool_calls",List.of(Map.of("index",0,"id","call-a","type","function","function",Map.of("name","submit_research","arguments","{\"plan\":")))),null);
        String end=frame(Map.of("tool_calls",List.of(Map.of("index",0,"function",Map.of("arguments","{\"action\":\"WAIT\"}}")))),null);
        var envelope=decode(start+end+frame(Map.of(),"tool_calls")+"data: [DONE]\n\n");
        var function=envelope.path("choices").path(0).path("message").path("tool_calls").path(0).path("function");
        assertThat(function.path("name").asText()).isEqualTo("submit_research");
        assertThat(function.path("arguments").asText()).isEqualTo("{\"plan\":{\"action\":\"WAIT\"}}");
    }
    @Test void rejectsInterruptedStreamsEvenWhenTheyAlreadyContainValidLookingJson(){
        String answer=frame(Map.of("content","{\"plan\":{\"action\":\"WAIT\"}}"),null);
        for(String broken:List.of(answer,answer+frame(Map.of(),"stop"),answer+"data: [DONE]\n\n",answer+frame(Map.of(),"stop")+"data: [DONE]"))
            assertThatThrownBy(()->decode(broken)).hasMessage("STREAM_INCOMPLETE");
        assertThatThrownBy(()->decode(answer+"data: {\"error\":{\"code\":\"UPSTREAM_TIMEOUT\"}}\n\ndata: [DONE]\n\n"))
            .hasMessage("INFERENCE_STREAM_ERROR_UPSTREAM_TIMEOUT");
    }
    @Test void rejectsConflictingOrCorruptFramesAndAllowsACompleteJsonFallback(){
        assertThatThrownBy(()->decode(frame(Map.of(),"stop")+frame(Map.of("content","late text"),null)+"data: [DONE]\n\n"))
            .hasMessage("INVALID_STREAM_SEQUENCE");
        assertThatThrownBy(()->decode("data: not-json\n\ndata: [DONE]\n\n")).hasMessage("INVALID_STREAM_JSON");
        assertThatThrownBy(()->OpenAiStream.decode(f.mapper,new byte[]{(byte)0xc3,(byte)0x28})).hasMessage("INVALID_STREAM_UTF8");
        assertThat(decode("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"complete\"}}]}").path("choices").size()).isEqualTo(1);
    }
}
