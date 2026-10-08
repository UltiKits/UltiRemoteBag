package com.ultikits.plugins.remotebag.service;

import com.ultikits.plugins.remotebag.config.RemoteBagConfig;
import com.ultikits.plugins.remotebag.entity.RemoteBagEditClaim;
import com.ultikits.plugins.remotebag.gui.RemoteBagContentGUI;
import com.ultikits.plugins.remotebag.util.ItemReturns;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.Service;
import com.ultikits.ultitools.entities.WhereCondition;
import com.ultikits.ultitools.interfaces.DataOperator;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * The edit claims of bag pages, shared through the database by every server that uses it, and the page writes of
 * the windows that hold them (UltiKits/UltiRemoteBag#54; maintainer decisions of 2026-10-06: option A, the route
 * change that makes the claim impossible to lose by accident, and the answers to gate 1 of plan 17-84).
 * <p>
 * When this server's {@link BagLockService} lets a session edit a page, the page is claimed here first; while
 * another server holds the claim, the page opens read-only.
 * <ul>
 *   <li><b>Renewal off the main thread.</b> This server renews the claims it holds on its own background task
 *       ({@link #startRenewals}), every third of {@code lock.timeout_seconds} of its monotonic clock. Each
 *       renewal adds one to the row's counter. The task needs neither the main thread nor any lock the main
 *       thread holds (the database pool has its own connections), so a stalled main thread does not stop it.
 *       Each claim's renewal runs as its own storage call, so one call that never returns holds up no other
 *       claim.</li>
 *   <li><b>Expiry by an observed counter, not by clocks.</b> A server that finds a page claimed by another
 *       remembers the counter it saw and its own {@code System.nanoTime}; it may take the claim over only after
 *       it has seen the same counter for a full {@code lock.timeout_seconds} on its own clock, and takes it with
 *       a conditional write on that counter -- so a holder that renewed in the meantime keeps it, and of two
 *       servers racing to take it over exactly one wins. No timestamp is compared between servers; the
 *       {@code claimed_at} wall time is diagnostic. A crashed holder renews nothing, so it is taken over one
 *       timeout after another server first saw it.</li>
 *   <li><b>Every storage call has three outcomes, and each has its answer.</b> Every call this service makes --
 *       claim, renewal, page write, release -- runs on the storage pool and is waited for at most a deadline
 *       shorter than a third of the timeout ({@link #backgroundDeadlineMillis}; on the main thread at most two
 *       seconds, {@link #mainThreadDeadlineMillis}). It either answers (done or refused), throws, or does not
 *       return in time; a call that does not return keeps running and its late answer is still used.
 *       <ul>
 *         <li>A renewal refused because the row carries another token: the claim is <b>lost</b>. The open window
 *             turns read-only and gives back the items put in since its last save
 *             ({@link RemoteBagContentGUI#claimLost}).</li>
 *         <li>A renewal that throws or does not return: the claim is <b>unconfirmed</b>. The window turns
 *             read-only at once ({@link RemoteBagContentGUI#claimTroubled}) and what it shows becomes a kept
 *             write; nothing is given back, because that write can still land. The claim is kept and renewed
 *             again; the next renewal first reads the row, so a renewal that landed although it reported an
 *             error is recognised. A window also turns read-only by itself when its claim has not been confirmed
 *             for longer than the renewal schedule allows ({@link #editState}), so a renewal task that is itself
 *             stuck cannot leave it editable.</li>
 *         <li>A page write refused because the stored page is not what the window read: the items put in are
 *             given back and the claim is released.</li>
 *         <li>A page write that throws or does not return: a <b>kept write</b>. The claim is kept and renewed,
 *             the content stays in memory and the same conditional write is retried in the background until it
 *             lands ({@link #renewDue}); meanwhile the page is read-only on every server, and a reopen on this
 *             server shows the kept content. A kept write is attempted only while its claim is confirmed.</li>
 *       </ul></li>
 *   <li><b>Every page save is fenced on the claim</b> (maintainer decision of 2026-10-06, gate 1 round 2): one
 *       database transaction raises the claim's counter conditionally on this session's token and the counter this
 *       run last confirmed, then writes the page conditionally on what the window read, and records the save as
 *       landed in the session's record row ({@link #fencedWrite}). On SQLite and MySQL a save therefore lands only
 *       while the claim is this session's, however late it runs. A save that threw or did not answer may have
 *       committed: whether it did is read from that record row, never guessed -- by the retry and by the abandon
 *       after a lost claim, both ordered after the save's transaction by the claim row's lock, and by the abandon at
 *       disable inside a transaction that first locks the claim row ({@link #startDecision}), so it waits for a
 *       save still running on the database. A save that cannot be decided in time is not given back, and logged.</li>
 *   <li><b>Release.</b> Closing the window, quitting and module disable end the session; its claim is released
 *       then, or, while a kept write is pending, as soon as that write lands or is refused. Module disable flushes
 *       kept writes for a bounded time and logs, with their items, the ones that still cannot land.</li>
 * </ul>
 * Every write is conditional on what was read (an insert under the page's id, which the table's primary key lets
 * only one server do, or {@code DataOperator#updateIf}), so a claim never overwrites another server's claim, and a
 * page write never overwrites a page somebody else changed.
 * <p>
 * Remaining limits: a writer outside the module (an older module version during a rolling upgrade, another
 * plugin, an operator editing the table) can still change a page or a claim under an open window; a server cut
 * off for a full timeout from a database another server still reaches loses its claim, and the items its window
 * had taken out since its last save and before it turned read-only (up to about half the timeout after the cut) stay
 * in the stored page as well; a save the database still runs when the module stops, undecided within the stop's
 * deadline, is not given back and is logged; a server crash loses a kept write, as it loses an
 * unsaved window, and items owed to a player who left (logged when owed). On the JSON storage backend, which
 * belongs to one server, the claims change nothing and the fence is not atomic with the page write (JSON has no
 * transaction across two tables).
 */
@Service
public class BagEditClaimService {

    /** What {@link #claim} achieved. */
    public enum Outcome {
        /** This session holds the page's claim. */
        CLAIMED,
        /** Another server holds the claim, and this server has not seen it unchanged for a full timeout. */
        HELD_ELSEWHERE,
        /** This server is still writing the page's last changes (a kept write); the page opens read-only. */
        SAVE_PENDING,
        /** The claim could not be read or written in time; the cause is logged. */
        FAILED
    }

    /** What an open editing window may do now ({@link #editState}). */
    public enum EditState {
        /** Edit: the claim is held and confirmed. */
        EDITABLE,
        /** The claim was lost: read-only, and the items put in go back. */
        LOST,
        /** The claim is not confirmed: read-only, and what the window shows is kept and written later. */
        UNCONFIRMED
    }

    /** What {@link #saveWindow} achieved. */
    public enum SaveOutcome {
        /** Written; {@link SaveResult#getWritten} is the page as now stored. */
        WRITTEN,
        /** Refused: the stored page is not what the window read; the caller gives back the items put in. */
        REFUSED,
        /** Kept: the database did not answer; the write is retried in the background. */
        KEPT,
        /** The claim was lost: nothing is written; the caller gives back the items put in. */
        LOST
    }

    /** A save's outcome and, when written, the page as stored. */
    public static final class SaveResult {
        private final SaveOutcome outcome;
        private final RemoteBagService.PageRead written;

        private SaveResult(SaveOutcome outcome, RemoteBagService.PageRead written) {
            this.outcome = outcome;
            this.written = written;
        }

        public SaveOutcome getOutcome() {
            return outcome;
        }

        /** The page as stored after a {@link SaveOutcome#WRITTEN} save, otherwise {@code null}. */
        public RemoteBagService.PageRead getWritten() {
            return written;
        }
    }

    /** Called on the main thread when one of this server's claims is found lost. */
    public interface ClaimLostListener {
        /**
         * @param holderUuid the player whose editing session held the claim
         * @param ownerUuid  the bag owner
         * @param page       the page number
         * @param token      the lost claim's session token: only the window holding that claim acts on it
         */
        void claimLost(UUID holderUuid, UUID ownerUuid, int page, String token);
    }

    /** Called on the main thread when one of this server's claims could not be confirmed (once per claim). */
    public interface ClaimTroubleListener {
        /**
         * @param holderUuid the player whose editing session holds the claim
         * @param ownerUuid  the bag owner
         * @param page       the page number
         * @param token      the claim's session token: only the window holding that claim acts on it
         */
        void claimTroubled(UUID holderUuid, UUID ownerUuid, int page, String token);
    }

    /** Gives items back to a player on the main thread. */
    public interface ItemReturner {
        /**
         * @return {@code false} if the player is not on this server (nothing was given)
         */
        boolean giveBack(UUID holderUuid, UUID ownerUuid, int page, List<ItemStack> items);
    }

    /** The claim timeout used only when no configuration is wired, in seconds. */
    private static final int DEFAULT_TIMEOUT_SECONDS = 300;

    /** The longest a storage call keeps the main thread waiting, in milliseconds. */
    private static final long MAIN_THREAD_DEADLINE_CAP_MILLIS = 2_000L;

    /** How long module disable keeps trying to write kept writes, in milliseconds. */
    private static final long FINAL_FLUSH_MILLIS = 5_000L;

    @Autowired
    private UltiToolsPlugin plugin;

    @Autowired
    private RemoteBagConfig config;

    @Autowired
    private RemoteBagService bagService;

    private DataOperator<RemoteBagEditClaim> claims;

    /** This server's run: a restarted server, or a re-enabled module, is a different holder. */
    private final String run = UUID.randomUUID().toString();

    /** Wall time, epoch milliseconds: written into {@code claimed_at} for people reading the table only. */
    private LongSupplier clock = System::currentTimeMillis;

    /** Monotonic time, nanoseconds: every duration this service measures. */
    private LongSupplier nanoTime = System::nanoTime;

    /** How the main thread is reached from the background. */
    private Executor mainThread = BagEditClaimService::runOnMainThread;

    /** Who switches a window to read-only when its claim is lost. */
    private ClaimLostListener lostListener = RemoteBagContentGUI::claimLost;

    /** Who switches a window to read-only when its claim cannot be confirmed. */
    private ClaimTroubleListener troubleListener = RemoteBagContentGUI::claimTroubled;

    /** Who gives items back to a player. */
    private ItemReturner returner = this::giveBackToPlayer;

    /** How often the background task runs, in milliseconds of real time. */
    private long renewTickMillis = 1000L;

    /** When positive, the deadline of every storage call, in milliseconds (tests); otherwise derived. */
    private long callDeadlineMillis;

    /** The background task, or {@code null} when not started or stopped. */
    private volatile ScheduledExecutorService renewer;

    /** The pool every storage call runs on; created on first use. */
    private volatile ExecutorService storage;

    /** Set while the module disables: main-thread work runs at once, on the disabling thread. */
    private volatile boolean stopping;

    /** The claims this run holds, by claim id. */
    private final Map<String, Held> held = new ConcurrentHashMap<>();

    /** What this server last saw of other servers' claims, by claim id. */
    private final Map<String, Observation> observed = new ConcurrentHashMap<>();

    /**
     * Claims this run held and found lost: page id -> the lost claim's session token, until the page is claimed again.
     * A page-level entry, but it names its session: it counts only for that session (lostFor), so a background result
     * about an earlier session never reaches a later session of the same page (gate 2 top-up).
     */
    private final Map<String, String> lost = new ConcurrentHashMap<>();

    /** Releases settled during module disable, made together with the final releases (P3-1). */
    private final List<Held> stopReleases = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Kept writes: page writes the database did not answer, by claim id. */
    private final Map<String, Kept> kept = new ConcurrentHashMap<>();

    /** Items a refused kept write owes a player who was not on this server, by player. */
    private final Map<UUID, List<Owed>> owed = new ConcurrentHashMap<>();

    /**
     * Give-backs not made yet: made by the main thread, and at disable by the disabling thread, so one the scheduler
     * drops (it drops tasks once the framework is disabled at server stop) is still made.
     */
    private final java.util.Queue<Return> returns = new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** One claim this run holds. */
    private static final class Held {
        private final UUID ownerUuid;
        private final int page;
        private final UUID holderUuid;
        private final String token;
        private volatile long renewals;
        /** When the last confirmed renewal (or the claim) started, on this server's monotonic clock. */
        private volatile long confirmedAtNanos;
        /** The last renewal threw or did not return: the next one reads the row first. */
        private volatile boolean unconfirmed;
        /** The window was told that the claim is not confirmed (once). */
        private volatile boolean troubled;
        /** The editing session ended while a kept write was pending: released once it settles. */
        private volatile boolean sessionEnded;
        private volatile boolean released;
        private volatile Future<Renewal> inFlight;
        /** When the running renewal was started, in real time ({@code System.nanoTime}). */
        private volatile long inFlightSinceRealNanos;

        private Held(UUID ownerUuid, int page, UUID holderUuid, String token, long renewals, long confirmedAtNanos) {
            this.ownerUuid = ownerUuid;
            this.page = page;
            this.holderUuid = holderUuid;
            this.token = token;
            this.renewals = renewals;
            this.confirmedAtNanos = confirmedAtNanos;
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

    /** A page write the database did not answer, retried until it lands or is refused. */
    private static final class Kept {
        private final String id;
        /** The editing session's claim token: the key of the row that records which of its saves landed. */
        private final String token;
        /** Every attempt made of this write, by attempt id. */
        private final Set<String> attempts = new java.util.concurrent.CopyOnWriteArraySet<>();
        /** An attempt threw or did not answer: it may have landed. */
        private volatile boolean uncertain;
        /** A read of the session's receipt still running (R3-4: one at a time). */
        private volatile Future<Boolean> receiptRead;
        private final UUID holderUuid;
        private final UUID ownerUuid;
        private final int page;
        private final ItemStack[] items;
        private final String contents;
        private final RemoteBagService.PageRead base;
        private volatile Future<WriteOutcome> inFlight;
        /** The claim was lost: no new attempt; settled (given back unless landed) once no attempt is running. */
        private volatile boolean abandoned;

        private Kept(String id, String token, UUID holderUuid, UUID ownerUuid, int page, ItemStack[] items,
                     String contents, RemoteBagService.PageRead base) {
            this.id = id;
            this.token = token;
            this.holderUuid = holderUuid;
            this.ownerUuid = ownerUuid;
            this.page = page;
            this.items = items;
            this.contents = contents;
            this.base = base;
        }
    }

    /** What one fenced page write found. */
    private enum WriteOutcome {
        /** The page now holds the write's content: written by this attempt, or recorded as written by an earlier one. */
        LANDED,
        /** The claim was this session's, but the stored page is not what the window read: nothing written. */
        REFUSED,
        /** The claim is no longer this session's: nothing written. */
        LOST
    }

    /** A fenced write's outcome and the claim's counter after it ({@code -1}: not moved). */
    private static final class Fenced {
        private final WriteOutcome outcome;
        private final long counter;

        private Fenced(WriteOutcome outcome, long counter) {
            this.outcome = outcome;
            this.counter = counter;
        }
    }

    /** Items to give back to a player, made on the main thread (or at disable, on the disabling thread). */
    private static final class Return {
        private final UUID holderUuid;
        private final UUID ownerUuid;
        private final int page;
        private final List<ItemStack> items;

        private Return(UUID holderUuid, UUID ownerUuid, int page, List<ItemStack> items) {
            this.holderUuid = holderUuid;
            this.ownerUuid = ownerUuid;
            this.page = page;
            this.items = items;
        }
    }

    /** Items owed to a player who was away when their kept write was refused. */
    private static final class Owed {
        private final UUID ownerUuid;
        private final int page;
        private final List<ItemStack> items;

        private Owed(UUID ownerUuid, int page, List<ItemStack> items) {
            this.ownerUuid = ownerUuid;
            this.page = page;
            this.items = items;
        }
    }

    /** What one renewal call found. */
    private static final class Renewal {
        private final boolean lost;
        private final long renewals;
        private final long startedAtNanos;

        private Renewal(boolean lost, long renewals, long startedAtNanos) {
            this.lost = lost;
            this.renewals = renewals;
            this.startedAtNanos = startedAtNanos;
        }
    }

    /** What one claim call decided (made on the storage pool; acted on by the caller). */
    private static final class ClaimDecision {
        private final Outcome outcome;
        private final long renewals;
        private final boolean alreadyMine;

        private ClaimDecision(Outcome outcome, long renewals, boolean alreadyMine) {
            this.outcome = outcome;
            this.renewals = renewals;
            this.alreadyMine = alreadyMine;
        }
    }

    // ==================== Lifecycle ====================

    /**
     * Obtains the claims table -- the framework creates it on first use if it does not exist -- and starts the
     * background task.
     */
    public void init() {
        this.claims = plugin.getDataOperator(RemoteBagEditClaim.class);
        startRenewals();
    }

    /** Starts the background task, once. */
    public synchronized void startRenewals() {
        if (renewer != null) {
            return;
        }
        stopping = false;
        ScheduledExecutorService task = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "UltiRemoteBag claim renewal");
            thread.setDaemon(true);
            return thread;
        });
        task.scheduleWithFixedDelay(this::renewDueSafely, renewTickMillis, renewTickMillis, TimeUnit.MILLISECONDS);
        renewer = task;
    }

    /**
     * Module disable: stops the background task, writes the kept writes for a bounded time, logs (with their
     * items) the ones that still cannot land and gives their put-in items back to players still online, releases
     * every claim this run holds, so another server can edit those pages at once, and nothing renews them
     * afterwards. Runs on the disabling (main) thread.
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
        // Only now: the background task has ended, so from here every piece of main-thread work runs on this, the
        // disabling (main) thread, and none on the task's thread (gate 1 round 2, R2-5).
        stopping = true;
        flushKept(FINAL_FLUSH_MILLIS);
        // One deadline for every record read below, however many writes are left.
        long recordsUntil = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(mainThreadDeadlineMillis());
        // Every decision is started before any is waited for (R4-3): one held up behind a save still running on the
        // database does not use up the deadline of the others.
        List<Kept> left = new ArrayList<>(kept.values());
        Map<Kept, Future<Boolean>> decisions = new LinkedHashMap<>();
        for (Kept write : left) {
            if (needsDecision(write)) {
                decisions.put(write, startDecision(write));
            }
        }
        for (Kept write : left) {
            abandonAtDisable(write, decisions.get(write), recordsUntil);
        }
        // Every give-back still queued -- including one the scheduler dropped because the framework was already
        // disabled -- is made here, synchronously (R2-3); a player who is not online is logged as owed.
        drainReturns();
        for (Map.Entry<UUID, List<Owed>> entry : owed.entrySet()) {
            for (Owed items : entry.getValue()) {
                plugin.getLogger().error(plugin.i18n("log_bag_items_owed_dropped")
                        .replace("{PAGE}", String.valueOf(items.page))
                        .replace("{OWNER}", nameOf(items.ownerUuid))
                        .replace("{ITEMS}", ItemReturns.describe(items.items))
                        .replace("{PLAYER}", nameOf(entry.getKey())));
            }
        }
        owed.clear();
        releaseAllHeld();
        ExecutorService pool;
        synchronized (this) {
            pool = storage;
            storage = null;
        }
        if (pool != null) {
            // A call that never returned is left to the JDBC driver; nothing waits for it.
            pool.shutdownNow();
        }
    }

    private long timeoutMillis() {
        int seconds = config != null ? config.getLockTimeout() : DEFAULT_TIMEOUT_SECONDS;
        return seconds * 1000L;
    }

    private long timeoutNanos() {
        return TimeUnit.MILLISECONDS.toNanos(timeoutMillis());
    }

    /** The deadline of a background storage call: a sixth of the timeout, well under a third of it. */
    private long backgroundDeadlineMillis() {
        return callDeadlineMillis > 0 ? callDeadlineMillis : timeoutMillis() / 6;
    }

    /** The deadline of a storage call the main thread waits for: as the background's, at most two seconds. */
    private long mainThreadDeadlineMillis() {
        return callDeadlineMillis > 0 ? callDeadlineMillis : Math.min(timeoutMillis() / 6, MAIN_THREAD_DEADLINE_CAP_MILLIS);
    }

    /**
     * How long a claim counts as confirmed after its last confirmed renewal started: the renewal is due after a
     * third of the timeout, may start up to one task tick later and is given a sixth of the timeout to answer. Past
     * this, half the timeout and a tick, the claim is unconfirmed; another server needs a full timeout to take it.
     */
    private long confirmedForNanos() {
        return timeoutNanos() / 2 + TimeUnit.MILLISECONDS.toNanos(renewTickMillis);
    }

    private boolean isConfirmed(Held claim, long now) {
        return !claim.unconfirmed && now - claim.confirmedAtNanos < confirmedForNanos();
    }

    private synchronized ExecutorService storage() {
        if (storage == null) {
            AtomicInteger number = new AtomicInteger();
            storage = Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "UltiRemoteBag claim renewal and page save #" + number.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            });
        }
        return storage;
    }

    private <T> Future<T> submit(Callable<T> call) {
        return storage().submit(call);
    }

    /** Main-thread work: queued for the main thread, or run at once while the module disables. */
    private void onMainThread(Runnable work) {
        if (stopping) {
            work.run();
        } else {
            mainThread.execute(work);
        }
    }

    // ==================== Claim ====================

    /**
     * Claims a page for an editing session of {@code holderUuid} on this server.
     * <p>
     * No row: inserted with a new token and counter 0 (a failed insert means another server's insert won: its
     * row is read and decided on). A released row, or this run's own leftover (a token no session of this run
     * holds), or another server's claim this server has seen with the same counter for a full timeout of its own
     * clock: taken with {@code updateIf} on the token and counter read (a miss: read and decide once more). A row
     * this run holds for the page already: kept. Any other claim: refused, and remembered as observed now. A page
     * with a kept write: refused ({@link Outcome#SAVE_PENDING}).
     *
     * @param ownerUuid  the bag owner
     * @param page       the page number
     * @param holderUuid the player the editing session belongs to
     * @return what was achieved; never throws
     */
    public Outcome claim(UUID ownerUuid, int page, UUID holderUuid) {
        String id = RemoteBagEditClaim.idOf(ownerUuid, page);
        if (kept.containsKey(id)) {
            return Outcome.SAVE_PENDING;
        }
        String token = UUID.randomUUID().toString();
        long started = nanoTime.getAsLong();
        try {
            ClaimDecision decision = submit(() -> decideClaim(id, ownerUuid, page, token, holderUuid))
                    .get(mainThreadDeadlineMillis(), TimeUnit.MILLISECONDS);
            if (decision.outcome == Outcome.CLAIMED && !decision.alreadyMine) {
                hold(id, ownerUuid, page, holderUuid, token, decision.renewals, started);
            }
            return decision.outcome;
        } catch (ExecutionException e) {
            log(e.getCause(), plugin.i18n("log_bag_claim_failed"), ownerUuid, page);
        } catch (TimeoutException e) {
            // A claim that lands later is this run's leftover: no session renews it, and another server takes it
            // over one timeout after it first sees it.
            log(e, plugin.i18n("log_bag_claim_failed"), ownerUuid, page);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return Outcome.FAILED;
    }

    private ClaimDecision decideClaim(String id, UUID ownerUuid, int page, String token, UUID holderUuid) {
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
                    return new ClaimDecision(Outcome.CLAIMED, 0L, false);
                }
                continue;
            }
            Held alreadyHeld = held.get(id);
            if (alreadyHeld != null) {
                if (isMine(row, alreadyHeld.token)) {
                    if (!alreadyHeld.holderUuid.equals(holderUuid)) {
                        // This run holds the page for another player's session: that session's claim is not
                        // handed over (gate 2 top-up sweep; was F5).
                        return new ClaimDecision(Outcome.HELD_ELSEWHERE, 0L, false);
                    }
                    return new ClaimDecision(Outcome.CLAIMED, row.getRenewals(), true);
                }
                // Taken over or changed since: that session's claim is lost (named by its token).
                claimLost(alreadyHeld);
            }
            if (!isTakeable(id, row)) {
                return new ClaimDecision(Outcome.HELD_ELSEWHERE, 0L, false);
            }
            long next = row.getRenewals() + 1;
            if (claims.updateIf(claimRow(ownerUuid, page, token, next), asRead(row))) {
                return new ClaimDecision(Outcome.CLAIMED, next, false);
            }
        }
        return new ClaimDecision(Outcome.HELD_ELSEWHERE, 0L, false);
    }

    /**
     * Whether another server holds the page now: a live claim of another run that this server has not yet seen
     * unchanged for a full timeout of its own clock. A storage failure, or no answer in time, answers true, so a
     * caller that refuses to change a claimed page refuses then too.
     *
     * @param ownerUuid the bag owner
     * @param page      the page number
     * @return true if the page is being edited on another server
     */
    public boolean isHeldElsewhere(UUID ownerUuid, int page) {
        String id = RemoteBagEditClaim.idOf(ownerUuid, page);
        try {
            return submit(() -> {
                RemoteBagEditClaim row = claims.getById(id);
                return row != null && isHeld(row) && !run.equals(row.getHolderRun()) && !observedUnchangedForTimeout(id, row);
            }).get(mainThreadDeadlineMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            log(e.getCause(), plugin.i18n("log_bag_claim_failed"), ownerUuid, page);
        } catch (TimeoutException e) {
            log(e, plugin.i18n("log_bag_claim_failed"), ownerUuid, page);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return true;
    }

    /**
     * Whether this server held the page's claim for an editing session and has lost it (a renewal found another
     * token in the row). Read from memory only.
     *
     * @param ownerUuid the bag owner
     * @param page      the page number
     * @return true if the claim was lost
     */
    public boolean isLost(UUID ownerUuid, int page) {
        return lostFor(RemoteBagEditClaim.idOf(ownerUuid, page), null);
    }

    /**
     * Whether the page's claim was lost for the session with {@code token}; with no token, for the session this run
     * holds the page for now, or -- when it holds none -- for the last session that held it.
     */
    private boolean lostFor(String id, String token) {
        String lostToken = lost.get(id);
        if (lostToken == null) {
            return false;
        }
        if (token != null) {
            return token.equals(lostToken);
        }
        Held current = held.get(id);
        return current == null || current.token.equals(lostToken);
    }

    /** Whether a kept write of the page belongs to the session with {@code token}. */
    private boolean keptFor(String id, String token) {
        Kept write = kept.get(id);
        return write != null && write.token.equals(token);
    }

    /**
     * The claim this run holds for the page for the session with {@code token}; with no token, whatever it holds;
     * {@code null} if it holds none, or holds it for another session.
     */
    private Held heldFor(String id, String token) {
        Held claim = held.get(id);
        return claim == null || token == null || claim.token.equals(token) ? claim : null;
    }

    /**
     * What an editing window of the page may do now; asked before every edit click and every save, from memory
     * only. A claim whose last confirmed renewal is older than the renewal schedule allows is unconfirmed even if
     * no renewal has reported anything (a renewal task that is itself stuck); it is marked so, and the background
     * does not tell the window a second time.
     *
     * @param ownerUuid the bag owner
     * @param page      the page number
     * @return the state; {@link EditState#EDITABLE} when this run holds no claim for the page and has not lost one
     */
    public EditState editState(UUID ownerUuid, int page) {
        return editState(ownerUuid, page, null);
    }

    /**
     * {@link #editState(UUID, int)} for the window of the session with {@code token} (recorded when it opened): a
     * lost claim or a claim of another session of the same page is not this window's.
     *
     * @param token the window's claim token, or {@code null} if it recorded none
     */
    public EditState editState(UUID ownerUuid, int page, String token) {
        String id = RemoteBagEditClaim.idOf(ownerUuid, page);
        if (lostFor(id, token)) {
            return EditState.LOST;
        }
        Held claim = held.get(id);
        if (claim == null) {
            return EditState.EDITABLE;
        }
        if (token != null && !claim.token.equals(token)) {
            // The page is held for another session: this window's claim is gone.
            return EditState.LOST;
        }
        if (claim.troubled || !isConfirmed(claim, nanoTime.getAsLong())) {
            claim.troubled = true;
            return EditState.UNCONFIRMED;
        }
        return EditState.EDITABLE;
    }

    // ==================== Page writes ====================

    /**
     * Saves a window's page, conditionally on what it read, and answers what happened (main thread). The write is
     * attempted only while the claim is confirmed; it is waited for at most {@link #mainThreadDeadlineMillis}.
     * Refused: the caller gives back the items put in. Thrown or no answer in time (or an unconfirmed claim): the
     * write is kept, retried in the background and the caller turns the window read-only; nothing is given back.
     * Lost claim: nothing is written.
     *
     * @param holderUuid the player whose window it is
     * @param ownerUuid  the bag owner
     * @param page       the page number
     * @param items      what the window shows
     * @param base       what the window read, or what its last save wrote
     * @return the outcome
     */
    public SaveResult saveWindow(UUID holderUuid, UUID ownerUuid, int page, ItemStack[] items,
                                 RemoteBagService.PageRead base) {
        return saveWindow(holderUuid, ownerUuid, page, items, base, null);
    }

    /**
     * {@link #saveWindow(UUID, UUID, int, ItemStack[], RemoteBagService.PageRead)} for the window of the session with
     * {@code token}: only that session's claim fences the write.
     *
     * @param token the window's claim token, or {@code null} if it recorded none
     */
    public SaveResult saveWindow(UUID holderUuid, UUID ownerUuid, int page, ItemStack[] liveItems,
                                 RemoteBagService.PageRead base, String token) {
        // Copied here, on the main thread, from the window's live stacks: nothing off this thread reads them (P3-3).
        ItemStack[] items = ItemReturns.deepCopy(liveItems);
        if (claims == null) {
            // No claims table (the service was never started): a plain conditional save.
            RemoteBagService.PageRead written = bagService.savePage(ownerUuid, page, items, base);
            return new SaveResult(written != null ? SaveOutcome.WRITTEN : SaveOutcome.REFUSED, written);
        }
        String id = RemoteBagEditClaim.idOf(ownerUuid, page);
        Held claim = heldFor(id, token);
        if (lostFor(id, token) || claim == null) {
            // No claim of this session: every editing window claims at open and releases at close, so only a lost
            // claim gets here.
            return new SaveResult(SaveOutcome.LOST, null);
        }
        String contents = bagService.serializePage(items);
        if (claim.troubled || !isConfirmed(claim, nanoTime.getAsLong())) {
            claim.troubled = true;
            keep(id, claim.token, holderUuid, ownerUuid, page, items, contents, base, null, null);
            return new SaveResult(SaveOutcome.KEPT, null);
        }
        String attempt = UUID.randomUUID().toString();
        Future<WriteOutcome> write = submit(() -> fencedWrite(claim, ownerUuid, page, items, contents, base, attempt,
                java.util.Collections.<String>emptySet()));
        try {
            switch (write.get(mainThreadDeadlineMillis(), TimeUnit.MILLISECONDS)) {
                case LANDED:
                    return new SaveResult(SaveOutcome.WRITTEN, bagService.storedRead(items, contents));
                case LOST:
                    claimLost(claim);
                    return new SaveResult(SaveOutcome.LOST, null);
                default:
                    plugin.getLogger().error(plugin.i18n("log_bag_update_failed"));
                    return new SaveResult(SaveOutcome.REFUSED, null);
            }
        } catch (ExecutionException e) {
            // It may have committed before the error reached this server: kept, and decided by its record.
            keep(id, claim.token, holderUuid, ownerUuid, page, items, contents, base, attempt, null);
            logKept(e.getCause(), holderUuid, ownerUuid, page);
        } catch (TimeoutException e) {
            keep(id, claim.token, holderUuid, ownerUuid, page, items, contents, base, attempt, write);
            logKept(e, holderUuid, ownerUuid, page);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            keep(id, claim.token, holderUuid, ownerUuid, page, items, contents, base, attempt, write);
        }
        return new SaveResult(SaveOutcome.KEPT, null);
    }

    /**
     * Keeps what a window shows as a write to make once the claim is confirmed again (main thread): the window's
     * claim could not be confirmed, or the module is disabling with the window open.
     *
     * @return false if the claim was lost (nothing is kept; the caller gives back the items put in)
     */
    public boolean keepWindow(UUID holderUuid, UUID ownerUuid, int page, ItemStack[] items,
                              RemoteBagService.PageRead base) {
        return keepWindow(holderUuid, ownerUuid, page, items, base, null);
    }

    /**
     * {@link #keepWindow(UUID, UUID, int, ItemStack[], RemoteBagService.PageRead)} for the window of the session with
     * {@code token}.
     *
     * @param token the window's claim token, or {@code null} if it recorded none
     */
    public boolean keepWindow(UUID holderUuid, UUID ownerUuid, int page, ItemStack[] items,
                              RemoteBagService.PageRead base, String token) {
        String id = RemoteBagEditClaim.idOf(ownerUuid, page);
        Held claim = heldFor(id, token);
        if (lostFor(id, token) || claim == null) {
            return false;
        }
        keep(id, claim.token, holderUuid, ownerUuid, page, items, bagService.serializePage(items), base, null, null);
        return true;
    }

    /**
     * The kept content of a page, for a window that shows it while its write is pending; {@code null} if none.
     *
     * @param ownerUuid the bag owner
     * @param page      the page number
     * @return a copy of the kept items, or {@code null}
     */
    /**
     * Whether a save of this page is kept on this server (main thread; memory only).
     *
     * @param ownerUuid the bag owner
     * @param page      the page number
     * @return true while the page's last changes are still being written
     */
    public boolean hasKeptWrite(UUID ownerUuid, int page) {
        return kept.containsKey(RemoteBagEditClaim.idOf(ownerUuid, page));
    }

    /**
     * Whether any save of this player's bag is kept on this server (memory only).
     *
     * @param ownerUuid the bag owner
     * @return true while any page of the bag is still being written
     */
    public boolean hasKeptWriteOf(UUID ownerUuid) {
        for (Kept write : kept.values()) {
            if (write.ownerUuid.equals(ownerUuid)) {
                return true;
            }
        }
        return false;
    }

    public ItemStack[] keptItems(UUID ownerUuid, int page) {
        Kept write = kept.get(RemoteBagEditClaim.idOf(ownerUuid, page));
        return write == null ? null : ItemReturns.deepCopy(write.items);
    }

    /**
     * Keeps a write. {@code attempt}: an attempt already made whose outcome is not known (it threw or did not answer
     * in time); {@code running}: that attempt, if it is still running.
     */
    private void keep(String id, String token, UUID holderUuid, UUID ownerUuid, int page, ItemStack[] items,
                      String contents, RemoteBagService.PageRead base, String attempt, Future<WriteOutcome> running) {
        // Detached from the window's live stacks (gate 2 Codex P1).
        Kept write = new Kept(id, token, holderUuid, ownerUuid, page, ItemReturns.deepCopy(items), contents, base);
        if (attempt != null) {
            write.attempts.add(attempt);
            write.uncertain = true;
        }
        write.inFlight = running;
        kept.put(id, write);
        if (lostFor(id, token)) {
            // The claim was found lost between this caller's check and the put (R2-6): the background settles it.
            write.abandoned = true;
        }
    }

    private void logKept(Throwable cause, UUID holderUuid, UUID ownerUuid, int page) {
        plugin.getLogger().warn(cause, plugin.i18n("log_bag_save_pending")
                .replace("{PAGE}", String.valueOf(page))
                .replace("{OWNER}", nameOf(ownerUuid))
                .replace("{PLAYER}", nameOf(holderUuid)));
    }

    /**
     * One page save, fenced on the claim (storage pool; maintainer decision of 2026-10-06, gate 1 round 2 R2-2). One
     * database transaction -- the page operator's, which the framework shares with the claims operator of the same
     * module (one {@code DataSourceTransactionManager} per module, a thread-bound connection that both operators'
     * {@code TransactionAwareDataSource} hand out) -- first raises the claim's counter with {@code updateIf} on this
     * session's token, this run and the counter this run last confirmed, then writes the page conditionally on what
     * the window read, and records the attempt as landed in the session's record row. The fence is a write, so it
     * takes the claim row's lock (InnoDB row lock; SQLite's write lock): another server's takeover, which is
     * conditional on the counter it saw, waits for this transaction and then misses, or committed before it and the
     * fence misses. Either the claim is this session's when the page is written, or nothing is written.
     * <p>
     * <b>The counter checked.</b> The counter this run last confirmed ({@code Held#renewals}). A claim's renewal and
     * its fenced saves run under the same lock ({@code synchronized} on the claim), so neither moves the counter while
     * the other runs, and each records the new value before it lets go. A renewal or save that threw may have moved
     * it under this session's token anyway: then the row is read, and with this session's token the counter read is
     * fenced instead -- only another token, or a counter that moves again inside this transaction, means lost.
     * <p>
     * <b>An earlier attempt that may have landed</b> ({@code earlier}, those that threw or did not answer): the
     * session's record row says whether one of them committed; if so, nothing is written again.
     */
    private WriteOutcome fencedWrite(Held claim, UUID ownerUuid, int page, ItemStack[] items, String contents,
                                     RemoteBagService.PageRead base, String attempt, Set<String> earlier) throws Exception {
        synchronized (claim) {
            long started = nanoTime.getAsLong();
            Fenced result;
            try {
                result = bagService.inPageTransaction(() ->
                        fenceAndWrite(claim, ownerUuid, page, items, contents, base, attempt, earlier));
            } catch (Exception e) {
                // Unknown whether it committed: the counter may have moved under this session's token, so the next
                // renewal reads the row first.
                claim.unconfirmed = true;
                throw e;
            }
            if (result.counter >= 0) {
                claim.renewals = Math.max(claim.renewals, result.counter);
                claim.confirmedAtNanos = started;
                claim.unconfirmed = false;
            }
            if (result.outcome == WriteOutcome.LANDED) {
                // Committed: only now may the display cache show it (R3-3).
                bagService.rememberWritten(ownerUuid, page, items);
            }
            return result.outcome;
        }
    }

    private Fenced fenceAndWrite(Held claim, UUID ownerUuid, int page, ItemStack[] items, String contents,
                                 RemoteBagService.PageRead base, String attempt, Set<String> earlier) {
        String id = RemoteBagEditClaim.idOf(ownerUuid, page);
        long counter = claim.renewals;
        if (!fence(claim, counter)) {
            RemoteBagEditClaim row = claims.getById(id);
            if (!isMine(row, claim.token)) {
                return new Fenced(WriteOutcome.LOST, -1L);
            }
            counter = row.getRenewals();
            if (!fence(claim, counter)) {
                return new Fenced(WriteOutcome.LOST, -1L);
            }
        }
        long fenced = counter + 1;
        if (!earlier.isEmpty() && landedEarlier(claim.token, earlier)) {
            return new Fenced(WriteOutcome.LANDED, fenced);
        }
        if (bagService.writePage(ownerUuid, page, items, contents, base) == null) {
            return new Fenced(WriteOutcome.REFUSED, fenced);
        }
        recordLanded(claim.token, ownerUuid, page, attempt);
        return new Fenced(WriteOutcome.LANDED, fenced);
    }

    /** Raises the claim's counter from {@code counter}, conditionally on this session's token, this run and it. */
    private boolean fence(Held claim, long counter) {
        return claims.updateIf(claimRow(claim.ownerUuid, claim.page, claim.token, counter + 1),
                WhereCondition.builder().column("holder_token").value(claim.token).build(),
                WhereCondition.builder().column("holder_run").value(run).build(),
                WhereCondition.builder().column("renewals").value(counter).build());
    }

    /**
     * The session's record row: a row of the claims table under the session's token (a claim row's id is
     * {@code <player uuid>:<page>}, never a bare token), holding the id of its last save that committed. Written in the
     * save's own transaction, so it says exactly whether that save landed; another server's takeover of the page does
     * not touch it. Deleted when the session's claim is released.
     */
    private void recordLanded(String token, UUID ownerUuid, int page, String attempt) {
        RemoteBagEditClaim record = RemoteBagEditClaim.builder()
                .playerUuid(ownerUuid.toString())
                .pageNumber(page)
                .holderRun(run)
                .holderToken(attempt)
                .renewals(0L)
                .claimedAt(clock.getAsLong())
                .build();
        record.setId(token);
        if (claims.getById(token) == null) {
            claims.insert(record);
        } else {
            claims.updateCounted(record);
        }
    }

    /** Whether the session's record row names one of {@code attempts} as its last save that committed. */
    private boolean landedEarlier(String token, Set<String> attempts) {
        RemoteBagEditClaim record = claims.getById(token);
        return record != null && attempts.contains(record.getHolderToken());
    }

    /** Deletes the session's record row, if there is one (storage pool). */
    private void forgetRecord(String token) {
        if (claims.getById(token) != null) {
            claims.delById(token);
        }
    }

    // ==================== Release ====================

    /**
     * Ends this run's editing session of a page: its claim is released with {@code updateIf} on this session's own
     * token, so a claim another server has taken since is left alone -- at once, or, while a kept write of the page
     * is pending, as soon as it lands or is refused. The background task does not renew it after that.
     *
     * @param ownerUuid the bag owner
     * @param page      the page number
     */
    public void release(UUID ownerUuid, int page) {
        release(ownerUuid, page, null);
    }

    /**
     * {@link #release(UUID, int)} for the session with {@code token}: a claim or a lost-mark of another session of the
     * page is left alone.
     *
     * @param token the session's claim token, or {@code null} for whatever this run holds for the page
     */
    public void release(UUID ownerUuid, int page, String token) {
        String id = RemoteBagEditClaim.idOf(ownerUuid, page);
        if (token == null) {
            lost.remove(id);
        } else {
            lost.remove(id, token);
        }
        Held claim = heldFor(id, token);
        if (claim != null) {
            endSession(id, claim);
        }
    }

    /**
     * Ends every editing session of {@code holderUuid} on this run (they quit); see {@link #release}.
     *
     * @param holderUuid the player
     */
    public void releaseHeldBy(UUID holderUuid) {
        for (Map.Entry<String, Held> entry : new ArrayList<>(held.entrySet())) {
            if (entry.getValue().holderUuid.equals(holderUuid)) {
                endSession(entry.getKey(), entry.getValue());
            }
        }
    }

    /** Releases every claim this run holds, kept writes or not (module disable, after its final flush). */
    public void releaseAllHeld() {
        List<Held> releasing = new ArrayList<>(stopReleases);
        stopReleases.clear();
        for (Map.Entry<String, Held> entry : new ArrayList<>(held.entrySet())) {
            if (held.remove(entry.getKey(), entry.getValue())) {
                releasing.add(entry.getValue());
            }
        }
        writeReleases(releasing);
    }

    private void endSession(String id, Held claim) {
        // Set before looking for a kept write; the background looks at this after removing one, so one of the two
        // always sees the other (UltiKits/UltiRemoteBag#54).
        claim.sessionEnded = true;
        if (!keptFor(id, claim.token) && held.remove(id, claim)) {
            writeReleases(java.util.Collections.singletonList(claim));
        }
    }

    /**
     * Releases a claim on the storage pool without waiting for it (the background pass): a release that does not
     * answer only keeps the claim until it answers or another server takes it over after a timeout. Its failure is
     * logged by the call itself.
     */
    private void releaseWithoutWaiting(Held claim) {
        if (claims == null) {
            return;
        }
        claim.released = true;
        submit(() -> {
            try {
                claims.updateIf(claimRow(claim.ownerUuid, claim.page, "", claim.renewals + 1),
                        WhereCondition.builder().column("holder_token").value(claim.token).build(),
                        WhereCondition.builder().column("holder_run").value(run).build());
            } catch (RuntimeException e) {
                log(e, plugin.i18n("log_bag_claim_release_failed"), claim.ownerUuid, claim.page);
            } finally {
                try {
                    forgetRecord(claim.token);
                } catch (RuntimeException ignored) {
                    // Best effort: a receipt nobody reads takes up one row.
                }
            }
            return null;
        });
    }

    /**
     * The token of the claim this run holds for a page, or {@code null}: what an editing window records when it
     * opens, so a notice about another session's claim of the same page does not touch it.
     *
     * @param ownerUuid the bag owner
     * @param page      the page number
     * @return the session token, or {@code null}
     */
    public String tokenOf(UUID ownerUuid, int page) {
        Held claim = held.get(RemoteBagEditClaim.idOf(ownerUuid, page));
        return claim == null ? null : claim.token;
    }

    /** Releases claims in parallel, waiting for all of them at most one main-thread deadline. */
    private void writeReleases(List<Held> releasing) {
        if (releasing.isEmpty() || claims == null) {
            return;
        }
        Map<Held, Future<Boolean>> writes = new LinkedHashMap<>();
        for (Held claim : releasing) {
            claim.released = true;
            writes.put(claim, submit(() -> {
                try {
                    return claims.updateIf(claimRow(claim.ownerUuid, claim.page, "", claim.renewals + 1),
                            WhereCondition.builder().column("holder_token").value(claim.token).build(),
                            WhereCondition.builder().column("holder_run").value(run).build());
                } finally {
                    // No save of the session is pending any more, so its receipt has nothing left to tell -- deleted
                    // even when the release itself failed (R3-2).
                    try {
                        forgetRecord(claim.token);
                    } catch (RuntimeException ignored) {
                        // Best effort: a receipt nobody reads takes up one row.
                    }
                }
            }));
        }
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(mainThreadDeadlineMillis());
        for (Map.Entry<Held, Future<Boolean>> write : writes.entrySet()) {
            Held claim = write.getKey();
            try {
                write.getValue().get(Math.max(0L, until - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (ExecutionException e) {
                // Another server then takes it over one timeout after it first sees it.
                log(e.getCause(), plugin.i18n("log_bag_claim_release_failed"), claim.ownerUuid, claim.page);
            } catch (TimeoutException e) {
                log(e, plugin.i18n("log_bag_claim_release_failed"), claim.ownerUuid, claim.page);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    // ==================== The background pass ====================

    private void renewDueSafely() {
        try {
            renewDue();
        } catch (RuntimeException e) {
            // Never let one failure end the scheduled task; each step logs its own failures.
            if (plugin != null) {
                plugin.getLogger().warn(e, "UltiRemoteBag claim renewal");
            }
        }
    }

    /**
     * One background pass (never on the main thread): renews each claim whose last renewal is a third of
     * {@code lock.timeout_seconds} old on this server's monotonic clock (or whose last renewal failed), each as its
     * own storage call, all waited for together at most {@link #backgroundDeadlineMillis}; then attempts each kept
     * write whose claim is confirmed, the same way. See the class comment for what each outcome does.
     */
    public void renewDue() {
        if (claims == null) {
            return;
        }
        // Waits at most one task tick: a call that runs longer is looked at again on the next pass, so one hung call
        // never delays the renewal of another claim by more than a tick.
        pass(Math.min(backgroundDeadlineMillis(), renewTickMillis));
    }

    private void pass(long waitMillis) {
        renewClaims(waitMillis);
        attemptKeptWrites(waitMillis);
    }

    private void renewClaims(long waitMillis) {
        long now = nanoTime.getAsLong();
        long due = timeoutNanos() / 3;
        Map<Held, Future<Renewal>> started = new LinkedHashMap<>();
        for (Held claim : new ArrayList<>(held.values())) {
            if (claim.released) {
                continue;
            }
            Future<Renewal> running = claim.inFlight;
            if (running != null) {
                if (!running.isDone()) {
                    unconfirmedIfOverdue(claim);
                    continue;
                }
                claim.inFlight = null;
                settleRenewal(claim, running);
                if (claim.released || !held.containsValue(claim)) {
                    continue;
                }
            }
            if (claim.unconfirmed || now - claim.confirmedAtNanos >= due) {
                claim.inFlightSinceRealNanos = System.nanoTime();
                Future<Renewal> renewal = submit(() -> renewOnce(claim));
                claim.inFlight = renewal;
                started.put(claim, renewal);
            }
        }
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMillis);
        for (Map.Entry<Held, Future<Renewal>> entry : started.entrySet()) {
            Held claim = entry.getKey();
            try {
                entry.getValue().get(Math.max(0L, until - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                // Still running: its answer is used when it comes; past its deadline the claim is unconfirmed.
                unconfirmedIfOverdue(claim);
                continue;
            } catch (ExecutionException e) {
                // Settled below.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            claim.inFlight = null;
            settleRenewal(claim, entry.getValue());
        }
    }

    /** A renewal still running past its deadline leaves the claim unconfirmed, and the window is told (once). */
    private void unconfirmedIfOverdue(Held claim) {
        long running = System.nanoTime() - claim.inFlightSinceRealNanos;
        if (!claim.unconfirmed && running >= TimeUnit.MILLISECONDS.toNanos(backgroundDeadlineMillis())) {
            claim.unconfirmed = true;
            log(new TimeoutException("no answer within " + backgroundDeadlineMillis() + " ms"),
                    plugin.i18n("log_bag_claim_failed"), claim.ownerUuid, claim.page);
            trouble(claim);
        }
    }

    private void settleRenewal(Held claim, Future<Renewal> done) {
        try {
            Renewal renewal = done.get();
            if (renewal.lost) {
                claimLost(claim);
            } else {
                claim.renewals = Math.max(claim.renewals, renewal.renewals);
                claim.confirmedAtNanos = Math.max(claim.confirmedAtNanos, renewal.startedAtNanos);
                claim.unconfirmed = false;
            }
        } catch (ExecutionException e) {
            claim.unconfirmed = true;
            log(e.getCause(), plugin.i18n("log_bag_claim_failed"), claim.ownerUuid, claim.page);
            trouble(claim);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * One renewal (storage pool): raises the counter with {@code updateIf} on this session's token, this run and the
     * counter last known. After a renewal that failed, or on a miss, the row is read first: another token means
     * lost; this session's token with another counter means an earlier renewal of this session landed late, and the
     * counter read is used.
     */
    private Renewal renewOnce(Held claim) {
        // Never at the same time as a fenced save of the same claim: the counter each of them checks is the one the
        // other last recorded (see fencedWrite).
        synchronized (claim) {
            Renewal renewal = renewLocked(claim);
            if (!renewal.lost) {
                claim.renewals = Math.max(claim.renewals, renewal.renewals);
            }
            return renewal;
        }
    }

    private Renewal renewLocked(Held claim) {
        long started = nanoTime.getAsLong();
        String id = RemoteBagEditClaim.idOf(claim.ownerUuid, claim.page);
        long counter = claim.renewals;
        if (claim.unconfirmed) {
            RemoteBagEditClaim row = claims.getById(id);
            if (!isMine(row, claim.token)) {
                return new Renewal(true, counter, started);
            }
            counter = row.getRenewals();
        }
        for (int attempt = 1; attempt <= 2; attempt++) {
            if (claims.updateIf(claimRow(claim.ownerUuid, claim.page, claim.token, counter + 1),
                    WhereCondition.builder().column("holder_token").value(claim.token).build(),
                    WhereCondition.builder().column("holder_run").value(run).build(),
                    WhereCondition.builder().column("renewals").value(counter).build())) {
                return new Renewal(false, counter + 1, started);
            }
            RemoteBagEditClaim row = claims.getById(id);
            if (!isMine(row, claim.token)) {
                return new Renewal(true, counter, started);
            }
            counter = row.getRenewals();
        }
        // This session's token, and the counter moved under every attempt: only a writer outside the module.
        return new Renewal(true, counter, started);
    }

    /**
     * The claim is lost: marked lost first, dropped, the window told; a kept write of the page is abandoned. The
     * background pass settles an abandoned write ({@link #attemptKeptWrites}), never this caller: an attempt may still
     * be running, and one that threw must be decided by the session's record row (a storage call).
     */
    private void claimLost(Held claim) {
        String id = RemoteBagEditClaim.idOf(claim.ownerUuid, claim.page);
        // Names the lost session: a later session of the page, which holds another token, is not lost (top-up P1).
        lost.put(id, claim.token);
        if (held.remove(id, claim)) {
            claim.released = true;
            onMainThread(() -> {
                plugin.getLogger().error(fill(plugin.i18n("log_bag_claim_lost"), claim.ownerUuid, claim.page));
                // The notice names the lost claim's token: a window of a later session of the page holds another
                // token and is left alone (gate 2 Codex run 2).
                lostListener.claimLost(claim.holderUuid, claim.ownerUuid, claim.page, claim.token);
            });
        }
        Kept write = kept.get(id);
        if (write != null && write.token.equals(claim.token)) {
            write.abandoned = true;
        } else if (write == null && claims != null && storage != null) {
            // No save of the session is pending, so its receipt has nothing left to tell (R3-2; best effort).
            submit(() -> {
                forgetRecord(claim.token);
                return null;
            });
        }
    }

    private void trouble(Held claim) {
        if (!claim.troubled) {
            claim.troubled = true;
            onMainThread(() -> troubleListener.claimTroubled(claim.holderUuid, claim.ownerUuid, claim.page, claim.token));
        }
    }

    private void attemptKeptWrites(long waitMillis) {
        long now = nanoTime.getAsLong();
        // One deadline for everything this pass waits on: attempts and receipt reads alike (gate 2 Codex P2).
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMillis);
        Map<Kept, Future<WriteOutcome>> started = new LinkedHashMap<>();
        List<Kept> abandonedNow = new ArrayList<>();
        for (Kept write : new ArrayList<>(kept.values())) {
            Future<WriteOutcome> running = write.inFlight;
            if (running != null) {
                if (!running.isDone()) {
                    // In-flight guard (R2-4): an attempt that can still land is never given back, abandoned or not.
                    continue;
                }
                write.inFlight = null;
                if (settleKept(write, running)) {
                    continue;
                }
            }
            if (write.abandoned) {
                abandonedNow.add(write);
                continue;
            }
            Held claim = heldFor(write.id, write.token);
            if (claim == null) {
                // No claim of this write's session left to fence it on: settled as abandoned.
                write.abandoned = true;
                abandonedNow.add(write);
                continue;
            }
            if (!isConfirmed(claim, now)) {
                // Attempted only while the claim is confirmed; the fence decides the rest.
                continue;
            }
            String attempt = UUID.randomUUID().toString();
            Set<String> earlier = new java.util.HashSet<>(write.attempts);
            write.attempts.add(attempt);
            Future<WriteOutcome> next = submit(() -> fencedWrite(claim, write.ownerUuid, write.page, write.items,
                    write.contents, write.base, attempt, earlier));
            write.inFlight = next;
            started.put(write, next);
        }
        // Every receipt read is started before any is waited for, then each waits only for what is left.
        for (Kept write : abandonedNow) {
            if (write.uncertain && write.receiptRead == null) {
                write.receiptRead = submit(() -> landedEarlier(write.token, write.attempts));
            }
        }
        for (Kept write : abandonedNow) {
            settleAbandoned(write, Math.max(0L, TimeUnit.NANOSECONDS.toMillis(until - System.nanoTime())));
        }
        for (Map.Entry<Kept, Future<WriteOutcome>> entry : started.entrySet()) {
            Kept write = entry.getKey();
            try {
                entry.getValue().get(Math.max(0L, until - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                write.uncertain = true;
                continue;
            } catch (ExecutionException e) {
                // Settled below.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            write.inFlight = null;
            if (!settleKept(write, entry.getValue()) && write.abandoned) {
                settleAbandoned(write, Math.max(0L, TimeUnit.NANOSECONDS.toMillis(until - System.nanoTime())));
            }
        }
    }

    /**
     * Acts on a finished attempt of a kept write.
     *
     * @return true if the kept write is settled (landed, or refused and given back)
     */
    private boolean settleKept(Kept write, Future<WriteOutcome> done) {
        try {
            switch (done.get()) {
                case LANDED:
                    logLanded(write);
                    finishKept(write);
                    return true;
                case REFUSED:
                    // Definitive: the claim was this session's and the stored page is not what the window read.
                    plugin.getLogger().error(plugin.i18n("log_bag_update_failed"));
                    giveBackPutIns(write);
                    finishKept(write);
                    return true;
                default:
                    // LOST: nothing written by this attempt; an earlier one may have landed -- settled as abandoned.
                    Held claim = heldFor(write.id, write.token);
                    if (claim != null) {
                        claimLost(claim);
                    }
                    write.abandoned = true;
                    return false;
            }
        } catch (ExecutionException e) {
            // It may have committed before the error reached this server (R2-1): decided by the record row later.
            write.uncertain = true;
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * A kept write whose claim is gone, with no attempt running. If no attempt is in doubt, nothing was written: the
     * put-in items go back. If one is (it threw or did not answer -- R2-1, the consumers being this abandon after a lost
     * claim, the abandon at disable, and the retry in {@link #fenceAndWrite}), the session's record row decides: an
     * attempt it names landed, so nothing goes back; otherwise none did, and the put-in items go back. A record that
     * cannot be read now leaves the write for the next pass.
     */
    private void settleAbandoned(Kept write, long waitMillis) {
        if (write.uncertain) {
            Boolean landed = recordSays(write, waitMillis);
            if (landed == null) {
                return;
            }
            if (landed) {
                logLanded(write);
                finishKept(write);
                return;
            }
        }
        giveBackPutIns(write);
        finishKept(write);
    }

    /** What the session's record row says of {@code write}'s attempts; {@code null} if it cannot be read in time. */
    private Boolean recordSays(Kept write, long waitMillis) {
        // In-flight guard (R3-4): a read still running is waited for again, never started a second time.
        Future<Boolean> read = write.receiptRead;
        if (read == null) {
            read = submit(() -> landedEarlier(write.token, write.attempts));
            write.receiptRead = read;
        }
        try {
            Boolean landed = read.get(Math.max(1L, waitMillis), TimeUnit.MILLISECONDS);
            write.receiptRead = null;
            return landed;
        } catch (ExecutionException e) {
            write.receiptRead = null;
            log(e.getCause(), plugin.i18n("log_bag_claim_failed"), write.ownerUuid, write.page);
        } catch (TimeoutException e) {
            log(e, plugin.i18n("log_bag_claim_failed"), write.ownerUuid, write.page);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    /** Whether a kept write left at disable must be decided by its receipt: no attempt running, one in doubt. */
    private static boolean needsDecision(Kept write) {
        Future<WriteOutcome> running = write.inFlight;
        return (running == null || running.isDone()) && (write.uncertain || running != null);
    }

    /**
     * Starts deciding a write in doubt at module disable (R3-1; maintainer decision 2026-10-06): one transaction that
     * first releases the session's claim -- an {@code updateIf} on the session's token and this run, a write to the
     * claim row, so it waits behind any transaction of the session still running on the database, which holds that row
     * from its fence to its commit -- then reads the session's receipt and deletes it. A save the database is still
     * running when the client gave up on it (MySQL's {@code socketTimeout}) is therefore decided only after it
     * committed or rolled back, and none of the session's saves can land after this transaction, because the claim
     * no longer carries its token.
     *
     * @return the decision: whether a save of {@code write} landed
     */
    private Future<Boolean> startDecision(Kept write) {
        Held claim = held.get(write.id);
        // Any counter: a released row carries no token, so another server may take it at once.
        long counter = claim != null && claim.token.equals(write.token) ? claim.renewals + 2 : 0L;
        return submit(() -> claims.transaction(() -> {
            // ESSENTIAL ORDER (R4-1): the claim-row write comes first, before any read in this transaction. On MySQL a
            // consistent read made before it would not see the receipt of a save still running on the database --
            // the write is what waits for that save. SQLite would hide a wrong order (it refuses a read-then-write
            // with SQLITE_BUSY), so BagStorageFailureTest#theStopDecisionWritesTheClaimRowBeforeItReads asserts it.
            claims.updateIf(claimRow(write.ownerUuid, write.page, "", counter),
                    WhereCondition.builder().column("holder_token").value(write.token).build(),
                    WhereCondition.builder().column("holder_run").value(run).build());
            boolean wasLanded = landedEarlier(write.token, write.attempts);
            forgetRecord(write.token);
            return wasLanded;
        }));
    }

    /**
     * Waits for a decision started by {@link #startDecision}, at most {@code waitMillis}.
     *
     * @return whether a save of {@code write} landed; {@code null} if this could not be decided in time (nothing is
     *         given back then)
     */
    private Boolean awaitDecision(Kept write, Future<Boolean> decision, long waitMillis) {
        try {
            Boolean landed = decision.get(Math.max(1L, waitMillis), TimeUnit.MILLISECONDS);
            Held claim = held.get(write.id);
            if (claim != null && claim.token.equals(write.token) && held.remove(write.id, claim)) {
                // Released by the decision itself.
                claim.released = true;
            }
            return landed;
        } catch (ExecutionException e) {
            log(e.getCause(), plugin.i18n("log_bag_claim_failed"), write.ownerUuid, write.page);
        } catch (TimeoutException e) {
            log(e, plugin.i18n("log_bag_claim_failed"), write.ownerUuid, write.page);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    private void logLanded(Kept write) {
        plugin.getLogger().info(plugin.i18n("log_bag_save_landed")
                .replace("{PAGE}", String.valueOf(write.page))
                .replace("{OWNER}", nameOf(write.ownerUuid)));
    }

    private void finishKept(Kept write) {
        if (!kept.remove(write.id, write)) {
            return;
        }
        Held claim = held.get(write.id);
        if (claim != null && claim.token.equals(write.token)) {
            if (claim.sessionEnded && held.remove(write.id, claim)) {
                if (stopping) {
                    // Module disable: collected and made together with the final releases, on one deadline (P3-1).
                    claim.released = true;
                    stopReleases.add(claim);
                } else {
                    // Submitted, never waited for: this runs on the background pass, whose schedule a release that
                    // does not answer must not hold up (gate 2 Codex run 2).
                    releaseWithoutWaiting(claim);
                }
            }
        } else if (claims != null && storage != null) {
            // The claim is gone: nothing releases it, so its record row is deleted here (best effort).
            submit(() -> {
                forgetRecord(write.token);
                return null;
            });
        }
        onMainThread(() -> RemoteBagContentGUI.keptWriteSettled(write.ownerUuid, write.page));
    }

    private void giveBackPutIns(Kept write) {
        List<ItemStack> putIn = ItemReturns.putIn(write.base == null ? null : write.base.getItems(), write.items);
        if (putIn.isEmpty()) {
            return;
        }
        returns.add(new Return(write.holderUuid, write.ownerUuid, write.page, putIn));
        onMainThread(this::drainReturns);
    }

    /**
     * Makes every queued give-back (main thread; at disable, the disabling thread): into the inventory of a player on
     * this server, and otherwise kept as owed for their next join here and logged with the player, the page and the
     * items.
     */
    public void drainReturns() {
        Return item;
        while ((item = returns.poll()) != null) {
            if (!returner.giveBack(item.holderUuid, item.ownerUuid, item.page, item.items)) {
                owed.computeIfAbsent(item.holderUuid, player -> new java.util.concurrent.CopyOnWriteArrayList<>())
                        .add(new Owed(item.ownerUuid, item.page, item.items));
                plugin.getLogger().error(plugin.i18n("log_bag_items_owed")
                        .replace("{PAGE}", String.valueOf(item.page))
                        .replace("{OWNER}", nameOf(item.ownerUuid))
                        .replace("{ITEMS}", ItemReturns.describe(item.items))
                        .replace("{PLAYER}", nameOf(item.holderUuid)));
            }
        }
    }

    /**
     * Gives a joining player the items a refused kept write owes them (main thread).
     *
     * @param player the player who joined
     */
    public void deliverOwed(Player player) {
        drainReturns();
        List<Owed> due = owed.remove(player.getUniqueId());
        if (due == null) {
            return;
        }
        for (Owed items : due) {
            ItemReturns.giveBack(player, plugin, items.ownerUuid, items.page, items.items);
        }
    }

    /** Module disable: keeps attempting kept writes (renewing their claims) until none is left or time is up. */
    private void flushKept(long millis) {
        if (claims == null) {
            return;
        }
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (!kept.isEmpty()) {
            long left = TimeUnit.NANOSECONDS.toMillis(until - System.nanoTime());
            if (left <= 0) {
                return;
            }
            pass(Math.min(left, backgroundDeadlineMillis()));
            if (!kept.isEmpty()) {
                try {
                    Thread.sleep(Math.min(50L, Math.max(1L, left)));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /**
     * A kept write still pending at the end of disable: logged with its items. Its put-in items are queued to go back
     * only when no attempt can still land: none running (in-flight guard, R2-4), and, if one is in doubt, the record
     * row read now says none landed (R2-1); one it names landed gives nothing back, and an unreadable record gives
     * nothing back either.
     */
    private void abandonAtDisable(Kept write, Future<Boolean> decision, long recordsUntilNanos) {
        kept.remove(write.id, write);
        Future<WriteOutcome> running = write.inFlight;
        List<ItemStack> returned = new ArrayList<>();
        if (running == null || running.isDone()) {
            long left = TimeUnit.NANOSECONDS.toMillis(recordsUntilNanos - System.nanoTime());
            Boolean landed = decision == null ? Boolean.FALSE
                    : left > 0 ? awaitDecision(write, decision, left) : null;
            if (Boolean.TRUE.equals(landed)) {
                logLanded(write);
                return;
            }
            if (Boolean.FALSE.equals(landed)) {
                List<ItemStack> putIn = ItemReturns.putIn(write.base == null ? null : write.base.getItems(), write.items);
                if (!putIn.isEmpty()) {
                    returns.add(new Return(write.holderUuid, write.ownerUuid, write.page, putIn));
                    returned = putIn;
                }
            }
        }
        plugin.getLogger().error(plugin.i18n("log_bag_save_abandoned")
                .replace("{PAGE}", String.valueOf(write.page))
                .replace("{OWNER}", nameOf(write.ownerUuid))
                .replace("{ITEMS}", ItemReturns.describe(ItemReturns.itemsOf(write.items)))
                .replace("{RETURNED}", ItemReturns.describe(returned))
                .replace("{PLAYER}", nameOf(write.holderUuid)));
    }

    /** The default {@link ItemReturner}: into the inventory of the player if they are on this server. */
    private boolean giveBackToPlayer(UUID holderUuid, UUID ownerUuid, int page, List<ItemStack> items) {
        Player player = Bukkit.getServer() == null ? null : Bukkit.getPlayer(holderUuid);
        if (player == null || !player.isOnline()) {
            return false;
        }
        ItemReturns.giveBack(player, plugin, ownerUuid, page, items);
        return true;
    }

    // ==================== Shared helpers ====================

    private void hold(String id, UUID ownerUuid, int page, UUID holderUuid, String token, long renewals, long started) {
        observed.remove(id);
        lost.remove(id);
        held.put(id, new Held(ownerUuid, page, holderUuid, token, renewals, started));
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

    private void log(Throwable e, String template, UUID ownerUuid, int page) {
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
