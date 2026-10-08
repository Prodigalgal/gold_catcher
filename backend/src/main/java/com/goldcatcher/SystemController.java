package com.goldcatcher;

import com.goldcatcher.market.MarketFeed;
import java.time.Clock;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SystemController {
    private final String sourceCommit;private final RuntimeSettings settings;private final MarketFeed market;private final Clock clock;

    public SystemController(@Value("${gold.source-commit}") String sourceCommit,RuntimeSettings settings,MarketFeed market,Clock clock) {
        this.sourceCommit=sourceCommit;this.settings=settings;this.market=market;this.clock=clock;
    }

    @GetMapping("/api/system/status")
    public Map<String, Object> status() {
        return Map.of("application","gold-catcher","version","0.2.0","sourceCommit",sourceCommit,"mode","INTERNAL_SIMULATION_ALPHA",
            "marketData",market.snapshot("XAUUSDT").state(),"indicators","JAVA_CLOSED_BAR","research",settings.routes().isEmpty()?"ROUTES_NOT_CONFIGURED":"MULTI_MODEL_STAGED",
            "simulation","INTERNAL_L1_ASSUMPTION","cfd","WAITING_MT5","serverTime",clock.instant());
    }
}
