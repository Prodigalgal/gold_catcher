package com.goldcatcher.research;

import com.goldcatcher.CanonicalJson;
import com.goldcatcher.analysis.*;
import com.goldcatcher.market.IndicatorReplay;
import com.goldcatcher.persistence.DocumentStore;
import java.time.Instant;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.*;

/** Authenticated read-only inspection. These endpoints cannot schedule work, collect data or place orders. */
@RestController
@RequestMapping("/api/data-flows")
public class DataFlowApi {
    private final InferenceJournal journal;private final DocumentStore store;private final ObjectMapper mapper;
    public DataFlowApi(InferenceJournal journal,DocumentStore store,ObjectMapper mapper){this.journal=journal;this.store=store;this.mapper=mapper;}
    @GetMapping("/{id}") public Map<String,Object> detail(@PathVariable String id){return journal.detail(id);}
    @GetMapping("/{id}/replay") public InferenceJournal.Replay replay(@PathVariable String id){return journal.replay(id);}
    @GetMapping("/{id}/stages/{stage}") public ModelDataFlow.StageSnapshot stage(@PathVariable String id,@PathVariable String stage){return journal.stage(id,stage);}
    @GetMapping("/analysis/{runId}/replay") public Map<String,Object> analysis(@PathVariable String runId){
        if(!runId.matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("INVALID_RESEARCH_ID");
        var saved=store.find("research-analysis-projection",runId,JsonNode.class).orElseThrow(()->new IllegalArgumentException("ANALYSIS_FLOW_NOT_RECORDED"));
        if(!AnalysisProjection.VERSION.equals(saved.path("version").asText()))return Map.of("supported",false,"version",saved.path("version").asText());
        var input=store.find("research-analysis",runId,AnalysisTypes.View.class).orElseThrow(()->new IllegalArgumentException("ANALYSIS_INPUT_NOT_FOUND"));
        var result=AnalysisProjection.project(mapper,input,Instant.parse(saved.path("cutoff").asText()));
        boolean matches=CanonicalJson.tree(mapper,mapper.valueToTree(result.packet())).equals(CanonicalJson.tree(mapper,saved.path("output")));
        return Map.of("version",AnalysisProjection.VERSION,"supported",true,"matches",matches,"selection",result.selection());
    }
    @GetMapping("/indicators/{runId}/replay") public Map<String,Object> indicators(@PathVariable String runId){
        if(!runId.matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("INVALID_RESEARCH_ID");
        var saved=store.find("research-indicator-inputs",runId,new tools.jackson.core.type.TypeReference<List<IndicatorReplay.Calculation>>(){}).orElse(List.of());
        var frozen=store.find("research-indicators",runId,JsonNode.class).orElseThrow(()->new IllegalArgumentException("INDICATORS_NOT_FOUND"));
        var checks=saved.stream().map(IndicatorReplay::replay).toList();
        return Map.of("version",IndicatorReplay.VERSION,"recorded",saved.size(),"expected",frozen.size(),"complete",saved.size()==frozen.size()&&!saved.isEmpty(),"checks",checks);
    }
    @GetMapping("/news/{runId}/replay") public Map<String,Object> news(@PathVariable String runId){
        if(!runId.matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("INVALID_RESEARCH_ID");
        var saved=store.find("research-summary-selection",runId,JsonNode.class).orElseThrow(()->new IllegalArgumentException("NEWS_FLOW_NOT_RECORDED"));
        if(!NewsSummaryProjection.VERSION.equals(saved.path("input").path("version").asText()))return Map.of("supported",false);
        var input=mapper.treeToValue(saved.path("input"),NewsSummaryProjection.Input.class);var result=NewsSummaryProjection.project(input);
        return Map.of("version",NewsSummaryProjection.VERSION,"supported",true,"matches",CanonicalJson.tree(mapper,mapper.valueToTree(result)).equals(CanonicalJson.tree(mapper,saved.path("output"))),
            "completed",result.selected().size(),"omitted",input.items().size()-result.selected().size());
    }
}
