package com.goldcatcher.external;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.goldcatcher.*;
import com.goldcatcher.auth.*;
import com.goldcatcher.auth.PersonalAccessTokens.Identity;
import com.goldcatcher.network.SourceProxyPool;
import com.goldcatcher.research.*;
import com.goldcatcher.simulation.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.node.ObjectNode;

class ExternalOperationsTest {
    final TestSupport f=new TestSupport();final RuntimeApi runtime=mock(RuntimeApi.class);final SimulationLedger ledger=mock(SimulationLedger.class);
    final ResearchService research=mock(ResearchService.class);final PersonalAccessTokens tokens=new PersonalAccessTokens(f.jdbc,f.clock,f.store);
    final ExternalCommands journal=new ExternalCommands(f.jdbc,f.mapper,f.clock);
    final ExternalOperations operations=new ExternalOperations(runtime,ledger,research,mock(EvidenceCompression.class),mock(ReviewService.class),mock(DataFlowApi.class),mock(SystemController.class),mock(SourceProxyPool.class),mock(Any2ApiClient.class),tokens,mock(OAuthService.class),journal,f.mapper);
    final ExternalTools tools=new ExternalTools(mock(ExternalData.class),operations);
    final Identity owner=new Identity("b".repeat(32),"admin",Set.copyOf(AccessScopes.ALL),120);
    @Test void oldReadGrantsStayAtTwelveAndWritesHaveAccurateAnnotationsAndStableKeys(){
        var readOnly=new Identity("read","admin",Set.copyOf(AccessScopes.DEFAULT),120);
        assertThat(tools.available(readOnly)).hasSize(12).allMatch(ExternalTools.Tool::readOnly);
        assertThat(tools.catalog()).hasSizeGreaterThan(50);
        for(var tool:tools.catalog())if(!tool.readOnly()){
            var definition=f.mapper.valueToTree(tool.definition());assertThat(definition.path("annotations").path("readOnlyHint").asBoolean()).isFalse();
            assertThat(definition.path("inputSchema").path("required").toString()).contains("request_key");
        }
        assertThatThrownBy(()->tools.call(readOnly,"start_research",f.mapper.readTree("{\"symbol\":\"XAU_USDT\",\"horizon\":\"SHORT\",\"request_key\":\"no-permission\"}"))).hasMessage("INSUFFICIENT_SCOPE");verifyNoInteractions(runtime,ledger,research);
    }
    @Test void startReturnsAdmissionAndRetryCannotEnqueueAnotherRun(){
        var run=new ResearchService.Run();run.id=UUID.randomUUID().toString();run.symbol="XAU_USDT";run.horizon="SHORT";run.status="QUEUED";
        when(runtime.start(any())).thenReturn(ResponseEntity.accepted().body(run));
        var args=f.mapper.readTree("{\"symbol\":\"XAU_USDT\",\"horizon\":\"SHORT\",\"request_key\":\"research-intent\"}");
        var first=(ExternalCommands.Result)tools.call(owner,"start_research",args);var retry=(ExternalCommands.Result)tools.call(owner,"start_research",args);
        assertThat(first.ok()).isTrue();assertThat(first.result().path("id").asText()).isEqualTo(run.id);assertThat(retry.replayed()).isTrue();verify(runtime,times(1)).start(any());
    }
    @Test void exactDecimalStringsAreMandatoryAndUnknownNestedControlsAreRejected(){
        var args=(ObjectNode)f.mapper.readTree("{\"symbol\":\"XAU_USDT\",\"side\":\"BUY\",\"type\":\"LIMIT\",\"quantity\":\"0.1234567890123456789\",\"leverage\":\"100\",\"entryPrice\":\"4000.01\",\"expiresAt\":\"2026-10-08T01:00:00Z\",\"holdUntil\":\"2026-10-08T02:00:00Z\",\"riskAssessment\":\"risk\",\"invalidate\":\"invalid\",\"request_key\":\"decimal-intent\"}");
        var rejected=new SimulationLedger.Order();rejected.status="REJECTED";rejected.rejection="QUOTE_NOT_EXECUTABLE";when(ledger.propose(anyString(),eq("XAU_USDT"),any())).thenReturn(rejected);
        assertThat(((ExternalCommands.Result)tools.call(owner,"place_simulation_order",args)).ok()).isFalse();
        verify(ledger).propose(anyString(),eq("XAU_USDT"),argThat(plan->plan.path("quantity").asText().equals("0.1234567890123456789")&&plan.path("action").asText().equals("CREATE")));
        args.put("quantity",0.1);assertThatThrownBy(()->tools.call(owner,"place_simulation_order",args)).hasMessage("INVALID_STRING_ARGUMENT");
        assertThatThrownBy(()->tools.call(owner,"update_source_configuration",f.mapper.readTree("{\"revision\":1,\"disabledSourceIds\":[{\"sql\":\"drop\"}],\"request_key\":\"invalid-nested\"}"))).hasMessage("INVALID_STRING_ARGUMENT");
    }
    @Test void accessManagementCannotDelegateRightsTheCallerDoesNotHoldAndSecretsAreOneTime(){
        var restricted=new Identity("limited","admin",Set.of("access:manage"),120);
        var args=f.mapper.readTree("{\"name\":\"child\",\"scopes\":[\"simulation:write\"],\"request_key\":\"delegation-test\"}");
        var denied=(ExternalCommands.Result)tools.call(restricted,"create_access_token",args);assertThat(denied.ok()).isFalse();assertThat(tokens.list("admin")).isEmpty();
        ((ObjectNode)args).put("request_key","delegation-full");var created=(ExternalCommands.Result)tools.call(owner,"create_access_token",args);String secret=created.result().path("secret").asText();
        assertThat(tokens.authenticate(secret).scopes()).containsExactly("simulation:write");
        var repeated=(ExternalCommands.Result)tools.call(owner,"create_access_token",args);assertThat(repeated.result().path("secret").isNull()).isTrue();assertThat(tokens.list("admin")).hasSize(1);
    }
    @Test void restGetCannotExecuteMutationsEvenWithFullGrants(){
        var api=new ExternalDataApi(tools,f.mapper,mock(OAuthService.class));
        var response=(ResponseEntity<?>)api.query(owner,"start_research",Map.of("symbol","XAU_USDT","horizon","SHORT","request_key","get-cannot-write"));
        assertThat(response.getStatusCode().value()).isEqualTo(405);verifyNoInteractions(runtime,ledger,research);assertThat(f.jdbc.queryForObject("SELECT count(*) FROM gc_external_command",Integer.class)).isZero();
    }
    @Test void perToolGrantsHideToolsAndCannotBeBypassedOrEscapedThroughDelegation(){
        var restricted=new Identity("limited","admin",Set.copyOf(AccessScopes.ALL),120,Set.of("get_quote","create_access_token","update_access_token_scopes"));
        assertThat(tools.available(restricted)).extracting(ExternalTools.Tool::name).containsExactlyInAnyOrder("get_quote","create_access_token","update_access_token_scopes");
        assertThatThrownBy(()->tools.call(restricted,"start_research",f.mapper.createObjectNode())).hasMessage("TOOL_NOT_GRANTED");verifyNoInteractions(runtime,ledger,research);
        var args=f.mapper.readTree("{\"name\":\"restricted-child\",\"scopes\":[\"market:read\"],\"request_key\":\"restricted-child\"}");
        var result=(ExternalCommands.Result)tools.call(restricted,"create_access_token",args);var child=tokens.authenticate(result.result().path("secret").asText());
        assertThat(child.allowedTools()).containsExactly("get_quote");
        var escalation=f.mapper.readTree("{\"name\":\"escalation\",\"scopes\":[\"market:read\"],\"allowedTools\":[\"get_candles\"],\"request_key\":\"cannot-escalate\"}");
        var denied=(ExternalCommands.Result)tools.call(restricted,"create_access_token",escalation);assertThat(denied.ok()).isFalse();assertThat(denied.result().path("error").asText()).isEqualTo("CANNOT_DELEGATE_UNGRANTED_TOOL");
    }
    @Test void completeResearchExportIsPagedWithoutExposingCredentialSnapshots(){
        var run=new ResearchService.Run();run.id="run";run.evidence=f.mapper.readTree("{\"apiKey\":\"must-never-leak\",\"nested\":{\"encryptedApiKey\":\"cipher\",\"price\":\"4000.1234567890123456789\"},\"text\":\"黄金😀\"}");
        when(runtime.exportResearch("run")).thenReturn(ResponseEntity.ok(run));
        var result=f.mapper.valueToTree(tools.call(owner,"export_research",f.mapper.readTree("{\"id\":\"run\",\"maxCharacters\":16000}")));
        assertThat(result.path("text").asText()).doesNotContain("must-never-leak","cipher").contains("黄金😀","4000.1234567890123456789");
    }
}
