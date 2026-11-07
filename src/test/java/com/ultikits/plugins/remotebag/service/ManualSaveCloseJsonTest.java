package com.ultikits.plugins.remotebag.service;

import com.ultikits.plugins.remotebag.testsupport.SharedDatabaseServers;

/** The same manual-close contract on the single-server JSON backend. */
class ManualSaveCloseJsonTest extends ManualSaveCloseTest {
    @Override
    protected SharedDatabaseServers.Backend backend() {
        return SharedDatabaseServers.Backend.JSON;
    }
}
