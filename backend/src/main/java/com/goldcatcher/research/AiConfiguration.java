package com.goldcatcher.research;

import com.goldcatcher.*;
import com.goldcatcher.RuntimeSettings.AiRoute;
import com.goldcatcher.persistence.DocumentStore;
import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Versioned server configuration. Plaintext credentials never enter responses or audit events. */
@Service
public class AiConfiguration {
    public record Selection(String provider,String model,int inputEstimateLimit,int characterLimit,int byteLimit,
                            int messageLimit,int outputReserve,int timeoutSeconds,Selection fallback) {
        public Selection(String provider,String model,int inputEstimateLimit,int characterLimit,int byteLimit,int messageLimit,int outputReserve,int timeoutSeconds){this(provider,model,inputEstimateLimit,characterLimit,byteLimit,messageLimit,outputReserve,timeoutSeconds,null);}
    }
    public record Horizon(List<Selection> researchers,List<Selection> judges) {}
    public record ProviderInput(String id,String label,String baseUrl,String protocol,String catalogMode,String apiKey,Boolean clearApiKey) {
        public ProviderInput(String id,String label,String baseUrl,String protocol,String catalogMode,String apiKey){this(id,label,baseUrl,protocol,catalogMode,apiKey,false);}
    }
    public record Update(long expectedRevision,List<ProviderInput> providers,Horizon shortRoutes,Horizon longRoutes,boolean compressionEnabled,Selection compression,ResearchPolicy shortPolicy,ResearchPolicy longPolicy) {
        public Update(long expectedRevision,List<ProviderInput> providers,Horizon shortRoutes,Horizon longRoutes,boolean compressionEnabled,Selection compression){this(expectedRevision,providers,shortRoutes,longRoutes,compressionEnabled,compression,null,null);}
    }
    public record Provider(String id,String label,String baseUrl,String protocol,String catalogMode,String encryptedApiKey) {}
    public record Configuration(long revision,Instant updatedAt,List<Provider> providers,Horizon shortRoutes,Horizon longRoutes,boolean compressionEnabled,Selection compression,ResearchPolicy shortPolicy,ResearchPolicy longPolicy,Integer schemaVersion) {
        public Configuration(long revision,Instant updatedAt,List<Provider> providers,Horizon shortRoutes,Horizon longRoutes,boolean compressionEnabled,Selection compression,ResearchPolicy shortPolicy,ResearchPolicy longPolicy){this(revision,updatedAt,providers,shortRoutes,longRoutes,compressionEnabled,compression,shortPolicy,longPolicy,1);}
        public Configuration{if(shortPolicy==null)shortPolicy=ResearchPolicy.defaults("SHORT");if(longPolicy==null)longPolicy=ResearchPolicy.defaults("LONG");if(schemaVersion==null)schemaVersion=0;}
    }
    public record ModelChoice(String id,String name,Boolean available,Long contextTokens,Long inputTokens,Long outputTokens) {}
    public record ModelCatalog(String provider,String state,Instant retrievedAt,List<ModelChoice> models,String error) {}
    private final RuntimeSettings settings;private final DocumentStore store;private final ObjectMapper mapper;
    private final Clock clock;private final EventHub events;private final RemoteHttp http;
    private final Map<String,String> bootstrapKeys=new HashMap<>();
    private final Map<String,ModelCatalog> catalogs=new ConcurrentHashMap<>();
    private final SecureRandom random=new SecureRandom();private volatile Configuration current;
    public AiConfiguration(RuntimeSettings settings,DocumentStore store,ObjectMapper mapper,Clock clock,EventHub events,RemoteHttp http){
        this.settings=settings;this.store=store;this.mapper=mapper;this.clock=clock;this.events=events;this.http=http;
    }
    @PostConstruct public void recover(){
        var providers=new LinkedHashMap<String,Provider>();
        for(var group:List.of(settings.bootstrapRoutes("SHORT"),settings.bootstrapRoutes("LONG"),settings.fallbackRoutes(),List.of(settings.compressionRoute())))
            for(var route:group)if(settings.aiProviderEnabled(route)){
                providers.putIfAbsent(route.provider(),new Provider(route.provider(),route.provider(),route.baseUrl(),"ANY2API","DISCOVERED",null));
                bootstrapKeys.putIfAbsent(route.provider(),settings.secret(route));
            }
        bootstrapKeys.put("arena",settings.text("ANY2API_ARENA_API_KEY",""));
        providers.putIfAbsent("arena",arenaProvider());
        var defaults=new Configuration(0,null,List.copyOf(providers.values()),arenaFallbacks(horizon(settings.bootstrapRoutes("SHORT"))),arenaFallbacks(horizon(settings.bootstrapRoutes("LONG"))),settings.enabled("GOLD_COMPRESSION_ENABLED"),arenaFallback(selection(settings.compressionRoute())),ResearchPolicy.from(settings,"SHORT"),ResearchPolicy.from(settings,"LONG"));
        current=store.find("ai-configuration","current",Configuration.class).orElse(defaults);
        if(current.schemaVersion()==0){
            var old=current;var upgradedProviders=new ArrayList<>(old.providers());
            if(upgradedProviders.stream().noneMatch(p->p.id().equals("arena"))){var arena=arenaProvider();upgradedProviders.add(new Provider(arena.id(),arena.label(),arena.baseUrl(),arena.protocol(),arena.catalogMode(),encryptionConfigured()?encrypt(bootstrapKeys.getOrDefault("arena","")):null));}
            current=new Configuration(old.revision()+1,clock.instant(),List.copyOf(upgradedProviders),arenaFallbacks(old.shortRoutes()),arenaFallbacks(old.longRoutes()),old.compressionEnabled(),arenaFallback(old.compression()),old.shortPolicy(),old.longPolicy());
            var upgraded=current;store.transaction(()->{store.save("ai-configuration-history",Long.toString(old.revision()),old);store.save("ai-configuration-history",Long.toString(upgraded.revision()),upgraded);store.save("ai-configuration","current",upgraded);return true;});
        }
        if(current.revision()==0)publish(current);
        if(current.revision()>0){publish(current);store.save("ai-configuration","current",current);store.save("ai-configuration-history",Long.toString(current.revision()),current);}
    }
    private Provider arenaProvider(){return new Provider("arena","Arena Max（随机模型）",settings.text("GOLD_AI_ARENA_BASE_URL",settings.text("GOLD_ANY2API_BASE_URL","https://any2api-direct.mnnu.eu.org").replaceAll("/+$","")+"/arena/v1"),"ANY2API","DISCOVERED",null);}
    private Horizon arenaFallbacks(Horizon h){return new Horizon(h.researchers().stream().map(this::arenaFallback).toList(),h.judges().stream().map(this::arenaFallback).toList());}
    private Selection arenaFallback(Selection s){return s.fallback()!=null?s:new Selection(s.provider(),s.model(),s.inputEstimateLimit(),s.characterLimit(),s.byteLimit(),s.messageLimit(),s.outputReserve(),s.timeoutSeconds(),new Selection("arena","arena/Max",s.inputEstimateLimit(),s.characterLimit(),s.byteLimit(),s.messageLimit(),s.outputReserve(),s.timeoutSeconds()));}
    private Horizon horizon(List<AiRoute> routes){return new Horizon(routes.stream().filter(r->!r.judge()).map(this::selection).toList(),routes.stream().filter(AiRoute::judge).map(this::selection).toList());}
    private Selection selection(AiRoute route){return new Selection(route.provider(),route.model(),route.inputEstimateLimit(),route.characterLimit(),route.byteLimit(),route.messageLimit(),route.outputReserve(),route.timeoutSeconds(),route.fallback()==null?null:selection(route.fallback()));}
    public Map<String,Object> view(){return publicView(current);}
    private Map<String,Object> publicView(Configuration config){
        var result=new LinkedHashMap<String,Object>(Map.of("revision",config.revision(),"updatedAt",config.updatedAt()==null?"":config.updatedAt(),"shortRoutes",config.shortRoutes(),"longRoutes",config.longRoutes(),"compressionEnabled",config.compressionEnabled(),"compression",config.compression(),
            "providers",config.providers().stream().map(p->Map.of("id",p.id(),"label",p.label(),"baseUrl",p.baseUrl(),"protocol",p.protocol(),"catalogMode",p.catalogMode(),
                "credentialConfigured",!credential(p).isBlank(),"disabled",settings.disabledAiProviders().contains(p.id()))).toList(),
            "encryptionConfigured",encryptionConfigured(),"catalogs",config.providers().stream().map(p->catalogs.getOrDefault(p.id(),new ModelCatalog(p.id(),p.catalogMode().equals("MANUAL")?"MANUAL":"NOT_CHECKED",null,List.of(),null))).toList()));
        result.put("shortPolicy",config.shortPolicy());result.put("longPolicy",config.longPolicy());return result;
    }
    public ResearchPolicy policy(String horizon){return horizon.equals("LONG")?current.longPolicy():current.shortPolicy();}
    public ResearchPolicy policy(long revision,String horizon){if(revision==0)return ResearchPolicy.from(settings,horizon);
        var snapshot=current;var frozen=snapshot.revision()==revision?snapshot:store.find("ai-configuration-history",Long.toString(revision),Configuration.class).orElseThrow(()->new IllegalStateException("FROZEN_AI_CONFIGURATION_UNAVAILABLE"));
        return horizon.equals("LONG")?frozen.longPolicy():frozen.shortPolicy();}
    /** Restore the encrypted revision used by a run, without publishing it as the current UI configuration. */
    public List<AiRoute> routesForRun(tools.jackson.databind.JsonNode frozen,String horizon){
        long revision=frozen.path("revision").asLong(-1);
        if(revision==0&&current.revision()==0)return buildRoutes(current).get(horizon);
        var configuration=current.revision()==revision?current:store.find("ai-configuration-history",Long.toString(revision),Configuration.class)
            .orElseThrow(()->new IllegalStateException("FROZEN_AI_CONFIGURATION_UNAVAILABLE"));
        var routes=buildRoutes(configuration).get(horizon);
        for(var archived:frozen.path("routes"))if(routes.stream().noneMatch(route->route.id().equals(archived.path("id").asText())&&route.model().equals(archived.path("model").asText())
            &&route.judge()==archived.path("judge").asBoolean()&&route.timeoutSeconds()==archived.path("timeoutSeconds").asInt()
            &&fallbackMatches(route,archived.path("fallback"))))
            throw new IllegalStateException("FROZEN_AI_CONFIGURATION_MISMATCH");
        return routes;
    }
    private boolean fallbackMatches(AiRoute route,tools.jackson.databind.JsonNode frozen){return route.fallback()==null?frozen.isMissingNode()||frozen.isNull():frozen.path("provider").asText().equals(route.fallback().provider())&&frozen.path("model").asText().equals(route.fallback().model())&&frozen.path("timeoutSeconds").asInt()==route.fallback().timeoutSeconds();}
    public synchronized Map<String,Object> save(Update update){
        if(update==null||update.expectedRevision()!=current.revision())throw new IllegalArgumentException("AI_CONFIG_REVISION_CONFLICT");
        if(!encryptionConfigured())throw new IllegalArgumentException("AI_CONFIG_ENCRYPTION_KEY_MISSING");
        if(update.providers()==null||update.providers().isEmpty()||update.providers().size()>20)throw new IllegalArgumentException("INVALID_AI_PROVIDERS");
        var providers=new ArrayList<Provider>();var ids=new HashSet<String>();
        for(var input:update.providers()){
            if(input==null||input.id()==null||!input.id().matches("[a-z][a-z0-9_-]{1,31}")||!ids.add(input.id())||settings.disabledAiProviders().contains(input.id()))throw new IllegalArgumentException("INVALID_OR_DISABLED_AI_PROVIDER");
            if(input.label()==null||input.label().isBlank()||input.label().length()>60||!List.of("ANY2API","OPENAI_COMPATIBLE").contains(input.protocol())||!List.of("DISCOVERED","MANUAL").contains(input.catalogMode())
                ||input.protocol().equals("ANY2API")&&!input.catalogMode().equals("DISCOVERED"))throw new IllegalArgumentException("INVALID_AI_PROVIDER_PROTOCOL");
            validateEndpoint(input.baseUrl());
            var old=current.providers().stream().filter(p->p.id().equals(input.id())).findFirst().orElse(null);
            if(Boolean.TRUE.equals(input.clearApiKey())&&input.apiKey()!=null&&!input.apiKey().isBlank())throw new IllegalArgumentException("INVALID_AI_CREDENTIAL");
            String key=Boolean.TRUE.equals(input.clearApiKey())?"":input.apiKey()==null||input.apiKey().isBlank()?(old==null?"":credential(old)):input.apiKey().strip();
            if(key.length()>16384||key.contains("\n")||key.contains("\r"))throw new IllegalArgumentException("INVALID_AI_CREDENTIAL");
            providers.add(new Provider(input.id(),input.label().strip(),input.baseUrl().replaceAll("/+$",""),input.protocol(),input.catalogMode(),encrypt(key)));
        }
        if(update.compression()==null)throw new IllegalArgumentException("INVALID_AI_MODEL_SELECTION");
        var shortRoutes=validateHorizon(update.shortRoutes(),ids);var longRoutes=validateHorizon(update.longRoutes(),ids);
        var compression=validateSelections(List.of(update.compression()),ids).getFirst();
        var shortPolicy=(update.shortPolicy()==null?current.shortPolicy():update.shortPolicy()).validated();
        var longPolicy=(update.longPolicy()==null?current.longPolicy():update.longPolicy()).validated();
        var next=new Configuration(current.revision()+1,clock.instant(),List.copyOf(providers),shortRoutes,longRoutes,update.compressionEnabled(),compression,shortPolicy,longPolicy);
        // Build before saving so malformed budgets/endpoints cannot partially mutate the configuration.
        var routes=buildRoutes(next);
        store.transaction(()->{if(current.revision()>0)store.save("ai-configuration-history",Long.toString(current.revision()),current);
            store.save("ai-configuration-history",Long.toString(next.revision()),next);store.save("ai-configuration","current",next);store.event("ai-configuration","CONFIG_CHANGED",publicView(next));return true;});
        current=next;settings.applyResearchRoutes(next.revision(),routes,buildCompression(next),next.compressionEnabled());catalogs.clear();events.changed();return view();
    }
    private Horizon validateHorizon(Horizon horizon,Set<String> providers){
        if(horizon==null||horizon.researchers()==null||horizon.judges()==null||horizon.researchers().size()!=3||horizon.judges().size()!=3)throw new IllegalArgumentException("THREE_RESEARCH_AND_JUDGE_MODELS_REQUIRED");
        return new Horizon(validateSelections(horizon.researchers(),providers),validateSelections(horizon.judges(),providers));
    }
    private List<Selection> validateSelections(List<Selection> selections,Set<String> providers){
        var result=new ArrayList<Selection>();var models=new HashSet<String>();var vendors=new HashSet<String>();
        for(var s:selections){
            if(s==null||!providers.contains(s.provider())||s.model()==null||!s.model().matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,159}"))throw new IllegalArgumentException("INVALID_AI_MODEL_SELECTION");
            String model=s.model().startsWith(s.provider()+"/")?s.model():s.provider()+"/"+s.model();
            if(!models.add(ResearchService.modelIdentity(model)))throw new IllegalArgumentException("DUPLICATE_AI_MODEL");
            if(!vendors.add(s.provider()))throw new IllegalArgumentException("THREE_DISTINCT_AI_PROVIDERS_REQUIRED");
            if(s.inputEstimateLimit()<512||s.inputEstimateLimit()>200000||s.characterLimit()<1024||s.characterLimit()>200000||s.byteLimit()<1024||s.byteLimit()>800000
                ||s.outputReserve()<128||s.outputReserve()>32000||s.messageLimit()!=2||s.timeoutSeconds()<1||s.timeoutSeconds()>300)throw new IllegalArgumentException("INVALID_AI_CONTEXT_BUDGET");
            Selection fallback=null;
            if(s.fallback()!=null){
                if(s.fallback().fallback()!=null)throw new IllegalArgumentException("INVALID_AI_FALLBACK");
                fallback=validateSelections(List.of(s.fallback()),providers).getFirst();
                if(s.provider().equals(fallback.provider())&&model.equals(fallback.model()))throw new IllegalArgumentException("INVALID_AI_FALLBACK");
            }
            result.add(new Selection(s.provider(),model,s.inputEstimateLimit(),s.characterLimit(),s.byteLimit(),s.messageLimit(),s.outputReserve(),s.timeoutSeconds(),fallback));
        }
        return List.copyOf(result);
    }
    private void publish(Configuration config){settings.applyResearchRoutes(config.revision(),buildRoutes(config),buildCompression(config),config.compressionEnabled());}
    private AiRoute buildCompression(Configuration config){var s=config.compression();var p=config.providers().stream().filter(provider->provider.id().equals(s.provider())).findFirst().orElseThrow();
        var route=new AiRoute("compressor-mimo",p.id(),s.model(),p.baseUrl(),"UI_CONFIGURED",s.inputEstimateLimit(),s.characterLimit(),s.byteLimit(),s.messageLimit(),s.outputReserve(),s.timeoutSeconds(),false,buildFallback(config,s,"compressor-mimo-fallback",false));
        settings.registerConfiguredRoute(route,credential(p),p.protocol(),p.catalogMode());return route;}
    private Map<String,List<AiRoute>> buildRoutes(Configuration config){
        var routes=new LinkedHashMap<String,List<AiRoute>>();
        for(String horizon:List.of("SHORT","LONG")){
            Horizon profile=horizon.equals("SHORT")?config.shortRoutes():config.longRoutes();var group=new ArrayList<AiRoute>();
            for(boolean judge:List.of(false,true))for(var s:judge?profile.judges():profile.researchers()){
                var p=config.providers().stream().filter(provider->provider.id().equals(s.provider())).findFirst().orElseThrow(()->new IllegalArgumentException("AI_PROVIDER_NOT_CONFIGURED"));
                var route=new AiRoute("cfg-"+config.revision()+"-"+horizon.toLowerCase(Locale.ROOT)+"-"+(judge?"judge":"research")+"-"+group.size(),p.id(),s.model(),p.baseUrl(),"UI_CONFIGURED",
                    s.inputEstimateLimit(),s.characterLimit(),s.byteLimit(),s.messageLimit(),s.outputReserve(),s.timeoutSeconds(),judge,buildFallback(config,s,"cfg-"+config.revision()+"-"+horizon.toLowerCase(Locale.ROOT)+"-fallback-"+group.size(),judge));
                settings.registerConfiguredRoute(route,credential(p),p.protocol(),p.catalogMode());group.add(route);
            }
            routes.put(horizon,List.copyOf(group));
        }
        return Map.copyOf(routes);
    }
    private AiRoute buildFallback(Configuration config,Selection selection,String id,boolean judge){
        var s=selection.fallback();if(s==null)return null;
        var p=config.providers().stream().filter(provider->provider.id().equals(s.provider())).findFirst().orElseThrow(()->new IllegalArgumentException("AI_PROVIDER_NOT_CONFIGURED"));
        var route=new AiRoute(id,p.id(),s.model(),p.baseUrl(),"UI_CONFIGURED",s.inputEstimateLimit(),s.characterLimit(),s.byteLimit(),s.messageLimit(),s.outputReserve(),s.timeoutSeconds(),judge);
        settings.registerConfiguredRoute(route,credential(p),p.protocol(),p.catalogMode());return route;
    }
    private String credential(Provider provider){return provider.encryptedApiKey()==null?bootstrapKeys.getOrDefault(provider.id(),""):decrypt(provider.encryptedApiKey());}
    private boolean encryptionConfigured(){try{return Base64.getDecoder().decode(settings.text("GOLD_AI_CONFIG_ENCRYPTION_KEY","")).length==32;}catch(IllegalArgumentException invalid){return false;}}
    private String encrypt(String value){try{
        byte[] nonce=new byte[12];random.nextBytes(nonce);var cipher=cipher(Cipher.ENCRYPT_MODE,nonce);byte[] encrypted=cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        byte[] combined=new byte[nonce.length+encrypted.length];System.arraycopy(nonce,0,combined,0,nonce.length);System.arraycopy(encrypted,0,combined,nonce.length,encrypted.length);return Base64.getEncoder().encodeToString(combined);
    }catch(Exception failure){throw new IllegalStateException("AI_CREDENTIAL_ENCRYPTION_FAILED");}}
    private String decrypt(String value){try{byte[] combined=Base64.getDecoder().decode(value);byte[] nonce=Arrays.copyOf(combined,12);
        return new String(cipher(Cipher.DECRYPT_MODE,nonce).doFinal(Arrays.copyOfRange(combined,12,combined.length)),StandardCharsets.UTF_8);
    }catch(Exception failure){throw new IllegalStateException("AI_CREDENTIAL_DECRYPTION_FAILED");}}
    private Cipher cipher(int mode,byte[] nonce)throws Exception{var cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode,new SecretKeySpec(Base64.getDecoder().decode(settings.text("GOLD_AI_CONFIG_ENCRYPTION_KEY","")),"AES"),new GCMParameterSpec(128,nonce));return cipher;}
    private void validateEndpoint(String endpoint){try{var uri=URI.create(endpoint);
        if(endpoint.length()>500||!List.of("http","https").contains(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null)throw new IllegalArgumentException();
    }catch(Exception invalid){throw new IllegalArgumentException("INVALID_AI_ENDPOINT");}}
    @Scheduled(initialDelay=7000,fixedDelay=300000) public void warmCatalogs(){refreshCatalogs();}
    public Map<String,Object> refreshCatalogs(){var config=current;
        if(config==null)return Map.of();
        for(var provider:config.providers()){
            ModelCatalog result;
            if(provider.catalogMode().equals("MANUAL"))result=new ModelCatalog(provider.id(),"MANUAL",clock.instant(),List.of(),null);
            else if(credential(provider).isBlank())result=new ModelCatalog(provider.id(),"CREDENTIAL_MISSING",clock.instant(),List.of(),null);
            else try{
                String endpoint=provider.baseUrl().replaceAll("/+$","");endpoint+=(endpoint.endsWith("/v1")?"/":"/v1/")+"models";
                var response=http.send(HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(6)).header("Authorization","Bearer "+credential(provider)).GET().build(),500_000);
                if(response.statusCode()!=200)throw new IllegalStateException("CATALOG_HTTP_"+response.statusCode());
                var body=mapper.readTree(response.body());if(!body.path("data").isArray())throw new IllegalStateException("INVALID_MODEL_CATALOG");
                var choices=new ArrayList<ModelChoice>();for(var model:body.path("data")){
                    String id=model.path("id").asText();if(!id.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,159}"))continue;
                    if(!id.startsWith(provider.id()+"/"))id=provider.id()+"/"+id;
                    choices.add(new ModelChoice(id,model.path("name").asText(id),model.path("available").isBoolean()?model.path("available").asBoolean():null,
                        tokenLimit(model,"max_context_tokens"),tokenLimit(model,"max_input_tokens"),tokenLimit(model,"max_output_tokens")));
                }
                result=new ModelCatalog(provider.id(),"CURRENT",clock.instant(),List.copyOf(choices),null);
            }catch(Exception failure){result=new ModelCatalog(provider.id(),"FAILED",clock.instant(),List.of(),failure.getMessage()!=null&&failure.getMessage().matches("CATALOG_HTTP_[0-9]+|INVALID_MODEL_CATALOG")?failure.getMessage():"CATALOG_REQUEST_FAILED");}
            synchronized(this){if(current.revision()==config.revision())catalogs.put(provider.id(),result);}
        }
        events.changed();return view();
    }
    private Long tokenLimit(tools.jackson.databind.JsonNode model,String key){return model.path(key).canConvertToLong()?model.path(key).asLong():null;}
}
