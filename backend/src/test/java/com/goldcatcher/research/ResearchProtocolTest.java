package com.goldcatcher.research;
import static org.assertj.core.api.Assertions.*;
import com.goldcatcher.TestSupport;
import org.junit.jupiter.api.Test;
class ResearchProtocolTest {
    @Test void missingTextMarkerAndKnownImpactAliasesPreserveSubmittedValues(){
        var f=new TestSupport();String body="title=观察\noutput=等待\nshortDirection=UNKNOWN\nlongDirection=MIXED\nplan.action=WAIT\nnewsImpact.1.newsId=original-id\nnewsImpact.1.horizon=SHORT\nnewsImpact.1.direction=UNKNOWN\nnewsImpact.1.reason=原始依据\nnewsImpact.1.counter=原始反证\n";
        assertThat(ResearchProtocol.text(body)).isTrue();var output=ResearchProtocol.parse(f.mapper,body,false,false);
        assertThat(output.path("impacts").path(0).path("newsId").asText()).isEqualTo("original-id");
        assertThat(output.path("plan").path("action").asText()).isEqualTo("WAIT");
        assertThatThrownBy(()->ResearchProtocol.parse(f.mapper,"protocol=OTHER\n"+body,false,false)).hasMessage("INVALID_STRUCTURED_OUTPUT");
        assertThatThrownBy(()->ResearchProtocol.parse(f.mapper,body+"impact.1.newsId=conflicting-id",false,false)).hasMessageStartingWith("INVALID_STRUCTURED_OUTPUT_CONFLICT");
        assertThatThrownBy(()->ResearchProtocol.parse(f.mapper,"summary=无协议",true,false)).hasMessage("INVALID_STRUCTURED_OUTPUT");
    }
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
            assertThatThrownBy(()->ResearchProtocol.parse(f.mapper,base+extra,false,false)).hasMessageStartingWith("INVALID_STRUCTURED_OUTPUT_");
        assertThatThrownBy(()->ResearchProtocol.parse(f.mapper,base,false,false)).hasMessage("MISSING_REQUIRED_OUTPUT_PLAN_ACTION");
        assertThatThrownBy(()->ResearchProtocol.parse(f.mapper,base+"selectedCandidateId=WAIT\nplan.action=CREATE",false,true)).hasMessage("INVALID_STRUCTURED_OUTPUT_JUDGE_PLAN");
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
    @Test void actualProviderEchoPatternsNormalizeWithoutInventingMissingActions(){
        String reply=base+"phase=0\nplan.action=WAIT\nplan.reason=缺少可靠成本\nevidenceRefs=[]\nquotedMessages=[]\nimpacts=[]\n";
        var output=ResearchProtocol.parse(f.mapper,reply+reply,false,false,0);
        assertThat(output.path("plan").path("riskAssessment").asText()).isEqualTo("缺少可靠成本");
        assertThat(output.path("plan").has("reason")).isFalse();assertThat(output.has("phase")).isFalse();
        assertThat(output.path("evidenceRefs").isEmpty()).isTrue();
        assertThatThrownBy(()->ResearchProtocol.parse(f.mapper,reply.replace("phase=0","phase=3"),false,false,0)).hasMessage("INVALID_STRUCTURED_OUTPUT_PHASE");
        assertThatThrownBy(()->ResearchProtocol.parse(f.mapper,reply.replace("plan.action=WAIT\n",""),false,false,0)).hasMessage("MISSING_REQUIRED_OUTPUT_PLAN_ACTION");
        assertThatThrownBy(()->ResearchProtocol.parse(f.mapper,reply+"plan.action=CREATE",false,false,0)).hasMessage("INVALID_STRUCTURED_OUTPUT_CONFLICT_PLAN_ACTION");
    }
    @Test void supplementalReasonIsPreservedWhenExplicitRiskAlreadyExistsAndJsonUsesSameAdapter(){
        var output=ResearchProtocol.parse(f.mapper,base+"plan.action=WAIT\nplan.reason=方向分歧\nplan.riskAssessment=本金可能全部亏损\n",false,false);
        assertThat(output.path("plan").path("riskAssessment").asText()).isEqualTo("本金可能全部亏损");
        assertThat(output.path("output").asText()).endsWith("；补充理由：方向分歧");
        var json=f.mapper.readTree("{\"phase\":0,\"plan\":{\"action\":\"WAIT\",\"reason\":\"方向分歧\",\"riskAssessment\":\"本金可能全部亏损\"},\"output\":\"成本=未知: 等待确认\"}");
        var normalized=ResearchProtocol.normalize(json,false,false,0);
        assertThat(normalized.path("plan")).isEqualTo(output.path("plan"));assertThat(normalized.path("output")).isEqualTo(output.path("output"));
        assertThat(json.path("plan").has("reason")).isTrue();
        assertThat(ResearchProtocol.normalize(json,true,false,0)).isSameAs(json);
    }
    @Test void quotedArrayIdsStayExactAndPluralImpactNamespaceIsUnambiguous(){
        var output=ResearchProtocol.parse(f.mapper,base+"plan.action=WAIT\nevidenceRefs=[\"original-id\",\"id,with-comma\"]\nimpacts.1.newsId=original-id\nimpacts.1.horizon=SHORT\nimpacts.1.direction=UNKNOWN\nimpacts.1.reason=观察\nimpacts.1.counter=缺确认\n",false,false);
        assertThat(output.path("evidenceRefs").path(1).asText()).isEqualTo("id,with-comma");
        assertThat(output.path("impacts").path(0).path("newsId").asText()).isEqualTo("original-id");
        assertThatThrownBy(()->ResearchProtocol.parse(f.mapper,base+"plan.action=WAIT\nevidenceRefs=[1]",false,false)).hasMessage("INVALID_STRUCTURED_OUTPUT_LIST");
    }
    @Test void repairFeedbackNamesTheActualConstraintAndPreservesAuditLengths(){
        var client=new Any2ApiClient(f.settings,new com.goldcatcher.RemoteHttp(),f.mapper,f.clock);
        var output=ResearchProtocol.parse(f.mapper,base+"plan.action=WAIT\nplan.invalidate="+"失".repeat(81),false,false);
        assertThatThrownBy(()->client.validate(output)).hasMessage("PLAN_NARRATIVE_TOO_LONG_INVALIDATE");
        assertThat(output.path("plan").path("invalidate").asText()).hasSize(81);
        assertThat(ResearchProtocol.repairInstruction("PLAN_NARRATIVE_TOO_LONG_INVALIDATE")).contains("plan.invalidate≤80");
        assertThat(ResearchProtocol.repairInstruction("MISSING_REQUIRED_OUTPUT_PLAN_ACTION")).contains("plan.action");
    }
    @Test void emptyImpactPlaceholdersAreAbsentButPartialNewsJudgmentsRemainInvalid(){
        var output=ResearchProtocol.parse(f.mapper,base+"selectedCandidateId=WAIT\nimpact.3.newsId=\nimpact.3.horizon=\nimpact.3.direction=\nimpact.3.reason=\nimpact.3.counter=\n",false,true);
        assertThat(output.path("impacts").isEmpty()).isTrue();
        var json=f.mapper.createObjectNode();json.put("selectedCandidateId","WAIT");json.putArray("impacts").addObject().put("newsId","").put("horizon","").put("direction","").put("reason","").putNull("counter");
        assertThat(ResearchProtocol.normalize(json,false,true,3).path("impacts").isEmpty()).isTrue();
        assertThatThrownBy(()->ResearchProtocol.parse(f.mapper,base+"selectedCandidateId=WAIT\nimpact.3.horizon=SHORT\nimpact.3.reason=无新闻id\n",false,true)).hasMessage("INVALID_IMPACT");
        assertThat(ResearchProtocol.repairInstruction("INVALID_IMPACT")).contains("newsId只抄news.id","省略整组");
        assertThat(ResearchProtocol.repairInstruction("INVALID_EVIDENCE_ARRAY")).contains("evidenceRefs最多14");
    }
}
