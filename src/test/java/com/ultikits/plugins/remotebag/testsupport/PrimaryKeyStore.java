package com.ultikits.plugins.remotebag.testsupport;

import com.ultikits.ultitools.abstracts.data.BaseDataEntity;
import com.ultikits.ultitools.entities.WhereCondition;
import com.ultikits.ultitools.exceptions.DataAccessException;
import com.ultikits.ultitools.exceptions.ErrorCode;
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.interfaces.Query;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Test support: one server's operator on the shared claims table, with hooks that let "another server"
 * act between this server's read and its write (UltiKits/UltiRemoteBag#54, Phase 17 plan 17-84).
 * <p>
 * Everything is forwarded to the real operator. {@link Hooks#beforeNextInsert} and
 * {@link Hooks#beforeNextUpdateIf} run something once, immediately before the next insert or conditional
 * write any server makes through a store sharing those hooks -- which is how two claims of one page meet.
 * On the JSON backend the store can also give the table the relational primary key ({@code enforce}): the
 * SQLite and MySQL operators create every table with {@code PRIMARY KEY (id)}, so an insert of an id that is
 * already stored fails, while the JSON operator ignores it and returns normally. Over real SQLite
 * {@code enforce} is off: the table's own key does it.
 */
public final class PrimaryKeyStore<T extends BaseDataEntity<String>> implements DataOperator<T> {

    /** Hooks shared by every server's store, so one server's action can run inside another's. */
    public static final class Hooks {
        private final AtomicReference<Runnable> beforeNextInsert = new AtomicReference<>();
        private final AtomicReference<Runnable> beforeNextUpdateIf = new AtomicReference<>();
        private final AtomicReference<String> lastUpdateIfThread = new AtomicReference<>();

        /** Runs {@code otherServer} once, immediately before the next insert. */
        public void beforeNextInsert(Runnable otherServer) {
            beforeNextInsert.set(otherServer);
        }

        /** Runs {@code otherServer} once, immediately before the next conditional write. */
        public void beforeNextUpdateIf(Runnable otherServer) {
            beforeNextUpdateIf.set(otherServer);
        }

        /** The name of the thread that made the last conditional write, or {@code null}. */
        public String lastUpdateIfThread() {
            return lastUpdateIfThread.get();
        }
    }

    private final DataOperator<T> real;
    private final boolean enforce;
    private final Hooks hooks;

    /**
     * @param real    the operator to forward to
     * @param enforce whether an insert of a stored id throws here (JSON standing in for the relational
     *                backends); off over a real relational table, whose key does it
     * @param hooks   the hooks shared by every server's store
     */
    public PrimaryKeyStore(DataOperator<T> real, boolean enforce, Hooks hooks) {
        this.real = real;
        this.enforce = enforce;
        this.hooks = hooks;
    }

    @Override
    public void insert(T obj) {
        Runnable other = hooks.beforeNextInsert.getAndSet(null);
        if (other != null) {
            other.run();
        }
        if (enforce && obj.getId() != null && real.getById(obj.getId()) != null) {
            throw new DataAccessException(ErrorCode.DATA_OPERATION_FAILED,
                    "Failed to insert entity: duplicate primary key " + obj.getId());
        }
        real.insert(obj);
    }

    @Override
    public boolean updateIf(T entity, WhereCondition... expected) {
        Runnable other = hooks.beforeNextUpdateIf.getAndSet(null);
        if (other != null) {
            other.run();
        }
        hooks.lastUpdateIfThread.set(Thread.currentThread().getName());
        return real.updateIf(entity, expected);
    }

    @Override
    public boolean exist(T object) {
        return real.exist(object);
    }

    @Override
    public boolean exist(WhereCondition... whereConditions) {
        return real.exist(whereConditions);
    }

    @Override
    public T getById(Object id) {
        return real.getById(id);
    }

    @Override
    public List<T> getAll() {
        return real.getAll();
    }

    @Override
    public List<T> getAll(WhereCondition... whereConditions) {
        return real.getAll(whereConditions);
    }

    @Override
    public List<T> getLike(String column, String value, LikeType likeType) {
        return real.getLike(column, value, likeType);
    }

    @Override
    public List<T> page(int page, int size, WhereCondition... whereConditions) {
        return real.page(page, size, whereConditions);
    }

    @Override
    public void del(WhereCondition... whereConditions) {
        real.del(whereConditions);
    }

    @Override
    public void delById(Object id) {
        real.delById(id);
    }

    @Override
    public void update(String column, Object value, Object id) {
        real.update(column, value, id);
    }

    @Override
    public void update(T obj) throws IllegalAccessException {
        real.update(obj);
    }

    @Override
    public int updateCounted(T entity) {
        return real.updateCounted(entity);
    }

    @Override
    public Query<T> query() {
        return real.query();
    }

    @Override
    public <R> R transaction(Callable<R> action) throws Exception {
        return real.transaction(action);
    }

    @Override
    public void transaction(Runnable action) {
        real.transaction(action);
    }

    @Override
    public void insertAll(List<T> entities) {
        real.insertAll(entities);
    }

    @Override
    public void updateAll(List<T> entities) throws IllegalAccessException {
        real.updateAll(entities);
    }
}
