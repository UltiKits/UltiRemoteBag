package com.ultikits.plugins.remotebag.service;

import com.ultikits.plugins.remotebag.testsupport.SharedDatabaseServers;

import org.junit.jupiter.api.DisplayName;

/**
 * Every case of {@link RemoteBagSharedDatabaseTest} on one real SQLite file that two "servers" open through
 * their own framework {@code SQLiteDataOperator}s (UltiKits/UltiRemoteBag#54): the conditional page save is the
 * framework's single {@code UPDATE ... WHERE id = ? AND contents = ?}, decided by the database.
 */
@DisplayName("Bag pages on a shared database, on real SQLite (UltiRemoteBag#54)")
class RemoteBagSharedDatabaseSqliteTest extends RemoteBagSharedDatabaseTest {

    @Override
    protected SharedDatabaseServers.Backend backend() {
        return SharedDatabaseServers.Backend.SQLITE;
    }
}
