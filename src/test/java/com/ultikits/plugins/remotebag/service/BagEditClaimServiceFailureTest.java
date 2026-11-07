package com.ultikits.plugins.remotebag.service;

import com.ultikits.plugins.remotebag.UltiRemoteBagTestHelper;
import com.ultikits.plugins.remotebag.entity.RemoteBagEditClaim;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.entities.WhereCondition;
import com.ultikits.ultitools.exceptions.DataAccessException;
import com.ultikits.ultitools.exceptions.ErrorCode;
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.interfaces.impl.logger.PluginLogger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The edit claim when storage fails (UltiKits/UltiRemoteBag#54): no path throws to its caller; a failure is
 * logged; the caller is told the safe answer (read-only, "held elsewhere", "not lost").
 */
@DisplayName("BagEditClaimService when storage fails (UltiRemoteBag#54)")
class BagEditClaimServiceFailureTest {

    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID HOLDER = UUID.randomUUID();

    private BagEditClaimService service;
    private DataOperator<RemoteBagEditClaim> claims;
    private PluginLogger logger;
    private long nanos = 1_000L;

    private static DataAccessException down() {
        return new DataAccessException(ErrorCode.DATA_OPERATION_FAILED, "database is down");
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        UltiToolsPlugin plugin = mock(UltiToolsPlugin.class);
        logger = mock(PluginLogger.class);
        lenient().when(plugin.getLogger()).thenReturn(logger);
        lenient().when(plugin.i18n(anyString())).thenAnswer(inv -> inv.getArgument(0));
        claims = mock(DataOperator.class);
        service = new BagEditClaimService();
        UltiRemoteBagTestHelper.setField(service, "plugin", plugin);
        UltiRemoteBagTestHelper.setField(service, "config", UltiRemoteBagTestHelper.createDefaultConfig());
        UltiRemoteBagTestHelper.setField(service, "claims", claims);
        UltiRemoteBagTestHelper.setField(service, "nanoTime", (LongSupplier) () -> nanos);
        UltiRemoteBagTestHelper.setField(service, "mainThread", (Executor) Runnable::run);
        UltiRemoteBagTestHelper.setField(service, "lostListener",
                (BagEditClaimService.ClaimLostListener) (holder, owner, page, token) -> { });
    }

    private RemoteBagEditClaim stored(String run, String token, long renewals) {
        RemoteBagEditClaim row = RemoteBagEditClaim.builder().playerUuid(OWNER.toString()).pageNumber(1)
                .holderRun(run).holderToken(token).renewals(renewals).build();
        row.setId(RemoteBagEditClaim.idOf(OWNER, 1));
        return row;
    }

    @Test
    @DisplayName("A read that fails: claim answers FAILED and logs; isHeldElsewhere answers true")
    void readFails() {
        when(claims.getById(any())).thenThrow(down());

        assertThat(service.claim(OWNER, 1, HOLDER)).isEqualTo(BagEditClaimService.Outcome.FAILED);
        assertThat(service.isHeldElsewhere(OWNER, 1)).isTrue();
        verify(logger, org.mockito.Mockito.atLeast(2)).warn(any(Throwable.class), anyString());
    }

    @Test
    @DisplayName("An insert that fails while no row exists: FAILED (a storage error, not another server's claim)")
    void insertFailsWithNoRow() {
        when(claims.getById(any())).thenReturn(null);
        doThrow(down()).when(claims).insert(any());

        assertThat(service.claim(OWNER, 1, HOLDER)).isEqualTo(BagEditClaimService.Outcome.FAILED);
    }

    @Test
    @DisplayName("A row that keeps changing between read and write: HELD_ELSEWHERE after two attempts")
    void conditionalWriteKeepsMissing() {
        when(claims.getById(any())).thenReturn(stored("other", "", 3L));
        when(claims.updateIf(any(), any(WhereCondition[].class))).thenReturn(false);

        assertThat(service.claim(OWNER, 1, HOLDER)).isEqualTo(BagEditClaimService.Outcome.HELD_ELSEWHERE);
    }

    @Test
    @DisplayName("Maintainer decision 3 (2026-10-06): a renewal that throws turns the window read-only at once; the claim is kept and renewed again; a release that throws is logged")
    void renewalAndReleaseFailures() throws Exception {
        List<Object[]> troubled = new CopyOnWriteArrayList<>();
        installListener("troubleListener", troubled);
        when(claims.getById(any())).thenReturn(null, stored("ignored", "ignored", 0L));
        // Claim: insert, then read back -- make the read-back return this run's own row.
        org.mockito.Mockito.doAnswer(inv -> {
            RemoteBagEditClaim mine = inv.getArgument(0);
            when(claims.getById(any())).thenReturn(mine);
            return null;
        }).when(claims).insert(any());
        assertThat(service.claim(OWNER, 1, HOLDER)).isEqualTo(BagEditClaimService.Outcome.CLAIMED);

        when(claims.updateIf(any(), any(WhereCondition[].class))).thenThrow(down());
        nanos += 400_000_000_000L;
        service.renewDue();

        // Decision 3: "a failed renewal puts the window into read-only immediately" -- a renewal that throws is a
        // failed renewal, not only one that finds the claim changed (gate 1 of plan 17-84, finding F1).
        assertThat(troubled).as("the holder's window is told at once").hasSize(1);
        // The notice also names the claim's token (gate 2 Codex run 2), so only that session's window acts on it.
        assertThat(java.util.Arrays.copyOf(troubled.get(0), 3)).containsExactly(HOLDER, OWNER, 1);
        assertThat(troubled.get(0)[3]).as("the claim's token").isNotNull();
        verify(logger, org.mockito.Mockito.atLeastOnce()).warn(any(Throwable.class), anyString());
        assertThat(service.isLost(OWNER, 1)).as("unknown is not lost: the claim is kept").isFalse();

        // Kept: the next pass renews it again (and the window is not told twice).
        nanos += 400_000_000_000L;
        service.renewDue();
        verify(claims, org.mockito.Mockito.times(2)).updateIf(any(), any(WhereCondition[].class));
        assertThat(troubled).hasSize(1);

        service.release(OWNER, 1);
        verify(logger, org.mockito.Mockito.atLeast(3)).warn(any(Throwable.class), anyString());
        verify(logger, never()).error(anyString());
    }

    @Test
    @DisplayName("A background pass shares one wait budget between renewal and kept saves (#57)")
    @SuppressWarnings("unchecked")
    void aPassSharesItsWaitBudget() throws Exception {
        claimPage(1);
        nanos += 100_000_000_001L; // page 1 is due; page 2, claimed now, is still confirmed
        claimPage(2);
        RemoteBagService bags = mock(RemoteBagService.class);
        UltiRemoteBagTestHelper.setField(service, "bagService", bags);
        assertThat(service.keepWindow(HOLDER, OWNER, 2, new org.bukkit.inventory.ItemStack[0],
                new RemoteBagService.PageRead(new org.bukkit.inventory.ItemStack[0], false, null, 0L))).isTrue();
        UltiRemoteBagTestHelper.setField(service, "callDeadlineMillis", 300L);

        java.util.concurrent.ExecutorService oldPool =
                (java.util.concurrent.ExecutorService) UltiRemoteBagTestHelper.getField(service, "storage");
        oldPool.shutdownNow();
        java.util.concurrent.ExecutorService pool = mock(java.util.concurrent.ExecutorService.class);
        java.util.concurrent.Future<Object> renewal = mock(java.util.concurrent.Future.class);
        java.util.concurrent.Future<Object> save = mock(java.util.concurrent.Future.class);
        when(pool.submit(org.mockito.ArgumentMatchers.<java.util.concurrent.Callable<Object>>any()))
                .thenReturn(renewal, save);
        when(renewal.get(org.mockito.ArgumentMatchers.anyLong(), any(java.util.concurrent.TimeUnit.class)))
                .thenAnswer(call -> {
                    // Deliberately spend part of the budget, not a delay used to guess whether a write settled.
                    new java.util.concurrent.CountDownLatch(1).await(200L, java.util.concurrent.TimeUnit.MILLISECONDS);
                    throw new java.util.concurrent.TimeoutException("renewal still running");
                });
        java.util.concurrent.atomic.AtomicLong saveAllowance = new java.util.concurrent.atomic.AtomicLong(-1L);
        when(save.get(org.mockito.ArgumentMatchers.anyLong(), any(java.util.concurrent.TimeUnit.class)))
                .thenAnswer(call -> {
                    saveAllowance.set(call.<java.util.concurrent.TimeUnit>getArgument(1)
                            .toMillis(call.<Long>getArgument(0)));
                    throw new java.util.concurrent.TimeoutException("save still running");
                });
        UltiRemoteBagTestHelper.setField(service, "storage", pool);

        service.renewDue();

        assertThat(saveAllowance.get()).as("save only gets the remainder of the 300 ms pass")
                .isBetween(0L, 150L);
    }

    @Test
    @DisplayName("Disable waits for a release already submitted by a background pass (#57)")
    @SuppressWarnings("unchecked")
    void disableWaitsForAnAlreadySubmittedRelease() throws Exception {
        claimPage(1);
        Object released = removeHeldClaim();
        java.util.concurrent.ExecutorService oldPool =
                (java.util.concurrent.ExecutorService) UltiRemoteBagTestHelper.getField(service, "storage");
        oldPool.shutdownNow();
        java.util.concurrent.ExecutorService pool = mock(java.util.concurrent.ExecutorService.class);
        java.util.concurrent.Future<Boolean> release = mock(java.util.concurrent.Future.class);
        when(pool.submit(org.mockito.ArgumentMatchers.<java.util.concurrent.Callable<Boolean>>any())).thenReturn(release);
        UltiRemoteBagTestHelper.setField(service, "storage", pool);
        java.lang.reflect.Method submitRelease = BagEditClaimService.class
                .getDeclaredMethod("releaseWithoutWaiting", released.getClass());
        submitRelease.setAccessible(true); // NOPMD - reproduces the background's asynchronous release handoff
        submitRelease.invoke(service, released);

        service.shutdown();

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(release, pool);
        order.verify(release).get(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.eq(java.util.concurrent.TimeUnit.NANOSECONDS));
        order.verify(pool).shutdownNow();
    }

    private void claimPage(int page) {
        java.util.concurrent.atomic.AtomicReference<RemoteBagEditClaim> row = new java.util.concurrent.atomic.AtomicReference<>();
        when(claims.getById(RemoteBagEditClaim.idOf(OWNER, page))).thenAnswer(call -> row.get());
        org.mockito.Mockito.doAnswer(call -> {
            RemoteBagEditClaim inserted = call.getArgument(0);
            row.set(inserted);
            return null;
        }).when(claims).insert(any());
        assertThat(service.claim(OWNER, page, HOLDER)).isEqualTo(BagEditClaimService.Outcome.CLAIMED);
    }

    /** Keeps the private claim type out of the test's source-level dependencies. */
    private Object removeHeldClaim() throws Exception {
        java.util.Map<?, ?> held = (java.util.Map<?, ?>) UltiRemoteBagTestHelper.getField(service, "held");
        Object claim = held.values().iterator().next();
        held.clear(); // finishKept removes an ended session before submitting its release
        return claim;
    }

    /** Records every call of the service's listener field {@code field}, if the service has it. */
    private void installListener(String field, List<Object[]> calls) throws Exception {
        java.lang.reflect.Field target;
        try {
            target = BagEditClaimService.class.getDeclaredField(field);
        } catch (NoSuchFieldException absent) {
            return;
        }
        Object listener = java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {target.getType()}, (self, method, args) -> {
                    if (method.getDeclaringClass().equals(Object.class)) {
                        return null;
                    }
                    calls.add(args);
                    return null;
                });
        UltiRemoteBagTestHelper.setField(service, field, listener);
    }

    @Test
    @DisplayName("Without a claims table nothing renews; a never-held page is not lost")
    void nothingToDo() throws Exception {
        UltiRemoteBagTestHelper.setField(service, "claims", null);
        service.renewDue();
        UltiRemoteBagTestHelper.setField(service, "claims", claims);
        assertThat(service.isLost(OWNER, 1)).isFalse();
        service.releaseHeldBy(HOLDER);
        service.releaseAllHeld();
        service.shutdown();
        verify(claims, never()).updateIf(any(), any(WhereCondition[].class));
    }
}
