package com.goldcatcher.auth;

import com.goldcatcher.TestSupport;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PersonalAccessTokensTest {
    private final TestSupport f = new TestSupport();
    private final PersonalAccessTokens tokens = new PersonalAccessTokens(f.jdbc, f.clock, f.store);
    private PersonalAccessTokens.Created create() { return tokens.create("admin", new PersonalAccessTokens.Create("Research client", List.of("information:read"), 1, 10)); }
    @Test void secretIsReturnedOnceAndOnlyDigestAndNonSecretAuditMetadataArePersisted() {
        var created = create(); assertThat(created.secret()).matches("gc_pat_[a-f0-9]{32}\\.[A-Za-z0-9_-]{43}");
        assertThat(tokens.authenticate(created.secret()).scopes()).containsExactly("information:read");
        var rows = f.jdbc.queryForList("SELECT * FROM gc_access_token");
        assertThat(rows.toString()).doesNotContain(created.secret());
        assertThat(f.mapper.writeValueAsString(tokens.list("admin"))).doesNotContain(created.secret(), "token_hash");
        assertThat(f.mapper.writeValueAsString(f.store.events(created.token().id(), 10))).doesNotContain(created.secret(), "token_hash");
        assertThat(tokens.list("other")).isEmpty();
        assertThatThrownBy(() -> tokens.authenticate(created.secret().substring(0, 70) + "xxxxxxxxxxxxx")).isInstanceOf(PersonalAccessTokens.Rejected.class);
        assertThatThrownBy(() -> tokens.authenticate("Bearer " + created.secret())).hasMessage("INVALID_ACCESS_TOKEN");
        assertThatThrownBy(() -> tokens.authenticate(created.secret()).require("market:read")).hasMessage("INSUFFICIENT_SCOPE");
    }
    @Test void expirationAndRevocationSurviveNewServiceInstancesAndRevocationIsOwnerScopedAndIdempotent() {
        var created = create(); var identity = tokens.authenticate(created.secret()); tokens.used(identity);
        var restarted = new PersonalAccessTokens(f.jdbc, f.clock, f.store);
        assertThat(restarted.list("admin").getFirst().useCount()).isEqualTo(1);
        assertThat(restarted.list("admin").getFirst().lastUsedAt()).isEqualTo(f.clock.instant());
        assertThatThrownBy(() -> restarted.revoke("other", created.token().id())).hasMessage("TOKEN_NOT_FOUND");
        assertThat(restarted.revoke("admin", created.token().id()).state()).isEqualTo("REVOKED");
        assertThat(restarted.revoke("admin", created.token().id()).state()).isEqualTo("REVOKED");
        assertThat(f.store.events(created.token().id(), 10)).hasSize(2);
        assertThatThrownBy(() -> tokens.authenticate(created.secret())).hasMessage("INVALID_ACCESS_TOKEN");
        assertThatThrownBy(() -> tokens.used(identity)).hasMessage("INVALID_ACCESS_TOKEN");
        var expiring = create(); f.clock.advance(Duration.ofDays(1).toMillis());
        assertThat(tokens.list("admin").stream().filter(t -> t.id().equals(expiring.token().id())).findFirst().orElseThrow().state()).isEqualTo("EXPIRED");
        assertThatThrownBy(() -> restarted.authenticate(expiring.secret())).hasMessage("INVALID_ACCESS_TOKEN");
    }
    @Test void invalidGrantsAndNamesCannotCreateTokensAndRandomSecretsAreUnique() {
        for (var scopes : List.of(List.<String>of(), List.of("market:write"), List.of("market:read", "market:read")))
            assertThatThrownBy(() -> tokens.create("admin", new PersonalAccessTokens.Create("client", scopes, 90, 120))).hasMessage("INVALID_TOKEN_SCOPES");
        assertThatThrownBy(() -> tokens.create("admin", new PersonalAccessTokens.Create("\n", List.of("market:read"), 90, 120))).hasMessage("INVALID_TOKEN_NAME");
        assertThatThrownBy(() -> tokens.create("admin", new PersonalAccessTokens.Create("client", List.of("market:read"), 0, 120))).hasMessage("INVALID_TOKEN_LIMITS");
        assertThatThrownBy(() -> tokens.create("admin", new PersonalAccessTokens.Create("client", List.of("market:read"), 90, 601))).hasMessage("INVALID_TOKEN_LIMITS");
        assertThat(tokens.list("admin")).isEmpty(); assertThat(create().secret()).isNotEqualTo(create().secret());
    }
}
