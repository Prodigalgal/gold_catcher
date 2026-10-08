package com.goldcatcher;

import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
public class EventHub {
    private final CopyOnWriteArrayList<SseEmitter> clients=new CopyOnWriteArrayList<>();
    private final AtomicLong revision=new AtomicLong();
    public SseEmitter connect() {
        var emitter=new SseEmitter(300_000L);
        clients.add(emitter);
        emitter.onCompletion(()->clients.remove(emitter));
        emitter.onTimeout(()->{clients.remove(emitter);emitter.complete();});
        emitter.onError(error->clients.remove(emitter));
        send(emitter,"resync");
        return emitter;
    }
    public void changed() { revision.incrementAndGet(); }
    @Scheduled(fixedDelay=1000)
    void publish() { for(var client:clients) send(client,"refresh"); }
    private void send(SseEmitter client,String event) {
        try { client.send(SseEmitter.event().name(event).id(Long.toString(revision.get()))
            .data(Map.of("revision",revision.get(),"snapshotRequired",event.equals("resync")))); }
        catch(Exception error) { clients.remove(client);client.complete(); }
    }
}
