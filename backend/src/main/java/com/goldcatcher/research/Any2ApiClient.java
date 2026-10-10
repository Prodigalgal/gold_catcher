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
        String inputHash,String limitSource,String outputEnforcement,ContextWindow.Limits limits,String preparation,
        Integer utilizationPercent,Integer originalBytes,Integer originalCharacters,List<ContextWindow.Change> changes,
        String sourcePacketHash,String sentPacketHash,String wireEncoding) {
        public Budget {
            if(preparation==null)preparation="LEGACY_NOT_RECORDED";
            if(changes==null)changes=List.of();
            if(wireEncoding==null)wireEncoding="LEGACY_NOT_RECORDED";
        }
        public Budget(int bytes,int characters,long gatewayInputEstimate,int messages,int outputReserve,
            String inputHash,String limitSource,String outputEnforcement) {
            this(bytes,characters,gatewayInputEstimate,messages,outputReserve,inputHash,limitSource,outputEnforcement,
                null,"LEGACY_NOT_RECORDED",0,bytes,characters,List.of(),null,null,null);
        }
    }
    public record Answer(String model,String analysis,String analysisSource,String raw,JsonNode output,
        JsonNode usage,String usageSource,String finishReason,Budget budget,long durationMs,List<Attempt> attempts) {
        public Answer{if(attempts==null)attempts=List.of();}
        public Answer(String model,String analysis,String analysisSource,String raw,JsonNode output,JsonNode usage,String usageSource,String finishReason,Budget budget,long durationMs){this(model,analysis,analysisSource,raw,output,usage,usageSource,finishReason,budget,durationMs,List.of());}
    }
    public record Attempt(int number,Instant startedAt,long durationMs,Integer httpStatus,String error) {}
    public record ModelState(String routeId,String provider,String model,boolean credentialConfigured,
        JsonNode catalog,Instant catalogRetrievedAt,String lastInference,String inferenceError,Budget lastBudget,Instant lastBudgetAt,String catalogError) {}
    private record Cached(JsonNode catalog,Instant at) {}
    private final RuntimeSettings settings;private final RemoteHttp http;private final ObjectMapper mapper;private final Clock clock;
    private final Map<String,Cached> catalogs=new ConcurrentHashMap<>();
    private final Map<String,Semaphore> leases=new ConcurrentHashMap<>();
    private final Map<String,String> inference=new ConcurrentHashMap<>(),failures=new ConcurrentHashMap<>();
    private final Map<String,Budget> budgets=new ConcurrentHashMap<>();private final Map<String,Instant> budgetTimes=new ConcurrentHashMap<>();
    private final Map<String,String> catalogErrors=new ConcurrentHashMap<>();private final Map<String,Instant> failedAt=new ConcurrentHashMap<>();
    public Any2ApiClient(RuntimeSettings settings,RemoteHttp http,ObjectMapper mapper,Clock clock) {
        this.settings=settings;this.http=http;this.mapper=mapper;this.clock=clock;
    }
    public List<ModelState> states() {return settings.allRoutes().stream().map(route->{var cached=catalogs.get(route.id());
        return new ModelState(route.id(),route.provider(),route.model(),!settings.secret(route).isBlank(),cached==null?null:cached.catalog(),
            cached==null?null:cached.at(),inference.getOrDefault(route.id(),"NOT_CALLED"),failures.get(route.id()),budgets.get(route.id()),budgetTimes.get(route.id()),catalogErrors.get(route.id()));}).toList();}
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=300000,initialDelay=5000) public void warmCatalogs(){
        for(var route:settings.allRoutes())if(!settings.secret(route).isBlank())try{catalog(route);}catch(Exception failure){catalogErrors.put(route.id(),safeError(failure));}
    }
    public List<AiRoute> selectRoutes(List<AiRoute> preferred,List<AiRoute> fallback) {
        var pool=new ArrayList<>(preferred);for(var route:fallback)if(pool.stream().noneMatch(p->p.id().equals(route.id())))pool.add(route);
        var selected=new ArrayList<AiRoute>();var identities=new HashSet<String>();
        int desired=(int)preferred.stream().filter(r->!r.judge()).count();
        for(boolean judge:List.of(true,false))for(var route:pool) {
            if(Boolean.parseBoolean(settings.text("GOLD_COMPRESSION_ENABLED","true"))&&route.provider().equals("mimo"))continue;
            if(route.judge()!=judge||selected.stream().filter(r->r.judge()==judge).count()>=(judge?1:desired))continue;
            if(identities.contains(ResearchService.modelIdentity(route)))continue;
            var failed=failedAt.get(modelKey(route));if(failed!=null&&failed.plusSeconds(settings.integer("GOLD_AI_FAILURE_COOLDOWN_SECONDS",600)).isAfter(clock.instant()))continue;
            try{if(!catalog(route).path("available").asBoolean(false))continue;}
            catch(Exception failure){catalogErrors.put(route.id(),safeError(failure));continue;}
            selected.add(route);identities.add(ResearchService.modelIdentity(route));
        }
        if(selected.stream().noneMatch(AiRoute::judge))throw new IllegalArgumentException("NO_AVAILABLE_INDEPENDENT_JUDGE");
        if(selected.stream().filter(r->!r.judge()).count()<2)throw new IllegalArgumentException("NO_AVAILABLE_MODEL_QUORUM");
        return selected.stream().sorted(Comparator.comparing(AiRoute::judge)).toList();
    }
    public Answer complete(AiRoute route,String instruction,Object packet) throws Exception {
        return complete(route,instruction,packet,false);
    }
    public Answer compress(AiRoute route,String instruction,Object packet) throws Exception {
        if(!route.provider().equals("mimo")||!route.id().equals("compressor-mimo"))throw new IllegalArgumentException("COMPRESSION_FIXED_TO_MIMO");
        return complete(route,instruction,packet,true);
    }
    private Answer complete(AiRoute route,String instruction,Object packet,boolean compression) throws Exception {
        long started=System.nanoTime();
        Semaphore lease=leases.computeIfAbsent(route.provider(),key->new Semaphore(1));
        if(!(compression?lease.tryAcquire():lease.tryAcquire(3,java.util.concurrent.TimeUnit.SECONDS)))throw new IllegalStateException("PROVIDER_REQUEST_STILL_RUNNING");
        Answer captured=null;
        List<Attempt> attempts=new ArrayList<>();
        try {
            JsonNode catalog=catalog(route);
            if(!catalog.path("available").asBoolean(false))throw new IllegalStateException("MODEL_NOT_AVAILABLE");
            JsonNode capabilities=catalog.path("capabilities");
            Map<String,Object> request=new LinkedHashMap<>();request.put("model",route.model());
            request.put("messages",List.of(Map.of("role","system","content",instruction),Map.of("role","user","content",mapper.writeValueAsString(packet))));
            var parameters=capabilities.path("supported_parameters").path("chat_completions");
            boolean structuredTool=!compression&&!settings.preferJsonText(route)&&capabilities.path("tools").path("function_calling").asBoolean(false)
                &&capabilities.path("tools").path("strict").asBoolean(false)&&!capabilities.path("tools").path("bridge").asText().equals("emulated_function_bridge");
            boolean jsonMode=capabilities.path("structured_output").asBoolean(false)||capabilities.path("structured_output").path("supported").asBoolean(false);
            if(!structuredTool&&jsonMode)for(var parameter:parameters)if(parameter.asText().equals("response_format"))request.put("response_format",Map.of("type","json_object"));
            var sourceOptions=new LinkedHashMap<String,Object>();
            if(capabilities.path("provider_options").has("web_search_status")&&capabilities.path("parameter_adaptation").path("search").path("mode").asText().equals("mapped"))sourceOptions.put("web_search_status","disabled");
            if(compression&&capabilities.path("provider_options").has("thinking"))sourceOptions.put("thinking",false);
            if(!sourceOptions.isEmpty())request.put("provider_options",Map.of(route.provider(),sourceOptions));
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
            var limits=ContextWindow.limits(route,catalog);
            var prepared=ContextWindow.prepare(mapper,request,packet,limits);String body=prepared.body();
            Budget budget=budget(route,body,catalog,outputParameter!=null,prepared,
                NewsService.hash(mapper.writeValueAsString(packet)),NewsService.hash(mapper.writeValueAsString(prepared.packet())));
            budgets.put(route.id(),budget);budgetTimes.put(route.id(),clock.instant());
            if(!ContextWindow.fits(prepared.sent(),limits,true)||(limits.declaredOutput()>0&&route.outputReserve()>limits.declaredOutput()))
                throw new InferenceFailure("CONTEXT_INSUFFICIENT",new Answer(route.model(),"","NOT_PROVIDED","",null,mapper.createObjectNode(),"UNKNOWN","NOT_SENT",budget,0));
            captured=new Answer(route.model(),"","NOT_PROVIDED","",null,mapper.createObjectNode(),"UNKNOWN","NOT_RECEIVED",budget,0);
            java.net.http.HttpResponse<byte[]> response=null;
            var packetJson=mapper.valueToTree(packet);var expires=packetJson.path("evidence").path("decisionExpiresAt").asText();
            Instant deadline=expires.isBlank()?clock.instant().plusSeconds(route.timeoutSeconds()):Instant.parse(expires);
            for(int attempt=1;attempt<=2;attempt++) {
                long remaining=Duration.between(clock.instant(),deadline).toMillis();if(remaining<=0)throw new IllegalStateException("DECISION_DEADLINE");
                var began=clock.instant();long measured=System.nanoTime();
                try{response=http.send(HttpRequest.newBuilder(URI.create(route.endpoint("chat/completions")))
                    .timeout(Duration.ofMillis(Math.min(route.timeoutSeconds()*1000L,remaining))).header("Authorization","Bearer "+settings.secret(route))
                    .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body,StandardCharsets.UTF_8)).build(),1_000_000);
                    attempts.add(new Attempt(attempt,began,(System.nanoTime()-measured)/1000000,response.statusCode(),response.statusCode()==200?null:"INFERENCE_HTTP_"+response.statusCode()));
                    if(attempt==2||!List.of(502,503,504).contains(response.statusCode())||Duration.between(clock.instant(),deadline).toSeconds()<5)break;
                }catch(Exception transport){attempts.add(new Attempt(attempt,began,(System.nanoTime()-measured)/1000000,null,safeError(transport)));throw transport;}
            }
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
            if(compression)validateCompression(output);else validate(output);
            String analysis=reasoning.isBlank()?output.path("analysisSummary").asText():reasoning;
            String source=reasoning.isBlank()?(analysis.isBlank()?"NOT_PROVIDED":"EXPLICIT_OUTPUT_SUMMARY"):"PROVIDER_FIELD";
            inference.put(route.id(),"VALID_OUTPUT");failures.remove(route.id());
            if(!compression)failedAt.remove(modelKey(route));
            return new Answer(envelope.path("model").asText(route.model()),analysis,source,raw,output,envelope.path("usage"),usageSource,
                finish,budget,(System.nanoTime()-started)/1_000_000,List.copyOf(attempts));
        }catch(Exception failure) {
            inference.put(route.id(),"FAILED");failures.put(route.id(),safeError(failure));
            if(!compression)failedAt.put(modelKey(route),clock.instant());
            if(captured!=null)throw new InferenceFailure(safeError(failure),new Answer(captured.model(),captured.analysis(),captured.analysisSource(),captured.raw(),captured.output(),
                captured.usage(),captured.usageSource(),captured.finishReason(),captured.budget(),(System.nanoTime()-started)/1_000_000,List.copyOf(attempts)));throw failure;
        }finally{lease.release();}
    }
    private void validateCompression(JsonNode output) {
        EvidenceCompression.validate(output);
    }
    private JsonNode catalog(AiRoute route) throws Exception {
        if(settings.secret(route).isBlank())throw new IllegalStateException("CREDENTIAL_NOT_CONFIGURED");
        var cached=catalogs.get(route.id());
        if(cached!=null&&cached.at().isAfter(clock.instant().minusSeconds(cached.catalog().path("available").asBoolean(false)?300:60)))return cached.catalog();
        String model=route.model();
        if(URI.create(route.baseUrl()).getPath().matches(".*/"+java.util.regex.Pattern.quote(route.provider())+"/v1/?$")&&model.startsWith(route.provider()+"/"))model=model.substring(route.provider().length()+1);
        model=Arrays.stream(model.split("/",-1)).map(part->URLEncoder.encode(part,StandardCharsets.UTF_8).replace("+","%20")).collect(java.util.stream.Collectors.joining("/"));
        var response=http.send(HttpRequest.newBuilder(URI.create(route.endpoint("models/"+model)))
            .timeout(Duration.ofSeconds(12)).header("Authorization","Bearer "+settings.secret(route)).GET().build(),500_000);
        if(response.statusCode()!=200)throw new IllegalStateException("CATALOG_HTTP_"+response.statusCode());
        JsonNode body=mapper.readTree(response.body());catalogs.put(route.id(),new Cached(body,clock.instant()));catalogErrors.remove(route.id());return body;
    }
    private String modelKey(AiRoute route){return route.provider()+"/"+ResearchService.modelIdentity(route);}
    public Budget verify(AiRoute route,String body,JsonNode catalog,boolean outputRequested) {
        var limits=ContextWindow.limits(route,catalog);var size=ContextWindow.size(body,2);
        var prepared=new ContextWindow.Prepared(body,null,size,size,List.of());
        Budget budget=budget(route,body,catalog,outputRequested,prepared,null,null);
        if(!ContextWindow.fits(size,limits,true)||(limits.declaredOutput()>0&&route.outputReserve()>limits.declaredOutput()))
            throw new InferenceFailure("CONTEXT_INSUFFICIENT",new Answer(route.model(),"","NOT_PROVIDED","",null,mapper.createObjectNode(),"UNKNOWN","NOT_SENT",budget,0));
        return budget;
    }
    private Budget budget(AiRoute route,String body,JsonNode catalog,boolean outputRequested,ContextWindow.Prepared prepared,String originalHash,String sentHash) {
        var limits=ContextWindow.limits(route,catalog);var size=prepared.sent();boolean fits=ContextWindow.fits(size,limits,true)
            &&(limits.declaredOutput()==0||route.outputReserve()<=limits.declaredOutput());
        return new Budget(size.bytes(),size.characters(),size.inputEstimate(),size.messages(),route.outputReserve(),NewsService.hash(body),
            limits.declaredInput()>0||limits.declaredContext()>0?"CATALOG_AND_CONFIG":"CONFIG_CONSERVATIVE_UNKNOWN_CAPACITY",
            outputRequested?"REQUESTED_NOT_ASSUMED_ENFORCED":"NOT_SUPPORTED",limits,
            !fits?"BLOCKED_BY_BUDGET":prepared.changes().isEmpty()?"WITHIN_BUDGET":"COMPACTED_WITH_ARCHIVE",
            ContextWindow.utilization(size,limits),prepared.original().bytes(),prepared.original().characters(),prepared.changes(),originalHash,sentHash,
            prepared.packet()==null?"NOT_APPLICABLE":mapper.readTree(body).path("messages").path(1).path("content").asText().startsWith("---")?"YAML_FLOW_LOSSLESS":"JSON");
    }
    JsonNode parseOutput(String raw) {
        String content=raw.strip();
        if(content.startsWith("```")){int newline=content.indexOf('\n');int end=content.lastIndexOf("```");
            if(newline<0||end<=newline)throw new IllegalStateException("INVALID_STRUCTURED_OUTPUT");content=content.substring(newline+1,end).strip();}
        JsonNode output=mapper.readTree(content);
        if(!output.isObject())throw new IllegalStateException("INVALID_STRUCTURED_OUTPUT");return output;
    }
    void validate(JsonNode output) {
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
        for(String field:List.of("riskAssessment","invalidate"))if(output.path("plan").path(field).asText().codePointCount(0,output.path("plan").path(field).asText().length())>(field.equals("riskAssessment")?120:80))throw new IllegalStateException("PLAN_NARRATIVE_TOO_LONG");
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
        Map<String,Object> plan=new LinkedHashMap<>();plan.put("action",Map.of("type","string","enum",List.of("WAIT","CREATE","CANCEL","CLOSE","MODIFY","REDUCE")));
        for(var field:List.of("orderId","side","type","quantity","leverage","entryPrice","triggerPrice","stopLoss","takeProfit","trailingDistance","trailingActivation","expiresAt","holdUntil","riskAssessment","invalidate"))plan.put(field,Map.of("type",List.of("string","null")));
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
        String message=error instanceof IllegalStateException||error instanceof IllegalArgumentException?error.getMessage():null;
        return message!=null&&message.matches("[A-Z0-9_]+")?message:"UPSTREAM_OR_PARSE_FAILED";
    }
}
