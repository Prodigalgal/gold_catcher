package com.goldcatcher.research;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.goldcatcher.*;
import com.goldcatcher.information.NewsService;
import com.goldcatcher.market.MarketFeed;
import com.goldcatcher.simulation.SimulationLedger;
import java.util.*;
import org.junit.jupiter.api.Test;
class ResearchSummaryTest {
    @Test void aggregatesResearchAndJudgeAttemptsWithoutStoringPromptsOrOutputs(){
        var f=new TestSupport();var run=new ResearchService.Run();run.id="historical";run.symbol="XAU_USDT";run.horizon="LONG";run.status="LOW_QUORUM";run.startedAt=f.clock.instant();
        run.actors=List.of(new ResearchService.Actor("A","研究","a","mimo","pro"),new ResearchService.Actor("J1","裁决","j1","mimo","pro"));
        var answer=new Any2ApiClient.Answer("pro","PRIVATE_ANALYSIS","WEB","PRIVATE_RAW",null,null,"NONE","stop",null,100);
        run.messages=List.of(new ResearchService.Message("one","A","研究",0,"SUBMITTED",f.clock.instant(),f.clock.instant(),null,List.of(),answer),new ResearchService.Message("two","J1","裁决",3,"FAILED",f.clock.instant(),f.clock.instant(),"REQUEST_TIMEOUT",List.of(),answer));
        var summary=ResearchService.summary(run);var model=summary.modelStatistics().getFirst();assertThat(model.calls()).isEqualTo(2);assertThat(model.submitted()).isEqualTo(1);assertThat(model.failed()).isEqualTo(1);assertThat(model.durationMillis()).isEqualTo(200);
        assertThat(f.mapper.writeValueAsString(summary)).doesNotContain("PRIVATE_ANALYSIS","PRIVATE_RAW");
    }
    @Test void backfillLeavesFinishedResearchAndEvidenceUnchanged(){
        var f=new TestSupport();var run=new ResearchService.Run();run.id="finished";run.symbol="XAU_USDT";run.horizon="SHORT";run.status="COMPLETED_EXPIRED";run.startedAt=f.clock.instant();
        run.evidence=f.mapper.valueToTree(Map.of("frozen","ORIGINAL"));f.store.save("research",run.id,run);String original=f.mapper.writeValueAsString(f.store.find("research",run.id,ResearchService.Run.class).orElseThrow());
        var news=new NewsService(f.settings,new RemoteHttp(),f.store,new EventHub(),f.clock);
        var research=new ResearchService(f.settings,f.store,mock(MarketFeed.class),news,mock(SimulationLedger.class),mock(Any2ApiClient.class),f.mapper,new EventHub(),f.clock);
        try{research.recover();assertThat(research.summaries()).hasSize(1);assertThat(research.latest("XAU_USDT").id).isEqualTo(run.id);assertThat(f.mapper.writeValueAsString(research.find(run.id))).isEqualTo(original);}
        finally{research.stop();}
    }
}
