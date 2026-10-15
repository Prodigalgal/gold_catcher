package com.goldcatcher.research;

import static org.assertj.core.api.Assertions.*;
import com.goldcatcher.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;

class ModelDataFlowTest {
    final TestSupport f=new TestSupport();
    JsonNode table(JsonNode rows,List<String> columns){return f.mapper.valueToTree(EvidenceTable.pack(f.mapper,rows,columns));}
    @Test void effectiveFeeWinsWithoutChangingPricesAccountAmountsOrDistinctMeanings(){
        var packet=f.mapper.createObjectNode();var e=packet.putObject("evidence").put("symbol","XAU_USDT");
        e.putObject("quote").put("symbol","XAU_USDT").put("bid","4096.123456789012345678").put("ask","4096.123456789012345679")
            .put("last","4096.10").put("mark","4096.00").put("exchangeTime","2026-10-15T03:00:00Z");
        e.putObject("account").put("symbol","XAU_USDT").put("equity","1000.00000000000000001").put("valuedAt","2026-10-15T03:00:00Z");
        e.putObject("rules").put("symbol","XAU_USDT").put("takerFeeRate","0.00075").put("tickSize","0.01").put("qtyStep","0.0001").put("minQty","0.0001");
        e.putObject("costConfiguration").put("takerFeeRate","0.0004").put("source","CONFIGURED_OVERRIDE_ASSUMPTION");
        var original=packet.deepCopy();var result=ModelDataFlow.project(f.mapper,packet,false);var out=result.output().path("evidence");
        assertThat(packet).isEqualTo(original);assertThat(out.path("rules").has("takerFeeRate")).isFalse();
        assertThat(out.path("costConfiguration").path("takerFeeRate").asText()).isEqualTo("0.0004");
        for(String key:List.of("bid","ask","last","mark"))assertThat(out.path("quote").path(key)).isEqualTo(e.path("quote").path(key));
        assertThat(out.path("account").path("equity")).isEqualTo(e.path("account").path("equity"));
        for(String key:List.of("qtyStep","minQty"))assertThat(out.path("rules").path(key).asText()).isEqualTo("0.0001");
        assertThat(ModelDataFlow.instruction(result.output())).contains("账户估值时点");
        assertThat(ModelDataFlow.project(f.mapper,packet,false)).isEqualTo(result);
    }
    @Test void compressionAndNewsDoNotMergeDifferentConditionsOrTruncateFinishedSummaries(){
        var packet=f.mapper.createObjectNode();packet.putObject("evidence").putArray("news").add("若美元走强则承压；未确认。😀").add("若美元走强则承压；未确认。😀").add("若美元走弱则支撑；未确认。😀");
        var news=ModelDataFlow.project(f.mapper,packet,false).output().path("evidence").path("news");assertThat(news.size()).isEqualTo(2);
        assertThat(news.path(0).asText()).contains("走强");assertThat(news.path(1).asText()).contains("走弱");
        var source=f.mapper.createObjectNode().put("task","SUMMARIZE").put("text","不应删减的原文，0.00000000001。").put("title","原文").put("chunkIndex",3);
        var copy=source.deepCopy();var flow=ModelDataFlow.project(f.mapper,source,true);
        assertThat(flow.output().propertyNames()).containsExactlyInAnyOrder("title","text");assertThat(source).isEqualTo(copy);
    }
    @Test void tablesPreserveEveryCellAndSeparateDailyIntradayNominalAndRealRates(){
        var rows=f.mapper.createArrayNode();for(String source:List.of("CNBC","TREASURY"))for(String kind:List.of("名义","实际"))
            rows.addObject().put("id",source+kind).put("label",kind).put("source",source).put("frequency",source.equals("CNBC")?"INTRADAY_PUBLIC_WEB":"DAILY")
                .put("unit","PERCENT").put("value",kind.equals("名义")?"4.100000000000001":"2.100000000000001").put("previous","0").put("note","不同含义, \"保留\"\n😀");
        var packet=f.mapper.createObjectNode();packet.putObject("evidence").putObject("analysis").set("drivers",table(rows,List.of("id","label","source","frequency","unit","value","previous","note")));
        var sanitized=ModelInput.project(f.mapper,packet).path("evidence").path("analysis").path("drivers");
        var projected=ModelDataFlow.project(f.mapper,packet,false).output().path("evidence").path("analysis").path("drivers");
        var restored=f.mapper.createArrayNode();if(projected.isArray())for(var t:projected)for(var r:ModelInput.records(f.mapper,t))restored.add(r);else restored=ModelInput.records(f.mapper,projected);
        assertThat(restored).containsExactlyInAnyOrderElementsOf(ModelInput.records(f.mapper,sanitized));assertThat(restored.size()).isEqualTo(4);
    }
    @Test void cfdCommissionIsDeduplicatedOnlyWithTheSameCurrencyAndLotBasis(){
        var packet=f.mapper.createObjectNode();var e=packet.putObject("evidence").put("symbol","GATE:XAUUSD");
        e.putObject("rules").put("commission_per_lot","0.20").put("leverage","500").put("maximumPermittedLeverage","100").putArray("permittedLeverages").add("50").add("100");
        e.putObject("costConfiguration").put("feePerLot","0.2").put("feeUnit","USD_PER_LOT");
        var projected=ModelDataFlow.project(f.mapper,packet,false).output().path("evidence");assertThat(projected.path("rules").has("commission_per_lot")).isFalse();assertThat(projected.path("rules").has("leverage")).isFalse();
        assertThat(projected.path("costConfiguration").path("feePerLot").asText()).isEqualTo("0.2");
        ((ObjectNode)e.path("costConfiguration")).put("feeUnit","USDT_PER_CONTRACT");
        assertThat(ModelDataFlow.project(f.mapper,packet,false).output().path("evidence").path("rules").has("commission_per_lot")).isTrue();
    }
    @Test void duplicateNumericalFormattingIsRemovedOnlyWithAnExactlyMatchingKnownUnit(){
        var rows=f.mapper.createArrayNode();rows.addObject().put("indicator","econ:PPI_MOM").put("unit","PERCENT_MOM_SA").put("forecast","0.6").put("rawForecast","0.6%").put("rawProviderForecast","0.7%");
        rows.addObject().put("indicator","UNMAPPED:Some Event").put("unit","UNMAPPED").putNull("forecast").put("rawForecast","199K");
        rows.addObject().put("indicator","econ:NFP").put("unit","COUNT").put("forecast","199000").put("rawForecast","199K");
        var packet=f.mapper.createObjectNode();packet.putObject("evidence").putObject("analysis").set("expectations",table(rows,List.of("indicator","unit","forecast","rawForecast","rawProviderForecast")));
        var out=ModelDataFlow.project(f.mapper,packet,false).output().path("evidence").path("analysis").path("expectations");var values=ModelInput.records(f.mapper,out);
        assertThat(values.path(0).path("rawForecast").isNull()).isTrue();assertThat(values.path(0).path("rawProviderForecast").asText()).isEqualTo("0.7%");
        assertThat(values.path(1).path("rawForecast").asText()).isEqualTo("199K");assertThat(values.path(2).path("rawForecast").isNull()).isTrue();
    }
    @Test void opinionsRemainIndependentAndUniqueReasonsAndPlansStayWhole(){
        var packet=f.mapper.createObjectNode();packet.putObject("evidence");var peers=packet.putArray("peers");
        for(String reason:List.of("美元走强，若反转则失效。","利率走高，尚未确认。")){
            var peer=peers.addObject().putObject("output");peer.put("title","独有标题").put("output",reason).put("changes",reason).put("shortDirection","BEARISH").put("longDirection","UNKNOWN");
            peer.putObject("plan").put("action","WAIT").put("riskAssessment","独有风险说明");
        }
        var out=ModelDataFlow.project(f.mapper,packet,false).output().path("peers");assertThat(out.size()).isEqualTo(2);
        for(int i=0;i<2;i++){assertThat(out.path(i).has("changes")).isFalse();assertThat(out.path(i).path("title").asText()).isEqualTo("独有标题");assertThat(out.path(i).path("plan")).isEqualTo(peers.path(i).path("output").path("plan"));}
    }
    @Test void modelBudgetFailureNeverPrefixCutsAReasonOrItsCounterCondition(){
        var input=f.mapper.createObjectNode();input.putObject("evidence");input.putArray("peers").addObject().put("output","判断".repeat(1000)+"，但此结论仅在美元未反转时成立。");
        var limits=new ContextWindow.Limits(2000,1000,600,0,0,0,2,512,90);var request=Map.<String,Object>of("messages",List.of(Map.of("role","system","content","研究"),Map.of("role","user","content","")));
        var result=ContextWindow.prepareModel(f.mapper,request,input,limits);assertThat(result.packet()).isEqualTo(input);assertThat(result.changes()).isEmpty();assertThat(ContextWindow.fits(result.sent(),limits,true)).isFalse();
    }
    @Test void mapConstructionOrderDoesNotAffectWireBytesOrFinancialStrings(){
        var first=f.mapper.createObjectNode().put("z","0001.00000000001").put("a","😀");var second=f.mapper.createObjectNode().put("a","😀").put("z","0001.00000000001");
        assertThat(EvidenceWire.encode(f.mapper,first,false)).isEqualTo(EvidenceWire.encode(f.mapper,second,false));
        assertThat(CanonicalJson.text(f.mapper,first)).isEqualTo(CanonicalJson.text(f.mapper,second));
    }
}
