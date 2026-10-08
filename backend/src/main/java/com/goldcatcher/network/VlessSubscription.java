package com.goldcatcher.network;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class VlessSubscription {
    record Node(String id,Map<String,Object> outbound) {}

    static List<Node> parse(byte[] bytes) {
        String text=new String(bytes,StandardCharsets.UTF_8).strip();
        if(!text.startsWith("vless://")) {
            try{text=new String(Base64.getMimeDecoder().decode(text),StandardCharsets.UTF_8).strip();}
            catch(IllegalArgumentException invalid){throw new IllegalStateException("PROXY_SUBSCRIPTION_FORMAT");}
        }
        Map<String,Node> nodes=new LinkedHashMap<>();
        for(String line:text.split("\\R")) {
            if(nodes.size()>=128)break;
            if(!line.startsWith("vless://"))continue;
            try {
                URI uri=URI.create(line.strip());String host=uri.getHost();int port=uri.getPort();
                if(host==null||port<1||port>65535)continue;
                String uuid=UUID.fromString(uri.getUserInfo()).toString();Map<String,String> query=new HashMap<>();
                for(String pair:Objects.toString(uri.getRawQuery(),"").split("&")) {
                    int equals=pair.indexOf('=');if(equals>0)query.put(decode(pair.substring(0,equals)),decode(pair.substring(equals+1)));
                }
                String transport=query.getOrDefault("type","tcp"),security=query.getOrDefault("security","none");
                if(!Set.of("tcp","ws","grpc").contains(transport)||!Set.of("tls","reality","none").contains(security))continue;
                String name=query.getOrDefault("sni",query.getOrDefault("host",host));
                Map<String,Object> tls=new LinkedHashMap<>();tls.put("enabled",!security.equals("none"));tls.put("server_name",name);tls.put("insecure",false);
                tls.put("utls",Map.of("enabled",true,"fingerprint",query.getOrDefault("fp","chrome")));
                if(security.equals("reality")) {
                    if(query.getOrDefault("pbk","").isBlank())continue;
                    tls.put("reality",Map.of("enabled",true,"public_key",query.get("pbk"),"short_id",query.getOrDefault("sid","")));
                }
                Map<String,Object> outbound=new LinkedHashMap<>();outbound.put("type","vless");outbound.put("tag","proxy");
                outbound.put("server",host);outbound.put("server_port",port);outbound.put("uuid",uuid);outbound.put("tls",tls);
                String flow=query.getOrDefault("flow","");if(!flow.isBlank()) {
                    if(!flow.equals("xtls-rprx-vision"))continue;outbound.put("flow",flow);
                }
                if(transport.equals("ws"))outbound.put("transport",Map.of("type","ws","path",query.getOrDefault("path","/"),"headers",Map.of("Host",query.getOrDefault("host",host))));
                if(transport.equals("grpc"))outbound.put("transport",Map.of("type","grpc","service_name",query.getOrDefault("serviceName","")));
                String id=com.goldcatcher.information.NewsService.hash(uuid+":"+host+":"+port+":"+new TreeMap<>(query)).substring(0,12);nodes.putIfAbsent(id,new Node(id,outbound));
            }catch(RuntimeException invalid){/* A subscription may contain unsupported or malformed candidates. */}
        }
        if(nodes.isEmpty())throw new IllegalStateException("PROXY_NO_SUPPORTED_NODES");return List.copyOf(nodes.values());
    }
    private static String decode(String value){return URLDecoder.decode(value,StandardCharsets.UTF_8);}
    static Map<String,Object> config(Node node,int port) {
        return Map.of("log",Map.of("disabled",true),"inbounds",List.of(Map.of("type","mixed","tag","source-in","listen","127.0.0.1","listen_port",port)),
            "outbounds",List.of(node.outbound()),"route",Map.of("final","proxy"));
    }
}
