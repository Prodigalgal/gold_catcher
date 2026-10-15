package com.goldcatcher.research;

import static org.assertj.core.api.Assertions.*;
import com.goldcatcher.TestSupport;
import com.goldcatcher.market.MarketTypes.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class IndicatorProjectionTest {
    @Test void rawCloseAndVolumeKeepOriginalPrecisionWhileDerivedValuesHaveExplicitRounding(){
        var f=new TestSupport();var values=Map.of("close","4096.123456789012345678","volume","0.123456789012345678","rsi14","51.123456789012345678","macdSignal","0.00000000123");
        var indicator=new Indicators("1",199,f.clock.instant(),"READY",values,f.clock.instant(),"CURRENT",Map.of());
        var input=Map.of("1",indicator);var projected=IndicatorProjection.project(f.mapper,input);var row=ModelInput.records(f.mapper,projected).path(0);
        assertThat(row.path("close").asText()).isEqualTo(values.get("close"));assertThat(row.path("volume").asText()).isEqualTo(values.get("volume"));
        assertThat(row.path("rsi14").asText()).isEqualTo("51.12345679");assertThat(row.path("macdSignal").asText()).isEqualTo("0");
        assertThat(input.get("1").values()).isEqualTo(values);assertThat(IndicatorProjection.project(f.mapper,input)).isEqualTo(projected);
    }
}
