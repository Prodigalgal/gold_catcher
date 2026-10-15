package com.goldcatcher.research;

import static org.assertj.core.api.Assertions.*;
import com.goldcatcher.TestSupport;
import org.junit.jupiter.api.Test;
class ModelProtocolTest {
    final TestSupport f=new TestSupport();
    final String base="protocol=GOLD_RESEARCH_V2\ntitle=观察\noutput=成本与反证\nshortDirection=UNKNOWN\nlongDirection=MIXED\n";
    @Test void textAndNativeJsonUseTheSameFullPlanWithExactNumbers(){
        var output=ModelProtocol.parse(f.mapper,"简短说明\n"+base+"plan.action=CREATE\nplan.quantity=0001.0000000000000001\nplan.entryPrice=4096.123456789012345678\nplan.stopLoss=0\nplan.triggerPrice=null\n");
        assertThat(ModelProtocol.normalize(output)).isEqualTo(output);assertThat(output.path("plan").path("entryPrice").asText()).isEqualTo("4096.123456789012345678");
        assertThat(output.path("plan").path("stopLoss").asText()).isEqualTo("0");assertThat(output.path("plan").path("triggerPrice").isNull()).isTrue();
        assertThat(output.has("quotedMessages")).isFalse();assertThat(output.has("evidenceRefs")).isFalse();
    }
    @Test void identifiersAndControlsAreRejectedInTextJsonAndToolSchema(){
        for(String field:java.util.List.of("selectedCandidateId=WAIT","evidenceRefs=","quotedMessages=","plan.orderId=uuid","phase=3","impact.1.newsId=uuid"))
            assertThatThrownBy(()->ModelProtocol.parse(f.mapper,base+"plan.action=WAIT\n"+field)).hasMessageStartingWith("INVALID_STRUCTURED_OUTPUT");
        var node=f.mapper.createObjectNode().put("selectedCandidateId","WAIT");assertThatThrownBy(()->ModelProtocol.normalize(node)).hasMessageStartingWith("INVALID_STRUCTURED_OUTPUT");
        assertThat(f.mapper.writeValueAsString(ModelProtocol.schema())).doesNotContain("orderId","CandidateId","newsId","evidenceRefs","quotedMessages");
    }
    @Test void managementTargetAndTopicJudgmentsNeedNoReference(){
        var output=ModelProtocol.parse(f.mapper,base+"plan.action=MODIFY\nplan.target.side=BUY\nplan.target.type=LIMIT\nplan.target.quantity=0.01\nplan.target.leverage=50\nplan.target.entryPrice=4100\nplan.stopLoss=4090\nimpact.1.topic=CPI高于预期\nimpact.1.horizon=SHORT\nimpact.1.direction=BEARISH\nimpact.1.reason=收益率可能走高\nimpact.1.counter=美元未确认\n");
        assertThat(output.path("plan").path("target").path("entryPrice").asText()).isEqualTo("4100");assertThat(output.path("impacts").path(0).path("topic").asText()).isEqualTo("CPI高于预期");
        assertThatThrownBy(()->ModelProtocol.parse(f.mapper,base+"plan.action=WAIT\nimpact.1.topic=仅主题")).hasMessage("INVALID_IMPACT");
        assertThatThrownBy(()->ModelProtocol.parse(f.mapper,base+"plan.action=WAIT\nplan.action=CREATE")).hasMessageStartingWith("INVALID_STRUCTURED_OUTPUT_CONFLICT");
    }
}
