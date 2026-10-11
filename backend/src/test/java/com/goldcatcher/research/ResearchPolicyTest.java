package com.goldcatcher.research;

import static org.assertj.core.api.Assertions.*;
import java.time.*;
import org.junit.jupiter.api.Test;

class ResearchPolicyTest {
    @Test void aSlowFirstResponseGetsTheFullRequestBudgetRatherThanOneThirdOfTheStage(){
        var policy=ResearchPolicy.defaults("SHORT");
        assertThat(policy.attemptMillis(600000,300)).isEqualTo(300000);
        assertThat(policy.attemptMillis(45000,300)).isEqualTo(45000);
        for(String code:new String[]{"STREAM_FIRST_BYTE_TIMEOUT","STREAM_IDLE_TIMEOUT","STREAM_TOTAL_TIMEOUT","STREAM_INCOMPLETE","INFERENCE_STREAM_ERROR_UPSTREAM_FAILED"})
            assertThat(ResearchPolicy.retryable(code)).isTrue();
    }
    @Test void transportAndFormatErrorsAreRetryableButCredentialsBudgetsAndCancellationAreNot(){
        for(String code:new String[]{"REQUEST_TIMEOUT","UPSTREAM_TRANSPORT_FAILED","INFERENCE_HTTP_429_RATE_LIMITED","INFERENCE_HTTP_503","INVALID_DIRECTION","INVALID_JUDGE_CANDIDATE","PLAN_NARRATIVE_TOO_LONG",
            "PLAN_NARRATIVE_TOO_LONG_INVALIDATE","INVALID_STRUCTURED_OUTPUT_CONFLICT_PLAN_ACTION","MISSING_REQUIRED_OUTPUT_PLAN_ACTION"})assertThat(ResearchPolicy.retryable(code)).isTrue();
        for(String code:new String[]{"INFERENCE_HTTP_401","INFERENCE_HTTP_403","CONTEXT_INSUFFICIENT","MODEL_NOT_AVAILABLE","REQUEST_CANCELED","WORKFLOW_DEADLINE"})assertThat(ResearchPolicy.retryable(code)).isFalse();
        assertThat(ResearchPolicy.retryable(null)).isFalse();
    }
    @Test void retryAfterIsNeverShortenedByTheConfiguredBackoffCap(){
        var policy=ResearchPolicy.defaults("SHORT");assertThat(policy.delayMillis(1,45000)).isEqualTo(45000);
        assertThat(policy.delayMillis(1,0)).isBetween(2000L,2400L);assertThat(policy.delayMillis(6,0)).isEqualTo(30000);
        var now=Instant.parse("2026-10-11T00:00:00Z");assertThat(Any2ApiClient.retryAfterMillis("15",now)).isEqualTo(15000);
        assertThat(Any2ApiClient.retryAfterMillis("Sun, 11 Oct 2026 00:01:00 GMT",now)).isEqualTo(60000);
        assertThat(Any2ApiClient.retryAfterMillis("invalid",now)).isZero();
    }
    @Test void validationKeepsExecutionAndTradingWindowsSeparateAndRejectsUnboundedRetries(){
        assertThat(ResearchPolicy.defaults("SHORT").validated().workflowSeconds()).isGreaterThan(ResearchPolicy.defaults("SHORT").decisionSeconds());
        assertThatThrownBy(()->new ResearchPolicy(100,2,30,360,360,1440,240).validated()).hasMessage("INVALID_RESEARCH_RETRY_POLICY");
    }
}
