package com.ultikits.plugins.remotebag.service;

import com.ultikits.plugins.remotebag.config.RemoteBagConfig;
import com.ultikits.plugins.remotebag.entity.RemoteBagEditClaim;
import com.ultikits.plugins.remotebag.gui.RemoteBagContentGUI;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.Service;
import com.ultikits.ultitools.entities.WhereCondition;
import com.ultikits.ultitools.interfaces.DataOperator;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * The edit claims of bag pages, shared through the database by every server that uses it
 * (UltiKits/UltiRemoteBag#54; maintainer decisions of 2026-10-06: option A, then the route change that makes
 * the claim impossible to lose by accident).
 * <p>
 * When this server's {@link BagLockService} lets a session edit a page, the page is claimed here first; while
 * another server holds the claim, the page opens read-only.
 * <ul>
 *   <li><b>Renewal off the main thread.</b> This server renews the claims it holds on its own background task
 *       ({@link #startRenewals}), every third of {@code lock.timeout_seconds} of its monotonic clock. Each
 *       renewal adds one to the row's counter. The task needs neither the main thread nor any lock the main
 *       thread holds (the database pool has its own connections), so a stalled main thread does not stop it.
 *       It renews only claims of open editing sessions: closing the window, quitting and module disable release
 *       the claim and remove it from the task.</li>
 *   <li><b>Expiry by an observed counter, not by clocks.</b> A server that finds a page claimed by another
 *       remembers the counter it saw and its own {@code System.nanoTime}; it may take the claim over only after
 *       it has seen the same counter for a full {@code lock.timeout_seconds} on its own clock, and takes it with
 *       a conditional write on that counter -- so a holder that renewed in the meantime keeps it, and of two
 *       servers racing to take it over exactly one wins. No timestamp is compared between servers; the
 *       {@code claimed_at} wall time is diagnostic. A crashed holder renews nothing, so it is taken over one
 *       timeout after another server first saw it.</li>
 *   <li><b>A lost claim.</b> A renewal that finds the counter moved or the token changed means something
 *       outside this server changed the claim. The claim is dropped, one SEVERE line names the player and the
 *       page, and the open window is switched to read-only on the main thread, giving back the items put in since
 *       its last save ({@link RemoteBagContentGUI#claimLost}).</li>
 * </ul>
 * Every write is conditional on what was read (an insert under the page's id, which the table's primary key lets
 * only one server do, or {@code DataOperator#updateIf}), so a claim never overwrites another server's claim.
 * <p>
 * Remaining limits: a writer outside the module (an older module version during a rolling upgrade, another
 * plugin, an operator editing the table) can still change a page or a claim under an open window; and a server
 * process frozen as a whole (its background threads included) for longer than the timeout, or cut off from a
 * database another server still reaches, can have its claim taken over. On the JSON storage backend, which
 * belongs to one server, the claims change nothing.
 */
@Service
public class BagEditClaimService {

    /** What {@link #claim} achieved. */
    public enum Outcome {
        /** This session holds the page's claim. */
        CLAIMED,
        /** Another server holds the claim, and this server has not seen it unchanged for a full timeout. */
        HELD_ELSEWHERE,
        /** The claim could not be read or written; the cause is logged. */
        FAILED
    }

    /** Called on the main thread when one of this server's claims is found lost. */
    public interface ClaimLostListener {
        /**
         * @param holderUuid the player whose editing session held the claim
         * @param ownerUuid  the bag owner
         * @param page       the page number
         */
        void claimLost(UUID holderUuid, UUID ownerUuid, int page);
    }

    /** The claim timeout used only when no configuration is wired, in seconds. */
    private static final int DEFAULT_TIMEOUT_SECONDS = 300;

    @Autowired
    private UltiToolsPlugin plugin;

    @Autowired
    private RemoteBagConfig config;

    private DataOperator<RemoteBagEditClaim> claims;

    /** This server's run: a restarted server, or a re-enabled module, is a different holder. */
    private final String run = UUID.randomUUID().toString();

    /** Wall time, epoch milliseconds: written into {@code claimed_at} for people reading the table only. */
    private LongSupplier clock = System::currentTimeMillis;

    /** Monotonic time, nanoseconds: every duration this service measures. */
    private LongSupplier nanoTime = System::nanoTime;

    /** How the main thread is reached from the renewal task. */
    private Executor mainThread = BagEditClaimService::runOnMainThread;

    /** Who switches a window to read-only when its claim is lost. */
    private ClaimLostListener lostListener = RemoteBagContentGUI::claimLost;

    /** How often the background task looks for claims due for renewal, in milliseconds of real time. */
    private long renewTickMillis = 1000L;

    /** The background renewal task, or {@code null} when not started or stopped. */
    private volatile ScheduledExecutorService renewer;

    /** The claims this run holds, by claim id. */
    private final Map<String, Held> held = new ConcurrentHashMap<>();

    /** What this server last saw of other servers' claims, by claim id. */
    private final Map<String, Observation> observed = new ConcurrentHashMap<>();

    /** Claims this run held and found lost, by claim id, until the page is claimed again. */
    private final Set<String> lost = ConcurrentHashMap.newKeySet();

    /** One claim this run holds. */
    private static final class Held {
        private final UUID ownerUuid;
        private final int page;
        private final UUID holderUuid;
        private final String token;
        private volatile long renewals;
        private volatile long renewedAtNanos;
        private volatile boolean released;

        private Held(UUID ownerUuid, int page, UUID holderUuid, String token, long renewals, long renewedAtNanos) {
            this.ownerUuid = ownerUuid;
            this.page = page;
            this.holderUuid = holderUuid;
            this.token = token;
            this.renewals = renewals;
            this.renewedAtNanos = renewedAtNanos;
        }
    }

    /** Another server's claim as this server saw it, and since when (this server's monotonic clock). */
    private static final class Observation {
        private final long renewals;
        private final String token;
        private final long sinceNanos;

        private Observation(long renewals, String token, long sinceNanos) {
            this.renewals = renewals;
            this.token = token;
            this.sinceNanos = sinceNanos;
        }
    }

    /**
     * Obtains the claims table -- the framework creates it on first use if it does not exist -- and starts the
     * background renewal task.
     */
    public void init() {
        this.claims = plugin.getDataOperator(RemoteBagEditClaim.class);
        startRenewals();
    }

    /** Starts the background renewal task, once. */
    public synchronized void startRenewals() {
        if (renewer != null) {
            return;
        }
        ScheduledExecutorService task = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "UltiRemoteBag claim renewal");
            thread.setDaemon(true);
            return thread;
        });
        task.scheduleWithFixedDelay(this::renewDueSafely, renewTickMillis, renewTickMillis, TimeUnit.MILLISECONDS);
        renewer = task;
    }

    /**
     * Module disable: stops the background task, then releases every claim this run holds, so another server
     * can edit those pages at once and nothing renews them afterwards.
     */
    public void shutdown() {
        ScheduledExecutorService task;
        synchronized (this) {
            task = renewer;
            renewer = null;
        }
        if (task != null) {
            task.shutdownNow();
            try {
                task.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        releaseAllHeld();
    }

    private long timeoutMillis() {
        int seconds = config != null ? config.getLockTimeout() : DEFAULT_TIMEOUT_SECONDS;
        return seconds * 1000L;
    }

    private long timeoutNanos() {
        return TimeUnit.MILLISECONDS.toNanos(timeoutMillis());
    }

    /**
     * Claims a page for an editing session of {@code holderUuid} on this server.
     * <p>
     * No row: inserted with a new token and counter 0 (a failed insert means another server's insert won: its
     * row is read and decided on). A released row, or this run's own leftover (a token no session of this run
     * holds), or another server's claim this server has seen with the same counter for a full timeout of its own
     * clock: taken with {@code updateIf} on the token and counter read (a miss: read and decide once more). A row
     * this run holds for the page already: kept. Any other claim: refused, and remembered as observed now.
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
                RemoteBagEditClaim row = claims.getById(id);
                if (row == null) {
                    try {
                        claims.insert(claimRow(ownerUuid, page, token, 0L));
                    } catch (RuntimeException insertFailed) {
                        if (claims.getById(id) == null) {
                            throw insertFailed;
                        }
                        continue;
                    }
                    // The JSON backend ignores an insert under an id it already holds and returns normally:
                    // only reading the row back proves this server's claim is the stored one.
                    if (isMine(claims.getById(id), token)) {
                        hold(id, ownerUuid, page, holderUuid, token, 0L);
                        return Outcome.CLAIMED;
                    }
                    continue;
                }
                Held alreadyHeld = held.get(id);
                if (alreadyHeld != null) {
                    if (isMine(row, alreadyHeld.token)) {
                        return Outcome.CLAIMED;
                    }
                    // Taken over or changed since: this run no longer holds it.
                    held.remove(id, alreadyHeld);
                }
                if (!isTakeable(id, row)) {
                    return Outcome.HELD_ELSEWHERE;
                }
                long next = row.getRenewals() + 1;
                if (claims.updateIf(claimRow(ownerUuid, page, token, next), asRead(row))) {
                    hold(id, ownerUuid, page, holderUuid, token, next);
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
     * Whether another server holds the page now: a live claim of another run that this server has not yet seen
     * unchanged for a full timeout of its own clock. A storage failure answers true, so a caller that refuses to
     * change a claimed page refuses then too.
     *
     * @param ownerUuid the bag owner
     * @param page      the page number
     * @return true if the page is being edited on another server
     */
    public boolean isHeldElsewhere(UUID ownerUuid, int page) {
        String id = RemoteBagEditClaim.idOf(ownerUuid, page);
        try {
            RemoteBagEditClaim row = claims.getById(id);
            return row != null && isHeld(row) && !run.equals(row.getHolderRun()) && !observedUnchangedForTimeout(id, row);
        } catch (RuntimeException e) {
            log(e, plugin.i18n("log_bag_claim_failed"), ownerUuid, page);
            return true;
        }
    }

    /**
     * Whether this server held the page's claim for an editing session and has lost it: a renewal found it
     * changed, or the stored row no longer carries this session's token. A window asks before it saves.
     *
     * @param ownerUuid the bag owner
     * @param page      the page number
     * @return true if the claim was lost
     */
    public boolean isLost(UUID ownerUuid, int page) {
        String id = RemoteBagEditClaim.idOf(ownerUuid, page);
        if (lost.contains(id)) {
            return true;
        }
        Held claim = held.get(id);
        if (claim == null) {
            return false;
        }
        try {
            if (isMine(claims.getById(id), claim.token)) {
                return false;
            }
        } catch (RuntimeException e) {
            // Unknown: the save itself stays conditional on the page, so it overwrites nothing.
            log(e, plugin.i18n("log_bag_claim_failed"), ownerUuid, page);
            return false;
        }
        if (held.remove(id, claim)) {
            lost.add(id);
        }
        return true;
    }

    /**
     * Releases this run's claim on a page, if it holds one: the token is emptied with {@code updateIf} on this
     * session's own token, so a claim another server has taken since is left alone. The background task does not
     * renew it after this.
     *
     * @param ownerUuid the bag owner
     * @param page      the page number
     */
    public void release(UUID ownerUuid, int page) {
        String id = RemoteBagEditClaim.idOf(ownerUuid, page);
        lost.remove(id);
        Held claim = held.remove(id);
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

    /** Releases every claim this run holds. */
    public void releaseAllHeld() {
        for (Map.Entry<String, Held> entry : new ArrayList<>(held.entrySet())) {
            if (held.remove(entry.getKey(), entry.getValue())) {
                writeRelease(entry.getValue());
            }
        }
    }

    private void renewDueSafely() {
        try {
            renewDue();
        } catch (RuntimeException e) {
            // Never let one failure end the scheduled task; renewDue already logs per claim.
            if (plugin != null) {
                plugin.getLogger().warn(e, "UltiRemoteBag claim renewal");
            }
        }
    }

    /**
     * One renewal pass, run by the background task (never on the main thread): each claim this run holds whose
     * last renewal is a third of {@code lock.timeout_seconds} old on this server's monotonic clock gets its
     * counter raised by one, with {@code updateIf} on this session's token and the counter this server last
     * wrote. A miss means the counter moved or the token changed: the claim is lost (see the class comment).
     */
    public void renewDue() {
        if (claims == null) {
            return;
        }
        long now = nanoTime.getAsLong();
        long due = timeoutNanos() / 3;
        for (Map.Entry<String, Held> entry : new ArrayList<>(held.entrySet())) {
            Held claim = entry.getValue();
            if (claim.released || now - claim.renewedAtNanos < due) {
                continue;
            }
            long next = claim.renewals + 1;
            try {
                boolean renewed = claims.updateIf(claimRow(claim.ownerUuid, claim.page, claim.token, next),
                        WhereCondition.builder().column("holder_token").value(claim.token).build(),
                        WhereCondition.builder().column("holder_run").value(run).build(),
                        WhereCondition.builder().column("renewals").value(claim.renewals).build());
                if (renewed) {
                    claim.renewals = next;
                    claim.renewedAtNanos = now;
                } else if (!claim.released && held.remove(entry.getKey(), claim)) {
                    lost.add(entry.getKey());
                    mainThread.execute(() -> {
                        plugin.getLogger().error(fill(plugin.i18n("log_bag_claim_lost"), claim.ownerUuid, claim.page));
                        lostListener.claimLost(claim.holderUuid, claim.ownerUuid, claim.page);
                    });
                }
            } catch (RuntimeException e) {
                // Kept: the next pass tries again.
                log(e, plugin.i18n("log_bag_claim_failed"), claim.ownerUuid, claim.page);
            }
        }
    }

    private void writeRelease(Held claim) {
        claim.released = true;
        try {
            claims.updateIf(claimRow(claim.ownerUuid, claim.page, "", claim.renewals + 1),
                    WhereCondition.builder().column("holder_token").value(claim.token).build(),
                    WhereCondition.builder().column("holder_run").value(run).build());
        } catch (RuntimeException e) {
            // Another server then takes it over one timeout after it first sees it.
            log(e, plugin.i18n("log_bag_claim_release_failed"), claim.ownerUuid, claim.page);
        }
    }

    private void hold(String id, UUID ownerUuid, int page, UUID holderUuid, String token, long renewals) {
        observed.remove(id);
        lost.remove(id);
        held.put(id, new Held(ownerUuid, page, holderUuid, token, renewals, nanoTime.getAsLong()));
    }

    private boolean isMine(RemoteBagEditClaim row, String token) {
        return row != null && token.equals(row.getHolderToken()) && run.equals(row.getHolderRun());
    }

    private static boolean isHeld(RemoteBagEditClaim row) {
        return row.getHolderToken() != null && !row.getHolderToken().isEmpty();
    }

    /** Released, this run's own leftover, or another server's claim seen unchanged for a full timeout. */
    private boolean isTakeable(String id, RemoteBagEditClaim row) {
        if (!isHeld(row) || run.equals(row.getHolderRun())) {
            return true;
        }
        return observedUnchangedForTimeout(id, row);
    }

    /**
     * Whether this server has seen {@code row}'s claim with the same token and counter for a full
     * {@code lock.timeout_seconds} of its own monotonic clock; if this is the first time it sees that token and
     * counter, it starts counting now.
     */
    private boolean observedUnchangedForTimeout(String id, RemoteBagEditClaim row) {
        long now = nanoTime.getAsLong();
        Observation seen = observed.get(id);
        if (seen == null || seen.renewals != row.getRenewals() || !String.valueOf(seen.token).equals(String.valueOf(row.getHolderToken()))) {
            observed.put(id, new Observation(row.getRenewals(), row.getHolderToken(), now));
            return false;
        }
        return now - seen.sinceNanos >= timeoutNanos();
    }

    /** The token and counter {@code row} was read with; a column read as {@code null} cannot be compared. */
    private static WhereCondition[] asRead(RemoteBagEditClaim row) {
        List<WhereCondition> conditions = new ArrayList<>();
        conditions.add(WhereCondition.builder().column("renewals").value(row.getRenewals()).build());
        if (row.getHolderToken() != null) {
            conditions.add(WhereCondition.builder().column("holder_token").value(row.getHolderToken()).build());
        }
        return conditions.toArray(new WhereCondition[0]);
    }

    private RemoteBagEditClaim claimRow(UUID ownerUuid, int page, String token, long renewals) {
        RemoteBagEditClaim row = RemoteBagEditClaim.builder()
                .playerUuid(ownerUuid.toString())
                .pageNumber(page)
                .holderRun(run)
                .holderToken(token)
                .renewals(renewals)
                .claimedAt(clock.getAsLong())
                .build();
        row.setId(RemoteBagEditClaim.idOf(ownerUuid, page));
        return row;
    }

    private void log(RuntimeException e, String template, UUID ownerUuid, int page) {
        plugin.getLogger().warn(e, fill(template, ownerUuid, page));
    }

    /** A catalogue line with the page and the player filled in. */
    private static String fill(String template, UUID ownerUuid, int page) {
        return template
                .replace("{PAGE}", String.valueOf(page))
                // The player last: it is data, so a brace sequence inside it stays as written.
                .replace("{PLAYER}", nameOf(ownerUuid));
    }

    /** The player's name when it is known without a lookup, otherwise the UUID (safe off the main thread). */
    private static String nameOf(UUID playerUuid) {
        if (Bukkit.getServer() != null && Bukkit.isPrimaryThread()) {
            OfflinePlayer player = Bukkit.getOfflinePlayer(playerUuid);
            if (player != null && player.getName() != null) {
                return player.getName();
            }
        }
        return playerUuid.toString();
    }

    /** Hands {@code task} to the server's main thread; dropped while the framework is not enabled (stopping). */
    private static void runOnMainThread(Runnable task) {
        Plugin host = Bukkit.getServer() == null ? null : Bukkit.getPluginManager().getPlugin("UltiTools");
        if (host != null && host.isEnabled()) {
            Bukkit.getScheduler().runTask(host, task);
        }
    }
}
