package com.goldcatcher.information;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.goldcatcher.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class SourceConfigurationTest {
    @Test void existingSelectionsMigrateOnceWithoutResettingUserChoices(){
        var f=new TestSupport();var legacy=f.mapper.createObjectNode().put("revision",7).put("updatedAt",f.clock.instant().toString());
        legacy.putArray("disabledSourceIds").add("EIA_NEW");f.store.save("source-configuration","active",legacy);
        var migrated=new SourceConfiguration(f.settings,f.store,f.clock,new EventHub());
        var configuration=(SourceConfiguration.Configuration)migrated.view().get("configuration");
        assertThat(configuration.revision()).isEqualTo(8);assertThat(configuration.schemaVersion()).isEqualTo(2);
        assertThat(configuration.disabledSourceIds()).containsExactly("EIA_NEW","TE_EXPECTATIONS");
        assertThat(f.settings.sourceEnabled("TE_EXPECTATIONS")).isFalse();assertThat(f.settings.sourceEnabled("BOJ_NEWS")).isTrue();
        var restored=new SourceConfiguration(f.settings,f.store,f.clock,new EventHub());
        assertThat(restored.view().get("configuration")).isEqualTo(configuration);
        migrated.save(new SourceConfiguration.Draft(8,List.of("EIA_NEW")));
        var enabledAgain=new SourceConfiguration(f.settings,f.store,f.clock,new EventHub());
        assertThat(f.settings.sourceEnabled("TE_EXPECTATIONS")).isTrue();
        assertThat(((SourceConfiguration.Configuration)enabledAgain.view().get("configuration")).revision()).isEqualTo(9);
    }
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
