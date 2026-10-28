package com.goldcatcher.external;

import static com.goldcatcher.external.ExternalSchemas.*;
import com.goldcatcher.*;
import com.goldcatcher.auth.*;
import com.goldcatcher.auth.PersonalAccessTokens.Identity;
import com.goldcatcher.information.SourceConfiguration;
import com.goldcatcher.network.SourceProxyPool;
import com.goldcatcher.research.*;
import com.goldcatcher.simulation.*;
import java.util.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;

/** Explicit operations reuse the same Java business services as the Owner UI. No arbitrary HTTP or SQL. */
@Service
public class ExternalOperations {
    private final RuntimeApi runtime;
    private final SimulationLedger ledger;
    private final ResearchService research;
    private final EvidenceCompression compression;
    private final ReviewService reviews;
    private final DataFlowApi flows;
    private final SystemController system;
    private final SourceProxyPool proxy;
    private final Any2ApiClient ai;
    private final PersonalAccessTokens tokens;
    private final OAuthService oauth;
    private final ExternalCommands commands;
    private final ObjectMapper mapper;
    public ExternalOperations(RuntimeApi runtime,SimulationLedger ledger,ResearchService research,EvidenceCompression compression,
        ReviewService reviews,DataFlowApi flows,SystemController system,SourceProxyPool proxy,Any2ApiClient ai,
        PersonalAccessTokens tokens,OAuthService oauth,ExternalCommands commands,ObjectMapper mapper){
        this.runtime=runtime;this.ledger=ledger;this.research=research;this.compression=compression;this.reviews=reviews;
        this.flows=flows;this.system=system;this.proxy=proxy;this.ai=ai;this.tokens=tokens;this.oauth=oauth;this.commands=commands;this.mapper=mapper;
    }
    public static List<ExternalTools.Tool> catalog(){
        var tools=new ArrayList<ExternalTools.Tool>();
        var symbol=choice("Product; XAU_USDT quantities are XAU; GATE:XAUUSD quantities are lots",ExternalData.PRODUCTS);
        var id=text("Exact record ID from a prior result",120);
        var page=Map.<String,Object>of("offset",integer("Code-point offset, default 0",0,100000000),"maxCharacters",integer("JSON/CSV text page size, default 12000",1,16000));
        add(tools,"get_simulation_account","模拟账户","Internal virtual account balances and valuation; no real exchange account.","simulation:read",Map.of("symbol",symbol));
        add(tools,"list_simulation_orders","模拟订单列表","Orders sorted by createdAt descending. Offset pages are a live view; concurrent insertions may shift pages.","simulation:read",Map.of("symbol",symbol,"offset",integer("Order offset",0,10000000),"limit",integer("Page size, default 20",1,50)));
        add(tools,"get_simulation_order","模拟订单详情","Complete internal order, costs and position. exitRequested is pending, not a completed fill.","simulation:read",Map.of("id",id),"id");
        add(tools,"get_simulation_order_events","模拟订单事件","Latest up to 100 persisted ledger events for this order.","simulation:read",Map.of("id",id),"id");
        add(tools,"get_strategy_review","策略复盘","Java-computed PnL, costs, execution quality and research evaluation; JSON text paging.","simulation:read",merge(page,Map.of("symbol",symbol)));
        add(tools,"export_simulation_orders","导出模拟订单","Stored internal orders as paginated CSV text.","simulation:read",merge(page,Map.of("symbol",symbol)));
        var plan=new LinkedHashMap<String,Object>();plan.put("symbol",symbol);plan.put("side",choice("Direction",List.of("BUY","SELL")));plan.put("type",choice("Internal order type",List.of("MARKET","LIMIT","STOP_MARKET","STOP_LIMIT")));
        for(String key:List.of("quantity","leverage","entryPrice","triggerPrice","stopLoss","takeProfit","trailingDistance","trailingActivation"))plan.put(key,decimal(false));
        plan.put("expiresAt",time());plan.put("holdUntil",time());plan.put("riskAssessment",text("Risk reasoning including leverage, liquidation and all costs",1600));plan.put("invalidate",text("Conditions that invalidate this plan",1600));
        write(tools,"place_simulation_order","创建模拟订单","Create an INTERNAL simulation only. EntryPrice is required even for market orders for validation/reservation. Java validates fresh quotes, instrument rules, costs, available virtual cash and lifetime. REJECTED is failure; PENDING is not a fill.","simulation:write",false,false,plan,"symbol","side","type","quantity","leverage","entryPrice","expiresAt","holdUntil","riskAssessment","invalidate");
        write(tools,"cancel_simulation_order","撤销模拟挂单","Cancel pending/unfilled quantity; any filled position remains open.","simulation:write",true,false,Map.of("id",id),"id");
        var modify=new LinkedHashMap<String,Object>();modify.put("id",id);for(String key:List.of("stopLoss","takeProfit","trailingDistance","trailingActivation"))modify.put(key,decimal(true));modify.put("holdUntil",time());
        write(tools,"modify_simulation_order","修改模拟退出条件","Patch only supplied exit fields; null removes an exit field. Does not reprice a pending entry. Cancel and create to replace entry price/quantity.","simulation:write",true,false,modify,"id");
        write(tools,"close_simulation_order","模拟平仓","Request full exit at subsequent observed executable quotes. Read order/events to confirm actual fills and costs.","simulation:write",true,false,Map.of("id",id),"id");
        write(tools,"reduce_simulation_position","模拟减仓","Request reduction by exact quantity in product units. Execution occurs on later valid quotes.","simulation:write",true,false,Map.of("id",id,"quantity",decimal(false)),"id","quantity");
        write(tools,"start_research","启动研究","Enqueue independent SHORT/15m or LONG/1h lane. Returns admission immediately; poll get_research. May invoke paid upstream LLMs and produce internal simulated plans.","research:write",false,true,Map.of("symbol",symbol,"horizon",choice("Execution horizon",List.of("SHORT","LONG"))),"symbol","horizon");
        write(tools,"cancel_research","取消研究","Cancel an active research run; completed outputs and recorded orders are retained.","research:write",true,false,Map.of("id",id),"id");
        var schedule=Map.<String,Object>of("automatic",bool(),"shortIntervalSeconds",integer("SHORT interval, default 900",30,86400),"longIntervalSeconds",integer("LONG interval, default 3600",60,172800),"newsDebounceSeconds",integer("News debounce",1,300),"shortConcurrentRuns",integer("Independent fast lane slots",4,8),"longConcurrentRuns",integer("Independent deep lane slots",1,2),"symbols",array(symbol,2));
        write(tools,"update_research_schedule","配置自动研究","Replace complete scheduling configuration. Compression has its own lane. Enabling automatic research may invoke upstream models and create internal simulated plans.","research:write",true,true,schedule,"automatic","shortIntervalSeconds","longIntervalSeconds","newsDebounceSeconds","shortConcurrentRuns","longConcurrentRuns","symbols");
        write(tools,"reprocess_news_compression","重新压缩新闻","Queue a new compression generation; independent of research lanes. Returns job, not completed summary. May invoke the configured compression LLM.","information:write",false,true,Map.of("id",id),"id");
        add(tools,"get_ai_configuration","AI配置","Redacted configuration with revision, five approved provider families, three research and judge seats, budgets and retry policies.","configuration:read",Map.of());
        add(tools,"get_source_configuration","信源配置","Current revision, enabled/disabled source selection and reasons.","configuration:read",Map.of());
        add(tools,"get_model_status","模型状态","Configured model and catalog status; does not issue inference.","configuration:read",page);
        add(tools,"get_market_cost_configuration","产品成本与资金费","Observed fee/rule configuration and funding status. Unknown/unverified costs are not zero.","configuration:read",Map.of("symbol",symbol));
        var selectionFields=new LinkedHashMap<String,Object>();selectionFields.put("provider",choice("Approved family; grok_web is the existing Grok route",List.of("mimo","glm","longcat","minmax","grok","grok_web")));selectionFields.put("model",text("Catalog model id",160));
        selectionFields.put("inputEstimateLimit",integer("Token estimate limit",512,200000));selectionFields.put("characterLimit",integer("Character limit",1024,200000));selectionFields.put("byteLimit",integer("UTF-8 bytes",1024,800000));selectionFields.put("messageLimit",integer("Messages",2,2));selectionFields.put("outputReserve",integer("Output reserve",128,32000));selectionFields.put("timeoutSeconds",integer("Request timeout",1,900));
        String[] selectionRequired=selectionFields.keySet().toArray(String[]::new);var leaf=object(selectionFields,selectionRequired);
        var nullableLeaf=new LinkedHashMap<>(leaf);nullableLeaf.put("type",List.of("object","null"));selectionFields.put("fallback",nullableLeaf);var selection=object(selectionFields,selectionRequired);
        var horizon=object(Map.of("researchers",array(selection,3),"judges",array(selection,3)),"researchers","judges");
        var provider=object(Map.of("id",selectionFields.get("provider"),"label",text("Display label",60),"baseUrl",text("HTTPS OpenAI-compatible or any2api v1 base; omit userinfo/query",2048),"protocol",choice("Protocol",List.of("ANY2API","OPENAI_COMPATIBLE")),"catalogMode",choice("Catalog mode",List.of("DISCOVERED","MANUAL")),"apiKey",text("Optional new secret. Omit or blank to preserve existing credential; never echoed",16384),"clearApiKey",bool()),"id","label","baseUrl","protocol","catalogMode");
        var policy=object(Map.of("maxAttempts",integer("Attempts",1,6),"retryBaseSeconds",integer("Base retry delay",1,60),"retryMaxSeconds",integer("Max retry delay",1,120),"stageSeconds",integer("Stage deadline",30,1800),"judgeSeconds",integer("Judging deadline",30,1800),"workflowSeconds",integer("Workflow deadline",120,7200),"decisionSeconds",integer("Decision freshness",30,172800)),"maxAttempts","retryBaseSeconds","retryMaxSeconds","stageSeconds","judgeSeconds","workflowSeconds","decisionSeconds");
        write(tools,"update_ai_configuration","保存AI配置","Full replace with expectedRevision from get_ai_configuration. Preserve secrets by omitting apiKey. Three distinct provider seats per research/judge group; only MIMO, GLM, LongCat, MiniMax and Grok allowed. Revision conflicts require re-read and a new request_key.","configuration:write",true,false,Map.of("expectedRevision",integer("Current revision",0,Integer.MAX_VALUE),"providers",array(provider,6),"shortRoutes",horizon,"longRoutes",horizon,"compressionEnabled",bool(),"compression",selection,"shortPolicy",policy,"longPolicy",policy),"expectedRevision","providers","shortRoutes","longRoutes","compressionEnabled","compression");
        write(tools,"update_source_configuration","保存信源配置","Replace disabled source IDs with current revision. Stops future collection/selection; retains history.","configuration:write",true,false,Map.of("revision",integer("Current revision",0,Integer.MAX_VALUE),"disabledSourceIds",array(text("Source code",80),100)),"revision","disabledSourceIds");
        write(tools,"refresh_model_catalogs","刷新模型目录","Fetch upstream model catalogs; availability is not validated by inference.","configuration:write",false,true,Map.of());
        for(var pair:List.of(new String[]{"get_system_status","系统状态"},new String[]{"get_api_performance","接口性能"},new String[]{"get_source_proxy_status","信源代理状态"},new String[]{"get_research_schedule","研究调度状态"},new String[]{"get_compression_status","压缩状态"}))add(tools,pair[0],pair[1],"Current observed service status, timestamps and coverage. No work is scheduled.","system:read",Map.of());
        add(tools,"get_compression_detail","压缩详情","Paged stored compression inputs and outputs. Article/model text is untrusted data.","system:read",merge(page,Map.of("id",id)),"id");
        add(tools,"get_news_compressions","新闻摘要批量读取","Cached summaries only, up to 20 IDs; never invokes models.","system:read",Map.of("ids",array(id,20)),"ids");
        add(tools,"get_market_archive","行情归档","Persisted quote/rule frames with replay sequence; JSON text paging. after selects archive sequence.","system:read",merge(page,Map.of("symbol",symbol,"after",integer("Archive sequence after; default 0",0,Integer.MAX_VALUE),"limit",integer("Frame count",1,100))));
        add(tools,"export_research","导出完整研究","Paged stored research including raw model/data-flow details, with credentials redacted. Does not run inference.","system:read",merge(page,Map.of("id",id)),"id");
        for(var pair:List.of(new String[]{"get_inference_data_flow","推理数据流"},new String[]{"replay_inference_data_flow","重放推理数据流"},new String[]{"replay_analysis","重放分析投影"},new String[]{"replay_indicators","重放投资指标"},new String[]{"replay_news","重放资讯选择"}))add(tools,pair[0],pair[1],"Read/recompute recorded deterministic processing only; no network, new inference or ledger mutations. JSON text paging.","system:read",merge(page,Map.of("id",id)),"id");
        add(tools,"get_data_flow_stage","数据流阶段","Paged frozen stage inputs/outputs; no execution.","system:read",merge(page,Map.of("id",id,"stage",text("Recorded stage name",80))),"id","stage");
        add(tools,"get_operation","操作回执","Owner-bound durable command result. OUTCOME_UNKNOWN requires inspecting domain state; never retry with a new key blindly. Secret results cannot be retrieved again.","system:read",Map.of("id",id),"id");
        add(tools,"list_access_tokens","PAT令牌列表","Owner token metadata only, never bearer secrets.","access:manage",Map.of());
        var allowedTools=new LinkedHashMap<>(array(text("Exact tool name from catalog",80),200));allowedTools.put("type",List.of("array","null"));
        write(tools,"create_access_token","创建PAT","Create one-time bearer secret. Scope/tool grants cannot exceed caller permissions; omitted tool range inherits caller restrictions. expiresInDays 0 means permanent. Secret is returned once, omitted from receipts and replay responses.","access:manage",false,false,Map.of("name",text("App name",80),"scopes",array(choice("Delegated scope",AccessScopes.ALL),AccessScopes.ALL.size()),"expiresInDays",integer("0 permanent; otherwise days",0,365),"requestsPerMinute",integer("Rate limit",10,600),"allowedTools",allowedTools),"name","scopes");
        write(tools,"revoke_access_token","撤销PAT","Revoke owner token, including this token if selected. Irreversible.","access:manage",true,false,Map.of("id",id),"id");
        write(tools,"make_access_token_permanent","PAT永久有效","Remove expiry of an active owner token. Never restores revoked or expired tokens.","access:manage",true,false,Map.of("id",id),"id");
        write(tools,"update_access_token_scopes","更新PAT权限","Edit an existing active PAT without rotating its secret. Scope/tool grants cannot exceed caller permissions. Omitted allowedTools preserves the prior range; null means all tools in scopes unless caller is restricted. Empty array permits no tools. Does not revive dead tokens.","access:manage",true,false,Map.of("id",id,"scopes",array(choice("Delegated scope",AccessScopes.ALL),AccessScopes.ALL.size()),"allowedTools",allowedTools),"id","scopes");
        add(tools,"list_oauth_authorizations","OAuth授权列表","Owner OAuth authorization metadata only.","access:manage",Map.of());
        write(tools,"revoke_oauth_authorization","撤销OAuth授权","Revoke an owner authorization and its access/refresh tokens. Irreversible.","access:manage",true,false,Map.of("id",id),"id");
        return List.copyOf(tools);
    }
    public Object call(Identity actor,ExternalTools.Tool tool,JsonNode args){
        return tool.readOnly()?read(actor,tool.name(),args):commands.execute(actor,tool.name(),args,operationId->write(actor,tool.name(),args,operationId));
    }
    private Object read(Identity actor,String name,JsonNode a){
        String symbol=a.path("symbol").asText("XAU_USDT"),id=a.path("id").asText();
        return switch(name){
            case "get_simulation_account" -> {var v=(ObjectNode)mapper.valueToTree(ledger.view(symbol));int count=v.path("orders").size();v.remove("orders");v.put("orderCount",count);yield v;}
            case "list_simulation_orders" -> {var all=ledger.view(symbol).orders().stream().sorted(Comparator.comparing((SimulationLedger.Order o)->o.createdAt).reversed().thenComparing(o->o.id)).toList();int offset=a.path("offset").asInt(0),limit=a.path("limit").asInt(20);var rows=all.stream().skip(offset).limit(limit).toList();yield Map.of("symbol",symbol,"items",rows,"total",all.size(),"nextOffset",Math.min(all.size(),(long)offset+rows.size()));}
            case "get_simulation_order" -> order(id);
            case "get_simulation_order_events" -> {order(id);yield Map.of("items",runtime.orderEvents(id));}
            case "get_strategy_review" -> page(reviews.view(symbol),a,"application/json");
            case "export_simulation_orders" -> page(runtime.exportOrders(symbol).getBody(),a,"text/csv");
            case "get_ai_configuration" -> runtime.aiConfiguration();
            case "get_source_configuration" -> runtime.sourceConfiguration();
            case "get_market_cost_configuration" -> runtime.costs(symbol);
            case "get_model_status" -> page(ai.states(),a,"application/json");
            case "get_system_status" -> system.status();
            case "get_api_performance" -> runtime.performance();
            case "get_source_proxy_status" -> proxy.status();
            case "get_research_schedule" -> research.scheduling();
            case "get_compression_status" -> compression.status();
            case "get_compression_detail" -> page(compression.detail(id),a,"application/json");
            case "get_news_compressions" -> Map.of("items",compression.summaries(strings(a.path("ids"))));
            case "get_market_archive" -> page(runtime.archive(symbol,a.path("after").asInt(0),a.path("limit").asInt(20)),a,"application/json");
            case "export_research" -> page(runtime.exportResearch(id).getBody(),a,"application/json");
            case "get_inference_data_flow" -> page(flows.detail(id),a,"application/json");
            case "get_data_flow_stage" -> page(flows.stage(id,a.path("stage").asText()),a,"application/json");
            case "replay_inference_data_flow" -> page(flows.replay(id),a,"application/json");
            case "replay_analysis" -> page(flows.analysis(id),a,"application/json");
            case "replay_indicators" -> page(flows.indicators(id),a,"application/json");
            case "replay_news" -> page(flows.news(id),a,"application/json");
            case "get_operation" -> commands.find(actor.owner(),id);
            case "list_access_tokens" -> Map.of("items",tokens.list(actor.owner()));
            case "list_oauth_authorizations" -> Map.of("items",oauth.list(actor.owner()));
            default -> throw new IllegalArgumentException("UNKNOWN_TOOL");
        };
    }
    private Object write(Identity actor,String name,JsonNode a,String operationId){
        String id=a.path("id").asText();var body=(ObjectNode)a.deepCopy();body.remove("request_key");
        return switch(name){
            case "place_simulation_order" -> {body.put("action","CREATE");yield ledger.propose(operationId,a.path("symbol").asText(),body);}
            case "cancel_simulation_order" -> {order(id);yield runtime.cancel(id);}
            case "modify_simulation_order","close_simulation_order","reduce_simulation_position" -> {order(id);if(name.equals("modify_simulation_order")&&body.size()==1)throw new IllegalArgumentException("EMPTY_ORDER_MODIFICATION");body.put("action",name.equals("modify_simulation_order")?"MODIFY":name.equals("close_simulation_order")?"CLOSE":"REDUCE");yield runtime.command(id,body);}
            case "start_research" -> receipt(runtime.start(new RuntimeApi.ResearchRequest(a.path("symbol").asText(),a.path("horizon").asText())).getBody());
            case "cancel_research" -> receipt(runtime.cancelResearch(id));
            case "update_research_schedule" -> runtime.scheduling(mapper.treeToValue(body,ResearchService.ScheduleConfig.class));
            case "reprocess_news_compression" -> runtime.reprocess(id);
            case "update_ai_configuration" -> runtime.configureAi(mapper.treeToValue(body,AiConfiguration.Update.class));
            case "update_source_configuration" -> runtime.sourceConfiguration(mapper.treeToValue(body,SourceConfiguration.Draft.class));
            case "refresh_model_catalogs" -> runtime.refreshModels();
            case "create_access_token" -> {var input=mapper.treeToValue(body,PersonalAccessTokens.Create.class);if(!actor.scopes().containsAll(input.scopes()))throw new IllegalArgumentException("CANNOT_DELEGATE_UNGRANTED_SCOPE");yield tokens.create(actor.owner(),new PersonalAccessTokens.Create(input.name(),input.scopes(),input.expiresInDays(),input.requestsPerMinute(),delegatedTools(actor,input.scopes(),input.allowedTools())));}
            case "revoke_access_token" -> tokens.revoke(actor.owner(),id);
            case "make_access_token_permanent" -> tokens.permanent(actor.owner(),id);
            case "update_access_token_scopes" -> {
                var selected=strings(a.path("scopes"));if(!actor.scopes().containsAll(selected))throw new IllegalArgumentException("CANNOT_DELEGATE_UNGRANTED_SCOPE");
                var old=tokens.list(actor.owner()).stream().filter(t->t.id().equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("TOKEN_NOT_FOUND"));
                var names=a.has("allowedTools")?(a.hasNonNull("allowedTools")?strings(a.get("allowedTools")):null):old.allowedTools()==null?null:ExternalTools.definitions().stream().filter(t->selected.contains(t.scope())&&old.allowedTools().contains(t.name())).map(ExternalTools.Tool::name).toList();
                yield tokens.grants(actor.owner(),id,selected,delegatedTools(actor,selected,names),true);
            }
            case "revoke_oauth_authorization" -> {oauth.revoke(actor.owner(),id);yield Map.of("id",id,"state","REVOKED");}
            default -> throw new IllegalArgumentException("UNKNOWN_TOOL");
        };
    }
    private SimulationLedger.Order order(String id){for(String product:ExternalData.PRODUCTS)for(var order:ledger.view(product).orders())if(order.id.equals(id))return order;throw new IllegalArgumentException("ORDER_NOT_FOUND");}
    private List<String> delegatedTools(Identity actor,List<String> scopes,List<String> names){
        if(actor.allowedTools()==null)return names;
        if(names!=null){if(!actor.allowedTools().containsAll(names))throw new IllegalArgumentException("CANNOT_DELEGATE_UNGRANTED_TOOL");return names;}
        return ExternalTools.definitions().stream().filter(t->scopes.contains(t.scope())&&actor.permitsTool(t.name())).map(ExternalTools.Tool::name).toList();
    }
    private Object receipt(ResearchService.Run run){return Map.of("id",run.id,"symbol",run.symbol,"horizon",run.horizon,"status",run.status,"stage",run.stage);}
    private Map<String,Object> page(Object source,JsonNode a,String contentType){
        String text=source instanceof String value?value:mapper.writeValueAsString(redact(mapper.valueToTree(source)));
        int total=text.codePointCount(0,text.length()),offset=a.path("offset").asInt(0);if(offset>total)throw new IllegalArgumentException("OFFSET_OUT_OF_RANGE");
        int end=Math.min(total,offset+a.path("maxCharacters").asInt(12000));var out=new LinkedHashMap<String,Object>();out.put("contentType",contentType);out.put("text",text.substring(text.offsetByCodePoints(0,offset),text.offsetByCodePoints(0,end)));out.put("totalCharacters",total);out.put("offset",offset);out.put("nextOffset",end<total?end:null);return out;
    }
    private JsonNode redact(JsonNode node){
        if(node.isObject()){var copy=(ObjectNode)node.deepCopy();for(String key:new ArrayList<>(copy.propertyNames())){if(Set.of("apikey","encryptedapikey","password","authorization","cookie","token_hash","secret","subscriptionurl").contains(key.toLowerCase(Locale.ROOT)))copy.remove(key);else copy.set(key,redact(copy.get(key)));}return copy;}
        if(node.isArray()){var copy=mapper.createArrayNode();node.forEach(value->copy.add(redact(value)));return copy;}return node;
    }
    private static List<String> strings(JsonNode a){var out=new ArrayList<String>();a.forEach(v->out.add(v.asText()));return out;}
    private static Map<String,Object> merge(Map<String,Object> a,Map<String,Object> b){var out=new LinkedHashMap<>(a);out.putAll(b);return out;}
    private static void add(List<ExternalTools.Tool> out,String name,String title,String desc,String scope,Map<String,Object> fields,String... required){out.add(new ExternalTools.Tool(name,title,desc,scope,object(fields,required),true,false,false));}
    private static void write(List<ExternalTools.Tool> out,String name,String title,String desc,String scope,boolean destructive,boolean openWorld,Map<String,Object> fields,String... required){
        var input=new LinkedHashMap<>(fields);input.put("request_key",text("Unique stable key for this intent, 8-128 ASCII letters/digits/_.:-. Reuse unchanged on retries. Inspect get_operation for unknown outcome.",128));var mandatory=new ArrayList<>(List.of(required));mandatory.add("request_key");
        out.add(new ExternalTools.Tool(name,title,desc,scope,object(input,mandatory.toArray(String[]::new)),false,destructive,openWorld));
    }
}
