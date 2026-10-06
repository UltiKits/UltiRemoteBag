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
 *             server shows the kept content. A retry that finds the stored page already holding this content
 *             (an earlier attempt landed although it reported an error) counts as landed. A kept write is
 *             attempted only while its claim is confirmed.</li>
 *       </ul></li>
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
 * had taken out before the cut stay in the stored page as well; a storage call that does not return for longer
 * than the timeout and then lands can land after another server took the claim over; a server crash loses a kept
 * write, as it loses an unsaved window. On the JSON storage backend, which belongs to one server, the claims
 * change nothing.
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
         */
        void claimLost(UUID holderUuid, UUID ownerUuid, int page);
    }

    /** Called on the main thread when one of this server's claims could not be confirmed (once per claim). */
    public interface ClaimTroubleListener {
        /**
         * @param holderUuid the player whose editing session holds the claim
         * @param ownerUuid  the bag owner
         * @param page       the page number
         */
        void claimTroubled(UUID holderUuid, UUID ownerUuid, int page);
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

    /** Claims this run held and found lost, by claim id, until the page is claimed again. */
    private final Set<String> lost = ConcurrentHashMap.newKeySet();

    /** Kept writes: page writes the database did not answer, by claim id. */
    private final Map<String, Kept> kept = new ConcurrentHashMap<>();

    /** Items a refused kept write owes a player who was not on this server, by player. */
    private final Map<UUID, List<Owed>> owed = new ConcurrentHashMap<>();

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
        private final UUID holderUuid;
        private final UUID ownerUuid;
        private final int page;
        private final ItemStack[] items;
        private final String contents;
        private final RemoteBagService.PageRead base;
        private volatile Future<Boolean> inFlight;
        /** The claim was lost: no new attempt; settled (given back unless landed) once no attempt is running. */
        private volatile boolean abandoned;

        private Kept(String id, UUID holderUuid, UUID ownerUuid, int page, ItemStack[] items, String contents,
                     RemoteBagService.PageRead base) {
            this.id = id;
            this.holderUuid = holderUuid;
            this.ownerUuid = ownerUuid;
            this.page = page;
            this.items = items;
            this.contents = contents;
            this.base = base;
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
        stopping = true;
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
        flushKept(FINAL_FLUSH_MILLIS);
        for (Kept write : new ArrayList<>(kept.values())) {
            abandonAtDisable(write);
        }
        for (Map.Entry<UUID, List<Owed>> entry : owed.entrySet()) {
            for (Owed items : entry.getValue()) {
                plugin.getLogger().error(plugin.i18n("log_bag_items_owed_dropped")
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
            ClaimDecision decision = submit(() -> decideClaim(id, ownerUuid, page, token))
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

    private ClaimDecision decideClaim(String id, UUID ownerUuid, int page, String token) {
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
                    return new ClaimDecision(Outcome.CLAIMED, row.getRenewals(), true);
                }
                // Taken over or changed since: this run no longer holds it.
                held.remove(id, alreadyHeld);
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
        return lost.contains(RemoteBagEditClaim.idOf(ownerUuid, page));
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
        String id = RemoteBagEditClaim.idOf(ownerUuid, page);
        if (lost.contains(id)) {
            return EditState.LOST;
        }
        Held claim = held.get(id);
        if (claim == null) {
            return EditState.EDITABLE;
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
        if (claims == null) {
            // No claims table (the service was never started): a plain conditional save.
            RemoteBagService.PageRead written = bagService.savePage(ownerUuid, page, items, base);
            return new SaveResult(written != null ? SaveOutcome.WRITTEN : SaveOutcome.REFUSED, written);
        }
        String id = RemoteBagEditClaim.idOf(ownerUuid, page);
        Held claim = held.get(id);
        if (lost.contains(id) || claim == null) {
            // No claim: every editing window claims at open and releases at close, so only a lost claim gets here.
            return new SaveResult(SaveOutcome.LOST, null);
        }
        String contents = bagService.serializePage(items);
        if (claim.troubled || !isConfirmed(claim, nanoTime.getAsLong())) {
            claim.troubled = true;
            keep(id, holderUuid, ownerUuid, page, items, contents, base, null);
            return new SaveResult(SaveOutcome.KEPT, null);
        }
        Future<Boolean> write = submit(() -> attemptWrite(ownerUuid, page, items, contents, base));
        try {
            if (write.get(mainThreadDeadlineMillis(), TimeUnit.MILLISECONDS)) {
                return new SaveResult(SaveOutcome.WRITTEN, bagService.storedRead(items, contents));
            }
            plugin.getLogger().error(plugin.i18n("log_bag_update_failed"));
            return new SaveResult(SaveOutcome.REFUSED, null);
        } catch (ExecutionException e) {
            keep(id, holderUuid, ownerUuid, page, items, contents, base, null);
            logKept(e.getCause(), holderUuid, ownerUuid, page);
        } catch (TimeoutException e) {
            keep(id, holderUuid, ownerUuid, page, items, contents, base, write);
            logKept(e, holderUuid, ownerUuid, page);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            keep(id, holderUuid, ownerUuid, page, items, contents, base, write);
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
        String id = RemoteBagEditClaim.idOf(ownerUuid, page);
        if (lost.contains(id) || !held.containsKey(id)) {
            return false;
        }
        keep(id, holderUuid, ownerUuid, page, items, bagService.serializePage(items), base, null);
        return true;
    }

    /**
     * The kept content of a page, for a window that shows it while its write is pending; {@code null} if none.
     *
     * @param ownerUuid the bag owner
     * @param page      the page number
     * @return a copy of the kept items, or {@code null}
     */
    public ItemStack[] keptItems(UUID ownerUuid, int page) {
        Kept write = kept.get(RemoteBagEditClaim.idOf(ownerUuid, page));
        return write == null ? null : write.items.clone();
    }

    private void keep(String id, UUID holderUuid, UUID ownerUuid, int page, ItemStack[] items, String contents,
                      RemoteBagService.PageRead base, Future<Boolean> running) {
        Kept write = new Kept(id, holderUuid, ownerUuid, page, items.clone(), contents, base);
        write.inFlight = running;
        kept.put(id, write);
    }

    private void logKept(Throwable cause, UUID holderUuid, UUID ownerUuid, int page) {
        plugin.getLogger().warn(cause, plugin.i18n("log_bag_save_pending")
                .replace("{PAGE}", String.valueOf(page))
                .replace("{OWNER}", nameOf(ownerUuid))
                .replace("{PLAYER}", nameOf(holderUuid)));
    }

    /**
     * One conditional page write (storage pool). A miss is a refusal unless the stored page already holds exactly
     * this content -- an earlier attempt landed although it reported an error.
     *
     * @return true if the page now holds {@code contents} through this write or an earlier one
     */
    private boolean attemptWrite(UUID ownerUuid, int page, ItemStack[] items, String contents,
                                 RemoteBagService.PageRead base) {
        if (bagService.writePage(ownerUuid, page, items, contents, base) != null) {
            return true;
        }
        return contents.equals(bagService.storedContents(ownerUuid, page));
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
        String id = RemoteBagEditClaim.idOf(ownerUuid, page);
        lost.remove(id);
        Held claim = held.get(id);
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
        List<Held> releasing = new ArrayList<>();
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
        if (!kept.containsKey(id) && held.remove(id, claim)) {
            writeReleases(java.util.Collections.singletonList(claim));
        }
    }

    /** Releases claims in parallel, waiting for all of them at most one main-thread deadline. */
    private void writeReleases(List<Held> releasing) {
        if (releasing.isEmpty() || claims == null) {
            return;
        }
        Map<Held, Future<Boolean>> writes = new LinkedHashMap<>();
        for (Held claim : releasing) {
            claim.released = true;
            writes.put(claim, submit(() -> claims.updateIf(claimRow(claim.ownerUuid, claim.page, "", claim.renewals + 1),
                    WhereCondition.builder().column("holder_token").value(claim.token).build(),
                    WhereCondition.builder().column("holder_run").value(run).build())));
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
                claim.renewals = renewal.renewals;
                claim.confirmedAtNanos = renewal.startedAtNanos;
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

    /** The claim is lost: marked lost first, dropped, the window told; a kept write of the page is abandoned. */
    private void claimLost(Held claim) {
        String id = RemoteBagEditClaim.idOf(claim.ownerUuid, claim.page);
        lost.add(id);
        if (held.remove(id, claim)) {
            claim.released = true;
            onMainThread(() -> {
                plugin.getLogger().error(fill(plugin.i18n("log_bag_claim_lost"), claim.ownerUuid, claim.page));
                lostListener.claimLost(claim.holderUuid, claim.ownerUuid, claim.page);
            });
        }
        Kept write = kept.get(id);
        if (write != null) {
            write.abandoned = true;
            if (write.inFlight == null) {
                settleAbandoned(write);
            }
        }
    }

    private void trouble(Held claim) {
        if (!claim.troubled) {
            claim.troubled = true;
            onMainThread(() -> troubleListener.claimTroubled(claim.holderUuid, claim.ownerUuid, claim.page));
        }
    }

    private void attemptKeptWrites(long waitMillis) {
        long now = nanoTime.getAsLong();
        Map<Kept, Future<Boolean>> started = new LinkedHashMap<>();
        for (Kept write : new ArrayList<>(kept.values())) {
            Future<Boolean> running = write.inFlight;
            if (running != null) {
                if (!running.isDone()) {
                    continue;
                }
                write.inFlight = null;
                if (settleKept(write, running)) {
                    continue;
                }
            }
            if (write.abandoned) {
                settleAbandoned(write);
                continue;
            }
            Held claim = held.get(write.id);
            if (claim == null || !isConfirmed(claim, now)) {
                // Written only while the claim is confirmed: no other server can have taken it then.
                continue;
            }
            Future<Boolean> attempt = submit(() -> attemptWrite(write.ownerUuid, write.page, write.items, write.contents, write.base));
            write.inFlight = attempt;
            started.put(write, attempt);
        }
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMillis);
        for (Map.Entry<Kept, Future<Boolean>> entry : started.entrySet()) {
            try {
                entry.getValue().get(Math.max(0L, until - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                continue;
            } catch (ExecutionException e) {
                // Settled below.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            entry.getKey().inFlight = null;
            settleKept(entry.getKey(), entry.getValue());
        }
    }

    /**
     * Acts on a finished attempt of a kept write.
     *
     * @return true if the kept write is settled (landed or refused)
     */
    private boolean settleKept(Kept write, Future<Boolean> done) {
        try {
            if (done.get()) {
                plugin.getLogger().info(plugin.i18n("log_bag_save_landed")
                        .replace("{PAGE}", String.valueOf(write.page))
                        .replace("{OWNER}", nameOf(write.ownerUuid)));
                finishKept(write);
                return true;
            }
            plugin.getLogger().error(plugin.i18n("log_bag_update_failed"));
            giveBackPutIns(write);
            finishKept(write);
            return true;
        } catch (ExecutionException e) {
            // Tried again on a later pass (or, abandoned, settled there).
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** A kept write whose claim was lost and that has no attempt running: its put-in items go back. */
    private void settleAbandoned(Kept write) {
        giveBackPutIns(write);
        finishKept(write);
    }

    private void finishKept(Kept write) {
        if (!kept.remove(write.id, write)) {
            return;
        }
        Held claim = held.get(write.id);
        if (claim != null && claim.sessionEnded && held.remove(write.id, claim)) {
            writeReleases(java.util.Collections.singletonList(claim));
        }
        onMainThread(() -> RemoteBagContentGUI.keptWriteSettled(write.ownerUuid, write.page));
    }

    private void giveBackPutIns(Kept write) {
        List<ItemStack> putIn = ItemReturns.putIn(write.base == null ? null : write.base.getItems(), write.items);
        if (putIn.isEmpty()) {
            return;
        }
        onMainThread(() -> {
            if (!returner.giveBack(write.holderUuid, write.ownerUuid, write.page, putIn)) {
                owed.computeIfAbsent(write.holderUuid, player -> new java.util.concurrent.CopyOnWriteArrayList<>())
                        .add(new Owed(write.ownerUuid, write.page, putIn));
                plugin.getLogger().error(plugin.i18n("log_bag_items_owed")
                        .replace("{PAGE}", String.valueOf(write.page))
                        .replace("{OWNER}", nameOf(write.ownerUuid))
                        .replace("{ITEMS}", ItemReturns.describe(putIn))
                        .replace("{PLAYER}", nameOf(write.holderUuid)));
            }
        });
    }

    /**
     * Gives a joining player the items a refused kept write owes them (main thread).
     *
     * @param player the player who joined
     */
    public void deliverOwed(Player player) {
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

    /** A kept write still pending at the end of disable: logged with its items; put-in items go back if safe. */
    private void abandonAtDisable(Kept write) {
        kept.remove(write.id, write);
        Future<Boolean> running = write.inFlight;
        List<ItemStack> returned = new ArrayList<>();
        if (running == null) {
            // No attempt is running, so none can land later: the put-in items go back to a player still online.
            List<ItemStack> putIn = ItemReturns.putIn(write.base == null ? null : write.base.getItems(), write.items);
            if (!putIn.isEmpty() && returner.giveBack(write.holderUuid, write.ownerUuid, write.page, putIn)) {
                returned = putIn;
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
