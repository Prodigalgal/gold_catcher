package com.goldcatcher.research;

import static org.assertj.core.api.Assertions.*;
import com.goldcatcher.*;
import com.goldcatcher.RuntimeSettings.AiRoute;
import java.util.*;
import org.junit.jupiter.api.Test;

class ContextWindowTest {
    private final TestSupport f=new TestSupport();
    private AiRoute route(int characters) {return new AiRoute("a","arena","model","https://localhost/v1","KEY",6000,characters,18000,2,1000,2,false);}
    private Map<String,Object> request() {
        return Map.of("model","model","messages",List.of(Map.of("role","system","content","固定指令，必须保留"),Map.of("role","user","content","")),
            "tools",List.of(Map.of("schema","完整函数schema也计入预算")));
    }
    @Test void compactsOnlyProseAndPreservesAllFinancialFactsPeerIdsAndUnicode() {
        var packet=f.mapper.createObjectNode();var evidence=packet.putObject("evidence");
        evidence.putObject("quote").put("bid","4000.123456789123456789").put("exchangeTime","2026-10-08T00:00:00Z");
        evidence.putObject("account").put("equity","1000.00000000000000001");
        evidence.putObject("rules").put("tickSize","0.01");evidence.putArray("evidenceIds").add("market:quote");
        evidence.putArray("news").addObject().put("id","news-a").put("publishedAt","2026-10-08T00:00:00Z").put("excerpt","黄金😀".repeat(2500));
        var peer=packet.putArray("peers").addObject();peer.put("id","previous-0-A");var output=peer.putObject("output");
        output.put("output","同伴研究观点😀".repeat(300));output.put("changes","修订".repeat(100));
        output.putArray("evidenceRefs").add("market:quote").add("news-a");output.putArray("quotedMessages").add("older-id");
        output.putObject("plan").put("action","CREATE").put("quantity","0.10000000000000001").put("stopLoss","3999.99").put("riskAssessment","风险全文不得裁剪".repeat(40));
        var original=packet.deepCopy();var limits=ContextWindow.limits(route(8000),f.mapper.createObjectNode());
        var prepared=ContextWindow.prepare(f.mapper,request(),packet,limits);
        assertThat(ContextWindow.fits(prepared.original(),limits,true)).isFalse();
        assertThat(ContextWindow.fits(prepared.sent(),limits,true)).isTrue();assertThat(prepared.changes()).isNotEmpty();
        assertThat(packet).isEqualTo(original);
        for(String field:List.of("quote","account","rules","evidenceIds"))assertThat(prepared.packet().path("evidence").path(field)).isEqualTo(original.path("evidence").path(field));
        var sentPeer=prepared.packet().path("peers").path(0);assertThat(sentPeer.path("id")).isEqualTo(peer.path("id"));
        for(String field:List.of("plan","evidenceRefs","quotedMessages"))assertThat(sentPeer.path("output").path(field)).isEqualTo(output.path(field));
        assertThat(prepared.packet().path("evidence").path("news").path(0).path("id").asText()).isEqualTo("news-a");
        assertThat(prepared.body()).contains("固定指令","完整函数schema");
        String excerpt=prepared.packet().path("evidence").path("news").path(0).path("excerpt").asText();
        assertThat(excerpt).isEqualTo(new String(excerpt.getBytes(java.nio.charset.StandardCharsets.UTF_8),java.nio.charset.StandardCharsets.UTF_8));
        assertThat(prepared.sent().inputEstimate()).isEqualTo((prepared.sent().bytes()+2L)/3);
    }
    @Test void leavesOversizedCriticalStateIntactAndMarksItUnsendable() {
        var packet=f.mapper.createObjectNode();var orders=packet.putObject("evidence").putObject("account").putArray("orders");
        for(int i=0;i<100;i++)orders.addObject().put("id","order-"+i).put("quantity","1.001").put("stopLoss","3999.99").put("riskAssessment","不能删除的订单原始状态".repeat(8));
        var limits=ContextWindow.limits(route(3000),f.mapper.createObjectNode());var prepared=ContextWindow.prepare(f.mapper,request(),packet,limits);
        assertThat(ContextWindow.fits(prepared.sent(),limits,true)).isFalse();assertThat(prepared.packet()).isEqualTo(packet);assertThat(prepared.changes()).isEmpty();
    }
    @Test void reservesOutputBridgeAllowanceAndTenPercentHeadroom() {
        var limits=ContextWindow.limits(route(8000),f.mapper.readTree("{\"capabilities\":{\"max_input_tokens\":2000,\"max_context_tokens\":3000,\"max_output_tokens\":1200},\"input_character_limit\":7000}"));
        assertThat(limits.inputEstimate()).isEqualTo(1488);assertThat(limits.characters()).isEqualTo(7000);
        assertThat(ContextWindow.fits(new ContextWindow.Size(4017,4000,1339,2),limits,true)).isTrue();
        assertThat(ContextWindow.fits(new ContextWindow.Size(4020,4000,1340,2),limits,true)).isFalse();
        assertThat(ContextWindow.fits(new ContextWindow.Size(4020,4000,1340,2),limits,false)).isTrue();
    }
}
