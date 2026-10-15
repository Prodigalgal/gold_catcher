package com.goldcatcher.research;

import java.util.*;
import tools.jackson.databind.*;

/** Pure decoding/normalization/validation of a recorded complete provider response. */
final class ModelResponse {
    record Decoded(String model,String reasoning,String raw,JsonNode usage,String usageSource,String finish,boolean submitted) {}
    private ModelResponse() {}
    static Decoded extract(ObjectMapper mapper,byte[] response,String requestedModel){
        var envelope=OpenAiStream.decode(mapper,response);var choice=envelope.path("choices").path(0);var message=choice.path("message");
        String finish=choice.path("finish_reason").asText("UNKNOWN"),raw=message.path("content").asText();var calls=message.path("tool_calls");
        boolean submitted=finish.equals("tool_calls")&&calls.isArray()&&calls.size()==1&&calls.path(0).path("function").path("name").asText().equals("submit_research");
        if(submitted)raw=calls.path(0).path("function").path("arguments").asText();
        return new Decoded(envelope.path("model").asText(requestedModel),message.path("reasoning_content").asText(),raw,envelope.path("usage"),
            envelope.path("usage_source").asText(envelope.path("usage").path("usage_source").asText("UNKNOWN")),finish,submitted);
    }
    static JsonNode accept(ObjectMapper mapper,Decoded response,boolean compression,boolean adjudication,boolean unified,boolean structuredTool,Integer phase){
        if(upstreamBusy(response.raw()))throw new IllegalStateException("UPSTREAM_BUSY_TEXT");
        if(structuredTool&&!response.submitted())throw new IllegalStateException("REQUIRED_SUBMISSION_MISSING");
        if(!response.submitted()&&!response.finish().equals("stop"))throw new IllegalStateException("OUTPUT_NOT_COMPLETE_"+response.finish().toUpperCase(Locale.ROOT));
        String raw=response.raw();var parsed=ResearchProtocol.text(raw)?null:parse(mapper,raw);
        JsonNode output=unified&&!compression?(parsed==null?ModelProtocol.parse(mapper,raw):ModelProtocol.normalize(parsed)):
            parsed==null?ResearchProtocol.parse(mapper,raw,compression,adjudication,phase):ResearchProtocol.normalize(parsed,compression,adjudication,phase);
        if(!compression&&!adjudication&&!output.path("plan").isObject())throw new IllegalStateException("MISSING_REQUIRED_OUTPUT");
        if(compression)EvidenceCompression.validate(output);else validateOutput(output);
        if(adjudication&&(unified?!output.path("plan").isObject():!output.path("selectedCandidateId").isTextual()||output.path("selectedCandidateId").asText().isBlank()))throw new IllegalStateException("MISSING_JUDGE_VOTE");
        return output;
    }
    static JsonNode parse(ObjectMapper mapper,String raw){var output=mapper.readTree(ResearchProtocol.unfence(raw));if(!output.isObject())throw new IllegalStateException("INVALID_STRUCTURED_OUTPUT");return output;}
    static String httpError(ObjectMapper mapper,int status,byte[] response){String code="";try{String upstream=mapper.readTree(response).path("error").path("code").asText().toUpperCase(Locale.ROOT);if(upstream.matches("[A-Z0-9_]{1,60}"))code="_"+upstream;}catch(RuntimeException ignored){}return "INFERENCE_HTTP_"+status+code;}
    private static boolean upstreamBusy(String raw){if(raw==null||raw.length()>120)return false;String plain=raw.strip().replaceAll("[\\p{Punct}，。！\\s]","").toLowerCase(Locale.ROOT);
        return Set.of("服务器繁忙请稍后再试","服务器繁忙请稍后重试","服务繁忙请稍后再试","serverbusypleasetryagainlater","theserverisbusypleasetryagainlater").contains(plain);}
    static void validateOutput(JsonNode output) {
        if(output.path("title").asText().isBlank()||output.path("output").asText().isBlank())throw new IllegalStateException("MISSING_REQUIRED_OUTPUT");
        if(output.path("title").asText().length()>160||output.path("analysisSummary").asText().length()>1800
            ||output.path("output").asText().length()>2400)throw new IllegalStateException("OUTPUT_FIELDS_TOO_LONG");
        for(String field:List.of("shortDirection","longDirection"))
            if(!List.of("BULLISH","BEARISH","UNKNOWN","MIXED").contains(output.path(field).asText()))throw new IllegalStateException("INVALID_DIRECTION");
        for(String field:List.of("quotedMessages","evidenceRefs","impacts")) {
            JsonNode values=output.path(field);
            int maximum=field.equals("impacts")?4:field.equals("quotedMessages")?6:14;
            if(!values.isMissingNode()&&(!values.isArray()||values.size()>maximum))throw new IllegalStateException("INVALID_EVIDENCE_ARRAY");
        }
        if(output.path("changes").asText().length()>800)throw new IllegalStateException("OUTPUT_FIELDS_TOO_LONG");
        for(String field:List.of("riskAssessment","invalidate"))if(output.path("plan").path(field).asText().codePointCount(0,output.path("plan").path(field).asText().length())>(field.equals("riskAssessment")?120:80))throw new IllegalStateException("PLAN_NARRATIVE_TOO_LONG_"+field.toUpperCase(Locale.ROOT));
        for(var impact:output.path("impacts"))if(!List.of("SHORT","LONG").contains(impact.path("horizon").asText())
            ||!List.of("BULLISH","BEARISH","UNKNOWN","MIXED").contains(impact.path("direction").asText())
            ||impact.path("reason").asText().length()>500||impact.path("counter").asText().length()>400)throw new IllegalStateException("INVALID_IMPACT");
    }
}
