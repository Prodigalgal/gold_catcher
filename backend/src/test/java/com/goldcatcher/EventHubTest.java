package com.goldcatcher;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class EventHubTest {
    @Test void disconnectedClientCannotBreakPublishingToTheRemainingClients() throws Exception {
        var hub=new EventHub();var disconnected=mock(SseEmitter.class);var healthy=mock(SseEmitter.class);
        doThrow(new IOException("browser disconnected")).when(disconnected).send(any(SseEmitter.SseEventBuilder.class));
        doThrow(new IllegalStateException("AsyncContext already ended")).when(disconnected).complete();
        @SuppressWarnings("unchecked") var clients=(List<SseEmitter>)ReflectionTestUtils.getField(hub,"clients");
        clients.add(disconnected);clients.add(healthy);
        assertThatCode(hub::publish).doesNotThrowAnyException();
        verify(healthy).send(any(SseEmitter.SseEventBuilder.class));
        verify(disconnected,never()).complete();assertThat(clients).containsExactly(healthy);
        hub.publish();verify(healthy,times(2)).send(any(SseEmitter.SseEventBuilder.class));
        verify(disconnected,times(1)).send(any(SseEmitter.SseEventBuilder.class));
    }
}
