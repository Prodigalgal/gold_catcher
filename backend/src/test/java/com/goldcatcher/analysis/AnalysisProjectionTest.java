package com.goldcatcher.analysis;

import static org.assertj.core.api.Assertions.*;
import static com.goldcatcher.analysis.AnalysisTypes.*;
import com.goldcatcher.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class AnalysisProjectionTest {
    @Test void frozenViewAndCutoffReplayExactlyAndFutureDriversAreNotAdmitted(){
        var f=new TestSupport();Instant cutoff=f.clock.instant();
        var current=new Driver("US_REAL_10Y","real","RATES","US_TREASURY","","https://example.invalid","DAILY","PERCENT","2.123456789012345678","2.1","2.3456789012345678","BASIS_POINTS","2026-10-07",cutoff,cutoff,1,"closed",Map.of(),null,"DAILY","background");
        var future=new Driver("US_INTRADAY_REAL_10Y","real","RATES","CNBC_PUBLIC_WEB","","https://example.invalid","INTRADAY_PUBLIC_WEB","PERCENT","9",null,null,"BASIS_POINTS","2026-10-08",cutoff.plusSeconds(1),cutoff.plusSeconds(1),1,"open",Map.of(),null,"PROVIDER_QUOTE_TIME","future");
        var view=new View("XAU_USDT",cutoff,1,"frozen",List.of(),List.of(future,current),List.of(),List.of(),List.of(),List.of(),List.of(),Map.of(),"version",null);
        String before=CanonicalJson.text(f.mapper,view);var result=AnalysisProjection.project(f.mapper,view,cutoff);String output=CanonicalJson.text(f.mapper,result);
        assertThat(CanonicalJson.text(f.mapper,view)).isEqualTo(before);assertThat(CanonicalJson.text(f.mapper,result.packet())).contains("2.123456789012345678").doesNotContain("US_INTRADAY_REAL_10Y");
        f.clock.advance(86400000);var copy=f.mapper.readValue(f.mapper.writeValueAsString(view),View.class);
        assertThat(CanonicalJson.text(f.mapper,AnalysisProjection.project(f.mapper,copy,cutoff))).isEqualTo(output);
        assertThat(f.store.ids("research-analysis",10)).isEmpty();
    }
}
