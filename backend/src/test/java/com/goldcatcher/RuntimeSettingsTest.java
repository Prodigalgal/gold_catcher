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
    @Test void allProfilesUseTheSameTrioForResearchAndJudgingWithSeparateMimoCompression() throws Exception {
        var f=new TestSupport();
        for(String profile:List.of("balanced","fast","deep")) {
            f.environment.setProperty("GOLD_AI_ROUTES_JSON",java.nio.file.Files.readString(java.nio.file.Path.of("../config/ai-routes."+profile+".example.json")));
            var researchers=f.settings.routes().stream().filter(route->!route.judge()).toList();
            var judges=f.settings.routes().stream().filter(RuntimeSettings.AiRoute::judge).toList();
            assertThat(researchers).hasSize(3);assertThat(judges).hasSize(3);
            assertThat(researchers).extracting(RuntimeSettings.AiRoute::provider).containsExactly("mimo","deepseek","grok_web");
            assertThat(judges).extracting(RuntimeSettings.AiRoute::model).containsExactlyElementsOf(researchers.stream().map(RuntimeSettings.AiRoute::model).toList());
            assertThat(researchers).noneMatch(route->route.id().equals("compressor-mimo"));
        }
        f.environment.setProperty("GOLD_AI_FALLBACK_ROUTES_JSON",java.nio.file.Files.readString(java.nio.file.Path.of("../config/ai-routes.fallback.example.json")));
        assertThat(f.settings.fallbackRoutes()).isEmpty();
        assertThat(f.settings.compressionRoute().provider()).isEqualTo("mimo");
        assertThat(f.settings.compressionRoute().model()).isNotEqualTo(f.settings.routes().getFirst().model());
    }
    @Test void equalRecoveredRoutesKeepCredentialsAndProtocolDuringGarbageCollection()throws Exception{
        var f=new TestSupport();registerTransientRoute(f.settings);
        var restored=route("restored","custom");f.settings.registerConfiguredRoute(restored,"configured-test-key","OPENAI_COMPATIBLE","MANUAL");
        for(int i=0;i<5;i++){
            System.gc();Thread.sleep(10);
            assertThat(f.settings.secret(restored)).isEqualTo("configured-test-key");
            assertThat(f.settings.genericAiEndpoint(restored)).isTrue();assertThat(f.settings.manualAiCatalog(restored)).isTrue();
        }
    }
    private void registerTransientRoute(RuntimeSettings settings){settings.registerConfiguredRoute(route("restored","custom"),"configured-test-key","OPENAI_COMPATIBLE","MANUAL");}
    private RuntimeSettings.AiRoute route(String id,String provider) {
        return new RuntimeSettings.AiRoute(id,provider,id+"-model","https://localhost/v1","TEST",10000,16000,30000,2,1000,2,false);
    }
}
