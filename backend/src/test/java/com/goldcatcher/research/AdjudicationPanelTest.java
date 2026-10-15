package com.goldcatcher.research;

import static org.assertj.core.api.Assertions.*;
import com.goldcatcher.TestSupport;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class AdjudicationPanelTest {
    @Test void noIdJudgesMustCopyOneWholeCandidateAndCannotBlendOrRewriteConditions(){
        var a=proposal("a","100");var b=proposal("b","105");
        var judges=new ArrayList<ResearchService.Message>();for(String actor:List.of("J1","J2","J3")){
            var output=(tools.jackson.databind.node.ObjectNode)output();output.set("plan",a.answer().output().path("plan").deepCopy());
            judges.add(message("judge-"+actor,actor,3,output));
        }
        var result=AdjudicationPanel.aggregate(f.mapper,List.of(a,b),judges,actors);
        assertThat(result.verdict().path("plan")).isEqualTo(a.answer().output().path("plan"));assertThat(result.audit().path("winningVotes").asInt()).isEqualTo(3);
        var mixed=judges.getFirst().answer().output().deepCopy();((tools.jackson.databind.node.ObjectNode)mixed.path("plan")).put("entryPrice","103");
        assertThatThrownBy(()->AdjudicationPanel.validateVote(f.mapper,mixed,List.of(a,b),f.mapper.createObjectNode())).hasMessage("INVALID_JUDGE_CANDIDATE");
        ((tools.jackson.databind.node.ObjectNode)mixed.path("plan")).put("entryPrice","100").put("invalidate","另一失效条件");
        assertThatThrownBy(()->AdjudicationPanel.validateVote(f.mapper,mixed,List.of(a,b),f.mapper.createObjectNode())).hasMessage("INVALID_JUDGE_CANDIDATE");
    }
    @Test void independentJudgesUsingArenaRandomFallbackStillNeedThreeDistinctSeats(){
        var votes=new ArrayList<ResearchService.Message>();for(String id:List.of("J1","J2","J3")){
            var vote=vote(id,"WAIT");var a=vote.answer();var actor=actors.stream().filter(item->item.id().equals(id)).findFirst().orElseThrow();
            var answer=new Any2ApiClient.Answer("arena/Max",a.analysis(),a.analysisSource(),a.raw(),a.output(),a.usage(),a.usageSource(),a.finishReason(),a.budget(),a.durationMs(),a.attempts(),new Any2ApiClient.Routing(actor.provider(),actor.model(),"arena","arena/Max",true,"MODEL_NOT_AVAILABLE",List.of()));
            votes.add(new ResearchService.Message(vote.id(),id,vote.role(),3,"SUBMITTED",vote.startedAt(),vote.completedAt(),null,List.of(),answer));
        }
        var result=AdjudicationPanel.aggregate(f.mapper,List.of(proposal("a","100")),votes,actors);
        assertThat(result.audit().path("validJudges").asInt()).isEqualTo(3);assertThat(result.audit().path("fallbackJudges").asInt()).isEqualTo(3);assertThat(result.audit().path("distinctReportedJudgeModels").asInt()).isEqualTo(1);
        assertThat(result.verdict().path("plan").path("action").asText()).isEqualTo("WAIT");
        assertThatThrownBy(()->AdjudicationPanel.aggregate(f.mapper,List.of(),List.of(votes.getFirst(),votes.getFirst(),votes.getLast()),actors)).hasMessage("DUPLICATE_JUDGE_MODEL");
    }
    final TestSupport f=new TestSupport();
    final List<ResearchService.Actor> actors=List.of(actor("A","mimo","pro"),actor("B","deepseek","default"),actor("C","grok","grok-3"),
        actor("J1","mimo","pro"),actor("J2","deepseek","default"),actor("J3","grok","grok-3"));
    ResearchService.Actor actor(String id,String provider,String model){return new ResearchService.Actor(id,"角色",id,provider,provider+"/"+model);}
    JsonNode output(){return f.mapper.createObjectNode().put("title","结论").put("output","依据").put("shortDirection","UNKNOWN").put("longDirection","UNKNOWN");}
    ResearchService.Message message(String id,String actor,int stage,JsonNode output){return new ResearchService.Message(id,actor,"角色",stage,"SUBMITTED",f.clock.instant(),f.clock.instant(),null,List.of(),
        new Any2ApiClient.Answer("model","摘要","EXPLICIT_OUTPUT_SUMMARY",output.toString(),output,f.mapper.createObjectNode(),"UNKNOWN","stop",null,1));}
    ResearchService.Message proposal(String id,String price){var output=(tools.jackson.databind.node.ObjectNode)output();
        output.putObject("plan").put("action","CREATE").put("side","BUY").put("type","LIMIT").put("quantity","0.01").put("leverage","50")
            .put("entryPrice",price).put("stopLoss","95").put("takeProfit","110").put("riskAssessment","亏损情景").put("invalidate","报价失效");
        return message(id,id,2,output);}
    ResearchService.Message vote(String actor,String candidate){var output=(tools.jackson.databind.node.ObjectNode)output();output.put("selectedCandidateId",candidate);return message("vote-"+actor,actor,3,output);}

    @Test void threeJudgesChooseOneExistingPlanWithoutBlendingPricesOrQuantities(){
        var a=proposal("a","100");var b=proposal("b","105");var votes=List.of(vote("J3","b"),vote("J2","a"),vote("J1","a"));
        var result=AdjudicationPanel.aggregate(f.mapper,List.of(a,b),votes,actors);
        assertThat(result.verdict().path("plan")).isEqualTo(a.answer().output().path("plan"));
        assertThat(result.audit().path("winningVotes").asInt()).isEqualTo(2);
        assertThat(result.audit().path("majorityThreshold").asInt()).isEqualTo(2);
        assertThat(result.audit().path("state").asText()).isEqualTo("MAJORITY");
        for(var vote:result.audit().path("votes"))assertThat(vote.path("participatedInResearch").asBoolean()).isTrue();
        assertThat(AdjudicationPanel.aggregate(f.mapper,List.of(b,a),votes.reversed(),actors).verdict()).isEqualTo(result.verdict());
    }
    @Test void threeDifferentChoicesProduceWaitAndKeepEachReason(){
        var result=AdjudicationPanel.aggregate(f.mapper,List.of(proposal("a","100"),proposal("b","105")),
            List.of(vote("J1","a"),vote("J2","b"),vote("J3","WAIT")),actors);
        assertThat(result.audit().path("state").asText()).isEqualTo("NO_MAJORITY");
        assertThat(result.verdict().path("plan").path("action").asText()).isEqualTo("WAIT");
        assertThat(result.audit().path("votes")).hasSize(3);assertThat(result.verdict().path("quotedMessages")).hasSize(3);
    }
    @Test void fewerThanThreeValidJudgesCannotProduceAnyVerdict(){
        assertThatThrownBy(()->AdjudicationPanel.aggregate(f.mapper,List.of(proposal("a","100")),List.of(vote("J1","a"),vote("J2","a")),actors))
            .hasMessage("INSUFFICIENT_VALID_JUDGE_OUTPUTS");
    }
    @Test void sameUnderlyingModelAcrossVendorsCannotGetTwoJudgeVotes(){
        var duplicates=List.of(actor("J1","vendor-a","same"),actor("J2","vendor-b","SAME"),actor("J3","grok","other"));
        assertThatThrownBy(()->AdjudicationPanel.aggregate(f.mapper,List.of(proposal("a","100")),
            List.of(vote("J1","a"),vote("J2","a"),vote("J3","a")),duplicates)).hasMessage("DUPLICATE_JUDGE_MODEL");
    }
    @Test void equivalentExecutablePlansShareVotesWhileTheOriginalPlanIsPreserved(){
        var a=proposal("a","100");var b=proposal("b","100.000");
        ((tools.jackson.databind.node.ObjectNode)b.answer().output().path("plan")).put("quantity","0.0100").putNull("triggerPrice");
        var result=AdjudicationPanel.aggregate(f.mapper,List.of(b,a),List.of(vote("J1","a"),vote("J2","b"),vote("J3","WAIT")),actors);
        assertThat(result.audit().path("winningVotes").asInt()).isEqualTo(2);
        assertThat(result.audit().path("candidateGroups").path(0).path("proposalIds")).hasSize(2);
        assertThat(result.verdict().path("plan")).isEqualTo(a.answer().output().path("plan"));
    }
    @Test void differentInvalidationConditionsCannotBeMergedIntoOnePlan(){
        var a=proposal("a","100");var b=proposal("b","100");
        ((tools.jackson.databind.node.ObjectNode)b.answer().output().path("plan")).put("invalidate","美元突破另一阈值时失效");
        var result=AdjudicationPanel.aggregate(f.mapper,List.of(a,b),List.of(vote("J1","a"),vote("J2","b"),vote("J3","WAIT")),actors);
        assertThat(result.audit().path("state").asText()).isEqualTo("NO_MAJORITY");
        assertThat(result.verdict().path("plan").path("action").asText()).isEqualTo("WAIT");
    }
    @Test void inventedCandidateOrMissingVoteCannotCount(){
        var proposals=List.of(proposal("a","100"));
        assertThatThrownBy(()->AdjudicationPanel.validateVote(vote("J1","invented").answer().output(),proposals)).hasMessage("INVALID_JUDGE_CANDIDATE");
        assertThatThrownBy(()->AdjudicationPanel.validateVote(output(),proposals)).hasMessage("MISSING_JUDGE_VOTE");
    }
    @Test void fourJudgeTieCannotPassAThreeVoteMajority(){
        var four=new ArrayList<>(actors);four.add(actor("J4","fourth","fourth"));
        var result=AdjudicationPanel.aggregate(f.mapper,List.of(proposal("a","100"),proposal("b","105")),
            List.of(vote("J1","a"),vote("J2","a"),vote("J3","b"),vote("J4","b")),four);
        assertThat(result.audit().path("majorityThreshold").asInt()).isEqualTo(3);
        assertThat(result.verdict().path("plan").path("action").asText()).isEqualTo("WAIT");
    }
    @Test void duplicateNewsClaimsByOneJudgeCannotManufactureConsensus(){
        var first=vote("J1","WAIT");var second=vote("J2","WAIT");var third=vote("J3","WAIT");
        var impact=f.mapper.createObjectNode().put("newsId","news").put("horizon","SHORT").put("direction","BULLISH").put("reason","依据").put("counter","反证");
        ((tools.jackson.databind.node.ObjectNode)first.answer().output()).putArray("impacts").add(impact).add(impact);
        var result=AdjudicationPanel.aggregate(f.mapper,List.of(proposal("a","100")),List.of(first,second,third),actors);
        assertThat(result.verdict().path("impacts")).isEmpty();
    }
}
