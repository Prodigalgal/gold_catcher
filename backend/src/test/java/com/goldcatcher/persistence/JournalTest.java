package com.goldcatcher.persistence;
import static org.assertj.core.api.Assertions.*;
import com.goldcatcher.TestSupport;
import java.util.Map;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class JournalTest {
    @Test void canceledWorkersArchiveTheirOutcomeWithoutPassingInterruptToJdbc()throws Exception{
        var f=new TestSupport();try(var workers=Executors.newVirtualThreadPerTaskExecutor()){
            var completed=workers.submit(()->{Thread.currentThread().interrupt();
                try{f.store.journal(()->{assertThat(Thread.currentThread().isInterrupted()).isFalse();f.store.save("cancel-test","run",Map.of("state","CANCELED"));return null;});
                    return Thread.currentThread().isInterrupted();}finally{Thread.interrupted();}});
            assertThat(completed.get(2,TimeUnit.SECONDS)).isTrue();
        }
        assertThat(f.store.find("cancel-test","run",Map.class)).isPresent();
    }
    @Test void failedArchiveRollsBackAndRestoresTheCancellationFlag()throws Exception{
        var f=new TestSupport();try(var workers=Executors.newVirtualThreadPerTaskExecutor()){
            var completed=workers.submit(()->{Thread.currentThread().interrupt();try{
                assertThatThrownBy(()->f.store.journal(()->{f.store.save("cancel-test","rollback",Map.of("state","WAIT"));throw new IllegalStateException("TEST_ROLLBACK");})).hasMessage("TEST_ROLLBACK");
                return Thread.currentThread().isInterrupted();}finally{Thread.interrupted();}});
            assertThat(completed.get(2,TimeUnit.SECONDS)).isTrue();
        }
        assertThat(f.store.find("cancel-test","rollback",Map.class)).isEmpty();
    }
}
