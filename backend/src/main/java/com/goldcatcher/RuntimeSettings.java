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
        List<AiRoute> routes=mapper.readValue(text("GOLD_AI_ROUTES_JSON", "[]"), new TypeReference<List<AiRoute>>() {});
        if(routes.stream().map(AiRoute::id).distinct().count()!=routes.size())throw new IllegalArgumentException("DUPLICATE_ROUTE_ID");
        if(routes.stream().filter(AiRoute::judge).count()>1)throw new IllegalArgumentException("ONE_JUDGE_REQUIRED");
        return routes;
    }
    public String secret(AiRoute route) { return text(route.apiKeyEnv(), ""); }
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
