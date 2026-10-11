package com.goldcatcher.research;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.goldcatcher.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class AiConfigurationTest {
    final TestSupport f=new TestSupport();final EventHub events=mock(EventHub.class);final RemoteHttp remote=mock(RemoteHttp.class);
    AiConfiguration service;
    AiConfigurationTest()throws Exception{
        f.environment.setProperty("GOLD_AI_ROUTES_JSON",Files.readString(Path.of("../config/ai-routes.balanced.example.json")));
        f.environment.setProperty("GOLD_AI_CONFIG_ENCRYPTION_KEY",Base64.getEncoder().encodeToString(new byte[32]));
        f.environment.setProperty("GOLD_COMPRESSION_ENABLED","true");
        for(var route:f.settings.allRoutes())f.environment.setProperty(route.apiKeyEnv(),"bootstrap-test-credential");
        service=new AiConfiguration(f.settings,f.store,f.mapper,f.clock,events,remote);service.recover();
    }
    AiConfiguration.Update update(long revision,String key){
        var view=f.mapper.valueToTree(service.view());var providers=new ArrayList<AiConfiguration.ProviderInput>();
        for(var p:view.path("providers"))providers.add(new AiConfiguration.ProviderInput(p.path("id").asText(),p.path("label").asText(),p.path("baseUrl").asText(),p.path("protocol").asText(),p.path("catalogMode").asText(),p.path("id").asText().equals("mimo")?key:null));
        return new AiConfiguration.Update(revision,providers,f.mapper.treeToValue(view.path("shortRoutes"),AiConfiguration.Horizon.class),f.mapper.treeToValue(view.path("longRoutes"),AiConfiguration.Horizon.class),true,
            f.mapper.treeToValue(view.path("compression"),AiConfiguration.Selection.class));
    }
    @Test void encryptedPersistenceAndAuditNeverExposeKeysAndRestartRestoresRoutes(){
        var result=service.save(update(0,"new-test-secret"));assertThat(result).containsEntry("revision",1L);
        var persisted=f.store.find("ai-configuration","current",AiConfiguration.Configuration.class).orElseThrow();
        assertThat(persisted.providers().getFirst().encryptedApiKey()).isNotBlank().doesNotContain("new-test-secret");
        assertThat(f.mapper.writeValueAsString(result)).doesNotContain("new-test-secret","bootstrap-test-credential","encryptedApiKey","apiKey");
        assertThat(f.mapper.writeValueAsString(f.store.events("ai-configuration",10))).doesNotContain("new-test-secret","bootstrap-test-credential","encryptedApiKey","apiKey");
        var restarted=new AiConfiguration(f.settings,f.store,f.mapper,f.clock,events,remote);restarted.recover();
        assertThat(restarted.view()).containsEntry("revision",1L);assertThat(f.settings.routes("SHORT")).hasSize(6);
        assertThat(f.settings.secret(f.settings.routes("SHORT").getFirst())).isEqualTo("new-test-secret");
        assertThat(f.settings.secret(f.settings.compressionRoute())).isEqualTo("new-test-secret");
        f.environment.setProperty("GOLD_AI_FALLBACK_ROUTES_JSON",f.settings.text("GOLD_AI_ROUTES_JSON","[]"));
        assertThat(f.settings.fallbackRoutes()).isEmpty();
    }
    @Test void newConfigurationKeepsPreviousSnapshotBudgetEndpointAndCredentialFrozen(){
        service.save(update(0,"first-test-secret"));var frozen=f.settings.researchRouteSnapshot("SHORT");var route=frozen.routes().getFirst();
        var second=update(1,"second-test-secret");var providers=new ArrayList<>(second.providers());var old=providers.getFirst();
        providers.set(0,new AiConfiguration.ProviderInput(old.id(),old.label(),"https://new.example.test/mimo/v1",old.protocol(),old.catalogMode(),old.apiKey()));
        service.save(new AiConfiguration.Update(1,providers,second.shortRoutes(),second.longRoutes(),false,second.compression()));
        assertThat(f.settings.researchRouteSnapshot("SHORT").revision()).isEqualTo(2);assertThat(frozen.revision()).isEqualTo(1);
        assertThat(route.baseUrl()).doesNotContain("new.example.test");assertThat(f.settings.secret(route)).isEqualTo("first-test-secret");
        assertThat(f.settings.secret(f.settings.routes("SHORT").getFirst())).isEqualTo("second-test-secret");
        assertThat(f.settings.text("GOLD_COMPRESSION_ENABLED","true")).isEqualTo("false");
    }
    @Test void staleSaveCannotOverwriteNewerVersion(){service.save(update(0,null));assertThatThrownBy(()->service.save(update(0,"stale-test-secret"))).hasMessage("AI_CONFIG_REVISION_CONFLICT");assertThat(service.view()).containsEntry("revision",1L);}
    @Test void retryPolicyPersistsAndOldEncryptedRevisionCanRestoreBindingsAfterProviderChanges(){
        var u=update(0,"first-test-secret");var policy=new ResearchPolicy(4,1,15,300,300,1200,180);
        service.save(new AiConfiguration.Update(0,u.providers(),u.shortRoutes(),u.longRoutes(),true,u.compression(),policy,null));
        var frozen=f.settings.researchRouteSnapshot("SHORT");var publicSnapshot=f.mapper.valueToTree(Map.of("revision",frozen.revision(),"routes",frozen.routes()));
        service.save(update(1,"second-test-secret"));
        var restarted=new AiConfiguration(f.settings,f.store,f.mapper,f.clock,events,remote);restarted.recover();
        assertThat(restarted.policy("SHORT")).isEqualTo(policy);assertThat(restarted.policy(1,"SHORT")).isEqualTo(policy);
        var restored=restarted.routesForRun(publicSnapshot,"SHORT");assertThat(f.settings.secret(restored.getFirst())).isEqualTo("first-test-secret");
        assertThat(f.settings.secret(f.settings.routes("SHORT").getFirst())).isEqualTo("second-test-secret");
        assertThat(f.mapper.writeValueAsString(restarted.view())).doesNotContain("first-test-secret","second-test-secret","encryptedApiKey");
    }
    @Test void invalidRetryPolicyCannotPartiallyPersistConfiguration(){
        var u=update(0,null);assertThatThrownBy(()->service.save(new AiConfiguration.Update(0,u.providers(),u.shortRoutes(),u.longRoutes(),true,u.compression(),new ResearchPolicy(100,2,30,360,360,1440,240),null))).hasMessage("INVALID_RESEARCH_RETRY_POLICY");
        assertThat(service.view()).containsEntry("revision",0L);assertThat(f.store.events("ai-configuration",10)).isEmpty();
    }
    @Test void invalidEndpointDoesNotPartiallySaveConfiguration(){
        var u=update(0,null);var providers=new ArrayList<>(u.providers());var p=providers.getFirst();providers.set(0,new AiConfiguration.ProviderInput(p.id(),p.label(),"https://user:secret@example.test/v1?token=key",p.protocol(),p.catalogMode(),null));
        assertThatThrownBy(()->service.save(new AiConfiguration.Update(0,providers,u.shortRoutes(),u.longRoutes(),true,u.compression()))).hasMessage("INVALID_AI_ENDPOINT");
        assertThat(service.view()).containsEntry("revision",0L);assertThat(f.store.events("ai-configuration",10)).isEmpty();
    }
    @Test void duplicateModelOrMissingThirdJudgeIsRejectedBeforePersistence(){
        var u=update(0,null);var same=new AiConfiguration.Horizon(List.of(u.shortRoutes().researchers().getFirst(),u.shortRoutes().researchers().getFirst(),u.shortRoutes().researchers().getLast()),u.shortRoutes().judges());
        assertThatThrownBy(()->service.save(new AiConfiguration.Update(0,u.providers(),same,u.longRoutes(),true,u.compression()))).hasMessage("DUPLICATE_AI_MODEL");
        var two=new AiConfiguration.Horizon(u.shortRoutes().researchers(),u.shortRoutes().judges().subList(0,2));
        assertThatThrownBy(()->service.save(new AiConfiguration.Update(0,u.providers(),two,u.longRoutes(),true,u.compression()))).hasMessage("THREE_RESEARCH_AND_JUDGE_MODELS_REQUIRED");
        assertThat(service.view()).containsEntry("revision",0L);
    }
    @Test void withdrawnProviderAndCorruptCiphertextCannotBeUsed(){
        var u=update(0,null);var providers=new ArrayList<>(u.providers());providers.add(new AiConfiguration.ProviderInput("qwen","Qwen","https://example.test/v1","OPENAI_COMPATIBLE","MANUAL","test-secret"));
        assertThatThrownBy(()->service.save(new AiConfiguration.Update(0,providers,u.shortRoutes(),u.longRoutes(),true,u.compression()))).hasMessage("INVALID_OR_DISABLED_AI_PROVIDER");
        service.save(u);f.environment.setProperty("GOLD_AI_CONFIG_ENCRYPTION_KEY",Base64.getEncoder().encodeToString("a".repeat(32).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThatThrownBy(()->new AiConfiguration(f.settings,f.store,f.mapper,f.clock,events,remote).recover()).hasMessage("AI_CREDENTIAL_DECRYPTION_FAILED");
    }
    @Test void absentEncryptionKeyFailsClearlyWithoutWriting(){f.environment.setProperty("GOLD_AI_CONFIG_ENCRYPTION_KEY","");assertThatThrownBy(()->service.save(update(0,null))).hasMessage("AI_CONFIG_ENCRYPTION_KEY_MISSING");assertThat(f.store.events("ai-configuration",10)).isEmpty();}
    @Test void explicitCredentialRemovalDoesNotSilentlyFallBackToBootstrapKey(){
        var u=update(0,null);var providers=new ArrayList<>(u.providers());var p=providers.getFirst();providers.set(0,new AiConfiguration.ProviderInput(p.id(),p.label(),p.baseUrl(),p.protocol(),p.catalogMode(),null,true));
        service.save(new AiConfiguration.Update(0,providers,u.shortRoutes(),u.longRoutes(),true,u.compression()));
        assertThat(f.settings.secret(f.settings.routes("SHORT").getFirst())).isBlank();assertThat(f.settings.secret(f.settings.compressionRoute())).isBlank();
        assertThat(f.mapper.valueToTree(service.view()).path("providers").path(0).path("credentialConfigured").asBoolean()).isFalse();
    }
    @Test void customEndpointAndManualModelRemainSeparateFromAdvertisedAvailability()throws Exception{
        var u=update(0,null);var providers=new ArrayList<>(u.providers());var p=providers.getFirst();providers.set(0,new AiConfiguration.ProviderInput(p.id(),"Custom MIMO","https://custom.example.test/v1","OPENAI_COMPATIBLE","MANUAL","custom-test-secret"));
        service.save(new AiConfiguration.Update(0,providers,u.shortRoutes(),u.longRoutes(),true,u.compression()));
        var route=f.settings.routes("SHORT").getFirst();assertThat(f.settings.genericAiEndpoint(route)).isTrue();assertThat(f.settings.requestModel(route)).isEqualTo("mimo-v2.6-pro");
        assertThat(f.settings.manualAiCatalog(route)).isTrue();assertThat(route.baseUrl()).isEqualTo("https://custom.example.test/v1");
        @SuppressWarnings("unchecked") var answer=(java.net.http.HttpResponse<byte[]>)mock(java.net.http.HttpResponse.class);
        when(answer.statusCode()).thenReturn(200);when(answer.body()).thenReturn(f.mapper.writeValueAsString(Map.of("choices",List.of(Map.of("finish_reason","stop","message",Map.of("content","{\"title\":\"等待\",\"output\":\"冻结证据不足\",\"shortDirection\":\"UNKNOWN\",\"longDirection\":\"UNKNOWN\",\"plan\":{\"action\":\"WAIT\"}}"))))).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        when(remote.send(any(),eq(1_000_000))).thenReturn(answer);
        var client=new Any2ApiClient(f.settings,remote,f.mapper,f.clock);assertThat(client.complete(route,"内部模拟",Map.of("phase",0,"evidence",Map.of()))).isNotNull();
        verify(remote,never()).send(any(),eq(500_000));assertThat(client.states().getFirst().catalog().path("availabilityBasis").asText()).contains("MANUAL_CONFIGURATION","NOT_INFERENCE_ACCEPTANCE");
    }
}
