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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
        JsonNode usage,String usageSource,String finishReason,Budget budget,long durationMs,List<Attempt> attempts,Routing routing) {
        public Answer{if(attempts==null)attempts=List.of();}
        public Answer(String model,String analysis,String analysisSource,String raw,JsonNode output,JsonNode usage,String usageSource,String finishReason,Budget budget,long durationMs,List<Attempt> attempts){this(model,analysis,analysisSource,raw,output,usage,usageSource,finishReason,budget,durationMs,attempts,null);}
        public Answer(String model,String analysis,String analysisSource,String raw,JsonNode output,JsonNode usage,String usageSource,String finishReason,Budget budget,long durationMs){this(model,analysis,analysisSource,raw,output,usage,usageSource,finishReason,budget,durationMs,List.of());}
    }
    public record RouteFailure(String provider,String model,String error,Answer partial) {}
    public record Routing(String requestedProvider,String requestedModel,String usedProvider,String usedModel,boolean fallbackUsed,String reason,List<RouteFailure> failures) {}
    public record Attempt(int number,Instant startedAt,long durationMs,Integer httpStatus,String error,String clientTraceId,String requestId,String outcome) {
        public Attempt{if(outcome==null)outcome="LEGACY_NOT_RECORDED";}
        public Attempt(int number,Instant startedAt,long durationMs,Integer httpStatus,String error){this(number,startedAt,durationMs,httpStatus,error,null,null,"LEGACY_NOT_RECORDED");}
        Attempt result(String outcome,String error){return new Attempt(number,startedAt,durationMs,httpStatus,error,clientTraceId,requestId,outcome);}
    }
    public record ModelState(String routeId,String provider,String model,boolean credentialConfigured,
        JsonNode catalog,Instant catalogRetrievedAt,String lastInference,String inferenceError,Budget lastBudget,Instant lastBudgetAt,String catalogError,Routing lastRouting) {}
    public record RouteCheck(String routeId,String provider,String model,boolean judge,String state,Instant catalogRetrievedAt,Instant retryAt,String fallbackFor) {}
    public record RouteSelection(String state,List<AiRoute> routes,List<RouteCheck> checks,String basis) {}
    private record Cached(JsonNode catalog,Instant at,String routeKey) {}
    private final RuntimeSettings settings;private final RemoteHttp http;private final ObjectMapper mapper;private final Clock clock;
    private final Map<String,Cached> catalogs=new ConcurrentHashMap<>();
    private static final class ProviderWork {
        final Semaphore lease;final int capacity;final AtomicInteger waiting=new AtomicInteger();
        ProviderWork(int concurrency){capacity=concurrency;lease=new Semaphore(concurrency,true);}
    }
    private record ProviderLease(ProviderWork work) implements AutoCloseable {
        @Override public void close(){work.lease.release();}
    }
    private final Map<String,ProviderWork> leases=new ConcurrentHashMap<>();
    private final Map<String,String> inference=new ConcurrentHashMap<>(),failures=new ConcurrentHashMap<>();
    private final Map<String,Budget> budgets=new ConcurrentHashMap<>();private final Map<String,Instant> budgetTimes=new ConcurrentHashMap<>();
    private final Map<String,Routing> routeAudits=new ConcurrentHashMap<>();
    private record FailureAt(Instant at,String error) {}
    private final Map<String,String> catalogErrors=new ConcurrentHashMap<>();private final Map<String,FailureAt> failedAt=new ConcurrentHashMap<>();
    public Any2ApiClient(RuntimeSettings settings,RemoteHttp http,ObjectMapper mapper,Clock clock) {
        this.settings=settings;this.http=http;this.mapper=mapper;this.clock=clock;
    }
    public List<ModelState> states() {return settings.allRoutes().stream().map(route->{var cached=currentCatalog(route);
        return new ModelState(route.id(),route.provider(),route.model(),!settings.secret(route).isBlank(),cached==null?null:cached.catalog(),
            cached==null?null:cached.at(),inference.getOrDefault(route.id(),"NOT_CALLED"),failures.get(route.id()),budgets.get(route.id()),budgetTimes.get(route.id()),catalogErrors.get(route.id()),routeAudits.get(route.id()));}).toList();}
    public boolean compressionAvailable(AiRoute route){
        var work=leases.get("compression_lane:"+route.provider());return work==null||work.lease.availablePermits()>0;
    }
    public List<Map<String,Object>> executionPools(){return leases.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(entry->{
        var split=entry.getKey().split(":",2);var work=entry.getValue();return Map.<String,Object>of("lane",split[0],"provider",split[1],"capacity",work.capacity,
            "running",work.capacity-work.lease.availablePermits(),"waiting",work.waiting.get(),"isolation","INDEPENDENT_CLIENT_POOL");}).toList();}
    private ProviderLease acquireProvider(AiRoute route,boolean compression,Instant deadline,String lane)throws InterruptedException {
        var work=leases.computeIfAbsent(lane+":"+route.provider(),key->new ProviderWork(route.provider().equals("arena")?Math.max(1,Math.min(3,settings.integer("GOLD_AI_ARENA_CONCURRENCY",3))):lane.equals("fast_lane")?2:1));boolean acquired=false;
        work.waiting.incrementAndGet();
        try{
            long available=deadline==null?route.timeoutSeconds()*1000L:Math.max(1,Duration.between(clock.instant(),deadline).toMillis());
            long wait=lane.equals("fast_lane")?Math.min(10000,available):Math.min(route.timeoutSeconds()*1000L,available);
            acquired=compression?work.lease.tryAcquire():work.lease.tryAcquire(wait,java.util.concurrent.TimeUnit.MILLISECONDS);
            if(!acquired)throw new IllegalStateException("PROVIDER_REQUEST_STILL_RUNNING");
            return new ProviderLease(work);
        }catch(InterruptedException|RuntimeException failure){
            if(acquired)work.lease.release();throw failure;
        }finally{work.waiting.decrementAndGet();}
    }
    private String catalogReadiness(AiRoute route,JsonNode catalog){
        if(catalog.path("available").asBoolean(false))return "READY";
        var runtime=catalog.path("runtime");
        // Any2API's OPEN state advances only when an inference attempts admission. Catalog polling alone cannot recover it.
        if(!settings.genericAiEndpoint(route)&&catalog.path("cataloged").asBoolean(false)
            &&runtime.path("circuit_state").asText().equals("OPEN")&&runtime.path("available_account_count").asInt()>0
            &&runtime.path("probe_status").asText().equals("READY"))return "MODEL_RECOVERY_READY";
        // Current Any2API catalogs describe models, not account health. Missing availability is unknown, not false.
        // Admit only the exact cataloged identity; inference/SSE validation and lane cooldowns remain authoritative.
        String localId=route.model().startsWith(route.provider()+"/")?route.model().substring(route.provider().length()+1):route.model();
        if(!settings.genericAiEndpoint(route)&&!catalog.has("available")&&catalog.path("cataloged").asBoolean(false)
            &&catalog.path("object").asText().equals("model")&&List.of(route.model(),localId).contains(catalog.path("id").asText())
            &&!runtime.has("circuit_state")&&!runtime.has("available_account_count")&&!runtime.has("probe_status"))return "CATALOG_ADMISSION_READY";
        return "MODEL_NOT_AVAILABLE";
    }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=300000,initialDelay=5000) public void warmCatalogs(){
        for(var route:settings.allRoutes())if(!settings.secret(route).isBlank())try{catalog(route);}catch(Exception failure){catalogErrors.put(route.id(),safeError(failure));}
    }
    public List<AiRoute> selectRoutes(List<AiRoute> preferred,List<AiRoute> fallback) {
        return selectRoutes(preferred,fallback,"LONG");
    }
    public List<AiRoute> selectRoutes(List<AiRoute> preferred,List<AiRoute> fallback,String horizon) {
        var selection=selection(preferred,fallback,true,ResearchLanes.name(horizon));
        if(!selection.state().equals("READY"))throw new IllegalArgumentException(selection.state());
        return selection.routes();
    }
    /** Read-only readiness: no catalog refresh, inference, semaphore acquisition or failure-state mutation. */
    public Map<String,Object> routeReadiness(String horizon) {
        var preferred=settings.routes(horizon);var selection=selection(preferred,settings.fallbackRoutes(),false,ResearchLanes.name(horizon));
        String error=ResearchService.configuredRouteError(preferred);
        return Map.of("horizon",horizon,"state",error==null?selection.state():error,"basis",selection.basis(),"checks",selection.checks(),
            "judgeMinimum",AdjudicationPanel.MINIMUM_JUDGES,"researcherMinimum",ResearchService.RESEARCH_MODEL_COUNT,
            "selected",selection.routes().stream().map(route->Map.of("routeId",route.id(),"provider",route.provider(),"model",route.model(),"judge",route.judge())).toList());
    }
    private RouteSelection selection(List<AiRoute> preferred,List<AiRoute> fallback,boolean refresh,String lane) {
        var pool=new ArrayList<>(preferred);for(var route:fallback)if(pool.stream().noneMatch(p->p.id().equals(route.id())))pool.add(route);
        var probePool=new ArrayList<>(pool);for(var route:pool)if(route.fallback()!=null)probePool.add(route.fallback());
        var fallbackParents=new HashMap<String,String>();for(var route:pool)if(route.fallback()!=null)fallbackParents.put(route.fallback().id(),route.id());
        var checks=new ArrayList<RouteCheck>();var ready=new HashSet<String>();
        for(var route:probePool) {
            String state="READY";Instant retryAt=null;var cached=currentCatalog(route);
            if(!settings.aiProviderEnabled(route))state="PROVIDER_DISABLED";
            else if(route.id().equals("compressor-mimo"))state="RESERVED_FOR_COMPRESSION";
            else if(settings.secret(route).isBlank())state="CREDENTIAL_MISSING";
            else {
                var failed=failedAt.get(modelKey(route,lane));
                int cooldown=failed!=null&&ResearchPolicy.retryable(failed.error())?settings.integer("GOLD_AI_TRANSIENT_COOLDOWN_SECONDS",15):settings.integer("GOLD_AI_FAILURE_COOLDOWN_SECONDS",600);
                if(failed!=null&&failed.at().plusSeconds(cooldown).isAfter(clock.instant())) {
                    state="FAILURE_COOLDOWN";retryAt=failed.at().plusSeconds(cooldown);
                } else if(refresh) {
                    try {state=catalogReadiness(route,catalog(route));cached=currentCatalog(route);}
                    catch(Exception failure){catalogErrors.put(route.id(),safeError(failure));state="CATALOG_QUERY_FAILED";}
                } else if(cached==null)state=catalogErrors.containsKey(route.id())?"CATALOG_QUERY_FAILED":"CATALOG_NOT_CHECKED";
                else if(!cached.at().isAfter(clock.instant().minusSeconds(300)))state="CATALOG_STALE";
                else state=catalogReadiness(route,cached.catalog());
            }
            checks.add(new RouteCheck(route.id(),route.provider(),route.model(),route.judge(),state,cached==null?null:cached.at(),retryAt,fallbackParents.get(route.id())));
            if(state.equals("READY")||state.equals("MODEL_RECOVERY_READY")||state.equals("CATALOG_ADMISSION_READY"))ready.add(route.id());
        }
        for(var route:pool)if(route.fallback()!=null&&ready.contains(route.fallback().id()))ready.add(route.id());
        var selected=new ArrayList<AiRoute>();
        int desired=(int)preferred.stream().filter(settings::aiProviderEnabled).filter(r->!r.judge()).count();
        int desiredJudges=Math.max(AdjudicationPanel.MINIMUM_JUDGES,(int)preferred.stream().filter(settings::aiProviderEnabled).filter(AiRoute::judge).count());
        for(boolean judge:List.of(true,false)) {
            var identities=new HashSet<String>();
            for(var route:pool) {
                if(!ready.contains(route.id()))continue;
                if(route.judge()!=judge||selected.stream().filter(r->r.judge()==judge).count()>=(judge?desiredJudges:desired))continue;
                if(identities.contains(ResearchService.modelIdentity(route)))continue;
                selected.add(route);identities.add(ResearchService.modelIdentity(route));
            }
        }
        String state=selected.stream().filter(AiRoute::judge).count()<AdjudicationPanel.MINIMUM_JUDGES?"NO_AVAILABLE_JUDGE_QUORUM":
            selected.stream().filter(r->!r.judge()).count()<ResearchService.RESEARCH_MODEL_COUNT?"NO_AVAILABLE_MODEL_QUORUM":"READY";
        return new RouteSelection(state,selected.stream().sorted(Comparator.comparing(AiRoute::judge)).toList(),List.copyOf(checks),
            "CREDENTIALS_CATALOG_AND_FAILURE_COOLDOWN_ONLY; NOT_INFERENCE_OR_CONTEXT_ACCEPTANCE");
    }
    public Answer complete(AiRoute route,String instruction,Object packet) throws Exception {
        return completeWithFallback(route,instruction,packet,false,null,null);
    }
    /** Research owns retries; this overload performs one deadline-bounded HTTP attempt. */
    public Answer complete(AiRoute route,String instruction,Object packet,Instant attemptDeadline) throws Exception {
        return completeWithFallback(route,instruction,packet,false,attemptDeadline,null);
    }
    public Answer completeFallback(AiRoute route,String instruction,Object packet,Instant attemptDeadline,String reason)throws Exception{return completeWithFallback(route,instruction,packet,false,attemptDeadline,reason);}
    public Answer compress(AiRoute route,String instruction,Object packet) throws Exception {
        if(!route.id().equals("compressor-mimo")||route.judge())throw new IllegalArgumentException("INVALID_COMPRESSION_ROUTE");
        return completeWithFallback(route,instruction,packet,true,null,null);
    }
    public static int requestSeconds(AiRoute route){return route.timeoutSeconds()+(route.fallback()==null?0:route.fallback().timeoutSeconds());}
    private boolean fastLane(Object packet){var json=mapper.valueToTree(packet);return json.path("lane").asText().equals("fast_lane")||json.path("evidence").path("horizon").asText().equals("SHORT");}
    private Answer completeWithFallback(AiRoute primary,String instruction,Object packet,boolean compression,Instant deadline,String forced)throws Exception{
        if(!compression&&fastLane(packet)){
            String model=primary.model().toLowerCase(Locale.ROOT);
            if(primary.provider().equals("arena")||model.contains("deepsearch")||model.contains("heavy"))throw new IllegalStateException("FAST_MODEL_NOT_ELIGIBLE");
            // Arena Max can be a slow random model. Fast rounds discard failed signals instead.
            Instant fastDeadline=clock.instant().plusSeconds(ResearchLanes.FAST_REQUEST_SECONDS);
            if(deadline!=null&&deadline.isBefore(fastDeadline))fastDeadline=deadline;
            long began=System.nanoTime();
            try{return routed(complete(primary,instruction,packet,false,fastDeadline),primary,primary,false,"FAST_LANE_NO_SLOW_FALLBACK",List.of(),began);}
            catch(InferenceFailure failure){throw new InferenceFailure(failure.getMessage(),routed(failure.partial(),primary,primary,false,"FAST_LANE_NO_SLOW_FALLBACK",List.of(),began),failure.retryAfterMillis());}
        }
        if(primary.fallback()==null)return complete(primary,instruction,packet,compression,deadline);
        Instant limit=deadline==null?clock.instant().plusSeconds(requestSeconds(primary)+3L):deadline;
        if(deadline==null){String expiry=mapper.valueToTree(packet).path("evidence").path("decisionExpiresAt").asText();if(!expiry.isBlank()){Instant expires=Instant.parse(expiry);if(expires.isBefore(limit))limit=expires;}}
        var failed=new ArrayList<RouteFailure>();String reason=forced;long began=System.nanoTime();
        if(forced==null)try{return routed(complete(primary,instruction,packet,compression,limit),primary,primary,false,null,failed,began);}
        catch(Exception failure){
            if(failure instanceof InterruptedException||Thread.currentThread().isInterrupted()||!fallbackAllowed(safeError(failure)))throw failure;
            var execution=mapper.valueToTree(packet).path("execution");
            // Research owns format-correction retries. Preserve its primary error until the final allowed attempt.
            if(!compression&&ResearchPolicy.repairable(safeError(failure))
                &&execution.path("attempt").asInt(1)<execution.path("maxAttempts").asInt(1))throw failure;
            reason=safeError(failure);failed.add(new RouteFailure(primary.provider(),primary.model(),reason,failure instanceof InferenceFailure partial?partial.partial():null));
        }
        if(!clock.instant().isBefore(limit)){Answer partial=failed.isEmpty()?null:failed.getFirst().partial();if(partial==null)partial=new Answer(primary.model(),"","NOT_PROVIDED","",null,mapper.createObjectNode(),"UNKNOWN","NOT_RECEIVED",null,0);throw new InferenceFailure("REQUEST_TIMEOUT",routed(partial,primary,primary,false,reason,failed,began));}
        var backup=primary.fallback();
        try{return routed(complete(backup,instruction,packet,compression,limit),primary,backup,true,reason,failed,began);}
        catch(Exception failure){
            if(failure instanceof InterruptedException||Thread.currentThread().isInterrupted())throw failure;
            Answer partial=failure instanceof InferenceFailure incomplete?incomplete.partial():null;
            if(partial==null)partial=new Answer(backup.model(),"","NOT_PROVIDED","",null,mapper.createObjectNode(),"UNKNOWN","NOT_RECEIVED",null,0);
            throw new InferenceFailure(safeError(failure),routed(partial,primary,backup,true,reason,failed,began),failure instanceof InferenceFailure incomplete?incomplete.retryAfterMillis():0);
        }
    }
    private static boolean fallbackAllowed(String error){return ResearchPolicy.retryable(error)||Set.of("MODEL_NOT_AVAILABLE","MODEL_NOT_ADVERTISED","CREDENTIAL_NOT_CONFIGURED","CONTEXT_INSUFFICIENT","AI_PROVIDER_DISABLED").contains(error)||error.matches("(?:INFERENCE|CATALOG)_HTTP_(?:400|401|403|404)(?:_[A-Z0-9_]+)?");}
    private Answer routed(Answer answer,AiRoute primary,AiRoute used,boolean fallback,String reason,List<RouteFailure> failures,long began){var routing=new Routing(primary.provider(),primary.model(),used.provider(),used.model(),fallback,reason,List.copyOf(failures));routeAudits.put(primary.id(),routing);if(fallback)inference.put(primary.id(),answer.output()!=null?"VALID_OUTPUT_VIA_FALLBACK":"FALLBACK_FAILED");return new Answer(answer.model(),answer.analysis(),answer.analysisSource(),answer.raw(),answer.output(),answer.usage(),answer.usageSource(),answer.finishReason(),answer.budget(),(System.nanoTime()-began)/1_000_000,answer.attempts(),routing);}
    private Answer complete(AiRoute route,String instruction,Object packet,boolean compression,Instant attemptDeadline) throws Exception {
        if(!settings.aiProviderEnabled(route))throw new IllegalArgumentException("AI_PROVIDER_DISABLED");
        long started=System.nanoTime();
        String lane=compression?"compression_lane":fastLane(packet)?"fast_lane":"deep_lane";
        var lease=acquireProvider(route,compression,attemptDeadline,lane);
        Answer captured=null;
        long retryAfter=0;
        List<Attempt> attempts=new ArrayList<>();
        try {
            JsonNode catalog=catalog(route);
            if(catalogReadiness(route,catalog).equals("MODEL_NOT_AVAILABLE"))throw new IllegalStateException("MODEL_NOT_AVAILABLE");
            JsonNode capabilities=catalog.path("capabilities");
            Map<String,Object> request=new LinkedHashMap<>();request.put("model",settings.requestModel(route));
            request.put("stream",true);
            request.put("stream_options",Map.of("include_usage",true));
            boolean adjudication=!compression&&mapper.valueToTree(packet).path("phase").asInt(-1)==3;
            boolean unified=mapper.valueToTree(packet).path("protocol").asText().equals(compression?ResearchProtocol.COMPRESSION_VERSION:ResearchProtocol.VERSION);
            request.put("messages",List.of(Map.of("role","system","content",instruction),Map.of("role","user","content",mapper.writeValueAsString(packet))));
            var parameters=capabilities.path("supported_parameters").path("chat_completions");
            boolean structuredTool=!compression&&!settings.preferJsonText(route)&&capabilities.path("tools").path("function_calling").asBoolean(false)
                &&capabilities.path("tools").path("strict").asBoolean(false)&&!capabilities.path("tools").path("bridge").asText().equals("emulated_function_bridge");
            boolean jsonMode=capabilities.path("structured_output").asBoolean(false)||capabilities.path("structured_output").path("supported").asBoolean(false);
            if(!structuredTool&&jsonMode&&!unified)for(var parameter:parameters)if(parameter.asText().equals("response_format"))request.put("response_format",Map.of("type","json_object"));
            if(unified&&!compression)request.put("messages",List.of(Map.of("role","system","content",instruction+ResearchProtocol.instruction(adjudication,structuredTool,ResearchProtocol.onlyWait(mapper.valueToTree(packet)))),Map.of("role","user","content",mapper.writeValueAsString(packet))));
            var sourceOptions=new LinkedHashMap<String,Object>();
            // One external research seat must not silently launch a second team inside MiniMax.
            if(route.provider().equals("minmax")&&capabilities.path("provider_options").has("enable_team"))sourceOptions.put("enable_team",false);
            if(capabilities.path("provider_options").has("web_search_status")&&capabilities.path("parameter_adaptation").path("search").path("mode").asText().equals("mapped"))sourceOptions.put("web_search_status","disabled");
            if((compression||lane.equals("fast_lane"))&&capabilities.path("provider_options").has("thinking"))sourceOptions.put("thinking",false);
            if(lane.equals("fast_lane")&&route.provider().equals("minmax")&&Set.of("mapped","toggle_mapping").contains(capabilities.path("parameter_adaptation").path("reasoning").path("mode").asText()))request.put("reasoning_effort","none");
            if(!sourceOptions.isEmpty())request.put("provider_options",Map.of(route.provider(),sourceOptions));
            if(structuredTool&&!unified) {
                int format=instruction.indexOf("格式："),wait=instruction.indexOf("WAIT 时");
                if(format>=0&&wait>format)request.put("messages",List.of(Map.of("role","system","content",instruction.substring(0,format)+instruction.substring(wait)),
                    Map.of("role","user","content",mapper.writeValueAsString(packet))));
            }
            if(structuredTool){request.put("tools",List.of(Map.of("type","function","function",Map.of("name","submit_research","description","提交本阶段研究结果，只有数据归档，无外部交易行为", "strict",true,"parameters",ResearchProtocol.schema(adjudication)))));
                request.put("tool_choice",Map.of("type","function","function",Map.of("name","submit_research")));}
            String outputParameter=null;
            for(String candidate:List.of("max_tokens","max_completion_tokens","max_output_tokens"))
                for(JsonNode parameter:parameters)if(parameter.asText().equals(candidate)
                    &&capabilities.path("parameter_adaptation").path(candidate).path("mode").asText().equals("mapped")){outputParameter=candidate;break;}
            if(settings.genericAiEndpoint(route))outputParameter="max_tokens";
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
            Instant deadline=attemptDeadline!=null?attemptDeadline:expires.isBlank()?clock.instant().plusSeconds(route.timeoutSeconds()*2L+3):Instant.parse(expires);
            int attemptLimit=attemptDeadline==null?2:1;
            for(int attempt=1;attempt<=attemptLimit;attempt++) {
                long remaining=Duration.between(clock.instant(),deadline).toMillis();if(remaining<=0)throw new IllegalStateException("REQUEST_TIMEOUT");
                var began=clock.instant();long measured=System.nanoTime();
                String rootTrace=packetJson.path("execution").path("clientTraceId").asText();
                if(!rootTrace.matches("[A-Za-z0-9._:-]{1,80}"))rootTrace="gold-"+UUID.randomUUID();
                String clientTraceId=rootTrace+"."+route.provider()+".http-"+attempt;var responseInfo=new AtomicReference<java.net.http.HttpResponse.ResponseInfo>();
                try{response=http.sendInference(HttpRequest.newBuilder(URI.create(route.endpoint("chat/completions")))
                    .timeout(Duration.ofMillis(Math.min(Math.min(route.timeoutSeconds(),lane.equals("fast_lane")?120:900)*1000L,remaining))).header("Authorization","Bearer "+settings.secret(route))
                    .header("X-Client-Trace-Id",clientTraceId)
                    .header("Content-Type","application/json").header("Accept","text/event-stream").header("Accept-Encoding","identity")
                    .POST(HttpRequest.BodyPublishers.ofString(body,StandardCharsets.UTF_8)).build(),8_000_000,responseInfo::set);
                    var headers=responseInfo.get()==null?response.headers():responseInfo.get().headers();
                    attempts.add(new Attempt(attempt,began,(System.nanoTime()-measured)/1000000,response.statusCode(),response.statusCode()==200?null:"INFERENCE_HTTP_"+response.statusCode(),clientTraceId,requestId(headers),response.statusCode()==200?"RECEIVED":"FAILED"));
                    retryAfter=response.statusCode()==200?0:retryAfterMillis(response.headers().firstValue("Retry-After").orElse(""),clock.instant());
                    if(attempt==attemptLimit||!List.of(408,425,429,500,502,503,504).contains(response.statusCode())||Duration.between(clock.instant(),deadline).toSeconds()<5)break;
                    long delay=Math.max(1000,retryAfter);if(delay>=Duration.between(clock.instant(),deadline).toMillis())break;Thread.sleep(delay);
                }catch(Exception transport){var info=responseInfo.get();attempts.add(new Attempt(attempt,began,(System.nanoTime()-measured)/1000000,info==null?null:info.statusCode(),safeError(transport),clientTraceId,info==null?null:requestId(info.headers()),"FAILED"));
                    if(attempt==attemptLimit||!ResearchPolicy.retryable(safeError(transport))||Duration.between(clock.instant(),deadline).toMillis()<=1000)throw transport;
                    Thread.sleep(1000);}
            }
            if(response.statusCode()!=200) {
                String code="";try{String upstream=mapper.readTree(response.body()).path("error").path("code").asText().toUpperCase(Locale.ROOT);
                    if(upstream.matches("[A-Z0-9_]{1,60}"))code="_"+upstream;}catch(RuntimeException ignored){}
                throw new IllegalStateException("INFERENCE_HTTP_"+response.statusCode()+code);
            }
            JsonNode envelope=OpenAiStream.decode(mapper,response.body()),choice=envelope.path("choices").path(0),message=choice.path("message");
            String finish=choice.path("finish_reason").asText("UNKNOWN");
            String raw=message.path("content").asText();
            var calls=message.path("tool_calls");
            boolean submitted=finish.equals("tool_calls")&&calls.isArray()&&calls.size()==1&&calls.path(0).path("function").path("name").asText().equals("submit_research");
            if(submitted)raw=calls.path(0).path("function").path("arguments").asText();
            String reasoning=message.path("reasoning_content").asText();
            String usageSource=envelope.path("usage_source").asText(envelope.path("usage").path("usage_source").asText("UNKNOWN"));
            captured=new Answer(envelope.path("model").asText(route.model()),reasoning,reasoning.isBlank()?"NOT_PROVIDED":"PROVIDER_FIELD",raw,null,envelope.path("usage"),usageSource,
                finish,budget,(System.nanoTime()-started)/1_000_000);
            if(upstreamBusy(raw))throw new IllegalStateException("UPSTREAM_BUSY_TEXT");
            if(structuredTool&&!submitted)throw new IllegalStateException("REQUIRED_SUBMISSION_MISSING");
            if(!submitted&&!finish.equals("stop"))throw new IllegalStateException("OUTPUT_NOT_COMPLETE_"+finish.toUpperCase(Locale.ROOT));
            Integer phase=packetJson.has("phase")?packetJson.path("phase").asInt():null;
            JsonNode output=ResearchProtocol.text(raw)?ResearchProtocol.parse(mapper,raw,compression,adjudication,phase):ResearchProtocol.normalize(parseOutput(raw),compression,adjudication,phase);
            if(!compression&&!adjudication&&!output.path("plan").isObject())throw new IllegalStateException("MISSING_REQUIRED_OUTPUT");
            if(compression)validateCompression(output);else validate(output);
            if(adjudication&&(!output.path("selectedCandidateId").isTextual()||output.path("selectedCandidateId").asText().isBlank()))throw new IllegalStateException("MISSING_JUDGE_VOTE");
            if(!attempts.isEmpty())attempts.set(attempts.size()-1,attempts.getLast().result("VALIDATED_OUTPUT",null));
            String analysis=reasoning.isBlank()?output.path("analysisSummary").asText():reasoning;
            String source=reasoning.isBlank()?(analysis.isBlank()?"NOT_PROVIDED":"EXPLICIT_OUTPUT_SUMMARY"):"PROVIDER_FIELD";
            inference.put(route.id(),"VALID_OUTPUT");failures.remove(route.id());
            if(!compression)failedAt.remove(modelKey(route,lane));
            return new Answer(envelope.path("model").asText(route.model()),analysis,source,raw,output,envelope.path("usage"),usageSource,
                finish,budget,(System.nanoTime()-started)/1_000_000,List.copyOf(attempts));
        }catch(Exception failure) {
            if(!attempts.isEmpty()&&attempts.getLast().outcome().equals("RECEIVED"))attempts.set(attempts.size()-1,attempts.getLast().result("FAILED",safeError(failure)));
            inference.put(route.id(),"FAILED");failures.put(route.id(),safeError(failure));
            if(!compression&&!(failure instanceof InterruptedException)&&!safeError(failure).equals("CONTEXT_INSUFFICIENT"))failedAt.put(modelKey(route,lane),new FailureAt(clock.instant(),safeError(failure)));
            if(captured!=null)throw new InferenceFailure(safeError(failure),new Answer(captured.model(),captured.analysis(),captured.analysisSource(),captured.raw(),captured.output(),
                captured.usage(),captured.usageSource(),captured.finishReason(),captured.budget(),(System.nanoTime()-started)/1_000_000,List.copyOf(attempts)),retryAfter);throw failure;
        }finally{lease.close();}
    }
    private static String requestId(java.net.http.HttpHeaders headers){if(headers==null)return null;
        String id=headers.firstValue("X-Request-Id").orElse(headers.firstValue("X-Any2API-Request-Id").orElse(""));
        return id.matches("[A-Za-z0-9._:-]{1,128}")?id:null;}
    private void validateCompression(JsonNode output) {
        EvidenceCompression.validate(output);
    }
    private JsonNode catalog(AiRoute route) throws Exception {
        if(!settings.aiProviderEnabled(route))throw new IllegalArgumentException("AI_PROVIDER_DISABLED");
        if(settings.secret(route).isBlank())throw new IllegalStateException("CREDENTIAL_NOT_CONFIGURED");
        var cached=currentCatalog(route);
        if(cached!=null&&cached.at().isAfter(clock.instant().minusSeconds(catalogReadiness(route,cached.catalog()).equals("MODEL_NOT_AVAILABLE")?60:300)))return cached.catalog();
        if(settings.genericAiEndpoint(route)) {
            var body=mapper.createObjectNode();body.put("id",route.model());body.put("available",true);body.putObject("capabilities");
            body.put("availabilityBasis",settings.manualAiCatalog(route)?"MANUAL_CONFIGURATION; NOT_INFERENCE_ACCEPTANCE":"ADVERTISED_MODEL_ONLY; NOT_INFERENCE_ACCEPTANCE");
            if(!settings.manualAiCatalog(route)) {
                var response=http.send(HttpRequest.newBuilder(URI.create(route.endpoint("models"))).timeout(Duration.ofSeconds(12))
                    .header("Authorization","Bearer "+settings.secret(route)).GET().build(),500_000);
                if(response.statusCode()!=200)throw new IllegalStateException("CATALOG_HTTP_"+response.statusCode());
                var advertised=mapper.readTree(response.body()).path("data");boolean present=false;
                for(var item:advertised)if(List.of(route.model(),settings.requestModel(route)).contains(item.path("id").asText()))present=true;
                if(!present)throw new IllegalStateException("MODEL_NOT_ADVERTISED");
            }
            catalogs.put(route.id(),new Cached(body,clock.instant(),routeKey(route)));catalogErrors.remove(route.id());return body;
        }
        String model=route.model();
        if(URI.create(route.baseUrl()).getPath().matches(".*/"+java.util.regex.Pattern.quote(route.provider())+"/v1/?$")&&model.startsWith(route.provider()+"/"))model=model.substring(route.provider().length()+1);
        model=Arrays.stream(model.split("/",-1)).map(part->URLEncoder.encode(part,StandardCharsets.UTF_8).replace("+","%20")).collect(java.util.stream.Collectors.joining("/"));
        var response=http.send(HttpRequest.newBuilder(URI.create(route.endpoint("models/"+model)))
            .timeout(Duration.ofSeconds(12)).header("Authorization","Bearer "+settings.secret(route)).GET().build(),500_000);
        if(response.statusCode()!=200)throw new IllegalStateException("CATALOG_HTTP_"+response.statusCode());
        JsonNode body=mapper.readTree(response.body());catalogs.put(route.id(),new Cached(body,clock.instant(),routeKey(route)));catalogErrors.remove(route.id());return body;
    }
    private String routeKey(AiRoute route){return route.provider()+"|"+route.model()+"|"+route.baseUrl()+"|"+settings.genericAiEndpoint(route)+"|"+settings.manualAiCatalog(route)+"|"+NewsService.hash(settings.secret(route));}
    private Cached currentCatalog(AiRoute route){String key=routeKey(route);var cached=catalogs.get(route.id());return cached!=null&&cached.routeKey().equals(key)?cached:catalogs.values().stream().filter(c->c.routeKey().equals(key)).max(Comparator.comparing(Cached::at)).orElse(null);}
    private String modelKey(AiRoute route){boolean shortOnly=settings.routes("SHORT").stream().anyMatch(r->r.id().equals(route.id()))&&settings.routes("LONG").stream().noneMatch(r->r.id().equals(route.id()));return modelKey(route,shortOnly?"fast_lane":"deep_lane");}
    private String modelKey(AiRoute route,String lane){return lane+"|"+route.provider()+"/"+ResearchService.modelIdentity(route)+"|"+route.baseUrl()+"|"+NewsService.hash(settings.secret(route));}
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
    private static boolean upstreamBusy(String raw){if(raw==null||raw.length()>120)return false;String plain=raw.strip().replaceAll("[\\p{Punct}，。！\\s]","").toLowerCase(Locale.ROOT);
        return Set.of("服务器繁忙请稍后再试","服务器繁忙请稍后重试","服务繁忙请稍后再试","serverbusypleasetryagainlater","theserverisbusypleasetryagainlater").contains(plain);}
    JsonNode parseOutput(String raw) {
        String content=ResearchProtocol.unfence(raw);
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
        for(String field:List.of("riskAssessment","invalidate"))if(output.path("plan").path(field).asText().codePointCount(0,output.path("plan").path(field).asText().length())>(field.equals("riskAssessment")?120:80))throw new IllegalStateException("PLAN_NARRATIVE_TOO_LONG_"+field.toUpperCase(Locale.ROOT));
        for(var impact:output.path("impacts"))if(!List.of("SHORT","LONG").contains(impact.path("horizon").asText())
            ||!List.of("BULLISH","BEARISH","UNKNOWN","MIXED").contains(impact.path("direction").asText())
            ||impact.path("reason").asText().length()>500||impact.path("counter").asText().length()>400)throw new IllegalStateException("INVALID_IMPACT");
    }
    public static class InferenceFailure extends IllegalStateException {
        private final Answer partial;
        private final long retryAfterMillis;
        public InferenceFailure(String code,Answer partial){this(code,partial,0);}
        public InferenceFailure(String code,Answer partial,long retryAfterMillis){super(code);this.partial=partial;this.retryAfterMillis=retryAfterMillis;}
        public Answer partial(){return partial;}
        public long retryAfterMillis(){return retryAfterMillis;}
    }
    static long retryAfterMillis(String value,Instant now){
        try{return Math.max(0,Math.multiplyExact(Long.parseLong(value.strip()),1000));}catch(RuntimeException ignored){}
        try{return Math.max(0,Duration.between(now,ZonedDateTime.parse(value,java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).toMillis());}catch(RuntimeException ignored){return 0;}
    }
    public static String safeError(Exception error) {
        if(error instanceof InterruptedException||error instanceof java.util.concurrent.CancellationException)return "REQUEST_CANCELED";
        if(error instanceof java.util.concurrent.TimeoutException||error instanceof java.net.http.HttpTimeoutException)return "REQUEST_TIMEOUT";
        if((error instanceof java.util.concurrent.ExecutionException||error instanceof java.util.concurrent.CompletionException)&&error.getCause() instanceof Exception cause)return safeError(cause);
        if(error instanceof tools.jackson.core.JacksonException)return "INVALID_STRUCTURED_OUTPUT";
        if(error instanceof java.io.IOException)return "UPSTREAM_TRANSPORT_FAILED";
        String message=error instanceof IllegalStateException||error instanceof IllegalArgumentException?error.getMessage():null;
        return message!=null&&message.matches("[A-Z0-9_]+")?message:"UPSTREAM_OR_PARSE_FAILED";
    }
}
