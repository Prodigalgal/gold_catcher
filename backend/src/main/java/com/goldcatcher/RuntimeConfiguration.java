package com.goldcatcher;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class RuntimeConfiguration {
    @Bean ExchangeClock clock(RuntimeSettings settings,RemoteHttp http,tools.jackson.databind.ObjectMapper mapper) {
        return new ExchangeClock(settings,http,mapper);
    }
}
