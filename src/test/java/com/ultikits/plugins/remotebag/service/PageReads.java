package com.ultikits.plugins.remotebag.service;

import org.bukkit.inventory.ItemStack;

/**
 * Test support: a window's read of its page, for tests whose {@link RemoteBagService} is a mock.
 * <p>
 * Since UltiKits/UltiRemoteBag#54 a content window shows what {@link RemoteBagService#readPage} read
 * from the database and conditions its save on it; a test that used to stub {@code getBagPage} stubs
 * {@code readPage} with one of these instead. Lives in the service's package because the read's
 * constructor is not public: production code gets a read only by reading.
 */
public final class PageReads {

    private PageReads() {
    }

    /** A stored page holding {@code items} (which may be {@code null}, as a mocked service could answer). */
    public static RemoteBagService.PageRead of(ItemStack[] items) {
        return new RemoteBagService.PageRead(items, true, "stored-contents", 1L);
    }
}
