package com.ultikits.plugins.remotebag.service;

import com.ultikits.plugins.remotebag.entity.RemoteBagData;
import com.ultikits.ultitools.entities.WhereCondition;
import com.ultikits.ultitools.interfaces.DataOperator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A real, in-memory {@link DataOperator} over {@code remote_bags} rows, for tests that need the
 * service's own persistence path to actually run.
 *
 * <p>A Mockito mock whose {@code list()} returns a canned list proves only that the service called
 * it. This class stores what {@code insert}/{@code update} are given and returns it to
 * {@code getAll(...)}, so a test can place an item, invoke the production save path and then read
 * the stored row back — {@code RemoteBagService#serializeItems} and {@code #deserializeItems}
 * included. The framework's own {@code QueryImpl} runs on top of this unchanged: the default
 * {@link DataOperator#query()} builds one and resolves its equality conditions through
 * {@link #getAll(WhereCondition...)} below, so the fluent chain the service writes is translated
 * by real framework code rather than stubbed away.
 *
 * <p>Only the operations this module's service actually uses are implemented. Every other method
 * throws rather than returning a plausible empty value, so a test cannot assert "nothing was
 * stored" against an operation this class silently ignores.
 */
public class InMemoryRemoteBagStore implements DataOperator<RemoteBagData> {

    private final List<RemoteBagData> rows = new ArrayList<>();
    private int nextId = 1;

    /** The stored rows, in insertion order. */
    public List<RemoteBagData> rows() {
        return Collections.unmodifiableList(rows);
    }

    /** Seeds a row as if it had been written by an earlier server run. */
    public RemoteBagData seed(String playerUuid, int pageNumber, String contents) {
        RemoteBagData data = RemoteBagData.builder()
                .playerUuid(playerUuid)
                .pageNumber(pageNumber)
                .contents(contents)
                .lastUpdated(System.currentTimeMillis())
                .build();
        data.setId(String.valueOf(nextId++));
        rows.add(data);
        return data;
    }

    /** The stored {@code contents} for one page, or {@code null} if no row exists. */
    public String storedContents(String playerUuid, int pageNumber) {
        for (RemoteBagData row : rows) {
            if (row.getPlayerUuid().equals(playerUuid) && row.getPageNumber() == pageNumber) {
                return row.getContents();
            }
        }
        return null;
    }

    @Override
    public List<RemoteBagData> getAll() {
        return new ArrayList<>(rows);
    }

    @Override
    public List<RemoteBagData> getAll(WhereCondition... whereConditions) {
        List<RemoteBagData> matches = new ArrayList<>();
        for (RemoteBagData row : rows) {
            if (matchesAll(row, whereConditions)) {
                matches.add(row);
            }
        }
        return matches;
    }

    @Override
    public boolean exist(RemoteBagData object) {
        return rows.contains(object);
    }

    @Override
    public boolean exist(WhereCondition... whereConditions) {
        return !getAll(whereConditions).isEmpty();
    }

    @Override
    public RemoteBagData getById(Object id) {
        for (RemoteBagData row : rows) {
            if (String.valueOf(id).equals(row.getId())) {
                return row;
            }
        }
        return null;
    }

    @Override
    public void insert(RemoteBagData obj) {
        obj.setId(String.valueOf(nextId++));
        rows.add(obj);
    }

    @Override
    public void update(RemoteBagData obj) {
        // The service mutates the very instance getAll(...) handed back, so the row is already
        // current; this records that the write happened for tests that care about the row identity.
        if (!rows.contains(obj)) {
            rows.add(obj);
        }
    }

    @Override
    public void delById(Object id) {
        rows.removeIf(row -> String.valueOf(id).equals(row.getId()));
    }

    @Override
    public void del(WhereCondition... whereConditions) {
        rows.removeAll(getAll(whereConditions));
    }

    @Override
    public List<RemoteBagData> getLike(String column, String value, DataOperator.LikeType likeType) {
        throw new UnsupportedOperationException("getLike is unused by this module; implement it before asserting against it");
    }

    @Override
    public List<RemoteBagData> page(int page, int size, WhereCondition... whereConditions) {
        throw new UnsupportedOperationException("page is unused by this module; implement it before asserting against it");
    }

    @Override
    public void update(String column, Object value, Object id) {
        throw new UnsupportedOperationException("column-wise update is unused by this module; implement it before asserting against it");
    }

    private boolean matchesAll(RemoteBagData row, WhereCondition... whereConditions) {
        for (WhereCondition condition : whereConditions) {
            if (condition == null || condition.isEmpty()) {
                continue;
            }
            if (!matches(row, condition)) {
                return false;
            }
        }
        return true;
    }

    private boolean matches(RemoteBagData row, WhereCondition condition) {
        String column = condition.getColumn();
        Object value = condition.getValue();
        if ("player_uuid".equals(column)) {
            return String.valueOf(value).equals(row.getPlayerUuid());
        }
        if ("page_number".equals(column)) {
            return String.valueOf(value).equals(String.valueOf(row.getPageNumber()));
        }
        if ("id".equals(column)) {
            return String.valueOf(value).equals(row.getId());
        }
        throw new UnsupportedOperationException(
                "no in-memory matcher for column '" + column + "'; add one before asserting against it");
    }
}
