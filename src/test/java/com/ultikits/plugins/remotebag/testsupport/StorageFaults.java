package com.ultikits.plugins.remotebag.testsupport;

import com.ultikits.plugins.remotebag.UltiRemoteBagTestHelper;
import com.ultikits.ultitools.exceptions.DataAccessException;
import com.ultikits.ultitools.exceptions.ErrorCode;
import com.ultikits.ultitools.interfaces.DataOperator;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * Test support: a database that fails for one server only, the way a real one does (UltiKits/UltiRemoteBag#54,
 * gate-1 findings F1 and F2 of plan 17-84).
 * <p>
 * {@link #install} replaces one server bean's {@link DataOperator} field with a proxy that forwards every call
 * to the real operator unless a fault is armed. A fault applies to the methods named in {@link #onlyMethods}
 * (all methods when none are named) and to calls whose first argument passes {@link #onlyFirstArgument}:
 * <ul>
 *   <li>{@link Mode#THROW}: the call throws the framework's {@link DataAccessException} without reaching the
 *       database -- an outage, a dead pooled connection, pool exhaustion.</li>
 *   <li>{@link Mode#HANG}: the call blocks until {@link #release} and then runs for real -- a statement on a
 *       half-open connection that returns, and lands, much later.</li>
 *   <li>{@link Mode#COMMIT_THEN_THROW}: the call runs for real and then throws -- the write landed, but the
 *       server was told it failed (a connection that broke after the commit). Applies once, then the store is
 *       healthy again.</li>
 * </ul>
 */
public final class StorageFaults {

    /** What an armed fault does. */
    public enum Mode {
        /** Forward every call. */
        NONE,
        /** Throw without reaching the database. */
        THROW,
        /** Block until released, then run for real. */
        HANG,
        /** Run for real, then throw; once. */
        COMMIT_THEN_THROW
    }

    private volatile Mode mode = Mode.NONE;
    private volatile Set<String> methods = new HashSet<>();
    private volatile Predicate<Object> firstArgument = argument -> true;
    private volatile CountDownLatch hang = new CountDownLatch(1);
    private final AtomicInteger hung = new AtomicInteger();
    private final java.util.concurrent.atomic.AtomicReference<Runnable> before = new java.util.concurrent.atomic.AtomicReference<>();

    /**
     * Wraps {@code bean}'s operator field {@code field} and returns the faults that drive it.
     *
     * @param bean  a server's service
     * @param field the name of its {@link DataOperator} field
     * @return the switch for that server's store
     */
    public static StorageFaults install(Object bean, String field) throws Exception {
        StorageFaults faults = new StorageFaults();
        Object real = UltiRemoteBagTestHelper.getField(bean, field);
        Object proxy = Proxy.newProxyInstance(StorageFaults.class.getClassLoader(), new Class<?>[] {DataOperator.class},
                (self, method, args) -> faults.invoke(real, method, args));
        UltiRemoteBagTestHelper.setField(bean, field, proxy);
        return faults;
    }

    /** Arms {@code next} for the selected calls. */
    public StorageFaults set(Mode next) {
        if (next == Mode.HANG) {
            hang = new CountDownLatch(1);
        }
        this.mode = next;
        return this;
    }

    /** Restricts the fault to these method names (for example {@code updateIf}). */
    public StorageFaults onlyMethods(String... names) {
        this.methods = new HashSet<>(Arrays.asList(names));
        return this;
    }

    /** Restricts the fault to calls whose first argument passes {@code test}. */
    public StorageFaults onlyFirstArgument(Predicate<Object> test) {
        this.firstArgument = test;
        return this;
    }

    /**
     * Runs {@code otherServer} once, immediately before the next selected call reaches the database (no fault
     * is needed): how another server acts in the middle of this server's action.
     */
    public StorageFaults onceBefore(Runnable otherServer) {
        before.set(otherServer);
        return this;
    }

    /** The store is healthy again; a hung call is let go and runs for real. */
    public void heal() {
        mode = Mode.NONE;
        release();
    }

    /** Lets every hung call go on (it then runs for real); the fault itself stays armed. */
    public void release() {
        hang.countDown();
    }

    /** How many calls are hung right now. */
    public int hungCalls() {
        return hung.get();
    }

    /** Waits (real time, at most two seconds) until at least {@code calls} calls are hung. */
    public boolean awaitHung(int calls) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2_000L;
        while (System.currentTimeMillis() < deadline) {
            if (hung.get() >= calls) {
                return true;
            }
            Thread.sleep(5L);
        }
        return hung.get() >= calls;
    }

    private boolean applies(Method method, Object[] args) {
        if (method.getDeclaringClass().equals(Object.class)) {
            return false;
        }
        if (!methods.isEmpty() && !methods.contains(method.getName())) {
            return false;
        }
        return firstArgument.test(args == null || args.length == 0 ? null : args[0]);
    }

    private Object invoke(Object real, Method method, Object[] args) throws Throwable {
        if (applies(method, args)) {
            Runnable other = before.getAndSet(null);
            if (other != null) {
                other.run();
            }
        }
        Mode now = mode;
        if (now != Mode.NONE && applies(method, args)) {
            switch (now) {
                case THROW:
                    throw outage();
                case HANG:
                    hung.incrementAndGet();
                    try {
                        hang.await(15, TimeUnit.SECONDS);
                    } finally {
                        hung.decrementAndGet();
                    }
                    return forward(real, method, args);
                case COMMIT_THEN_THROW:
                    mode = Mode.NONE;
                    forward(real, method, args);
                    throw outage();
                default:
                    break;
            }
        }
        return forward(real, method, args);
    }

    private static Object forward(Object real, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(real, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static DataAccessException outage() {
        return new DataAccessException(ErrorCode.DATA_OPERATION_FAILED, "test: database unreachable",
                new SQLException("test: connection reset"));
    }
}
