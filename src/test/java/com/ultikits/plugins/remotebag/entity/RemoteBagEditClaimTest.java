package com.ultikits.plugins.remotebag.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The edit claim row (UltiKits/UltiRemoteBag#54): its id, its fields, and value equality. */
@DisplayName("RemoteBagEditClaim")
class RemoteBagEditClaimTest {

    private static RemoteBagEditClaim claim(String token, long renewals) {
        RemoteBagEditClaim claim = RemoteBagEditClaim.builder()
                .playerUuid("p").pageNumber(1).holderRun("run").holderToken(token).renewals(renewals).claimedAt(5L)
                .build();
        claim.setId("p:1");
        return claim;
    }

    @Test
    @DisplayName("The id of a page's claim is <player uuid>:<page number>")
    void idOf() {
        UUID owner = UUID.fromString("00000000-0000-0000-0000-000000000001");
        assertThat(RemoteBagEditClaim.idOf(owner, 3)).isEqualTo("00000000-0000-0000-0000-000000000001:3");
    }

    @Test
    @DisplayName("Builder, getters and setters carry every column")
    void fields() {
        RemoteBagEditClaim claim = claim("t", 4L);
        assertThat(claim.getPlayerUuid()).isEqualTo("p");
        assertThat(claim.getPageNumber()).isEqualTo(1);
        assertThat(claim.getHolderRun()).isEqualTo("run");
        assertThat(claim.getHolderToken()).isEqualTo("t");
        assertThat(claim.getRenewals()).isEqualTo(4L);
        assertThat(claim.getClaimedAt()).isEqualTo(5L);
        RemoteBagEditClaim empty = new RemoteBagEditClaim();
        empty.setPlayerUuid("q");
        empty.setPageNumber(2);
        empty.setHolderRun("r");
        empty.setHolderToken("");
        empty.setRenewals(9L);
        empty.setClaimedAt(1L);
        assertThat(empty.toString()).contains("q", "r", "9");
        assertThat(new RemoteBagEditClaim("a", 1, "b", "c", 2L, 3L).getHolderToken()).isEqualTo("c");
    }

    @Test
    @DisplayName("Equality is by value, field by field")
    void equality() {
        RemoteBagEditClaim a = claim("t", 4L);
        assertThat(a).isEqualTo(a).isEqualTo(claim("t", 4L)).hasSameHashCodeAs(claim("t", 4L));
        assertThat(a).isNotEqualTo(claim("u", 4L)).isNotEqualTo(claim("t", 5L)).isNotEqualTo(null).isNotEqualTo("p:1");
        RemoteBagEditClaim otherPage = claim("t", 4L);
        otherPage.setPageNumber(2);
        RemoteBagEditClaim otherRun = claim("t", 4L);
        otherRun.setHolderRun("x");
        RemoteBagEditClaim otherPlayer = claim("t", 4L);
        otherPlayer.setPlayerUuid("x");
        RemoteBagEditClaim otherTime = claim("t", 4L);
        otherTime.setClaimedAt(6L);
        RemoteBagEditClaim nulls = new RemoteBagEditClaim();
        assertThat(a).isNotEqualTo(otherPage).isNotEqualTo(otherRun).isNotEqualTo(otherPlayer).isNotEqualTo(otherTime)
                .isNotEqualTo(nulls);
        assertThat(nulls).isNotEqualTo(a).isEqualTo(new RemoteBagEditClaim());
        assertThat(nulls.hashCode()).isEqualTo(new RemoteBagEditClaim().hashCode());
        RemoteBagEditClaim nullToken = claim(null, 4L);
        assertThat(nullToken).isNotEqualTo(a);
        assertThat(a).isNotEqualTo(nullToken);
    }
}
