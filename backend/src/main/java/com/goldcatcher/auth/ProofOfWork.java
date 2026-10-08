package com.goldcatcher.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Bounded, single-use, session-bound challenges. Expiry uses monotonic time, independent of exchange calibration. */
@Service
public class ProofOfWork {
    public record Challenge(String id,String nonce,int difficulty,int expiresInSeconds,String algorithm) {}
    private record Pending(Challenge challenge,String session,long issuedAt) {}
    private static final long TTL = 120_000_000_000L;
    private final Map<String,Pending> pending = new HashMap<>();
    private final Map<String,Window> windows = new HashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final LongSupplier nanoTime;
    private final int baseDifficulty;
    private static final class Window { long start; int issued,failures; Window(long now){start=now;} }
    public static final class Rejected extends RuntimeException {
        public final int status; public Rejected(String code,int status){super(code);this.status=status;}
    }
    @org.springframework.beans.factory.annotation.Autowired
    public ProofOfWork(@Value("${gold.auth.pow-bits:18}") int bits) { this(bits,System::nanoTime); }
    ProofOfWork(int bits,LongSupplier nanoTime) {
        if(bits<8||bits>24)throw new IllegalArgumentException("POW_DIFFICULTY_OUT_OF_RANGE");
        this.baseDifficulty=bits;this.nanoTime=nanoTime;
    }
    public synchronized Challenge issue(String session,String address) {
        long now=nanoTime.getAsLong();
        pending.values().removeIf(value -> now-value.issuedAt()>=TTL);
        windows.values().removeIf(value -> now-value.start>=60_000_000_000L);
        if(pending.size()>=1024||windows.size()>=1024)throw new Rejected("POW_BUSY",429);
        var window=windows.computeIfAbsent(address,key->new Window(now));
        if(++window.issued>12)throw new Rejected("POW_RATE_LIMITED",429);
        // At most one outstanding puzzle per session; asking again invalidates its previous puzzle.
        pending.values().removeIf(value -> value.session().equals(session));
        var challenge=new Challenge(hex(16),hex(16),Math.min(24,baseDifficulty+window.failures/3),120,"SHA-256");
        pending.put(challenge.id(),new Pending(challenge,session,now));return challenge;
    }
    public synchronized void verify(String session,String id,String solution) {
        var value=id==null?null:pending.get(id);
        if(value==null||!value.session().equals(session))throw new Rejected("POW_INVALID",400);
        pending.remove(id); // All verification attempts consume the challenge, including invalid proofs.
        if(nanoTime.getAsLong()-value.issuedAt()>=TTL)throw new Rejected("POW_EXPIRED",400);
        if(solution==null||!solution.matches("[0-9]{1,16}"))throw new Rejected("POW_INVALID",400);
        try {
            var digest=MessageDigest.getInstance("SHA-256").digest(payload(value.challenge(),solution).getBytes(StandardCharsets.UTF_8));
            if(!valid(digest,value.challenge().difficulty()))throw new Rejected("POW_INVALID",400);
        }catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    public synchronized void failed(String address) {
        long now=nanoTime.getAsLong();var window=windows.get(address);
        if(window!=null&&now-window.start<60_000_000_000L)window.failures++;
    }
    private String hex(int length){byte[] bytes=new byte[length];random.nextBytes(bytes);return HexFormat.of().formatHex(bytes);}
    public static String payload(Challenge challenge,String solution){return "GC1:"+challenge.id()+":"+challenge.nonce()+":"+solution;}
    public static boolean valid(byte[] digest,int bits) {
        for(int i=0;i<bits/8;i++)if(digest[i]!=0)return false;
        int remainder=bits%8;return remainder==0||(digest[bits/8]&255) >>> (8-remainder)==0;
    }
}
