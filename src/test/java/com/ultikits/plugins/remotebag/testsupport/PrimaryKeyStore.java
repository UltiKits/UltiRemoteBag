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
 * Test support: a store with the one property of the relational backends the bag edit claim relies on
 * that the framework's JSON operator does not have (UltiKits/UltiRemoteBag#54, Phase 17 plan 17-84).
 * <p>
 * The SQLite and MySQL operators create every table with {@code PRIMARY KEY (id)}, so an insert of an id
 * that is already stored fails with a {@link DataAccessException}; the JSON operator ignores such an insert
 * and returns normally. Everything is forwarded to the real operator, except that {@link #insert} of a
 * stored id throws, as the relational backends do. {@link #beforeNextInsert} runs something -- "another
 * server" -- between this caller's read and its insert, once, which is how two first claims of one page
 * meet.
 */
public final class PrimaryKeyStore<T extends BaseDataEntity<String>> implements DataOperator<T> {

    private final DataOperator<T> real;
    private final boolean enforce;
    private final AtomicReference<Runnable> beforeNextInsert = new AtomicReference<>();

    /**
     * @param real    the operator to forward to
     * @param enforce whether an insert of a stored id throws (the relational backends) or is passed on
     *                (the JSON backend, which ignores it)
     */
    public PrimaryKeyStore(DataOperator<T> real, boolean enforce) {
        this.real = real;
        this.enforce = enforce;
    }

    /** Runs {@code otherServer} once, immediately before the next insert this store is asked for. */
    public void beforeNextInsert(Runnable otherServer) {
        beforeNextInsert.set(otherServer);
    }

    @Override
    public void insert(T obj) {
        Runnable other = beforeNextInsert.getAndSet(null);
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
    public boolean updateIf(T entity, WhereCondition... expected) {
        return real.updateIf(entity, expected);
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
