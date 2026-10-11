package com.goldcatcher.research;

import com.goldcatcher.RuntimeSettings;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/** Execution budget is independent of the age at which an order may be proposed. */
public record ResearchPolicy(int maxAttempts,int retryBaseSeconds,int retryMaxSeconds,
                             int stageSeconds,int judgeSeconds,int workflowSeconds,int decisionSeconds) {
    public static ResearchPolicy defaults(String horizon){
        boolean longTerm=horizon.equals("LONG");
        return new ResearchPolicy(3,2,30,longTerm?900:600,longTerm?900:600,longTerm?3600:2400,longTerm?900:240);
    }
    public static ResearchPolicy from(RuntimeSettings settings,String horizon){
        var d=defaults(horizon);String prefix=horizon.equals("LONG")?"GOLD_LONG_":"GOLD_SHORT_";
        return new ResearchPolicy(settings.integer("GOLD_RESEARCH_MAX_ATTEMPTS",d.maxAttempts()),
            settings.integer("GOLD_RESEARCH_RETRY_BASE_SECONDS",d.retryBaseSeconds()),settings.integer("GOLD_RESEARCH_RETRY_MAX_SECONDS",d.retryMaxSeconds()),
            settings.integer(prefix+"STAGE_SECONDS",d.stageSeconds()),settings.integer("GOLD_JUDGE_STAGE_SECONDS",d.judgeSeconds()),
            settings.integer(prefix+"WORKFLOW_SECONDS",d.workflowSeconds()),settings.integer(prefix+"DECISION_SECONDS",d.decisionSeconds()));
    }
    public ResearchPolicy validated(){
        if(maxAttempts<1||maxAttempts>6||retryBaseSeconds<1||retryBaseSeconds>60||retryMaxSeconds<retryBaseSeconds||retryMaxSeconds>120
            ||stageSeconds<30||stageSeconds>1800||judgeSeconds<30||judgeSeconds>1800||workflowSeconds<120||workflowSeconds>7200
            ||workflowSeconds<Math.max(stageSeconds,judgeSeconds)||decisionSeconds<30||decisionSeconds>172800)
            throw new IllegalArgumentException("INVALID_RESEARCH_RETRY_POLICY");
        return this;
    }
    public long delayMillis(int failedAttempt,long retryAfterMillis){
        long base=Math.min(retryMaxSeconds*1000L,retryBaseSeconds*1000L*(1L<<Math.min(10,Math.max(0,failedAttempt-1))));
        long jitter=ThreadLocalRandom.current().nextLong(Math.max(1,base/5+1));
        // Retry-After is a lower bound; a stage with less time left must stop, never retry early.
        return Math.max(retryAfterMillis,Math.min(retryMaxSeconds*1000L,base+jitter));
    }
    public long attemptMillis(long remaining,int requestSeconds){
        // Give a slow but healthy first attempt its configured duration; retries consume the remaining stage budget.
        return Math.max(1,Math.min(remaining,requestSeconds*1000L));
    }
    private static final Set<String> REPAIRABLE=Set.of("INVALID_STRUCTURED_OUTPUT","MISSING_REQUIRED_OUTPUT","OUTPUT_FIELDS_TOO_LONG",
        "INVALID_DIRECTION","INVALID_EVIDENCE_ARRAY","PLAN_NARRATIVE_TOO_LONG","INVALID_IMPACT","MISSING_JUDGE_VOTE",
        "INVALID_JUDGE_CANDIDATE","INVALID_PREVIOUS_STAGE_REFERENCE","INVALID_NEWS_REFERENCE","INVALID_EVIDENCE_REFERENCE",
        "REQUIRED_SUBMISSION_MISSING","INSUFFICIENT_CANDIDATE_QUORUM");
    public static boolean repairable(String code){return code!=null&&(REPAIRABLE.contains(code)||code.startsWith("OUTPUT_NOT_COMPLETE_")
        ||code.startsWith("INVALID_STRUCTURED_OUTPUT_")||code.startsWith("MISSING_REQUIRED_OUTPUT_")||code.startsWith("PLAN_NARRATIVE_TOO_LONG_"));}
    public static boolean retryable(String code){return code!=null&&(repairable(code)||Set.of("REQUEST_TIMEOUT","PROVIDER_REQUEST_STILL_RUNNING",
        "UPSTREAM_TRANSPORT_FAILED","UPSTREAM_OR_PARSE_FAILED","STREAM_FIRST_BYTE_TIMEOUT","STREAM_IDLE_TIMEOUT",
        "STREAM_TOTAL_TIMEOUT","STREAM_INCOMPLETE","INVALID_STREAM_UTF8","INVALID_STREAM_JSON").contains(code)
        ||code.startsWith("INFERENCE_STREAM_ERROR")
        ||code.matches("(?:INFERENCE|CATALOG)_HTTP_(?:408|425|429|500|502|503|504)(?:_[A-Z0-9_]+)?"));}
}
