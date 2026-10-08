package com.ultikits.plugins.remotebag;

import com.ultikits.plugins.remotebag.config.RemoteBagConfig;
import com.ultikits.plugins.remotebag.config.RemovedConfigKeys;
import com.ultikits.plugins.remotebag.gui.RemoteBagContentGUI;
import com.ultikits.plugins.remotebag.service.BagEditClaimService;
import com.ultikits.plugins.remotebag.service.RemoteBagService;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.UltiToolsModule;

import java.util.Arrays;
import java.util.List;

/**
 * UltiRemoteBag - Virtual cloud storage module.
 * Provides remote bag (virtual chest) functionality for players.
 *
 * @author wisdomme
 * @version 2.0.0
 */
@UltiToolsModule(
    scanBasePackages = {"com.ultikits.plugins.remotebag"}
)
public class UltiRemoteBag extends UltiToolsPlugin {

    @Override
    public boolean registerSelf() {
        // 初始化服务
        RemoteBagService bagService = getContext().getBean(RemoteBagService.class);
        if (bagService != null) {
            bagService.init();
        }
        // The edit claims shared with other servers; the framework creates their table on first use
        // (UltiKits/UltiRemoteBag#54).
        BagEditClaimService claimService = getContext().getBean(BagEditClaimService.class);
        if (claimService != null) {
            claimService.init();
        }

        // lock.timeout_seconds is read by BagLockService at each use, so /ul reload applies it
        // (UltiKits/UltiRemoteBag#39); nothing is copied here.
        RemoteBagConfig config = getContext().getBean(RemoteBagConfig.class);

        // Settings removed in 6.3.0 stay in the operator's file until they delete them, so say so
        // once per boot rather than letting an edited value fail silently.
        RemovedConfigKeys.warnIfStillPresent(config, getLogger());

        getLogger().info(i18n("bag_enabled"));
        return true;
    }

    /**
     * Writes no cached bag: a cached copy written here would overwrite a change another server sharing the
     * database made since. Every edit window still open keeps what it shows and closes; the claim service then
     * writes those and every other kept write for a bounded time (logging, with its items, any that cannot land),
     * stops the background claim renewal and releases every edit claim this server holds, so another server can
     * edit those pages at once (UltiKits/UltiRemoteBag#54, gate 1 F2 and F3).
     */
    @Override
    protected void onUnregister() {
        BagEditClaimService claimService = getContext().getBean(BagEditClaimService.class);
        if (claimService != null) {
            RemoteBagContentGUI.keepOpenEditWindowsForDisable();
            claimService.shutdown();
        }
        getLogger().info(i18n("bag_disabled"));
    }

    @Override
    public List<String> supported() {
        return Arrays.asList("zh", "en");
    }
}
