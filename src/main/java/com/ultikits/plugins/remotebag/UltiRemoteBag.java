package com.ultikits.plugins.remotebag;

import com.ultikits.plugins.remotebag.config.RemoteBagConfig;
import com.ultikits.plugins.remotebag.config.RemovedConfigKeys;
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
     * Writes nothing: every bag change was written when it was made, and a cached copy written here would
     * overwrite a change another server sharing the database made since (UltiKits/UltiRemoteBag#54).
     */
    @Override
    protected void onUnregister() {
        getLogger().info(i18n("bag_disabled"));
    }

    @Override
    public List<String> supported() {
        return Arrays.asList("zh", "en");
    }
}
