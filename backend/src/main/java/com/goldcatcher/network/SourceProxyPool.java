package com.goldcatcher.network;

import com.goldcatcher.*;
import jakarta.annotation.PreDestroy;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclEntryPermission;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** A private sing-box process serves only explicitly selected sources, with verified candidates and bounded retries. */
@Service
public class SourceProxyPool {
    public record TargetState(String source,String nodeId,Integer status,String state,Instant checkedAt,String route) {}
    public record Status(String state,boolean configured,boolean enabled,String binaryVersion,int candidates,int tested,
        int rejected,String activeNodeId,Instant lastSubscription,Instant lastVerified,Instant nextCheck,String error,List<TargetState> targets) {}
    private record Session(String nodeId,Process process,Path config,InetSocketAddress address) {}
    private final RuntimeSettings settings;private final RemoteHttp http;private final ObjectMapper mapper;private final Clock clock;
    private final AtomicBoolean checking=new AtomicBoolean();private final Map<String,Instant> cooldowns=new ConcurrentHashMap<>();
    private final Map<String,Instant> fallbackUntil=new ConcurrentHashMap<>();
    private final Map<String,TargetState> targets=new ConcurrentHashMap<>();
    private volatile Session active;private volatile String state="DISABLED",error;
    private volatile Instant lastSubscription,lastVerified,nextCheck=Instant.EPOCH;
    private volatile int candidates,tested,rejected;private volatile boolean stopping;

    public SourceProxyPool(RuntimeSettings settings,RemoteHttp http,ObjectMapper mapper,Clock clock) {
        this.settings=settings;this.http=http;this.mapper=mapper;this.clock=clock;
    }
    public boolean routes(String source) {
        return settings.enabled("GOLD_SOURCE_PROXY_ENABLED")&&Arrays.stream(settings.text("GOLD_SOURCE_PROXY_SOURCES","FED_PRESS,FED_SPEECH,ECB_PRESS,BEA_NEWS,BEA_CALENDAR,BLS_CPI,BLS_EMPLOYMENT,BLS_PPI,BLS_JOLTS,BLS_CALENDAR,FOMC_CALENDAR").split(","))
            .map(String::strip).anyMatch(source::equals);
    }
    public String routeName(String source){return !routes(source)?"DIRECT":fallbackMode()?"DIRECT_WITH_CF_FALLBACK":"CF_PROXY";}
    private boolean fallbackMode(){return settings.text("GOLD_SOURCE_PROXY_MODE","FALLBACK").equalsIgnoreCase("FALLBACK");}
    public Status status() {
        var session=active;String current=session!=null&&!session.process().isAlive()?"PROCESS_EXITED":state;
        return new Status(current,!settings.text("GOLD_SOURCE_PROXY_SUBSCRIPTION_URL","").isBlank(),settings.enabled("GOLD_SOURCE_PROXY_ENABLED"),"1.14.2",
            candidates,tested,rejected,session==null?null:session.nodeId(),lastSubscription,lastVerified,nextCheck,error,
            targets.values().stream().sorted(Comparator.comparing(TargetState::source)).toList());
    }
    public HttpResponse<byte[]> send(String source,HttpRequest request,int maximum) throws Exception {
        if(!routes(source))return http.send(request,maximum);
        if(fallbackMode()&&!fallbackUntil.getOrDefault(source,Instant.EPOCH).isAfter(clock.instant())) {
            try {
                var direct=http.send(request,maximum);int code=direct.statusCode();
                if(code!=403) {
                    fallbackUntil.remove(source);
                    targets.put(source,new TargetState(source,null,code,code==200||code==304?"ACCESSIBLE":"TARGET_HTTP_ERROR",clock.instant(),"DIRECT"));
                    return direct;
                }
                targets.put(source,new TargetState(source,null,code,"TARGET_ACCESS_DENIED",clock.instant(),"DIRECT"));
                fallbackUntil.put(source,clock.instant().plusSeconds(300));
            }catch(Exception failure) {
                if(contentFailure(failure)){targets.put(source,new TargetState(source,null,null,"CONTENT_INVALID",clock.instant(),"DIRECT"));throw failure;}
                targets.put(source,new TargetState(source,null,null,"NETWORK_FAILED",clock.instant(),"DIRECT"));
                fallbackUntil.put(source,clock.instant().plusSeconds(300));
            }
        }
        var session=active;
        if(session==null||!session.process().isAlive())throw new IllegalStateException("PROXY_NOT_READY");
        try {
            var response=http.send(request,maximum,session.address());int code=response.statusCode();
            targets.put(source,new TargetState(source,session.nodeId(),code,code==403?"TARGET_ACCESS_DENIED":code==200||code==304?"ACCESSIBLE":"TARGET_HTTP_ERROR",clock.instant(),"CF_PROXY"));
            return response;
        }catch(Exception failure) {
            if(contentFailure(failure)) {
                targets.put(source,new TargetState(source,session.nodeId(),null,"CONTENT_INVALID",clock.instant(),"CF_PROXY"));throw failure;
            }
            targets.put(source,new TargetState(source,session.nodeId(),null,"NETWORK_FAILED",clock.instant(),"CF_PROXY"));
            if(active==session){state="DEGRADED";nextCheck=clock.instant();cooldowns.put(session.nodeId(),clock.instant().plusSeconds(900));}
            throw new IllegalStateException("PROXY_NETWORK_FAILED");
        }
    }
    private boolean contentFailure(Exception failure){return failure instanceof IllegalStateException&&Set.of("RESPONSE_TOO_LARGE","CONTENT_DECOMPRESSION_FAILED","CONTENT_ENCODING_UNSUPPORTED").contains(failure.getMessage());}
    @Scheduled(fixedDelay=5000,initialDelay=500) void maintain() {
        if(stopping||!settings.enabled("GOLD_SOURCE_PROXY_ENABLED"))return;
        if(nextCheck.isAfter(clock.instant())||!checking.compareAndSet(false,true))return;
        Thread.ofVirtual().name("gold-source-proxy").start(()->{try{refresh();}finally{checking.set(false);}});
    }
    void refresh() {
        if(stopping)return;
        try {
            cooldowns.entrySet().removeIf(entry->!entry.getValue().isAfter(clock.instant()));
            if(active!=null&&active.process().isAlive()&&!cooldowns.containsKey(active.nodeId())) {
                try {verify(active);lastVerified=clock.instant();state="READY";error=null;nextCheck=clock.instant().plusSeconds(300);return;}
                catch(Exception failed){cooldowns.put(active.nodeId(),clock.instant().plusSeconds(1800));}
            }
            state="VALIDATING";
            URI subscription=URI.create(settings.text("GOLD_SOURCE_PROXY_SUBSCRIPTION_URL",""));
            if(!"https".equalsIgnoreCase(subscription.getScheme())||subscription.getHost()==null||subscription.getUserInfo()!=null)
                throw new IllegalStateException("PROXY_SUBSCRIPTION_INVALID");
            var response=http.send(BrowserSourceRequest.navigation(subscription,Duration.ofSeconds(15)).GET().build(),1_000_000);
            if(response.statusCode()!=200)throw new IllegalStateException("PROXY_SUBSCRIPTION_HTTP_"+response.statusCode());
            var nodes=new ArrayList<>(VlessSubscription.parse(response.body()));Collections.shuffle(nodes);candidates=nodes.size();lastSubscription=clock.instant();tested=0;rejected=0;
            int maximum=Math.max(1,Math.min(12,settings.integer("GOLD_SOURCE_PROXY_MAX_ATTEMPTS",8)));
            for(var node:nodes) {
                if(stopping)return;
                if(cooldowns.getOrDefault(node.id(),Instant.EPOCH).isAfter(clock.instant()))continue;
                if(tested>=maximum)break;tested++;Session candidate=null;
                try {
                    candidate=start(node);verify(candidate);
                    if(stopping){close(candidate);return;}
                    var previous=active;active=candidate;close(previous);cooldowns.remove(node.id());lastVerified=clock.instant();state="READY";error=null;
                    nextCheck=clock.instant().plusSeconds(300);return;
                }catch(Exception invalid){close(candidate);rejected++;cooldowns.put(node.id(),clock.instant().plusSeconds(1800));}
            }
            throw new IllegalStateException("PROXY_NO_VERIFIED_NODE");
        }catch(Exception failure) {
            state="FETCH_FAILED";error=failure instanceof IllegalStateException&&failure.getMessage()!=null&&failure.getMessage().matches("[A-Z0-9_]+")?failure.getMessage():"PROXY_REFRESH_FAILED";
            close(active);active=null;nextCheck=clock.instant().plusSeconds(300);
        }
    }
    private Session start(VlessSubscription.Node node) throws Exception {
        int port;try(var socket=new ServerSocket(0,1,InetAddress.getLoopbackAddress())){port=socket.getLocalPort();}
        Path config=Files.createTempFile("gold-source-proxy-",".json");Process process=null;
        try {
            if(Files.getFileStore(config).supportsFileAttributeView("posix"))Files.setPosixFilePermissions(config,PosixFilePermissions.fromString("rw-------"));
            else {
                var acl=Files.getFileAttributeView(config,AclFileAttributeView.class);
                if(acl==null)throw new IllegalStateException("PROXY_PRIVATE_STORAGE_UNAVAILABLE");
                var owner=config.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(System.getProperty("user.name"));
                acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
            }
            Files.writeString(config,mapper.writeValueAsString(VlessSubscription.config(node,port)));
            process=new ProcessBuilder(settings.text("GOLD_SOURCE_PROXY_BINARY","sing-box"),"run","-c",config.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            var session=new Session(node.id(),process,config,new InetSocketAddress("127.0.0.1",port));
            for(int attempt=0;attempt<30;attempt++) {
                if(!process.isAlive())throw new IllegalStateException("PROXY_CORE_START_FAILED");
                try(var socket=new Socket()){socket.connect(session.address(),100);return session;}
                catch(java.io.IOException waiting){Thread.sleep(50);}
            }
            throw new IllegalStateException("PROXY_CORE_BIND_TIMEOUT");
        }catch(Exception failure){if(process!=null)process.destroyForcibly();Files.deleteIfExists(config);throw failure;}
    }
    private void verify(Session session) throws Exception {
        URI url=URI.create("https://www.federalreserve.gov/feeds/press_all.xml");
        var response=http.send(BrowserSourceRequest.navigation(url,Duration.ofSeconds(12)).GET().build(),1_000_000,session.address());
        if(response.statusCode()!=200||!new String(response.body(),java.nio.charset.StandardCharsets.UTF_8).contains("<rss"))
            throw new IllegalStateException("PROXY_VERIFICATION_FAILED");
    }
    private void close(Session session) {
        if(session==null)return;http.forgetProxy(session.address());session.process().destroy();
        try{if(!session.process().waitFor(2,java.util.concurrent.TimeUnit.SECONDS))session.process().destroyForcibly();Files.deleteIfExists(session.config());}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();session.process().destroyForcibly();}
        catch(java.io.IOException cleanup){error="PROXY_CONFIG_CLEANUP_FAILED";}
    }
    @PreDestroy void stop(){stopping=true;close(active);active=null;}
}
