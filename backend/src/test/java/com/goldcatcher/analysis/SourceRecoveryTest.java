package com.goldcatcher.analysis;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.goldcatcher.*;
import com.goldcatcher.analysis.AnalysisTypes.SourceCheck;
import com.goldcatcher.market.GateCfdFeed;
import org.junit.jupiter.api.Test;

class SourceRecoveryTest {
    @Test void restartKeepsServerRetryDeadlineAndDisabledSourcesStayDisabled(){
        var f=new TestSupport();f.environment.setProperty("GOLD_NEWS_ENABLED","true");var until=f.clock.instant().plusSeconds(600);
        f.store.save("public-source-check","GLD_HOLDINGS",new SourceCheck("GLD_HOLDINGS","GLD",SupplementalSources.URLS.get("GLD_HOLDINGS"),"FETCH_FAILED",3600,f.clock.instant().minusSeconds(100),until,"PUBLIC_HTTP_429",0));
        var extra=new SupplementalSources(f.settings,new RemoteHttp(),f.mapper,f.store,null,f.clock,new EventHub());extra.recover();
        assertThat(extra.view(f.clock.instant()).sources()).filteredOn(s->s.id().equals("GLD_HOLDINGS")).allSatisfy(s->assertThat(s.nextPoll()).isEqualTo(until));
        f.environment.setProperty("GOLD_SUPPLEMENTAL_ENABLED","false");
        assertThat(extra.view(f.clock.instant()).sources()).allSatisfy(s->assertThat(s.state()).isEqualTo("DISABLED"));
        f.environment.setProperty("GOLD_SUPPLEMENTAL_ENABLED","true");
        f.store.save("driver-source","FF_EXPECTATIONS",new SourceCheck("FF_EXPECTATIONS","Forecast","url","FETCH_FAILED",1800,f.clock.instant(),until,"DRIVER_HTTP_429",0));
        var drivers=new DriverSources(f.settings,f.store,new RemoteHttp(),f.mapper,mock(GateCfdFeed.class),new EventHub(),f.clock);drivers.recover();
        assertThat(drivers.sources()).filteredOn(s->s.id().equals("FF_EXPECTATIONS")).allSatisfy(s->assertThat(s.nextPoll()).isEqualTo(until));
    }
}
