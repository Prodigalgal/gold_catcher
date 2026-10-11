package com.goldcatcher.research;
import static org.assertj.core.api.Assertions.*;
import com.goldcatcher.TestSupport;
import org.junit.jupiter.api.Test;
class ResearchProtocolTest {
    final TestSupport f=new TestSupport();
    final String base="protocol=GOLD_RESEARCH_V1\ntitle=形势观察\noutput=成本=未知: 等待确认\nshortDirection=UNKNOWN\nlongDirection=MIXED\n";
    @Test void flatPlanPreservesEveryExplicitDecimalAndReferenceWithoutGuessing(){
        var output=ResearchProtocol.parse(f.mapper,base+"evidenceRefs=market:quote,analysis:drivers\nquotedMessages=peer-1,peer-2\nplan.action=CREATE\nplan.quantity=0001.0000000000000001\nplan.entryPrice=4096.123456789012345678\nplan.stopLoss=0\nplan.triggerPrice=null\nplan.riskAssessment=价格不利时全部亏损\n",false,false);
        assertThat(output.path("plan").path("quantity").asText()).isEqualTo("0001.0000000000000001");
        assertThat(output.path("plan").path("entryPrice").asText()).isEqualTo("4096.123456789012345678");
        assertThat(output.path("plan").path("stopLoss").asText()).isEqualTo("0");assertThat(output.path("plan").path("triggerPrice").isNull()).isTrue();
        assertThat(output.path("plan").has("leverage")).isFalse();assertThat(output.path("output").asText()).isEqualTo("成本=未知: 等待确认");
        assertThat(output.path("evidenceRefs").size()).isEqualTo(2);assertThat(output.path("quotedMessages").path(0).asText()).isEqualTo("peer-1");
    }
    @Test void fixedTextAndNativeJsonProduceSameInternalVoteFields(){
        var output=ResearchProtocol.parse(f.mapper,"```text\n"+base+"selectedCandidateId=peer-1\nevidenceRefs=market:quote\n```",false,true);
        var expected=f.mapper.readTree("{\"title\":\"形势观察\",\"output\":\"成本=未知: 等待确认\",\"shortDirection\":\"UNKNOWN\",\"longDirection\":\"MIXED\",\"selectedCandidateId\":\"peer-1\",\"quotedMessages\":[],\"evidenceRefs\":[\"market:quote\"],\"impacts\":[]}");
        assertThat(output).isEqualTo(expected);
    }
    @Test void newsImpactGroupsMapToCanonicalArrays(){
        var output=ResearchProtocol.parse(f.mapper,base+"plan.action=WAIT\nimpact.1.newsId=original-id\nimpact.1.horizon=SHORT\nimpact.1.direction=UNKNOWN\nimpact.1.reason=发布后观察\nimpact.1.counter=缺少市场确认\n",false,false);
        assertThat(output.path("impacts").size()).isEqualTo(1);assertThat(output.path("impacts").path(0).path("newsId").asText()).isEqualTo("original-id");
    }
    @Test void ambiguousDuplicateUnknownAndIncompleteFieldsAreNeverSilentlyRepaired(){
        for(String extra:new String[]{"plan.action=WAIT\nplan.action=CREATE","plan.action=WAIT\nplan.magicPrice=1","plan.action=WAIT\nextra prose","plan.action=WAIT\nevidenceRefs=id1,,id2"})
            assertThatThrownBy(()->ResearchProtocol.parse(f.mapper,base+extra,false,false)).hasMessage("INVALID_STRUCTURED_OUTPUT");
        assertThatThrownBy(()->ResearchProtocol.parse(f.mapper,base,false,false)).hasMessage("MISSING_REQUIRED_OUTPUT");
        assertThatThrownBy(()->ResearchProtocol.parse(f.mapper,base+"selectedCandidateId=WAIT\nplan.action=CREATE",false,true)).hasMessage("INVALID_STRUCTURED_OUTPUT");
        assertThatThrownBy(()->ResearchProtocol.parse(f.mapper,base+"plan.action=WAIT\nimpact.1.newsId=id",false,false)).hasMessage("INVALID_IMPACT");
    }
    @Test void directionErrorsStillGoBackToModelInsteadOfInventingAnInterpretation(){
        var invalid=ResearchProtocol.parse(f.mapper,base.replace("shortDirection=UNKNOWN","shortDirection=UP")+"plan.action=WAIT",false,false);
        var client=new Any2ApiClient(f.settings,new com.goldcatcher.RemoteHttp(),f.mapper,f.clock);
        assertThatThrownBy(()->client.validate(invalid)).hasMessage("INVALID_DIRECTION");assertThat(invalid.path("shortDirection").asText()).isEqualTo("UP");
    }
    @Test void compressionUsesSameTextConventionAndCannotInjectAnOrder(){
        assertThat(ResearchProtocol.parse(f.mapper,"protocol=GOLD_COMPRESSION_V1\nsummary=官方资料精华",true,false).path("summary").asText()).isEqualTo("官方资料精华");
        assertThatThrownBy(()->ResearchProtocol.parse(f.mapper,"protocol=GOLD_COMPRESSION_V1\nsummary=摘要\nplan.action=CREATE",true,false)).hasMessage("INVALID_STRUCTURED_OUTPUT");
    }
}
