package com.goldcatcher.research;

import com.goldcatcher.*;
import com.goldcatcher.RuntimeSettings.AiRoute;
import com.goldcatcher.information.NewsService;
import java.net.*;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Service;
import tools.jackson.databind.*;

@Service
public class Any2ApiClient {
    public record Budget(int bytes,int characters,long gatewayInputEstimate,int messages,int outputReserve,
        String inputHash,String limitSource,String outputEnforcement) {}
    public record Answer(String model,String analysis,String analysisSource,String raw,JsonNode output,
        JsonNode usage,String usageSource,String finishReason,Budget budget,long durationMs) {}
    public record ModelState(String routeId,String provider,String model,boolean credentialConfigured,
        JsonNode catalog,Instant catalogRetrievedAt,String lastInference,String inferenceError) {}
    private record Cached(JsonNode catalog,Instant at) {}
    private final RuntimeSettings settings;private final RemoteHttp http;private final ObjectMapper mapper;private final Clock clock;
    private final Map<String,Cached> catalogs=new ConcurrentHashMap<>();
    private final Map<String,Semaphore> leases=new ConcurrentHashMap<>();
    private final Map<String,String> inference=new ConcurrentHashMap<>(),failures=new ConcurrentHashMap<>();
    public Any2ApiClient(RuntimeSettings settings,RemoteHttp http,ObjectMapper mapper,Clock clock) {
        this.settings=settings;this.http=http;this.mapper=mapper;this.clock=clock;
    }
    public List<ModelState> states() {return settings.routes().stream().map(route->{var cached=catalogs.get(route.id());
        return new ModelState(route.id(),route.provider(),route.model(),!settings.secret(route).isBlank(),cached==null?null:cached.catalog(),
            cached==null?null:cached.at(),inference.getOrDefault(route.id(),"NOT_CALLED"),failures.get(route.id()));}).toList();}
    public Answer complete(AiRoute route,String instruction,Object packet) throws Exception {
        long started=System.nanoTime();
        Semaphore lease=leases.computeIfAbsent(route.provider(),key->new Semaphore(1));
        if(!lease.tryAcquire())throw new IllegalStateException("PROVIDER_REQUEST_STILL_RUNNING");
        Answer captured=null;
        try {
            JsonNode catalog=catalog(route);
            if(!catalog.path("available").asBoolean(false))throw new IllegalStateException("MODEL_NOT_AVAILABLE");
            JsonNode capabilities=catalog.path("capabilities");
            Map<String,Object> request=new LinkedHashMap<>();request.put("model",route.model());
            request.put("messages",List.of(Map.of("role","system","content",instruction),Map.of("role","user","content",mapper.writeValueAsString(packet))));
            var parameters=capabilities.path("supported_parameters").path("chat_completions");
            boolean structuredTool=capabilities.path("tools").path("function_calling").asBoolean(false)
                &&capabilities.path("tools").path("strict").asBoolean(false);
            if(structuredTool) {
                int format=instruction.indexOf("格式："),wait=instruction.indexOf("WAIT 时");
                if(format>=0&&wait>format)request.put("messages",List.of(Map.of("role","system","content",instruction.substring(0,format)+instruction.substring(wait)),
                    Map.of("role","user","content",mapper.writeValueAsString(packet))));
            }
            if(structuredTool){request.put("tools",List.of(Map.of("type","function","function",Map.of("name","submit_research","description","提交本阶段研究结果，只有数据归档，无外部交易行为", "strict",true,"parameters",outputSchema()))));
                request.put("tool_choice",Map.of("type","function","function",Map.of("name","submit_research")));}
            String outputParameter=null;
            for(String candidate:List.of("max_tokens","max_completion_tokens","max_output_tokens"))
                for(JsonNode parameter:parameters)if(parameter.asText().equals(candidate)
                    &&capabilities.path("parameter_adaptation").path(candidate).path("mode").asText().equals("mapped")){outputParameter=candidate;break;}
            if(outputParameter!=null)request.put(outputParameter,route.outputReserve());
            String body=mapper.writeValueAsString(request);
            Budget budget=verify(route,body,catalog,outputParameter!=null);
            captured=new Answer(route.model(),"","NOT_PROVIDED","",null,mapper.createObjectNode(),"UNKNOWN","NOT_RECEIVED",budget,0);
            var response=http.send(HttpRequest.newBuilder(URI.create(route.endpoint("chat/completions")))
                .timeout(Duration.ofSeconds(route.timeoutSeconds())).header("Authorization","Bearer "+settings.secret(route))
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body,StandardCharsets.UTF_8)).build(),1_000_000);
            if(response.statusCode()!=200) {
                String code="";try{String upstream=mapper.readTree(response.body()).path("error").path("code").asText().toUpperCase(Locale.ROOT);
                    if(upstream.matches("[A-Z0-9_]{1,60}"))code="_"+upstream;}catch(RuntimeException ignored){}
                throw new IllegalStateException("INFERENCE_HTTP_"+response.statusCode()+code);
            }
            JsonNode envelope=mapper.readTree(response.body()),choice=envelope.path("choices").path(0),message=choice.path("message");
            String finish=choice.path("finish_reason").asText("UNKNOWN");
            String raw=message.path("content").asText();
            var calls=message.path("tool_calls");
            boolean submitted=finish.equals("tool_calls")&&calls.isArray()&&calls.size()==1&&calls.path(0).path("function").path("name").asText().equals("submit_research");
            if(submitted)raw=calls.path(0).path("function").path("arguments").asText();
            String reasoning=message.path("reasoning_content").asText();
            String usageSource=envelope.path("usage_source").asText(envelope.path("usage").path("usage_source").asText("UNKNOWN"));
            captured=new Answer(envelope.path("model").asText(route.model()),reasoning,reasoning.isBlank()?"NOT_PROVIDED":"PROVIDER_FIELD",raw,null,envelope.path("usage"),usageSource,
                finish,budget,(System.nanoTime()-started)/1_000_000);
            if(structuredTool&&!submitted)throw new IllegalStateException("REQUIRED_SUBMISSION_MISSING");
            if(!submitted&&!finish.equals("stop"))throw new IllegalStateException("OUTPUT_NOT_COMPLETE_"+finish.toUpperCase(Locale.ROOT));
            JsonNode output=parseOutput(raw);
            validate(output);
            String analysis=reasoning.isBlank()?output.path("analysisSummary").asText():reasoning;
            String source=reasoning.isBlank()?(analysis.isBlank()?"NOT_PROVIDED":"EXPLICIT_OUTPUT_SUMMARY"):"PROVIDER_FIELD";
            inference.put(route.id(),"VALID_OUTPUT");failures.remove(route.id());
            return new Answer(envelope.path("model").asText(route.model()),analysis,source,raw,output,envelope.path("usage"),usageSource,
                finish,budget,(System.nanoTime()-started)/1_000_000);
        }catch(Exception failure) {
            inference.put(route.id(),"FAILED");failures.put(route.id(),safeError(failure));
            if(captured!=null)throw new InferenceFailure(safeError(failure),new Answer(captured.model(),captured.analysis(),captured.analysisSource(),captured.raw(),captured.output(),
                captured.usage(),captured.usageSource(),captured.finishReason(),captured.budget(),(System.nanoTime()-started)/1_000_000));throw failure;
        }finally{lease.release();}
    }
    private JsonNode catalog(AiRoute route) throws Exception {
        if(settings.secret(route).isBlank())throw new IllegalStateException("CREDENTIAL_NOT_CONFIGURED");
        var cached=catalogs.get(route.id());
        if(cached!=null&&cached.at().isAfter(clock.instant().minusSeconds(900)))return cached.catalog();
        String model=route.model();
        if(URI.create(route.baseUrl()).getPath().matches(".*/"+java.util.regex.Pattern.quote(route.provider())+"/v1/?$")&&model.startsWith(route.provider()+"/"))model=model.substring(route.provider().length()+1);
        model=Arrays.stream(model.split("/",-1)).map(part->URLEncoder.encode(part,StandardCharsets.UTF_8).replace("+","%20")).collect(java.util.stream.Collectors.joining("/"));
        var response=http.send(HttpRequest.newBuilder(URI.create(route.endpoint("models/"+model)))
            .timeout(Duration.ofSeconds(12)).header("Authorization","Bearer "+settings.secret(route)).GET().build(),500_000);
        if(response.statusCode()!=200)throw new IllegalStateException("CATALOG_HTTP_"+response.statusCode());
        JsonNode body=mapper.readTree(response.body());catalogs.put(route.id(),new Cached(body,clock.instant()));return body;
    }
    public Budget verify(AiRoute route,String body,JsonNode catalog,boolean outputRequested) {
        int bytes=body.getBytes(StandardCharsets.UTF_8).length,characters=body.codePointCount(0,body.length());
        long estimate=(bytes+2L)/3L;JsonNode capabilities=catalog.path("capabilities");
        long declaredInput=positive(capabilities,"max_input_tokens"),declaredContext=positive(capabilities,"max_context_tokens"),
            declaredOutput=positive(capabilities,"max_output_tokens");
        long channelCharacters=characterLimit(catalog);
        Budget budget=new Budget(bytes,characters,estimate,2,route.outputReserve(),NewsService.hash(body),
            declaredInput>0||declaredContext>0?"CATALOG_AND_CONFIG":"CONFIG_CONSERVATIVE_UNKNOWN_CAPACITY",
            outputRequested?"REQUESTED_NOT_ASSUMED_ENFORCED":"NOT_SUPPORTED");
        if(bytes>route.byteLimit()||characters>Math.min(route.characterLimit(),channelCharacters)||estimate>route.inputEstimateLimit()
            ||(declaredInput>0&&estimate>declaredInput)||(declaredOutput>0&&route.outputReserve()>declaredOutput)
            ||(declaredContext>0&&estimate+route.outputReserve()+512>declaredContext)||route.messageLimit()<2)
            throw new InferenceFailure("CONTEXT_INSUFFICIENT",new Answer(route.model(),"","NOT_PROVIDED","",null,mapper.createObjectNode(),"UNKNOWN","NOT_SENT",budget,0));
        return budget;
    }
    private long positive(JsonNode node,String field){long value=node.path(field).asLong(0);return Math.max(value,0);}
    private long characterLimit(JsonNode node){long own=positive(node,"input_character_limit"),limit=own>0?own:Long.MAX_VALUE;
        if(node.path("mode").asText().equals("declared_character_limit")&&positive(node,"limit")>0)limit=Math.min(limit,positive(node,"limit"));
        if(node.isObject()||node.isArray())for(JsonNode child:node)if(child.isObject()||child.isArray())limit=Math.min(limit,characterLimit(child));return limit;}
    JsonNode parseOutput(String raw) {
        String content=raw.strip();
        if(content.startsWith("```")){int newline=content.indexOf('\n');int end=content.lastIndexOf("```");
            if(newline<0||end<=newline)throw new IllegalStateException("INVALID_STRUCTURED_OUTPUT");content=content.substring(newline+1,end).strip();}
        JsonNode output=mapper.readTree(content);
        if(!output.isObject())throw new IllegalStateException("INVALID_STRUCTURED_OUTPUT");return output;
    }
    private void validate(JsonNode output) {
        if(output.path("title").asText().isBlank()||output.path("output").asText().isBlank())throw new IllegalStateException("MISSING_REQUIRED_OUTPUT");
        if(output.path("title").asText().length()>160||output.path("analysisSummary").asText().length()>1800
            ||output.path("output").asText().length()>2400)throw new IllegalStateException("OUTPUT_FIELDS_TOO_LONG");
        for(String field:List.of("shortDirection","longDirection"))
            if(!List.of("BULLISH","BEARISH","UNKNOWN","MIXED").contains(output.path(field).asText()))throw new IllegalStateException("INVALID_DIRECTION");
        for(String field:List.of("quotedMessages","evidenceRefs","impacts")) {
            JsonNode values=output.path(field);
            if(!values.isMissingNode()&&(!values.isArray()||values.size()>(field.equals("impacts")?4:12)))throw new IllegalStateException("INVALID_EVIDENCE_ARRAY");
        }
        if(output.path("changes").asText().length()>800)throw new IllegalStateException("OUTPUT_FIELDS_TOO_LONG");
        for(var impact:output.path("impacts"))if(!List.of("SHORT","LONG").contains(impact.path("horizon").asText())
            ||!List.of("BULLISH","BEARISH","UNKNOWN","MIXED").contains(impact.path("direction").asText())
            ||impact.path("reason").asText().length()>500||impact.path("counter").asText().length()>400)throw new IllegalStateException("INVALID_IMPACT");
    }
    private Map<String,Object> outputSchema() {
        Map<String,Object> properties=new LinkedHashMap<>();
        for(var field:List.of("title","analysisSummary","output","changes"))properties.put(field,Map.of("type","string"));
        for(var field:List.of("shortDirection","longDirection"))properties.put(field,Map.of("type","string","enum",List.of("BULLISH","BEARISH","UNKNOWN","MIXED")));
        for(var field:List.of("quotedMessages","evidenceRefs"))properties.put(field,Map.of("type","array","items",Map.of("type","string")));
        properties.put("impacts",Map.of("type","array","items",Map.of("type","object","properties",Map.of(
            "newsId",Map.of("type","string"),"horizon",Map.of("type","string","enum",List.of("SHORT","LONG")),"direction",properties.get("shortDirection"),
            "reason",Map.of("type","string"),"counter",Map.of("type","string")),"required",List.of("newsId","horizon","direction","reason","counter"),"additionalProperties",false)));
        Map<String,Object> plan=new LinkedHashMap<>();plan.put("action",Map.of("type","string","enum",List.of("WAIT","CREATE","CANCEL","CLOSE")));
        for(var field:List.of("orderId","side","type","quantity","leverage","entryPrice","triggerPrice","stopLoss","takeProfit","expiresAt","holdUntil","riskAssessment","invalidate"))plan.put(field,Map.of("type",List.of("string","null")));
        properties.put("plan",Map.of("type","object","properties",plan,"required",List.copyOf(plan.keySet()),"additionalProperties",false));
        return Map.of("type","object","properties",properties,"required",List.copyOf(properties.keySet()),"additionalProperties",false);
    }
    public static class InferenceFailure extends IllegalStateException {
        private final Answer partial;
        public InferenceFailure(String code,Answer partial){super(code);this.partial=partial;}
        public Answer partial(){return partial;}
    }
    public static String safeError(Exception error) {
        if(error instanceof java.util.concurrent.TimeoutException||error instanceof java.net.http.HttpTimeoutException)return "REQUEST_TIMEOUT";
        if(error instanceof java.util.concurrent.ExecutionException&&error.getCause() instanceof Exception cause)return safeError(cause);
        if(error instanceof tools.jackson.core.JacksonException)return "INVALID_STRUCTURED_OUTPUT";
        String message=error instanceof IllegalStateException?error.getMessage():null;
        return message!=null&&message.matches("[A-Z0-9_]+")?message:"UPSTREAM_OR_PARSE_FAILED";
    }
}
