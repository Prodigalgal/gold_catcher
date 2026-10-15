package com.goldcatcher.research;

import static org.assertj.core.api.Assertions.*;
import com.goldcatcher.TestSupport;
import com.goldcatcher.EvidenceTable;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;

class ModelInputTest {
    final TestSupport f=new TestSupport();
    @Test void csvProjectionPreservesQuotedCommasNewlinesEmptyUnknownZeroAndLongNumbers(){
        var rows=f.mapper.createArrayNode();rows.addObject().put("id","private-id").put("value","00010000000000000000000000001")
            .put("label","a,\"quoted\"\nline").put("empty","").putNull("unknown").put("zero","0");
        var packet=f.mapper.createObjectNode();packet.putObject("evidence").set("indicators",f.mapper.valueToTree(EvidenceTable.pack(f.mapper,rows,List.of("id","value","label","empty","unknown","zero"))));
        var projected=ModelInput.research(f.mapper,packet);var restored=ModelInput.records(f.mapper,projected.path("evidence").path("indicators"));
        var expected=rows.deepCopy();((ObjectNode)expected.path(0)).remove("id");assertThat(restored).isEqualTo(expected);
        assertThat(projected.toString()).doesNotContain("private-id");
    }
    @Test void researchSeparatesEveryIdentityControlAndNewsMetadataFromFinancialFacts(){
        var packet=f.mapper.createObjectNode().put("protocol",ModelProtocol.VERSION).put("phase",3).put("actorId","private-actor");
        packet.putObject("execution").put("clientTraceId","private-trace").put("attempt",3);packet.putObject("submissionIds").putArray("news").add("private-news");
        packet.putObject("formatCorrection").put("error","private-error");var evidence=packet.putObject("evidence");
        evidence.put("symbol","XAU_USDT").put("horizon","SHORT").put("evidenceCutoff","2026-10-15T03:00:00Z").put("decisionExpiresAt","private-deadline").put("analysisOnly",true);
        evidence.putArray("news").addObject().put("id","private-news").put("excerpt","CPI同比3.7%，高于发布前预期3.6%。").put("bodyHash","private-hash").put("title","private-title").put("publishedAt","private-news-date").put("source","private-source");
        evidence.putObject("newsCoverage").put("omitted",3);evidence.putArray("evidenceIds").add("private-ref");
        evidence.putObject("quote").put("sequence",123).put("bid","4096.123456789012345678").put("ask","4096.123456789012345679").put("mark","0").put("exchangeTime","2026-10-15T02:59:59Z").put("receivedAt","2026-10-15T03:00:00Z");
        var peer=packet.putArray("peers").addObject().put("id","private-peer").put("actor","private-actor");var output=peer.putObject("output");
        output.put("title","候选").put("output","条件判断：pub:7二十档偏卖，analysis:drivers显示US_INTRADAY_REAL_10Y走高。").putArray("evidenceRefs").add("private-ref");
        output.putObject("plan").put("action","CREATE").put("quantity","0001.0000000000000001").put("entryPrice","4096.123456789012345678").put("riskAssessment","可亏损本金").putNull("triggerPrice");
        var original=packet.deepCopy();var projected=ModelInput.research(f.mapper,packet);var wire=EvidenceWire.encode(f.mapper,projected,false);
        assertThat(wire).doesNotContain("private-","execution","submissionIds","newsCoverage","decisionExpiresAt","actorId","evidenceRefs","bodyHash","&id","*id");
        assertThat(wire).doesNotContain("pub:7","analysis:drivers","US_INTRADAY_REAL_10Y").contains("二十档偏卖","美债10年实际收益率（公开盘中）");
        assertThat(projected.path("evidence").path("news")).isEqualTo(f.mapper.valueToTree(List.of("CPI同比3.7%，高于发布前预期3.6%。")));
        assertThat(projected.path("peers").path(0).path("plan").path("quantity").asText()).isEqualTo("0001.0000000000000001");
        assertThat(projected.path("evidence").path("quote").path("bid").asText()).isEqualTo("4096.123456789012345678");
        assertThat(projected.path("evidence").path("quote").path("mark").asText()).isEqualTo("0");assertThat(packet).isEqualTo(original);
    }
    @Test void csvJoinsResolveBeforeIdsDisappearAndNoForecastOrRevisionChanges(){
        var evidence=f.mapper.createObjectNode();var analysis=evidence.putObject("analysis");
        var metadata=f.mapper.readTree("[{\"id\":\"s1\",\"source\":\"BLS\",\"publishedAt\":\"2026-10-15T12:30:00Z\",\"period\":\"2026-09\",\"extractedAt\":\"private-extraction\"}]");
        analysis.set("releaseSources",f.mapper.valueToTree(EvidenceTable.pack(f.mapper,metadata,List.of("id","source","publishedAt","period","extractedAt"))));
        analysis.set("metricColumns",f.mapper.valueToTree(List.of("id","unit","actual","previous","forecast","surprise")));
        analysis.putArray("releases").addObject().put("sourceId","s1").put("csv","econ:NFP,COUNT,29000,,30000,-1000\necon:CPI_MOM,PERCENT,0.600000000000000001,0.4,0.6,0");
        var revision=f.mapper.readTree("[{\"metricId\":\"econ:NFP\",\"period\":\"July\",\"before\":\"21000\",\"after\":\"-10000\",\"delta\":\"-31000\",\"unit\":\"COUNT\"}]");
        analysis.set("revisions",f.mapper.valueToTree(EvidenceTable.pack(f.mapper,revision,List.of("metricId","period","before","after","delta","unit"))));
        var packet=f.mapper.createObjectNode().set("evidence",evidence);var projected=ModelInput.research(f.mapper,packet).path("evidence").path("analysis");
        var group=projected.path("releases").path(0);assertThat(group.path("source").asText()).isEqualTo("BLS");assertThat(group.path("period").asText()).isEqualTo("2026-09");
        var metrics=ModelInput.records(f.mapper,group.path("metrics"));assertThat(metrics.path(0).path("name").asText()).isEqualTo("非农就业变动");assertThat(metrics.path(0).path("previous").isNull()).isTrue();
        assertThat(metrics.path(1).path("actual").asText()).isEqualTo("0.600000000000000001");assertThat(metrics.path(1).path("surprise").asText()).isEqualTo("0");
        assertThat(ModelInput.records(f.mapper,projected.path("revisions")).path(0).path("after").asText()).isEqualTo("-10000");
        assertThat(projected.toString()).doesNotContain("sourceId","metricId","s1","econ:","private-extraction");
    }
    @Test void orderManagementUsesFinancialTupleAndRejectsAmbiguousTargets(){
        var evidence=f.mapper.createObjectNode();var orders=evidence.putObject("account").putArray("orders");var order=orders.addObject();
        order.put("id","server-only-order").put("side","BUY").put("type","LIMIT").put("quantity","0.0100").put("leverage","50").put("entryPrice","4100.00");
        var plan=f.mapper.createObjectNode().put("action","MODIFY").put("stopLoss","4090");plan.set("target",order.deepCopy());((ObjectNode)plan.path("target")).remove("id");
        var bound=ModelInput.bindOrder(f.mapper,plan,evidence);assertThat(bound.path("orderId").asText()).isEqualTo("server-only-order");assertThat(plan.has("orderId")).isFalse();
        var projected=ModelInput.plan(f.mapper,bound,evidence);assertThat(projected).isEqualTo(plan);
        orders.add(order.deepCopy().put("id","other-server-order"));assertThatThrownBy(()->ModelInput.bindOrder(f.mapper,plan,evidence)).hasMessage("AMBIGUOUS_ORDER_TARGET");
        ((ObjectNode)plan.path("target")).put("entryPrice","4101");assertThatThrownBy(()->ModelInput.bindOrder(f.mapper,plan,evidence)).hasMessage("ORDER_TARGET_NOT_FOUND");
    }
    @Test void compressionContainsOnlyMaterialAndResearchNeverTrimsCompletedNewsAgain(){
        var packet=f.mapper.createObjectNode().put("protocol",ResearchProtocol.COMPRESSION_VERSION).put("task","SUMMARIZE").put("chunkIndex",1).put("totalChunks",8).put("text","来源正文").put("title","标题");
        packet.putObject("evidence").put("decisionExpiresAt","private-deadline");assertThat(ModelInput.compression(f.mapper,packet).propertyNames()).containsExactlyInAnyOrder("title","text");
        var input=f.mapper.createObjectNode();input.putObject("evidence").putArray("news").add("重要事实".repeat(70));var peer=input.putArray("peers").addObject();peer.put("output","可缩短的说明".repeat(150));peer.putObject("plan").put("action","WAIT");
        var request=Map.<String,Object>of("messages",List.of(Map.of("role","system","content","研究"),Map.of("role","user","content","")));
        var limits=new ContextWindow.Limits(2000,1500,1000,0,0,0,2,512,90);var result=ContextWindow.prepareModel(f.mapper,request,input,limits);
        assertThat(result.packet().path("evidence").path("news")).isEqualTo(input.path("evidence").path("news"));assertThat(result.body()).doesNotContain("&id","*id");
    }
}
