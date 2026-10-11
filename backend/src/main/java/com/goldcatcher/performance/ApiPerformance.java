package com.goldcatcher.performance;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

/** Bounded HTTP telemetry: route patterns, duration and size; never bodies, headers or query values. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE+20)
public class ApiPerformance extends OncePerRequestFilter {
    private record Sample(long micros,long bytes,int status) {}
    private static final class Window {
        private final Deque<Sample> recent=new ArrayDeque<>();private long calls,failures;
        synchronized void add(Sample sample){calls++;if(sample.status()>=400)failures++;recent.addLast(sample);while(recent.size()>128)recent.removeFirst();}
        synchronized Map<String,Object> view(String route){
            long[] times=recent.stream().mapToLong(Sample::micros).sorted().toArray();var last=recent.getLast();
            return Map.of("route",route,"calls",calls,"failures",failures,"samples",times.length,"p50Ms",times[Math.max(0,(int)Math.ceil(times.length*.5)-1)]/1000.0,
                "p95Ms",times[Math.max(0,(int)Math.ceil(times.length*.95)-1)]/1000.0,"lastMs",last.micros()/1000.0,"lastBytes",last.bytes(),"lastStatus",last.status());
        }
    }
    private final Map<String,Window> windows=new ConcurrentHashMap<>();
    @Override protected boolean shouldNotFilter(HttpServletRequest request){return !request.getRequestURI().startsWith("/api/")||request.getRequestURI().equals("/api/events");}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
        long started=System.nanoTime();long[] bytes={0};
        var wrapped=new HttpServletResponseWrapper(response){
            private ServletOutputStream stream;
            private void timing(){if(!isCommitted())setHeader("Server-Timing","app;dur="+String.format(Locale.ROOT,"%.3f",(System.nanoTime()-started)/1_000_000.0));}
            @Override public ServletOutputStream getOutputStream()throws IOException{
                if(stream==null){timing();var target=super.getOutputStream();stream=new ServletOutputStream(){
                    @Override public boolean isReady(){return target.isReady();}
                    @Override public void setWriteListener(WriteListener listener){target.setWriteListener(listener);}
                    @Override public void write(int value)throws IOException{target.write(value);bytes[0]++;}
                    @Override public void write(byte[] data,int offset,int length)throws IOException{target.write(data,offset,length);bytes[0]+=length;}
                    @Override public void flush()throws IOException{target.flush();}
                    @Override public void close()throws IOException{target.close();}};}
                return stream;
            }
            @Override public PrintWriter getWriter()throws IOException{timing();return super.getWriter();}
        };
        try{chain.doFilter(request,wrapped);}finally{
            Object matched=request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            String path=matched==null?(List.of("/api/auth/login","/api/auth/logout").contains(request.getRequestURI())?request.getRequestURI():"UNMAPPED"):matched.toString();
            String method=List.of("GET","POST","PUT","PATCH","DELETE","HEAD","OPTIONS").contains(request.getMethod())?request.getMethod():"OTHER";
            String route=method+" "+path;
            windows.computeIfAbsent(route,unused->new Window()).add(new Sample((System.nanoTime()-started)/1000,bytes[0],response.getStatus()));
        }
    }
    public Map<String,Object> view(){return Map.of("scope","THIS_PROCESS; LAST_128_SAMPLES_PER_ROUTE; APPLICATION_BYTES_BEFORE_COMPRESSION; SSE_EXCLUDED","routes",windows.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(entry->entry.getValue().view(entry.getKey())).toList());}
}
