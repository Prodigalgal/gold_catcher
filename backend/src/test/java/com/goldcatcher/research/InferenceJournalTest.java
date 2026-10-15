package com.goldcatcher.research;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.goldcatcher.*;
import com.goldcatcher.RuntimeSettings.AiRoute;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

class InferenceJournalTest {
    final TestSupport f=new TestSupport();final RemoteHttp remote=mock(RemoteHttp.class);
    final InferenceJournal journal=new InferenceJournal(f.store,f.mapper,f.clock);
    AiRoute route(){var r=new AiRoute("custom","custom","custom/model","https://localhost/v1","UI",20000,30000,60000,2,1000,180,false);
        f.settings.registerConfiguredRoute(r,"PRIVATE_TEST_SECRET","OPENAI_COMPATIBLE","MANUAL");return r;}
    @SuppressWarnings("unchecked") HttpResponse<byte[]> response(int status,String text){var r=(HttpResponse<byte[]>)mock(HttpResponse.class);when(r.statusCode()).thenReturn(status);when(r.body()).thenReturn(text.getBytes(StandardCharsets.UTF_8));
        when(r.headers()).thenReturn(HttpHeaders.of(Map.of("X-Request-Id",List.of("upstream-known-id")),(a,b)->true));return r;}
    Map<String,Object> packet(){return Map.of("protocol",ModelProtocol.VERSION,"phase",0,"execution",Map.of("clientTraceId","private-control"),"evidence",Map.of("horizon","LONG","symbol","XAU_USDT","news",List.of(Map.of("id","private-news","excerpt","美元变化，尚未确认。"))));}
    String envelope(String raw){return f.mapper.writeValueAsString(Map.of("choices",List.of(Map.of("finish_reason","stop","message",Map.of("content",raw)))));}
    @Test void realClientPathArchivesExactRequestAndReplayHasNoNetworkOrWrites()throws Exception{
        var r=route();String valid="{\"title\":\"等待\",\"output\":\"尚未确认\",\"shortDirection\":\"UNKNOWN\",\"longDirection\":\"UNKNOWN\",\"plan\":{\"action\":\"WAIT\"}}";
        var good=response(200,envelope(valid));when(remote.sendInference(any(),eq(8_000_000),any())).thenReturn(good);
        var client=new Any2ApiClient(f.settings,remote,f.mapper,f.clock,journal);var answer=client.complete(r,"研究",packet(),f.clock.instant().plusSeconds(300));
        String id=answer.budget().dataFlowId();assertThat(id).isNotBlank();var snapshot=journal.find(id);
        assertThat(snapshot.body()).doesNotContain("PRIVATE_TEST_SECRET","private-control","private-news","dataFlowId");
        assertThat(snapshot.flow().steps()).hasSize(9);assertThat(snapshot.accepted()).isEqualTo(answer.output());
        var inputRevision=f.store.revision("inference-flow");var exchangeRevision=f.store.revision("inference-exchange");var ledgerRevision=f.store.revision("simulation");
        f.clock.advance(86400000);var replay=journal.replay(id);assertThat(replay.supported()).isTrue();assertThat(replay.inputMatches()).isTrue();assertThat(replay.requestMatches()).isTrue();assertThat(replay.stepsMatch()).isTrue();assertThat(replay.responseMatches()).isTrue();
        var stage=journal.stage(id,"boundary");assertThat(stage.input()).isEqualTo(snapshot.input().packet());assertThat(stage.output().toString()).doesNotContain("private-news","private-control");
        assertThat(f.store.revision("inference-flow")).isEqualTo(inputRevision);assertThat(f.store.revision("inference-exchange")).isEqualTo(exchangeRevision);assertThat(f.store.revision("simulation")).isEqualTo(ledgerRevision);
        verify(remote,times(1)).sendInference(any(),eq(8_000_000),any());verify(remote,never()).send(any(),anyInt());
        var roundTrip=f.mapper.readValue(f.mapper.writeValueAsString(snapshot),InferenceJournal.Snapshot.class);
        assertThat(InferenceJournal.replay(f.mapper,roundTrip,List.of(new InferenceJournal.Exchange(1,200,envelope(valid),"trace","upstream-known-id",null))).requestMatches()).isTrue();
    }
    @Test void sseErrorWithHttp200IsArchivedAsFailureAndReplaysAsFailure()throws Exception{
        var r=route();var bad=response(200,"event: error\ndata: {\"error\":{\"code\":\"UPSTREAM_FAILURE\",\"message\":\"bad\"}}\n\n");when(remote.sendInference(any(),eq(8_000_000),any())).thenReturn(bad);
        var client=new Any2ApiClient(f.settings,remote,f.mapper,f.clock,journal);
        assertThatThrownBy(()->client.complete(r,"研究",packet(),f.clock.instant().plusSeconds(300))).isInstanceOf(Any2ApiClient.InferenceFailure.class);
        var snapshot=f.store.list("inference-flow",InferenceJournal.Snapshot.class,10).getFirst();assertThat(snapshot.state()).isEqualTo("FAILED");assertThat(snapshot.accepted().isNull()).isTrue();
        var replay=journal.replay(snapshot.id());assertThat(replay.responseState()).isEqualTo("FAILED");assertThat(replay.responseMatches()).isTrue();assertThat(replay.requestMatches()).isTrue();
    }
    @Test void timeoutWithoutResponseIsExplicitlyNotAResponseReplay()throws Exception{
        var r=route();when(remote.sendInference(any(),eq(8_000_000),any())).thenThrow(new HttpTimeoutException("test"));
        var client=new Any2ApiClient(f.settings,remote,f.mapper,f.clock,journal);
        assertThatThrownBy(()->client.complete(r,"研究",packet(),f.clock.instant().plusSeconds(300))).hasMessage("REQUEST_TIMEOUT");
        var snapshot=f.store.list("inference-flow",InferenceJournal.Snapshot.class,10).getFirst();var replay=journal.replay(snapshot.id());
        assertThat(replay.requestMatches()).isTrue();assertThat(replay.responseMatches()).isNull();assertThat(replay.responseState()).isEqualTo("TRANSPORT_WITHOUT_COMPLETE_RESPONSE");
        verify(remote,times(1)).sendInference(any(),eq(8_000_000),any());
    }
    @Test void changedInputsAndUnknownVersionCannotPassReplay(){
        var packet=f.mapper.valueToTree(packet());var input=new InferenceRequest.Input(InferenceRequest.VERSION,"model","custom",1000,true,true,false,"deep_lane",f.mapper.createObjectNode(),new ContextWindow.Limits(60000,30000,20000,0,0,0,2,512,90),"研究",packet);
        var plan=InferenceRequest.prepare(f.mapper,input);String id=journal.prepare(plan);var saved=journal.find(id);
        var tampered=new InferenceJournal.Snapshot(saved.id(),saved.createdAt(),saved.version(),saved.kind(),saved.input(),saved.flow(),saved.body()+" ",saved.bodyHash(),saved.state(),null,null);
        assertThat(InferenceJournal.replay(f.mapper,tampered,List.of()).requestMatches()).isFalse();
        var old=new InferenceJournal.Snapshot(saved.id(),saved.createdAt(),"UNKNOWN_VERSION",saved.kind(),saved.input(),saved.flow(),saved.body(),saved.bodyHash(),saved.state(),null,null);
        assertThat(InferenceJournal.replay(f.mapper,old,List.of()).supported()).isFalse();
    }
}
