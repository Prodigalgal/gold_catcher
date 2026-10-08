package com.goldcatcher.research;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.goldcatcher.*;
import com.goldcatcher.RuntimeSettings.AiRoute;
import org.junit.jupiter.api.Test;
class Any2ApiClientTest {
    @Test void historicalEightFieldBudgetRemainsReadableAfterUpgrade() {
        var f=new TestSupport();var budget=f.mapper.readValue("{\"bytes\":1500,\"characters\":500,\"gatewayInputEstimate\":500,\"messages\":2,\"outputReserve\":1000,\"inputHash\":\"previous\",\"limitSource\":\"CONFIG\",\"outputEnforcement\":\"NOT_SUPPORTED\"}",Any2ApiClient.Budget.class);
        assertThat(budget.preparation()).isEqualTo("LEGACY_NOT_RECORDED");assertThat(budget.limits()).isNull();
        assertThat(budget.characters()).isEqualTo(500);assertThat(budget.utilizationPercent()).isNull();assertThat(budget.changes()).isEmpty();
    }
    @Test void oversizedAccountBlocksHttpAndPreservesAuditableBudget() throws Exception {
        var f=new TestSupport();f.environment.setProperty("TEST_KEY","test-only");var remote=mock(RemoteHttp.class);
        @SuppressWarnings("unchecked") var response=(java.net.http.HttpResponse<byte[]>)mock(java.net.http.HttpResponse.class);
        when(response.statusCode()).thenReturn(200);when(response.body()).thenReturn("{\"available\":true,\"capabilities\":{}}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        when(remote.send(any(),eq(500_000))).thenReturn(response);
        var client=new Any2ApiClient(f.settings,remote,f.mapper,f.clock);
        var route=new AiRoute("a","arena","test","https://localhost/v1","TEST_KEY",2000,2000,6000,2,1000,1,false);
        f.environment.setProperty("GOLD_AI_ROUTES_JSON",f.mapper.writeValueAsString(java.util.List.of(route)));
        var packet=f.mapper.createObjectNode();packet.putObject("evidence").putObject("account").put("unshortenedOrderState","账户状态".repeat(2000));
        assertThatThrownBy(()->client.complete(route,"不能修改的研究指令",packet)).isInstanceOfSatisfying(Any2ApiClient.InferenceFailure.class,failure->{
            assertThat(failure.getMessage()).isEqualTo("CONTEXT_INSUFFICIENT");assertThat(failure.partial().finishReason()).isEqualTo("NOT_SENT");
            assertThat(failure.partial().budget().preparation()).isEqualTo("BLOCKED_BY_BUDGET");
            assertThat(failure.partial().budget().changes()).isEmpty();
            assertThat(f.mapper.writeValueAsString(failure.partial().budget())).contains("\"targetPercent\":90","\"safetyTokens\":512");
            var reread=f.mapper.readValue(f.mapper.writeValueAsString(failure.partial().budget()),Any2ApiClient.Budget.class);
            assertThat(reread.limits().safetyTokens()).isEqualTo(512);assertThat(reread.preparation()).isEqualTo("BLOCKED_BY_BUDGET");
        });
        verify(remote,never()).send(any(),eq(1_000_000));
        assertThat(client.states().getFirst().lastBudget().preparation()).isEqualTo("BLOCKED_BY_BUDGET");
        assertThat(client.states().getFirst().lastInference()).isEqualTo("FAILED");
    }
    @Test void countsUtf8AndEnforcesIndependentLimitsWithoutInventingTokens() {
        var f=new TestSupport();var client=new Any2ApiClient(f.settings,new RemoteHttp(),f.mapper,f.clock);
        var route=new AiRoute("a","arena","arena/test","https://localhost/arena/v1","TEST_KEY",600,1500,1800,2,128,1,false);
        var empty=f.mapper.createObjectNode();var budget=client.verify(route,"金".repeat(500),empty,false);
        assertThat(budget.bytes()).isEqualTo(1500);assertThat(budget.characters()).isEqualTo(500);assertThat(budget.gatewayInputEstimate()).isEqualTo(500);
        assertThat(budget.limitSource()).isEqualTo("CONFIG_CONSERVATIVE_UNKNOWN_CAPACITY");assertThat(budget.outputEnforcement()).isEqualTo("NOT_SUPPORTED");
        assertThatThrownBy(()->client.verify(route,"金".repeat(601),empty,false)).hasMessage("CONTEXT_INSUFFICIENT");
        assertThatThrownBy(()->client.verify(route,"a".repeat(1501),empty,false)).hasMessage("CONTEXT_INSUFFICIENT");
        assertThatThrownBy(()->client.verify(route,"金".repeat(400),f.mapper.readTree("{\"capabilities\":{\"max_context_tokens\":1000}}"),false)).hasMessage("CONTEXT_INSUFFICIENT");
        assertThatThrownBy(()->client.verify(route,"a".repeat(101),f.mapper.readTree("{\"parameter_adaptation\":{\"input\":{\"mode\":\"declared_character_limit\",\"limit\":100}}}"),false)).hasMessage("CONTEXT_INSUFFICIENT");
    }
    @Test void rejectsAmbiguousOutputInsteadOfRepairingOrInventingIt() {
        var f=new TestSupport();var client=new Any2ApiClient(f.settings,new RemoteHttp(),f.mapper,f.clock);
        assertThat(client.parseOutput("```json\n{\"ok\":true}\n```").path("ok").asBoolean()).isTrue();
        assertThatThrownBy(()->client.parseOutput("Some explanation then {\"ok\":true}"));
        assertThatThrownBy(()->client.parseOutput("[1,2]"));
    }
    @Test void allowsEveryReferenceInTheFourNewsSixCalendarAndFourAnchorPacket() {
        var f=new TestSupport();var client=new Any2ApiClient(f.settings,new RemoteHttp(),f.mapper,f.clock);
        var output=f.mapper.createObjectNode();output.put("title","等待确认");output.put("output","根据冻结证据等待");
        output.put("shortDirection","UNKNOWN");output.put("longDirection","UNKNOWN");
        var refs=output.putArray("evidenceRefs");
        for(String id:java.util.List.of("market:quote","market:rules","account","indicators"))refs.add(id);
        for(int i=0;i<4;i++)refs.add("news-"+i);
        for(int i=0;i<6;i++)refs.add("calendar-"+i);
        assertThatCode(()->client.validate(output)).doesNotThrowAnyException();
        refs.add("extra");assertThatThrownBy(()->client.validate(output)).hasMessage("INVALID_EVIDENCE_ARRAY");
    }
}
