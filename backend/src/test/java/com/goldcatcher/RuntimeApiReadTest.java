package com.goldcatcher;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.goldcatcher.analysis.*;
import com.goldcatcher.information.NewsService;
import com.goldcatcher.network.SourceReliability;
import com.goldcatcher.network.SourceProxyPool;
import com.goldcatcher.market.*;
import com.goldcatcher.performance.*;
import com.goldcatcher.research.*;
import com.goldcatcher.simulation.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

class RuntimeApiReadTest {
    @Test void researchPageUsesTheSmallIndexAndFreshQuotesAndAccountsWithoutLoadingOtherPagePanels(){
        var f=new TestSupport();var market=mock(MarketFeed.class);var news=mock(NewsService.class);var ledger=mock(SimulationLedger.class);
        var research=mock(ResearchService.class);var ai=mock(Any2ApiClient.class);var trend=mock(TrendAnalysis.class);var review=mock(ReviewService.class);
        var compression=mock(EvidenceCompression.class);var archive=mock(MarketArchive.class);
        ObjectProvider<StringRedisTemplate> redis=mock(ObjectProvider.class);var cache=new UiReadCache(f.settings,f.mapper,f.clock,redis);
        when(market.provenance("XAU_USDT")).thenReturn(Map.of());when(market.costConfiguration("XAU_USDT")).thenReturn(Map.of());
        when(market.snapshot("XAU_USDT")).thenReturn(mock(MarketTypes.Snapshot.class));when(ledger.view("XAU_USDT")).thenReturn(mock(SimulationLedger.View.class));
        when(research.scheduling()).thenReturn(Map.of());when(research.summaries()).thenReturn(List.of());when(news.sources()).thenReturn(List.of());
        var api=new RuntimeApi(market,news,ledger,research,ai,f.settings,new EventHub(),f.store,f.clock,mock(FundingService.class),
            mock(SourceProxyPool.class),trend,mock(SourceReliability.class),mock(DriverSources.class),mock(SupplementalSources.class),archive,review,compression,mock(AiConfiguration.class),cache,new ApiPerformance());
        for(int i=0;i<2;i++){var data=api.dashboard("XAU_USDT","research");assertThat(data).containsEntry("dashboardView","research");
            assertThat(data).doesNotContainKeys("analysis","review","compression","marketArchive");}
        verify(market,times(2)).snapshot("XAU_USDT");verify(ledger,times(2)).view("XAU_USDT");verify(research,times(2)).summaries();
        verify(research,times(1)).latest("XAU_USDT");verify(research,never()).history();verify(news,never()).news();verifyNoInteractions(trend,review,compression,archive,ai);
        assertThatThrownBy(()->api.dashboard("XAU_USDT","unknown")).hasMessage("UNKNOWN_DASHBOARD_VIEW");
    }
}
