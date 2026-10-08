package com.goldcatcher.auth;
import static org.assertj.core.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.junit.jupiter.api.Test;

class ProofOfWorkTest {
    private String solve(ProofOfWork.Challenge challenge) throws Exception {
        var hash=MessageDigest.getInstance("SHA-256");for(long i=0;;i++)if(ProofOfWork.valid(hash.digest(ProofOfWork.payload(challenge,""+i).getBytes(StandardCharsets.UTF_8)),challenge.difficulty()))return ""+i;
    }
    @Test void bindingConsumptionAndMonotonicExpiry() throws Exception {
        long[] now={0};var pow=new ProofOfWork(8,()->now[0]);var c=pow.issue("session","ip");
        assertThatThrownBy(()->pow.verify("other",c.id(),"0")).hasMessage("POW_INVALID");
        pow.verify("session",c.id(),solve(c));assertThatThrownBy(()->pow.verify("session",c.id(),"0")).hasMessage("POW_INVALID");
        var expired=pow.issue("session","ip");now[0]=120_000_000_000L;
        assertThatThrownBy(()->pow.verify("session",expired.id(),solve(expired))).hasMessage("POW_EXPIRED");
    }
    @Test void incorrectProofConsumesAndRetryReplacesPriorPuzzle() {
        var pow=new ProofOfWork(8,()->0);var old=pow.issue("s","ip");var current=pow.issue("s","ip");
        assertThatThrownBy(()->pow.verify("s",old.id(),"0")).hasMessage("POW_INVALID");
        assertThatThrownBy(()->pow.verify("s",current.id(),"malformed")).hasMessage("POW_INVALID");
        assertThatThrownBy(()->pow.verify("s",current.id(),"0")).hasMessage("POW_INVALID");
    }
    @Test void rateLimitAndAdaptiveDifficultyAreBounded() {
        long[] now={0};var pow=new ProofOfWork(18,()->now[0]);assertThat(pow.issue("s","ip").difficulty()).isEqualTo(18);
        for(int i=0;i<3;i++)pow.failed("ip");assertThat(pow.issue("s","ip").difficulty()).isEqualTo(19);
        for(int i=2;i<12;i++)pow.issue("s","ip");assertThatThrownBy(()->pow.issue("s","ip")).hasMessage("POW_RATE_LIMITED");
        now[0]=60_000_000_001L;assertThat(pow.issue("s","ip").difficulty()).isEqualTo(18);
    }
}
