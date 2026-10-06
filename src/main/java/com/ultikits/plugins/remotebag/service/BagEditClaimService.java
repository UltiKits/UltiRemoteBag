package com.ultikits.plugins.remotebag.service;

import com.ultikits.plugins.remotebag.config.RemoteBagConfig;
import com.ultikits.plugins.remotebag.entity.RemoteBagEditClaim;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.Scheduled;
import com.ultikits.ultitools.annotations.Service;
import com.ultikits.ultitools.entities.WhereCondition;
import com.ultikits.ultitools.interfaces.DataOperator;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * The edit claims of bag pages, shared through the database by every server that uses it
 * (UltiKits/UltiRemoteBag#54; maintainer decision of 2026-10-06, option A).
 * <p>
 * When this server's {@link BagLockService} lets a session edit a page, the page is claimed here first; while
 * another server holds a live claim, the page opens read-only. A claim expires {@code lock.timeout_seconds}
 * after it was taken or last renewed, so one left by a crash lets go by itself; this server renews the claims
 * it holds every third of that while their windows are open ({@link #renewDue}), and releases a claim when its
 * window closes, when its holder quits, and all of them when the module disables.
 * <p>
 * Every write is conditional on what was read -- an insert under the page's id, which the table's primary key
 * lets only one server do, or {@code DataOperator#updateIf} on the token, run and time read -- so a claim
 * never overwrites another server's claim.
 * <p>
 * Limits: expiry compares the holder's timestamp with this server's clock, so a difference between the two
 * servers' clocks shifts the expiry by that difference; a server that stalls for longer than the timeout can
 * lose its claim to another server while its window is open (one SEVERE line names the player and the page,
 * and that window's save stays conditional on what it read, so it overwrites nothing). On the JSON storage
 * backend, which belongs to one server, the claims change nothing.
 */
@Service
public class BagEditClaimService {

    /** What {@link #claim} achieved. */
    public enum Outcome {
        /** This session holds the page's claim. */
        CLAIMED,
        /** Another server holds a live claim on the page. */
        HELD_ELSEWHERE,
        /** The claim could not be read or written; the cause is logged. */
        FAILED
    }

    /** The claim lock's timeout used only when no configuration is wired, in seconds. */
    private static final int DEFAULT_TIMEOUT_SECONDS = 300;

    @Autowired
    private UltiToolsPlugin plugin;

    @Autowired
    private RemoteBagConfig config;

    private DataOperator<RemoteBagEditClaim> claims;

    /** This server's run: a restarted server, or a re-enabled module, is a different holder. */
    private final String run = UUID.randomUUID().toString();

    /** The time source, epoch milliseconds; a field so a test can move it. */
    private LongSupplier clock = System::currentTimeMillis;

    /** The claims this run holds, by claim id. */
    private final Map<String, Held> held = new ConcurrentHashMap<>();

    /** One claim this run holds. */
    private static final class Held {
        private final UUID ownerUuid;
        private final int page;
        private final UUID holderUuid;
        private final String token;
        private long renewedAt;

        private Held(UUID ownerUuid, int page, UUID holderUuid, String token, long renewedAt) {
            this.ownerUuid = ownerUuid;
            this.page = page;
            this.holderUuid = holderUuid;
            this.token = token;
            this.renewedAt = renewedAt;
        }
    }

    /** Obtains the claims table; the framework creates it on first use if it does not exist. */
    public void init() {
        this.claims = plugin.getDataOperator(RemoteBagEditClaim.class);
    }

    private long timeoutMillis() {
        int seconds = config != null ? config.getLockTimeout() : DEFAULT_TIMEOUT_SECONDS;
        return seconds * 1000L;
    }

    /**
     * Claims a page for an editing session of {@code holderUuid} on this server.
     * <p>
     * No row: inserted with a new token (a failed insert means another server's insert won: its row is read
     * and decided on). A released, expired, or this run's own leftover row: taken with {@code updateIf} on the
     * token, run and time read (a miss: read and decide once more). A row this run holds for the page already:
     * kept. A live claim of another server: refused.
     *
     * @param ownerUuid  the bag owner
     * @param page       the page number
     * @param holderUuid the player the editing session belongs to
     * @return what was achieved; never throws
     */
    public Outcome claim(UUID ownerUuid, int page, UUID holderUuid) {
        String id = RemoteBagEditClaim.idOf(ownerUuid, page);
        String token = UUID.randomUUID().toString();
        try {
            for (int attempt = 1; attempt <= 2; attempt++) {
                long now = clock.getAsLong();
                RemoteBagEditClaim row = claims.getById(id);
                if (row == null) {
                    RemoteBagEditClaim mine = claimRow(ownerUuid, page, token, now);
                    try {
                        claims.insert(mine);
                    } catch (RuntimeException insertFailed) {
                        if (claims.getById(id) == null) {
                            throw insertFailed;
                        }
                        continue;
                    }
                    // The JSON backend ignores an insert under an id it already holds and returns normally:
                    // only reading the row back proves this server's claim is the stored one.
                    if (isMine(claims.getById(id), token)) {
                        hold(id, ownerUuid, page, holderUuid, token, now);
                        return Outcome.CLAIMED;
                    }
                    continue;
                }
                Held alreadyHeld = held.get(id);
                if (alreadyHeld != null) {
                    if (isMine(row, alreadyHeld.token)) {
                        return Outcome.CLAIMED;
                    }
                    // Taken over by another server since: this run no longer holds it.
                    held.remove(id, alreadyHeld);
                }
                if (!isTakeable(row, now)) {
                    return Outcome.HELD_ELSEWHERE;
                }
                WhereCondition[] asRead = asRead(row);
                if (claims.updateIf(claimRow(ownerUuid, page, token, now), asRead)) {
                    hold(id, ownerUuid, page, holderUuid, token, now);
                    return Outcome.CLAIMED;
                }
            }
            return Outcome.HELD_ELSEWHERE;
        } catch (RuntimeException e) {
            log(e, plugin.i18n("log_bag_claim_failed"), ownerUuid, page);
            return Outcome.FAILED;
        }
    }

    /**
     * Whether another server holds a live claim on the page (a claim of this run is not "elsewhere"). A
     * storage failure answers true, so a caller that refuses to change a claimed page refuses then too.
     *
     * @param ownerUuid the bag owner
     * @param page      the page number
     * @return true if the page is being edited on another server
     */
    public boolean isHeldElsewhere(UUID ownerUuid, int page) {
        try {
            RemoteBagEditClaim row = claims.getById(RemoteBagEditClaim.idOf(ownerUuid, page));
            return row != null && !run.equals(row.getHolderRun()) && isLive(row, clock.getAsLong());
        } catch (RuntimeException e) {
            log(e, plugin.i18n("log_bag_claim_failed"), ownerUuid, page);
            return true;
        }
    }

    /**
     * Releases this run's claim on a page, if it holds one: the token is emptied with {@code updateIf} on this
     * run's own token, so a claim another server has taken since is left alone.
     *
     * @param ownerUuid the bag owner
     * @param page      the page number
     */
    public void release(UUID ownerUuid, int page) {
        Held claim = held.remove(RemoteBagEditClaim.idOf(ownerUuid, page));
        if (claim != null) {
            writeRelease(claim);
        }
    }

    /**
     * Releases every claim this run holds for editing sessions of {@code holderUuid} (they quit).
     *
     * @param holderUuid the player
     */
    public void releaseHeldBy(UUID holderUuid) {
        for (Map.Entry<String, Held> entry : new ArrayList<>(held.entrySet())) {
            if (entry.getValue().holderUuid.equals(holderUuid) && held.remove(entry.getKey(), entry.getValue())) {
                writeRelease(entry.getValue());
            }
        }
    }

    /** Releases every claim this run holds (the module disables). */
    public void releaseAllHeld() {
        for (Map.Entry<String, Held> entry : new ArrayList<>(held.entrySet())) {
            if (held.remove(entry.getKey(), entry.getValue())) {
                writeRelease(entry.getValue());
            }
        }
    }

    /**
     * Renews each claim this run holds whose last renewal is a third of {@code lock.timeout_seconds} old, with
     * {@code updateIf} on this run's token, so a long editing session is not taken over. A renewal that misses
     * means another server took the claim after it expired (this server stalled past the timeout): the claim
     * is dropped and one SEVERE line names the player and the page. Runs every second on the main thread,
     * where claims are also taken and released.
     */
    @Scheduled(delay = 20, period = 20, async = false)
    public void renewDue() {
        if (claims == null) {
            return;
        }
        long now = clock.getAsLong();
        long due = timeoutMillis() / 3;
        List<Map.Entry<String, Held>> entries = new ArrayList<>(held.entrySet());
        for (Map.Entry<String, Held> entry : entries) {
            Held claim = entry.getValue();
            if (now - claim.renewedAt < due) {
                continue;
            }
            try {
                boolean renewed = claims.updateIf(claimRow(claim.ownerUuid, claim.page, claim.token, now),
                        WhereCondition.builder().column("holder_token").value(claim.token).build(),
                        WhereCondition.builder().column("holder_run").value(run).build());
                if (renewed) {
                    claim.renewedAt = now;
                } else if (held.remove(entry.getKey(), claim)) {
                    plugin.getLogger().error(fill(plugin.i18n("log_bag_claim_lost"), claim.ownerUuid, claim.page));
                }
            } catch (RuntimeException e) {
                // Kept: the next tick tries again, and the claim expires by itself if storage stays down.
                log(e, plugin.i18n("log_bag_claim_failed"), claim.ownerUuid, claim.page);
            }
        }
    }

    private void writeRelease(Held claim) {
        try {
            claims.updateIf(releasedRow(claim),
                    WhereCondition.builder().column("holder_token").value(claim.token).build(),
                    WhereCondition.builder().column("holder_run").value(run).build());
        } catch (RuntimeException e) {
            // The claim then expires after lock.timeout_seconds.
            log(e, plugin.i18n("log_bag_claim_release_failed"), claim.ownerUuid, claim.page);
        }
    }

    private void hold(String id, UUID ownerUuid, int page, UUID holderUuid, String token, long now) {
        held.put(id, new Held(ownerUuid, page, holderUuid, token, now));
    }

    private boolean isMine(RemoteBagEditClaim row, String token) {
        return row != null && token.equals(row.getHolderToken()) && run.equals(row.getHolderRun());
    }

    /** Released, expired, or this run's own leftover (a token no session of this run holds). */
    private boolean isTakeable(RemoteBagEditClaim row, long now) {
        if (!isLive(row, now)) {
            return true;
        }
        return run.equals(row.getHolderRun());
    }

    private boolean isLive(RemoteBagEditClaim row, long now) {
        String token = row.getHolderToken();
        return token != null && !token.isEmpty() && now - row.getClaimedAt() < timeoutMillis();
    }

    /** The token, run and time {@code row} was read with; a column read as {@code null} cannot be compared. */
    private static WhereCondition[] asRead(RemoteBagEditClaim row) {
        List<WhereCondition> conditions = new ArrayList<>();
        conditions.add(WhereCondition.builder().column("claimed_at").value(row.getClaimedAt()).build());
        if (row.getHolderToken() != null) {
            conditions.add(WhereCondition.builder().column("holder_token").value(row.getHolderToken()).build());
        }
        if (row.getHolderRun() != null) {
            conditions.add(WhereCondition.builder().column("holder_run").value(row.getHolderRun()).build());
        }
        return conditions.toArray(new WhereCondition[0]);
    }

    private RemoteBagEditClaim claimRow(UUID ownerUuid, int page, String token, long now) {
        RemoteBagEditClaim row = RemoteBagEditClaim.builder()
                .playerUuid(ownerUuid.toString())
                .pageNumber(page)
                .holderRun(run)
                .holderToken(token)
                .claimedAt(now)
                .build();
        row.setId(RemoteBagEditClaim.idOf(ownerUuid, page));
        return row;
    }

    private RemoteBagEditClaim releasedRow(Held claim) {
        return claimRow(claim.ownerUuid, claim.page, "", clock.getAsLong());
    }

    private void log(RuntimeException e, String template, UUID ownerUuid, int page) {
        plugin.getLogger().warn(e, fill(template, ownerUuid, page));
    }

    /** A catalogue line with the page and the player's name filled in. */
    private static String fill(String template, UUID ownerUuid, int page) {
        return template
                .replace("{PAGE}", String.valueOf(page))
                // The player's name last: it is data, so a brace sequence inside it stays as written.
                .replace("{PLAYER}", nameOf(ownerUuid));
    }

    private static String nameOf(UUID playerUuid) {
        if (Bukkit.getServer() != null) {
            OfflinePlayer player = Bukkit.getOfflinePlayer(playerUuid);
            if (player != null && player.getName() != null) {
                return player.getName();
            }
        }
        return playerUuid.toString();
    }
}
