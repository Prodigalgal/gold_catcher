package com.goldcatcher;

import com.goldcatcher.market.MarketFeed;
import java.time.Clock;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SystemController {
    private final String sourceCommit;private final RuntimeSettings settings;private final MarketFeed market;private final Clock clock;private final BuildProperties build;

    public SystemController(@Value("${gold.source-commit}") String sourceCommit,RuntimeSettings settings,MarketFeed market,Clock clock,BuildProperties build) {
        this.sourceCommit=sourceCommit;this.settings=settings;this.market=market;this.clock=clock;this.build=build;
    }

    @GetMapping("/api/system/status")
    public Map<String, Object> status() {
        Map<String,Object> status=new java.util.LinkedHashMap<>();
        status.put("application","gold-catcher");status.put("version",build.getVersion());status.put("sourceCommit",sourceCommit);status.put("mode","INTERNAL_SIMULATION_ALPHA");
        status.put("venue","GATE");status.put("products",java.util.List.of("XAU_USDT","GATE:XAUUSD"));
        status.put("marketData",market.snapshot("XAU_USDT").state());status.put("indicators","JAVA_CLOSED_BAR");
        status.put("research",settings.routes("SHORT").isEmpty()?"ROUTES_NOT_CONFIGURED":"MULTI_MODEL_STAGED");status.put("simulation","INTERNAL_L1_ASSUMPTION");
        status.put("cfd",market.snapshot("GATE:XAUUSD").state());status.put("cfdSimulation",Boolean.TRUE.equals(market.provenance("GATE:XAUUSD").get("simulationReady"))?"INTERNAL_CFD_QUOTE_SCENARIO":"WAITING_CURRENT_CFD_RULES");
        status.put("features",java.util.List.of("OFFICIAL_HTML_PDF_XLSX","PUBLIC_INTRADAY_TREASURY_TIPS","PUBLIC_FINANCIAL_MEDIA_RSS","MIMO_CACHED_SOURCE_COMPRESSION","DIVERSE_NEWS_CANDIDATE_POOL","INDEPENDENT_COMPRESSION_LANE","ANY2API_CIRCUIT_RECOVERY","IMMEDIATE_SOURCE_PREPROCESSING","SUMMARY_ONLY_NEWS_ADMISSION","CONFIGURABLE_COMPRESSION_REPROCESSING","NONBLOCKING_RESEARCH_ADMISSION","FOUR_STAGE_DIVERSE_RESEARCH","THREE_MODEL_RESEARCH_AND_JUDGING","BLIND_THREE_JUDGE_VOTING","PERSISTED_AI_PROVIDER_CONFIGURATION","PER_MODEL_ARENA_MAX_FALLBACK","RESEARCH_SEAT_RETRIES","STALE_EVIDENCE_ANALYSIS_CONTINUES","RESEARCH_CHECKPOINT_RECOVERY","UNIFIED_RESEARCH_TEXT_PROTOCOL","PERSISTED_SHORT_LONG_SCHEDULER","INTERNAL_ORDER_MANAGEMENT","CFD_SEPARATE_ACCOUNT","CURSOR_MARKET_ARCHIVE","MINUTE_EQUITY_REVIEW","CSV_JSON_EXPORT"));
        status.put("uiCache","REDIS_WITH_BOUNDED_LOCAL_FALLBACK");status.put("modelTransport","OPENAI_SSE_WITH_COMPLETION_VALIDATION");
        status.put("authentication","SESSION_CSRF_SHA256_POW");status.put("serverTime",clock.instant());return status;
    }
}
