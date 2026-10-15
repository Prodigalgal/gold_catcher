package com.goldcatcher.research;

import java.util.*;
import tools.jackson.databind.*;

/** Frozen configuration + facts -> exact request bytes. No catalog refresh, settings, clock or HTTP here. */
final class InferenceRequest {
    static final String VERSION="INFERENCE_REQUEST_V1";
    record Input(String version,String model,String provider,int outputReserve,boolean preferJson,boolean generic,
        boolean compression,String lane,JsonNode capabilities,ContextWindow.Limits limits,String instruction,JsonNode packet) {}
    record Plan(Input input,ModelDataFlow.Result flow,JsonNode request,ContextWindow.Prepared prepared,
        boolean unified,boolean adjudication,boolean structuredTool,boolean outputRequested) {}
    private InferenceRequest() {}
    static Plan prepare(ObjectMapper mapper,Input input){
        if(!VERSION.equals(input.version()))throw new IllegalArgumentException("UNSUPPORTED_REQUEST_VERSION");
        var packet=input.packet();var capabilities=input.capabilities();boolean compression=input.compression();
        boolean adjudication=!compression&&packet.path("phase").asInt(-1)==3;
        boolean unified=Set.of(ResearchProtocol.VERSION,ModelProtocol.VERSION,ResearchProtocol.COMPRESSION_VERSION).contains(packet.path("protocol").asText());
        boolean facts=compression||unified;
        var flow=facts?ModelDataFlow.project(mapper,packet,compression):new ModelDataFlow.Result(ModelDataFlow.VERSION,"LEGACY",packet.deepCopy(),List.of());
        String instruction=input.instruction()+(facts?ModelInput.instruction(packet,compression)+ModelDataFlow.instruction(flow.output()):"");
        var request=new LinkedHashMap<String,Object>();request.put("model",input.model());request.put("stream",true);request.put("stream_options",Map.of("include_usage",true));
        var parameters=capabilities.path("supported_parameters").path("chat_completions");
        boolean structuredTool=!compression&&!input.preferJson()&&capabilities.path("tools").path("function_calling").asBoolean(false)
            &&capabilities.path("tools").path("strict").asBoolean(false)&&!capabilities.path("tools").path("bridge").asText().equals("emulated_function_bridge");
        boolean jsonMode=capabilities.path("structured_output").asBoolean(false)||capabilities.path("structured_output").path("supported").asBoolean(false);
        if(!structuredTool&&jsonMode&&!unified)for(var parameter:parameters)if(parameter.asText().equals("response_format"))request.put("response_format",Map.of("type","json_object"));
        if(unified&&!compression)instruction+=ModelProtocol.instruction(adjudication,structuredTool,ResearchProtocol.onlyWait(packet));
        var options=new LinkedHashMap<String,Object>();
        if(input.provider().equals("minmax")&&capabilities.path("provider_options").has("enable_team"))options.put("enable_team",false);
        if(capabilities.path("provider_options").has("web_search_status")&&capabilities.path("parameter_adaptation").path("search").path("mode").asText().equals("mapped"))options.put("web_search_status","disabled");
        if((compression||input.lane().equals("fast_lane"))&&capabilities.path("provider_options").has("thinking"))options.put("thinking",false);
        if(input.lane().equals("fast_lane")&&input.provider().equals("minmax")&&Set.of("mapped","toggle_mapping").contains(capabilities.path("parameter_adaptation").path("reasoning").path("mode").asText()))request.put("reasoning_effort","none");
        if(!options.isEmpty())request.put("provider_options",Map.of(input.provider(),options));
        if(structuredTool&&!unified){int format=instruction.indexOf("格式："),wait=instruction.indexOf("WAIT 时");if(format>=0&&wait>format)instruction=instruction.substring(0,format)+instruction.substring(wait);}
        request.put("messages",List.of(Map.of("role","system","content",instruction),Map.of("role","user","content",mapper.writeValueAsString(flow.output()))));
        if(structuredTool){request.put("tools",List.of(Map.of("type","function","function",Map.of("name","submit_research","description","提交本阶段研究结果，只有数据归档，无外部交易行为","strict",true,"parameters",unified?ModelProtocol.schema():ResearchProtocol.schema(adjudication)))));
            request.put("tool_choice",Map.of("type","function","function",Map.of("name","submit_research")));}
        String output=null;for(String candidate:List.of("max_tokens","max_completion_tokens","max_output_tokens"))for(var parameter:parameters)
            if(parameter.asText().equals(candidate)&&capabilities.path("parameter_adaptation").path(candidate).path("mode").asText().equals("mapped")){output=candidate;break;}
        if(input.generic())output="max_tokens";if(output!=null)request.put(output,input.outputReserve());
        var prepared=facts?ContextWindow.prepareModel(mapper,request,flow.output(),input.limits()):ContextWindow.prepare(mapper,request,packet,input.limits());
        return new Plan(input,flow,mapper.valueToTree(request),prepared,unified,adjudication,structuredTool,output!=null);
    }
}
