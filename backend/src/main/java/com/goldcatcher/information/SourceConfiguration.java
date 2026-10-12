package com.goldcatcher.information;

import com.goldcatcher.*;
import com.goldcatcher.persistence.DocumentStore;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** Mutable source selection; original articles and frozen research evidence are retained. */
@Service
public class SourceConfiguration {
    public record Configuration(long revision,Instant updatedAt,List<String> disabledSourceIds,Integer schemaVersion) {
        public Configuration{if(schemaVersion==null)schemaVersion=0;disabledSourceIds=List.copyOf(disabledSourceIds);}
        public Configuration(long revision,Instant updatedAt,List<String> disabledSourceIds){this(revision,updatedAt,disabledSourceIds,2);}
    }
    public record Draft(long revision,List<String> disabledSourceIds) {}
    static final Map<String,String> RETIRED=Map.of(
        "EIA_NEW","产品目录，已有记录无核验发布时间或正文，不能作为及时快讯。",
        "EIA_PRESS","既有记录发布时间均未核验；停用该适配通道，能源统计覆盖仍为缺口。",
        "EIA_ENERGY","以能源背景文章为主，既有记录发布时间未核验，对黄金短线增量有限。",
        "BOJ_NEWS","既有记录缺少核验发布时间，全部被研究时间检查排除；待可靠事件通道替代。",
        "TE_EXPECTATIONS","公开网页在主程序直连及代理均返回202拦截页，默认停用；调查预期与自有预测适配保留，授权API未配置。");
    private static Set<String> sourceIds(){var ids=new LinkedHashSet<>(NewsService.sourceIds());ids.add("TE_EXPECTATIONS");return Set.copyOf(ids);}
    private final RuntimeSettings settings;private final DocumentStore store;private final Clock clock;private final EventHub events;
    private volatile Configuration current;
    public SourceConfiguration(RuntimeSettings settings,DocumentStore store,Clock clock,EventHub events){
        this.settings=settings;this.store=store;this.clock=clock;this.events=events;
        current=store.find("source-configuration","active",Configuration.class).orElseGet(()->{
            var initial=new Configuration(1,clock.instant(),RETIRED.keySet().stream().sorted().toList());store.save("source-configuration","active",initial);return initial;
        });
        if(current.schemaVersion()<2){var disabled=new LinkedHashSet<>(current.disabledSourceIds());disabled.add("TE_EXPECTATIONS");
            current=new Configuration(current.revision()+1,clock.instant(),disabled.stream().sorted().toList());store.save("source-configuration","active",current);store.event("source-selection","SOURCE_SELECTION_MIGRATED",current);
        }settings.applySourceSelection(current.disabledSourceIds());
    }
    public Map<String,Object> view(){return Map.of("configuration",current,"channels",sourceIds().stream().sorted().map(id->Map.of(
        "id",id,"family",id.equals("TE_EXPECTATIONS")?"EXPECTATIONS":"NEWS","enabled",settings.sourceEnabled(id),"reason",RETIRED.getOrDefault(id,"保留官方发布、事件日历或及时财经摘要；同出版方重复信息合并。"))).toList(),
        "scope","资讯、官方日历与可选经济预期；产品报价、利率及结构化背景数据分别保留。停用后停止采集、压缩与新研究选入，历史研究证据保留。");}
    public synchronized Map<String,Object> save(Draft draft){
        if(draft.revision()!=current.revision())throw new IllegalArgumentException("SOURCE_CONFIGURATION_CHANGED");
        if(draft.disabledSourceIds()==null||draft.disabledSourceIds().size()>sourceIds().size()||draft.disabledSourceIds().stream().anyMatch(id->id==null||!sourceIds().contains(id)))throw new IllegalArgumentException("INVALID_SOURCE_SELECTION");
        var next=new Configuration(current.revision()+1,clock.instant(),draft.disabledSourceIds().stream().distinct().sorted().toList());
        store.transaction(()->{store.save("source-configuration","active",next);store.event("source-selection","SOURCE_SELECTION_CHANGED",next);return next;});
        current=next;settings.applySourceSelection(next.disabledSourceIds());events.changed();return view();
    }
}
