package com.ultikits.plugins.remotebag.service;

import com.ultikits.plugins.remotebag.testsupport.SharedDatabaseServers;

import org.junit.jupiter.api.DisplayName;

/**
 * Every case of {@link BagEditClaimTest} on the framework's JSON backend (UltiKits/UltiRemoteBag#54), with the
 * relational primary key given to the claims table by the test store. JSON storage belongs to one server, so
 * this proves the claim changes nothing there that the module relies on.
 */
@DisplayName("The edit claim on the JSON backend (UltiRemoteBag#54)")
class BagEditClaimJsonTest extends BagEditClaimTest {

    @Override
    protected SharedDatabaseServers.Backend backend() {
        return SharedDatabaseServers.Backend.JSON;
    }
}
