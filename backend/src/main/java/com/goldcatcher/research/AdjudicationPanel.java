package com.goldcatcher.research;

import java.math.BigDecimal;
import java.util.*;
import tools.jackson.databind.*;

/** Independent votes choose an existing proposal. Numeric plans are never blended. */
public final class AdjudicationPanel {
    public static final int MINIMUM_JUDGES=3;
    private static final List<String> EXECUTION_FIELDS=List.of("action","orderId","side","type","quantity","leverage",
        "entryPrice","triggerPrice","stopLoss","takeProfit","trailingDistance","trailingActivation","expiresAt","holdUntil","riskAssessment","invalidate","target");
    private static final Set<String> DECIMALS=Set.of("quantity","leverage","entryPrice","triggerPrice","stopLoss","takeProfit","trailingDistance","trailingActivation");
    public record Result(JsonNode verdict,JsonNode audit) {}
    private AdjudicationPanel() {}

    static void validateVote(JsonNode output,List<ResearchService.Message> proposals) {
        var candidate=output.path("selectedCandidateId");
        if(!candidate.isTextual()||candidate.asText().isBlank())throw new IllegalStateException("MISSING_JUDGE_VOTE");
        if(candidate.asText().equals("WAIT"))return;
        var chosen=proposals.stream().filter(message->message.id().equals(candidate.asText())).findFirst()
            .orElseThrow(()->new IllegalStateException("INVALID_JUDGE_CANDIDATE"));
        if(!chosen.answer().output().path("plan").isObject())throw new IllegalStateException("INVALID_JUDGE_CANDIDATE");
    }

    static void validateVote(ObjectMapper mapper,JsonNode output,List<ResearchService.Message> proposals,JsonNode evidence) {
        selected(mapper,output,proposals,evidence);
    }
    private static String selected(ObjectMapper mapper,JsonNode output,List<ResearchService.Message> proposals,JsonNode evidence) {
        if(output.has("selectedCandidateId")){validateVote(output,proposals);return output.path("selectedCandidateId").asText();}
        var plan=output.path("plan");if(!plan.isObject()||plan.path("action").asText().isBlank())throw new IllegalStateException("MISSING_JUDGE_VOTE");
        if(plan.path("action").asText().equals("WAIT"))return "WAIT";
        String signature=signature(mapper,plan);
        return proposals.stream().filter(proposal->proposal.answer().output().path("plan").isObject())
            .filter(proposal->signature(mapper,ModelInput.plan(mapper,proposal.answer().output().path("plan"),evidence)).equals(signature))
            .sorted(Comparator.comparing(ResearchService.Message::id)).map(ResearchService.Message::id).findFirst()
            .orElseThrow(()->new IllegalStateException("INVALID_JUDGE_CANDIDATE"));
    }

    public static Result aggregate(ObjectMapper mapper,List<ResearchService.Message> proposals,
                                   List<ResearchService.Message> judges,List<ResearchService.Actor> actors) {
        return aggregate(mapper,proposals,judges,actors,mapper.createObjectNode());
    }
    public static Result aggregate(ObjectMapper mapper,List<ResearchService.Message> proposals,
                                   List<ResearchService.Message> judges,List<ResearchService.Actor> actors,JsonNode evidence) {
        if(judges.size()<MINIMUM_JUDGES)throw new IllegalStateException("INSUFFICIENT_VALID_JUDGE_OUTPUTS");
        var actorModels=new HashMap<String,String>();actors.forEach(actor->actorModels.put(actor.id(),actor.model()));
        var judgeModels=new HashSet<String>();var judgeIds=new HashSet<String>();
        for(var judge:judges) {
            String model=actorModels.get(judge.actorId());
            if(!judge.actorId().startsWith("J")||model==null||!judgeIds.add(judge.actorId())
                ||!judgeModels.add(ResearchService.modelIdentity(model)))throw new IllegalStateException("DUPLICATE_JUDGE_MODEL");
            validateVote(mapper,judge.answer().output(),proposals,evidence);
        }
        // Equivalent decimal spellings and missing/null fields do not split votes.
        // Risk and invalidation text must also agree; different conditions do not merge.
        var byId=new HashMap<String,ResearchService.Message>();
        var candidates=new LinkedHashMap<String,ResearchService.Message>();
        for(var proposal:proposals.stream().sorted(Comparator.comparing(ResearchService.Message::id)).toList()) {
            byId.put(proposal.id(),proposal);
            var plan=proposal.answer().output().path("plan");
            if(plan.isObject())candidates.putIfAbsent(signature(mapper,plan),proposal);
        }
        int majority=judges.size()/2+1;
        var tallies=new LinkedHashMap<String,Integer>();var votes=mapper.createArrayNode();
        var orderedJudges=judges.stream().sorted(Comparator.comparing(ResearchService.Message::actorId)).toList();
        for(var judge:orderedJudges) {
            var output=judge.answer().output();String selected=selected(mapper,output,proposals,evidence);
            String key=selected.equals("WAIT")?"WAIT":signature(mapper,byId.get(selected).answer().output().path("plan"));
            String canonical=key.equals("WAIT")?"WAIT":candidates.get(key).id();
            tallies.merge(key,1,Integer::sum);
            var vote=votes.addObject();vote.put("actorId",judge.actorId());vote.put("requestedModel",actorModels.get(judge.actorId()));
            vote.put("model",judge.answer().routing()==null?actorModels.get(judge.actorId()):judge.answer().model());
            vote.put("fallbackUsed",judge.answer().routing()!=null&&judge.answer().routing().fallbackUsed());
            if(judge.answer().routing()!=null)vote.put("routedModel",judge.answer().routing().usedModel());
            vote.put("participatedInResearch",actors.stream().filter(actor->!actor.id().startsWith("J"))
                .anyMatch(actor->ResearchService.modelIdentity(actor.model()).equals(ResearchService.modelIdentity(actorModels.get(judge.actorId())))));
            vote.put("messageId",judge.id());vote.put("selectedCandidateId",selected);vote.put("canonicalCandidateId",canonical);
            vote.put("shortDirection",output.path("shortDirection").asText());vote.put("longDirection",output.path("longDirection").asText());
            vote.put("reason",output.path("output").asText());
        }
        String winner=tallies.entrySet().stream().filter(entry->entry.getValue()>=majority).map(Map.Entry::getKey).findFirst().orElse(null);
        String state=winner==null?"NO_MAJORITY":winner.equals("WAIT")?"WAIT_MAJORITY":"MAJORITY";
        String chosenId=winner==null||winner.equals("WAIT")?"WAIT":candidates.get(winner).id();
        var verdict=mapper.createObjectNode();verdict.put("title",chosenId.equals("WAIT")?"多模型裁决：等待":"多模型裁决：采纳候选方案");
        String explanation=winner==null?"裁决未形成多数，保持等待。":winner.equals("WAIT")?"多数裁决选择等待。":"多数裁决选择同一候选方案，执行原方案数值。";
        verdict.put("analysisSummary",explanation);verdict.put("output",judges.size()+" 席独立裁决；多数门槛 "+majority+" 票。"+explanation+" 实际模型路由、兜底与各方理由保留在裁决记录。");
        verdict.put("shortDirection",direction(orderedJudges,"shortDirection",majority));
        verdict.put("longDirection",direction(orderedJudges,"longDirection",majority));
        verdict.put("selectedCandidateId",chosenId);verdict.put("changes","Java 按独立席位票数汇总；价格、数量、杠杆及退出条件不做平均或拼接。");
        var citations=verdict.putArray("quotedMessages");orderedJudges.forEach(judge->citations.add(judge.id()));
        var evidenceIds=new TreeSet<String>();orderedJudges.forEach(judge->judge.answer().output().path("evidenceRefs").forEach(id->evidenceIds.add(id.asText())));
        var evidenceReferences=verdict.putArray("evidenceRefs");evidenceIds.stream().limit(14).forEach(evidenceReferences::add);
        verdict.set("impacts",consensusImpacts(mapper,orderedJudges,majority));
        if(chosenId.equals("WAIT"))verdict.putObject("plan").put("action","WAIT").put("riskAssessment",explanation).put("invalidate","取得新的冻结证据后重新研究。");
        else verdict.set("plan",candidates.get(winner).answer().output().path("plan").deepCopy());
        var audit=mapper.createObjectNode();audit.put("method","BLIND_INDEPENDENT_CANDIDATE_VOTE");audit.put("state",state);
        audit.put("fallbackJudges",judges.stream().filter(j->j.answer().routing()!=null&&j.answer().routing().fallbackUsed()).count());
        audit.put("distinctReportedJudgeModels",judges.stream().map(j->j.answer().routing()==null?actorModels.get(j.actorId()):j.answer().model()).distinct().count());
        audit.put("modelIdentityBasis","CONFIGURED_PRIMARY_MODELS_AND_REPORTED_RESPONSE_LABELS; ARENA_MAX_RANDOM_BACKENDS_MAY_NOT_BE_REPORTED");
        audit.put("minimumJudges",MINIMUM_JUDGES);audit.put("validJudges",judges.size());audit.put("majorityThreshold",majority);
        audit.put("winningVotes",winner==null?0:tallies.get(winner));audit.put("selectedCandidateId",chosenId);audit.set("votes",votes);
        var groups=audit.putArray("candidateGroups");
        for(var entry:candidates.entrySet()) {
            var group=groups.addObject();group.put("candidateId",entry.getKey().equals("WAIT")?"WAIT":entry.getValue().id());
            group.put("votes",tallies.getOrDefault(entry.getKey(),0));var ids=group.putArray("proposalIds");
            proposals.stream().filter(proposal->proposal.answer().output().path("plan").isObject())
                .filter(proposal->signature(mapper,proposal.answer().output().path("plan")).equals(entry.getKey()))
                .map(ResearchService.Message::id).sorted().forEach(ids::add);
        }
        return new Result(verdict,audit);
    }

    private static String direction(List<ResearchService.Message> judges,String field,int majority) {
        var counts=new TreeMap<String,Integer>();judges.forEach(judge->counts.merge(judge.answer().output().path(field).asText(),1,Integer::sum));
        return counts.entrySet().stream().filter(entry->entry.getValue()>=majority).map(Map.Entry::getKey).findFirst().orElse("MIXED");
    }
    private static JsonNode consensusImpacts(ObjectMapper mapper,List<ResearchService.Message> judges,int majority) {
        var groups=new TreeMap<String,Map<String,JsonNode>>();
        for(var judge:judges)for(var impact:judge.answer().output().path("impacts")) {
            String key=(impact.has("newsId")?impact.path("newsId").asText():impact.path("topic").asText())+"/"+impact.path("horizon").asText()+"/"+impact.path("direction").asText();
            groups.computeIfAbsent(key,ignored->new TreeMap<>()).putIfAbsent(judge.actorId(),impact);
        }
        var result=mapper.createArrayNode();groups.values().stream().filter(group->group.size()>=majority).limit(4)
            .forEach(group->result.add(group.values().iterator().next().deepCopy()));return result;
    }
    private static String signature(ObjectMapper mapper,JsonNode plan) {
        if(plan.path("action").asText().equals("WAIT"))return "WAIT";
        var canonical=mapper.createObjectNode();var fields=new TreeSet<>(EXECUTION_FIELDS);
        plan.properties().forEach(entry->fields.add(entry.getKey()));
        for(var field:fields) {
            var value=plan.path(field);
            if(value.isMissingNode()||value.isNull())canonical.putNull(field);
            else if(DECIMALS.contains(field)) {
                try{canonical.put(field,new BigDecimal(value.asText()).stripTrailingZeros().toPlainString());}
                catch(NumberFormatException invalid){canonical.set(field,value.deepCopy());}
            }else if(field.equals("target")&&value.isObject()){
                var target=canonical.putObject(field);for(String key:ModelProtocol.TARGET_FIELDS){var v=value.path(key);
                    if(DECIMALS.contains(key))try{target.put(key,new BigDecimal(v.asText()).stripTrailingZeros().toPlainString());}catch(NumberFormatException invalid){target.set(key,v.deepCopy());}
                    else target.set(key,v.deepCopy());}
            }else canonical.set(field,value.deepCopy());
        }
        return mapper.writeValueAsString(canonical);
    }
}
