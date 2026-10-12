package com.goldcatcher.research;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.goldcatcher.*;
import com.goldcatcher.RuntimeSettings.AiRoute;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class FallbackRoutingTest {
    final TestSupport f=new TestSupport();final RemoteHttp remote=mock(RemoteHttp.class);
    final Any2ApiClient client=new Any2ApiClient(f.settings,remote,f.mapper,f.clock);
    AiRoute route(String provider,boolean judge){
        var backup=new AiRoute(provider+"-fallback","arena","arena/Max","https://localhost/arena/v1","TEST",10000,20000,30000,2,2200,300,judge);
        var primary=new AiRoute(provider,provider,provider+"/"+provider+"-main","https://localhost/"+provider+"/v1","TEST",10000,20000,30000,2,2200,300,judge,backup);
        f.environment.setProperty("TEST","test-secret");return primary;
    }
    @SuppressWarnings("unchecked") HttpResponse<byte[]> response(int status,String body){var result=(HttpResponse<byte[]>)mock(HttpResponse.class);when(result.statusCode()).thenReturn(status);when(result.body()).thenReturn(body.getBytes(StandardCharsets.UTF_8));when(result.headers()).thenReturn(HttpHeaders.of(Map.of(),(a,b)->true));return result;}
    HttpResponse<byte[]> valid(){return response(200,f.mapper.writeValueAsString(Map.of("model","arena/Max","choices",List.of(Map.of("finish_reason","stop","message",Map.of("content","protocol=GOLD_RESEARCH_V1\ntitle=等待\noutput=等待新证据\nshortDirection=UNKNOWN\nlongDirection=UNKNOWN\nplan.action=WAIT\n"))))));}
    Map<String,Object> packet(){return Map.of("protocol",ResearchProtocol.VERSION,"phase",0,"evidence",Map.of());}
    void catalogs()throws Exception{when(remote.send(any(),eq(500_000))).thenAnswer(call->{var request=(HttpRequest)call.getArgument(0);return response(200,"{\"available\":"+request.uri().getPath().contains("/arena/")+"}");});}

    @Test void unavailablePrimaryUsesItsOwnArenaBackupAndRecordsReasonWithoutPrimaryPost()throws Exception{
        var route=route("deepseek",false);catalogs();var valid=valid();when(remote.send(any(),eq(8_000_000))).thenReturn(valid);
        var answer=client.complete(route,"冻结证据",packet(),f.clock.instant().plusSeconds(600));
        assertThat(answer.output().path("plan").path("action").asText()).isEqualTo("WAIT");
        assertThat(answer.routing().requestedModel()).isEqualTo("deepseek/deepseek-main");assertThat(answer.routing().usedModel()).isEqualTo("arena/Max");
        assertThat(answer.routing().fallbackUsed()).isTrue();assertThat(answer.routing().reason()).isEqualTo("MODEL_NOT_AVAILABLE");
        verify(remote).send(argThat(r->r.uri().getPath().equals("/arena/v1/chat/completions")),eq(8_000_000));
        verify(remote,never()).send(argThat(r->r.uri().getPath().equals("/deepseek/v1/chat/completions")),eq(8_000_000));
    }
    @Test void invalidCompletePrimaryOutputIsArchivedAndThenFallbackIsValidated()throws Exception{
        var route=route("mimo",false);f.settings.registerConfiguredRoute(route,"primary-secret","OPENAI_COMPATIBLE","MANUAL");f.settings.registerConfiguredRoute(route.fallback(),"backup-secret","OPENAI_COMPATIBLE","MANUAL");
        var invalid=response(200,"{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"bad output\"}}]}");var valid=valid();when(remote.send(any(),eq(8_000_000))).thenReturn(invalid,valid);
        var answer=client.complete(route,"观察",packet(),f.clock.instant().plusSeconds(600));
        assertThat(answer.routing().fallbackUsed()).isTrue();assertThat(answer.routing().failures()).hasSize(1);
        assertThat(answer.routing().failures().getFirst().partial().raw()).isEqualTo("bad output");assertThat(answer.routing().failures().getFirst().partial().output()).isNull();
        assertThat(answer.budget().characters()).isLessThanOrEqualTo(route.fallback().characterLimit());
        assertThat(f.mapper.writeValueAsString(answer)).doesNotContain("primary-secret","backup-secret");
    }
    @Test void cancellationDoesNotStartFallback()throws Exception{
        var route=route("grok",false);f.settings.registerConfiguredRoute(route,"test-secret","OPENAI_COMPATIBLE","MANUAL");f.settings.registerConfiguredRoute(route.fallback(),"test-secret","OPENAI_COMPATIBLE","MANUAL");
        when(remote.send(any(),eq(8_000_000))).thenThrow(new InterruptedException());
        assertThatThrownBy(()->client.complete(route,"观察",packet(),f.clock.instant().plusSeconds(600))).hasMessage("REQUEST_CANCELED");
        verify(remote,times(1)).send(any(),eq(8_000_000));
    }
    @Test void oversizedPrimaryResponseUsesArenaOnceWithoutRemovingOutputBounds()throws Exception{
        var route=route("minmax",false);f.settings.registerConfiguredRoute(route,"test-secret","OPENAI_COMPATIBLE","MANUAL");f.settings.registerConfiguredRoute(route.fallback(),"test-secret","OPENAI_COMPATIBLE","MANUAL");
        when(remote.send(any(),eq(8_000_000))).thenThrow(new IllegalStateException("RESPONSE_TOO_LARGE")).thenReturn(valid());
        var answer=client.complete(route,"观察",packet(),f.clock.instant().plusSeconds(600));
        assertThat(answer.routing().fallbackUsed()).isTrue();assertThat(answer.routing().reason()).isEqualTo("RESPONSE_TOO_LARGE");
        verify(remote,times(2)).send(any(),eq(8_000_000));
    }
    @Test void compressionUsesTheSameFallbackAndRequiresAValidSummary()throws Exception{
        var base=route("mimo",false);var primary=new AiRoute("compressor-mimo",base.provider(),base.model(),base.baseUrl(),"TEST",10000,20000,30000,2,1000,300,false,base.fallback());
        catalogs();var complete=response(200,f.mapper.writeValueAsString(Map.of("model","arena/Max","choices",List.of(Map.of("finish_reason","stop","message",Map.of("content","{\"summary\":\"官方发布值低于此前读数，发布时间按原文，具体影响尚需后续数据确认。\"} "))))));
        when(remote.send(any(),eq(8_000_000))).thenReturn(complete);
        var answer=client.compress(primary,"仅摘要",Map.of("protocol",ResearchProtocol.COMPRESSION_VERSION,"task","SUMMARIZE","text","官方材料正文"));
        assertThat(answer.routing().fallbackUsed()).isTrue();assertThat(answer.output().properties()).hasSize(1);assertThat(answer.output().path("summary").asText()).contains("官方发布值");
    }
    @Test void stageDeadlineLeavesNoTimeForFallbackAfterPrimaryTimeout()throws Exception{
        var route=route("grok",false);f.settings.registerConfiguredRoute(route,"test-secret","OPENAI_COMPATIBLE","MANUAL");
        when(remote.send(any(),eq(8_000_000))).thenAnswer(call->{f.clock.advance(300_001);throw new HttpTimeoutException("test");});
        assertThatThrownBy(()->client.complete(route,"观察",packet(),f.clock.instant().plusSeconds(300))).hasMessage("REQUEST_TIMEOUT");
        verify(remote,times(1)).send(any(),eq(8_000_000));
    }
    @SuppressWarnings("unchecked") @Test void allSixLogicalSeatsCanRemainConfiguredWhileSharingTheArenaFallback()throws Exception{
        var routes=new ArrayList<AiRoute>();for(boolean judge:List.of(false,true))for(String name:List.of("mimo","deepseek","grok")){var route=route(name,judge);routes.add(new AiRoute(name+(judge?"-judge":"-research"),route.provider(),route.model(),route.baseUrl(),route.apiKeyEnv(),route.inputEstimateLimit(),route.characterLimit(),route.byteLimit(),2,2200,300,judge,new AiRoute(name+(judge?"-jf":"-rf"),"arena","arena/Max",route.fallback().baseUrl(),"TEST",10000,20000,30000,2,2200,300,judge)));}
        catalogs();assertThat(client.selectRoutes(routes,List.of())).containsExactlyElementsOf(routes);
        f.settings.applyResearchRoutes(1,Map.of("SHORT",routes),f.settings.compressionRoute(),true);
        var checks=(List<Any2ApiClient.RouteCheck>)client.routeReadiness("SHORT").get("checks");
        assertThat(checks).filteredOn(check->check.fallbackFor()==null).hasSize(6);
        assertThat(checks).filteredOn(check->check.fallbackFor()!=null).hasSize(6)
            .allSatisfy(check->assertThat(routes).anyMatch(primary->primary.id().equals(check.fallbackFor())&&primary.fallback().id().equals(check.routeId())));
        verify(remote,never()).send(any(),eq(8_000_000));
    }
    @Test void threeArenaFallbackCallsCanAdvanceConcurrently()throws Exception{
        catalogs();var entered=new CountDownLatch(3);var release=new CountDownLatch(1);
        when(remote.send(any(),eq(8_000_000))).thenAnswer(call->{entered.countDown();assertThat(release.await(3,TimeUnit.SECONDS)).isTrue();return valid();});
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
            var futures=List.of("mimo","deepseek","grok").stream().map(name->executor.submit(()->client.complete(route(name,false),"观察",packet(),f.clock.instant().plusSeconds(600)))).toList();
            try{assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();}finally{release.countDown();}
            for(var future:futures)assertThat(future.get(3,TimeUnit.SECONDS).routing().fallbackUsed()).isTrue();
        }
    }
}
