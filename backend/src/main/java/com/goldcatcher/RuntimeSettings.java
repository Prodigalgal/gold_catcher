package com.goldcatcher;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Component
public class RuntimeSettings {
    private final Environment environment;
    private final ObjectMapper mapper;
    public RuntimeSettings(Environment environment, ObjectMapper mapper) {
        this.environment = environment; this.mapper = mapper;
    }
    public String text(String key, String fallback) { return environment.getProperty(key, fallback); }
    public int integer(String key, int fallback) { return environment.getProperty(key, Integer.class, fallback); }
    public boolean enabled(String key) { return environment.getProperty(key, Boolean.class, false); }
    public BigDecimal decimal(String key, String fallback) {
        String value = text(key, fallback);
        return value == null || value.isBlank() ? null : new BigDecimal(value);
    }
    public List<AiRoute> routes() {
        return parseRoutes(text("GOLD_AI_ROUTES_JSON", "[]"));
    }
    public List<AiRoute> routes(String horizon){return parseRoutes(text("GOLD_AI_"+horizon+"_ROUTES_JSON",text("GOLD_AI_ROUTES_JSON","[]")));}
    public List<AiRoute> fallbackRoutes(){return parseRoutes(text("GOLD_AI_FALLBACK_ROUTES_JSON","[]"),true);}
    public AiRoute compressionRoute(){
        String configured=text("GOLD_AI_COMPRESSION_ROUTE_JSON","");
        var route=configured.isBlank()?new AiRoute("compressor-mimo","mimo","mimo/mimo-v2.6-pro",
            text("GOLD_AI_MIMO_BASE_URL","https://any2api-direct.mnnu.eu.org/mimo/v1"),"ANY2API_MIMO_API_KEY",
            8000,14000,24000,2,1000,45,false):mapper.readValue(configured,AiRoute.class);
        if(!route.id().equals("compressor-mimo")||!route.provider().equals("mimo")||route.judge())
            throw new IllegalArgumentException("COMPRESSION_FIXED_TO_MIMO");
        return route;
    }
    public List<AiRoute> allRoutes(){var routes=new java.util.LinkedHashMap<String,AiRoute>();
        for(var group:List.of(routes(),routes("SHORT"),routes("LONG"),fallbackRoutes(),List.of(compressionRoute())))for(var route:group){var previous=routes.putIfAbsent(route.id(),route);
            if(previous!=null&&(!previous.provider().equals(route.provider())||!previous.model().equals(route.model())))throw new IllegalArgumentException("CONFLICTING_ROUTE_ID");}
        return List.copyOf(routes.values());}
    private List<AiRoute> parseRoutes(String json){
        return parseRoutes(json,false);
    }
    private List<AiRoute> parseRoutes(String json,boolean fallback){
        List<AiRoute> routes=mapper.readValue(json, new TypeReference<List<AiRoute>>() {});
        if(routes.stream().map(AiRoute::id).distinct().count()!=routes.size())throw new IllegalArgumentException("DUPLICATE_ROUTE_ID");
        if(!fallback&&routes.stream().filter(AiRoute::judge).count()>1)throw new IllegalArgumentException("ONE_JUDGE_REQUIRED");
        return routes;
    }
    public String secret(AiRoute route) { return text(route.apiKeyEnv(), ""); }
    public boolean preferJsonText(AiRoute route) {
        return java.util.Arrays.stream(text("GOLD_AI_JSON_TEXT_PROVIDERS","mimo,glm").split(","))
            .map(String::strip).anyMatch(route.provider()::equals);
    }
    public record AiRoute(String id, String provider, String model, String baseUrl, String apiKeyEnv,
        int inputEstimateLimit, int characterLimit, int byteLimit, int messageLimit,
        int outputReserve, int timeoutSeconds, boolean judge) {
        public AiRoute {
            if (id == null || id.isBlank() || provider == null || provider.isBlank() || model == null || model.isBlank() || baseUrl == null || apiKeyEnv == null || apiKeyEnv.isBlank()
                || inputEstimateLimit < 512 || characterLimit < 1024 || byteLimit < 1024 || messageLimit < 2
                || outputReserve < 128 || timeoutSeconds < 1 || timeoutSeconds > 300)
                throw new IllegalArgumentException("Invalid AI route or independent context budget");
            var uri = java.net.URI.create(baseUrl);
            if (!List.of("http", "https").contains(uri.getScheme()) || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null)
                throw new IllegalArgumentException("Invalid AI endpoint");
        }
        public String endpoint(String resource) {
            String base = baseUrl.replaceAll("/+$", "");
            return base + (base.endsWith("/v1") ? "/" : "/v1/") + resource;
        }
    }
}
