package com.goldcatcher.simulation;
import static org.assertj.core.api.Assertions.*;
import com.goldcatcher.persistence.DocumentStore.AccountSample;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
class ReviewServiceTest {
    @Test void sampledDrawdownIncludesNegativeEquityAndDoesNotFillUnknownValues(){
        var at=Instant.parse("2026-10-10T00:00:00Z");
        var samples=List.of(new AccountSample("XAU_USDT",at,"1000","1000","0","VALUED"),new AccountSample("XAU_USDT",at.plusSeconds(60),"1000",null,null,"STALE"),
            new AccountSample("XAU_USDT",at.plusSeconds(120),"900","900","0","VALUED"),new AccountSample("XAU_USDT",at.plusSeconds(180),"1200","1200","0","VALUED"),new AccountSample("XAU_USDT",at.plusSeconds(240),"-100","-100","0","VALUED"));
        var result=ReviewService.curve(samples);assertThat(result.get("unvaluedSamples")).isEqualTo(1);assertThat(result.get("sampledMaximumDrawdown")).isEqualTo("1300");
        assertThat(result.get("sampledMaximumDrawdownPct").toString()).startsWith("108.333333333333");
        assertThat(ReviewService.curve(List.of(samples.get(1))).get("sampledMaximumDrawdown")).isNull();
    }
}
