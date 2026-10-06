package com.ultikits.plugins.remotebag.service;

import com.ultikits.plugins.remotebag.testsupport.SharedDatabaseServers;

import org.junit.jupiter.api.DisplayName;

/**
 * Every case of {@link BagStorageFailureTest} on the framework's JSON backend (UltiKits/UltiRemoteBag#54, gate 1
 * F1-F4 of plan 17-84), with the relational primary key given to the claims table by the test store.
 */
@DisplayName("Storage failures of the claim and the page save on the JSON backend (UltiRemoteBag#54)")
class BagStorageFailureJsonTest extends BagStorageFailureTest {

    @Override
    protected SharedDatabaseServers.Backend backend() {
        return SharedDatabaseServers.Backend.JSON;
    }
}
