package com.goldcatcher;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SystemController {
    private final String sourceCommit;

    public SystemController(@Value("${gold.source-commit}") String sourceCommit) {
        this.sourceCommit = sourceCommit;
    }

    @GetMapping("/api/system/status")
    public Map<String, Object> status() {
        return Map.of(
            "application", "gold-catcher",
            "version", "0.1.0",
            "sourceCommit", sourceCommit,
            "mode", "DESIGN_PROTOTYPE",
            "marketData", "NOT_IMPLEMENTED",
            "indicators", "NOT_IMPLEMENTED",
            "research", "NOT_IMPLEMENTED",
            "simulation", "NOT_IMPLEMENTED"
        );
    }
}
