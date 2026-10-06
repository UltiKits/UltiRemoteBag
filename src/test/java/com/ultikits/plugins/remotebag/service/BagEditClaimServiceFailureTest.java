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
                (BagEditClaimService.ClaimLostListener) (holder, owner, page) -> { });
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
        assertThat(troubled.get(0)).containsExactly(HOLDER, OWNER, 1);
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
