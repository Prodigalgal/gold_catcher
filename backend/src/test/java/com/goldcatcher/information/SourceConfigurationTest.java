package com.goldcatcher.information;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.goldcatcher.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class SourceConfigurationTest {
    @Test void retiredSourcesStopNewEvidenceButRetainArchivedArticlesAndCanBeRestored(){
        var f=new TestSupport();var policy=new SourceConfiguration(f.settings,f.store,f.clock,new EventHub());var news=new NewsService(f.settings,mock(RemoteHttp.class),f.store,new EventHub(),f.clock);
        var article=new NewsService.News("old","BOJ_NEWS","Official record","Fact","https://www.boj.or.jp/en/record",f.clock.instant().minusSeconds(10),f.clock.instant().minusSeconds(5),"OFFICIAL_SOURCE",1,"hash");
        f.store.save("news","old",article);assertThat(f.settings.sourceEnabled("BOJ_NEWS")).isFalse();assertThat(news.news()).isEmpty();assertThat(news.evidence(f.clock.instant())).isEmpty();assertThat(news.compressionCandidates(f.clock.instant())).isEmpty();
        assertThat(f.store.find("news","old",NewsService.News.class)).isPresent();policy.save(new SourceConfiguration.Draft(1,List.of()));assertThat(f.settings.sourceEnabled("BOJ_NEWS")).isTrue();assertThat(news.news()).hasSize(1);
        assertThatThrownBy(()->policy.save(new SourceConfiguration.Draft(1,List.of()))).hasMessage("SOURCE_CONFIGURATION_CHANGED");
        assertThatThrownBy(()->policy.save(new SourceConfiguration.Draft(2,List.of("UNKNOWN")))).hasMessage("INVALID_SOURCE_SELECTION");
        var restored=new SourceConfiguration(f.settings,f.store,f.clock,new EventHub());assertThat(f.settings.sourceEnabled("BOJ_NEWS")).isTrue();assertThat(restored.view().get("configuration")).isEqualTo(policy.view().get("configuration"));
    }
}
