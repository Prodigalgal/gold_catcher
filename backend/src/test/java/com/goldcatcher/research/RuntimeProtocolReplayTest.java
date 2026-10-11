package com.goldcatcher.research;

import static org.assertj.core.api.Assertions.*;
import com.goldcatcher.TestSupport;
import com.goldcatcher.RemoteHttp;
import java.nio.file.*;
import org.junit.jupiter.api.Test;

class RuntimeProtocolReplayTest {
    @Test void actualRejectedProviderRepliesRecoverOnlyTheirUnambiguousFormatting()throws Exception{
        String path=System.getenv("GOLD_TEST_PROTOCOL_RUN");
        org.junit.jupiter.api.Assumptions.assumeTrue(path!=null&&!path.isBlank(),"Optional actual research output replay; no model HTTP");
        var f=new TestSupport();var run=f.mapper.readTree(Files.readString(Path.of(path)));
        var client=new Any2ApiClient(f.settings,new RemoteHttp(),f.mapper,f.clock);int recovered=0;
        for(var message:run.path("messages")){
            if(message.path("stage").asInt()!=0)continue;
            String actor=message.path("actorId").asText();int attempt=message.path("attempt").asInt();
            if(!(actor.equals("B")&&attempt==3||actor.equals("C")&&(attempt==2||attempt==3)))continue;
            var normalized=ResearchProtocol.parse(f.mapper,message.path("answer").path("raw").asText(),false,false,0);
            assertThatCode(()->client.validate(normalized)).doesNotThrowAnyException();
            assertThat(normalized.path("plan").path("action").asText()).isEqualTo("WAIT");
            for(var id:normalized.path("evidenceRefs"))assertThat(run.path("evidence").toString()).contains(id.asText());
            recovered++;
        }
        assertThat(recovered).isEqualTo(3);
    }
}
