package com.goldcatcher.performance;

import static org.assertj.core.api.Assertions.*;
import com.goldcatcher.TestSupport;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.web.servlet.HandlerMapping;

class ApiPerformanceTest {
    @Test void boundsSamplesMeasuresApplicationBytesAndNeverRecordsCredentialsOrDynamicIds()throws Exception{
        var metrics=new ApiPerformance();var f=new TestSupport();
        for(int i=0;i<130;i++){
            var request=new MockHttpServletRequest("GET","/api/research/private-id");request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,"/api/research/{id}");
            request.setQueryString("apiKey=private-credential");request.addHeader("Authorization","Bearer private-credential");var response=new MockHttpServletResponse();
            metrics.doFilter(request,response,(req,res)->res.getOutputStream().write("黄金".getBytes(StandardCharsets.UTF_8)));
            assertThat(response.getHeader("Server-Timing")).startsWith("app;dur=");
        }
        var profile=f.mapper.valueToTree(metrics.view());var route=profile.path("routes").get(0);
        assertThat(route.path("calls").asInt()).isEqualTo(130);assertThat(route.path("samples").asInt()).isEqualTo(128);
        assertThat(route.path("lastBytes").asInt()).isEqualTo(6);assertThat(profile.toString()).doesNotContain("private-id","private-credential");
    }
    @Test void groupsAllowlistedDashboardViewsAndExcludesEventStreams()throws Exception{
        var metrics=new ApiPerformance();var request=new MockHttpServletRequest("GET","/api/dashboard");request.addParameter("view","research");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,"/api/dashboard");
        metrics.doFilter(request,new MockHttpServletResponse(),(req,res)->res.getOutputStream().write(1));
        metrics.doFilter(new MockHttpServletRequest("GET","/api/events"),new MockHttpServletResponse(),(req,res)->res.getOutputStream().write(1));
        var routes=new TestSupport().mapper.valueToTree(metrics.view()).path("routes");assertThat(routes.size()).isEqualTo(1);
        assertThat(routes.get(0).path("route").asText()).isEqualTo("GET /api/dashboard [research]");
    }
}
