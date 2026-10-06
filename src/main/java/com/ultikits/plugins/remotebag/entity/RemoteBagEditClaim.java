package com.ultikits.plugins.remotebag.entity;

import com.ultikits.ultitools.abstracts.data.BaseDataEntity;
import com.ultikits.ultitools.annotations.Column;
import com.ultikits.ultitools.annotations.Table;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * The edit claim of one bag page, shared by every server that uses the same database
 * (UltiKits/UltiRemoteBag#54; maintainer decision of 2026-10-06, option A).
 * <p>
 * One row per page that has ever been claimed, in its own table: the framework creates a table with
 * {@code CREATE TABLE IF NOT EXISTS} and never adds a column to an existing one, and a page save writes the
 * whole {@code remote_bags} row, so the claim cannot live there. The id is {@code <player uuid>:<page number>},
 * set before insert, so the table's primary key lets only one of two servers' first claims of a page be
 * stored. A row whose {@link #holderToken} is empty is released. A held claim has expired only for an observer
 * that has seen the same {@link #renewals} value for a full {@code lock.timeout_seconds} on its own monotonic
 * clock; no timestamp is compared between servers ({@link #claimedAt} is for people reading the table). A page
 * with no row is unclaimed, which is every page of a database written by an earlier version: nothing is
 * migrated.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table("remote_bag_claims")
public class RemoteBagEditClaim extends BaseDataEntity<String> {

    /** The bag owner's UUID. */
    @Column("player_uuid")
    private String playerUuid;

    /** The page number. */
    @Column("page_number")
    private int pageNumber;

    /** The holding server's run: an identifier generated each time the module is enabled. */
    @Column("holder_run")
    private String holderRun;

    /** The holding editing session's token; empty when released. */
    @Column("holder_token")
    private String holderToken;

    /**
     * How many times the claim has been taken or renewed: every renewal adds one, so a holder that is alive
     * keeps it moving, and every takeover is a conditional write on the value the taker observed.
     */
    @Column(value = "renewals", type = "BIGINT")
    private long renewals;

    /**
     * When the claim was taken or last renewed, in epoch milliseconds of the holder's wall clock. Diagnostic
     * only: expiry never compares it with another server's clock.
     */
    @Column(value = "claimed_at", type = "BIGINT")
    private long claimedAt;

    /**
     * The id of a page's claim row.
     *
     * @param ownerUuid the bag owner
     * @param page      the page number
     * @return {@code <player uuid>:<page number>}
     */
    public static String idOf(UUID ownerUuid, int page) {
        return ownerUuid + ":" + page;
    }
}
