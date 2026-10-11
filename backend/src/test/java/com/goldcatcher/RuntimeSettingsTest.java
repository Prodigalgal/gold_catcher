package com.goldcatcher;

import static org.assertj.core.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class RuntimeSettingsTest {
    @Test void withdrawnProviderCannotReturnThroughAnyRouteGroupOrEmptyOverride() {
        var f=new TestSupport();var banned=route("withdrawn"," QWEN ");var allowed=route("active","glm");
        String json=f.mapper.writeValueAsString(List.of(banned,allowed));
        for(String key:List.of("GOLD_AI_ROUTES_JSON","GOLD_AI_SHORT_ROUTES_JSON","GOLD_AI_LONG_ROUTES_JSON","GOLD_AI_FALLBACK_ROUTES_JSON"))
            f.environment.setProperty(key,json);
        f.environment.setProperty("GOLD_AI_DISABLED_PROVIDERS","");
        assertThat(f.settings.routes()).extracting(RuntimeSettings.AiRoute::id).containsExactly("active");
        assertThat(f.settings.routes("SHORT")).extracting(RuntimeSettings.AiRoute::id).containsExactly("active");
        assertThat(f.settings.routes("LONG")).extracting(RuntimeSettings.AiRoute::id).containsExactly("active");
        assertThat(f.settings.fallbackRoutes()).extracting(RuntimeSettings.AiRoute::id).containsExactly("active");
        assertThat(f.settings.allRoutes()).extracting(RuntimeSettings.AiRoute::provider).containsExactly("glm","mimo");
        assertThat(f.settings.aiProviderEnabled(banned)).isFalse();
    }
    @Test void additionalDisabledProvidersAreNormalizedWithoutRemovingMandatoryExclusion() {
        var f=new TestSupport();f.environment.setProperty("GOLD_AI_DISABLED_PROVIDERS"," GLM ,glm,, Arena ");
        assertThat(f.settings.disabledAiProviders()).containsExactly("arena","glm","qwen");
        assertThat(f.settings.aiProviderEnabled(route("a","GLM"))).isFalse();
        assertThat(f.settings.aiProviderEnabled(route("b","minmax"))).isTrue();
    }
    private RuntimeSettings.AiRoute route(String id,String provider) {
        return new RuntimeSettings.AiRoute(id,provider,id+"-model","https://localhost/v1","TEST",10000,16000,30000,2,1000,2,false);
    }
}
