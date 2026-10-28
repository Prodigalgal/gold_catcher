package com.goldcatcher.external;

import com.goldcatcher.TestSupport;
import com.goldcatcher.auth.PersonalAccessTokens.Identity;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ExternalCommandsTest {
    final TestSupport f=new TestSupport();
    final ExternalCommands commands=new ExternalCommands(f.jdbc,f.mapper,f.clock);
    final Identity actor=new Identity("a".repeat(32),"admin",Set.of("simulation:write","system:read"),120);
    @Test void retriesAcrossInstancesReturnTheSameReceiptWithoutRepeatingBusinessWork(){
        var args=f.mapper.readTree("{\"request_key\":\"one-intent-1\",\"quantity\":\"0.1234567890123456789\"}");var calls=new AtomicInteger();
        var first=commands.execute(actor,"place",args,id->{calls.incrementAndGet();return Map.of("id",id,"quantity",args.path("quantity").asText());});
        var restarted=new ExternalCommands(f.jdbc,f.mapper,f.clock);var replay=restarted.execute(actor,"place",args,id->{throw new AssertionError("duplicated side effect");});
        assertThat(replay.operationId()).isEqualTo(first.operationId());assertThat(replay.replayed()).isTrue();assertThat(replay.result().path("quantity").asText()).isEqualTo("0.1234567890123456789");assertThat(calls.get()).isEqualTo(1);
        assertThatThrownBy(()->commands.execute(actor,"cancel",args,id->Map.of())).hasMessage("REQUEST_KEY_CONFLICT");
        assertThatThrownBy(()->commands.find("other",first.operationId())).hasMessage("OPERATION_NOT_FOUND");
    }
    @Test void concurrentDuplicateCannotDispatchTwiceAndReportsTheExistingOperation()throws Exception{
        var args=f.mapper.readTree("{\"request_key\":\"concurrent-intent\"}");var entered=new CountDownLatch(1);var finish=new CountDownLatch(1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
            var pending=executor.submit(()->commands.execute(actor,"place",args,id->{entered.countDown();try{assertThat(finish.await(5,TimeUnit.SECONDS)).isTrue();}catch(InterruptedException e){throw new IllegalStateException(e);}return Map.of("id",id);}));
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            try{assertThatThrownBy(()->commands.execute(actor,"place",args,id->{throw new AssertionError("duplicate");})).hasMessage("COMMAND_IN_PROGRESS");}finally{finish.countDown();}
            assertThat(pending.get(5,TimeUnit.SECONDS).ok()).isTrue();
        }
    }
    @Test void crashAfterDomainCommitIsUnknownAndNeverReexecutedEvenInTheSameProcess(){
        var args=f.mapper.readTree("{\"request_key\":\"ambiguous-intent\"}");var calls=new AtomicInteger();
        assertThatThrownBy(()->commands.execute(actor,"place",args,id->{calls.incrementAndGet();throw new IllegalStateException("network lost after commit");})).hasMessage("COMMAND_OUTCOME_UNKNOWN");
        assertThatThrownBy(()->commands.execute(actor,"place",args,id->{calls.incrementAndGet();return Map.of();})).hasMessage("COMMAND_OUTCOME_UNKNOWN");
        String id=f.jdbc.queryForObject("SELECT id FROM gc_external_command",String.class);assertThat(commands.find(actor.owner(),id)).containsEntry("state","OUTCOME_UNKNOWN");assertThat(calls.get()).isEqualTo(1);
    }
    @Test void businessRejectionIsFailureAndOneTimeSecretNeverAppearsInReceipts(){
        var failure=commands.execute(actor,"place",f.mapper.readTree("{\"request_key\":\"rejected-intent\"}"),id->Map.of("status","REJECTED","rejection","STALE_QUOTE"));assertThat(failure.ok()).isFalse();
        var args=f.mapper.readTree("{\"request_key\":\"secret-intent\"}");var issued=commands.execute(actor,"create_token",args,id->Map.of("secret","one-time-test-secret","token",Map.of("id","child")));
        assertThat(issued.result().path("secret").asText()).isEqualTo("one-time-test-secret");
        var replay=commands.execute(actor,"create_token",args,id->{throw new AssertionError();});assertThat(replay.result().path("secret").isNull()).isTrue();
        assertThat(f.mapper.writeValueAsString(commands.find(actor.owner(),issued.operationId()))).doesNotContain("one-time-test-secret");
        assertThat(f.jdbc.queryForList("SELECT * FROM gc_external_command").toString()).doesNotContain("one-time-test-secret");
    }
}
