package com.goldcatcher.research;
import static org.assertj.core.api.Assertions.*;
import com.goldcatcher.*;
import com.goldcatcher.RuntimeSettings.AiRoute;
import org.junit.jupiter.api.Test;
class Any2ApiClientTest {
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
}
