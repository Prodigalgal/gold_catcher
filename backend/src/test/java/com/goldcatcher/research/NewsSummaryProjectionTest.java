package com.goldcatcher.research;

import static org.assertj.core.api.Assertions.*;
import com.goldcatcher.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class NewsSummaryProjectionTest {
    final TestSupport f=new TestSupport();final Instant cutoff=f.clock.instant();
    EvidenceCompression.Result summary(String sourceHash,Instant completed,String state){var node=f.mapper.createObjectNode().put("id","summary").put("newsId","news").put("sourceTextHash",sourceHash)
        .put("state",state).put("excerpt","事实0.6%，条件尚未确认。😀").put("completedAt",completed.toString()).put("totalChunks",2).put("processedChunks",2).put("sourceCharacters",1000).put("sourceTruncated",false).put("provider","mimo").put("model","mimo/flash");return f.mapper.treeToValue(node,EvidenceCompression.Result.class);}
    NewsSummaryProjection.Item item(EvidenceCompression.Result result){return new NewsSummaryProjection.Item(Map.of("id","news","title","标题","bodyHash","body","sourceTextHash","known-source","excerpt","原文摘录"),"known-source","HTML_TEXT",1000,result);}
    @Test void completeMatchingSummaryIsSelectedWithoutRewritingItAndRoundTripsForReplay(){
        var input=new NewsSummaryProjection.Input(NewsSummaryProjection.VERSION,true,cutoff,"mimo","mimo/flash",List.of(item(summary("known-source",cutoff,"EXTRACTED"))));
        String before=CanonicalJson.text(f.mapper,input);var result=NewsSummaryProjection.project(input);assertThat(result.selected()).hasSize(1);
        assertThat(result.selected().getFirst().get("excerpt")).isEqualTo("事实0.6%，条件尚未确认。😀");assertThat(CanonicalJson.text(f.mapper,input)).isEqualTo(before);
        var copy=f.mapper.readValue(f.mapper.writeValueAsString(input),NewsSummaryProjection.Input.class);f.clock.advance(86400000);
        assertThat(CanonicalJson.text(f.mapper,NewsSummaryProjection.project(copy))).isEqualTo(CanonicalJson.text(f.mapper,result));
    }
    @Test void futureWrongVersionAndPartialSummariesCannotEnterDownstream(){
        for(var result:List.of(summary("known-source",cutoff.plusSeconds(1),"EXTRACTED"),summary("different-source",cutoff,"EXTRACTED"),summary("known-source",cutoff,"PARTIAL"))){
            var selected=NewsSummaryProjection.project(new NewsSummaryProjection.Input(NewsSummaryProjection.VERSION,true,cutoff,"mimo","mimo/flash",List.of(item(result))));
            assertThat(selected.selected()).isEmpty();assertThat(selected.audit().get("omittedArticles")).isEqualTo(1);
        }
    }
}
