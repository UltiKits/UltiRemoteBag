# Changelog

All notable changes to this project are documented in this file.
Format based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

本文件记录本项目的所有重要更改，格式基于 [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)。

## [Unreleased]

### Fixed

- `/ul reload UltiRemoteBag` now reloads this module's configuration and refreshes its language
  catalogue; previously this module replaced the framework's reload step, so neither happened and
  an edited `config/remotebag.yml` took effect only after a restart (UltiKits/UltiRemoteBag#12).
- Unloading this module (`/upm uninstall UltiRemoteBag`, or server shutdown) still saves every
  cached bag first, and now also unregisters the module's commands afterwards, which previously
  never happened on either path; on `/upm uninstall` it now also unregisters the module's
  listeners, which previously stayed registered (UltiKits/UltiRemoteBag#12).
- `/ul reload UltiRemoteBag` 现在会重载本模块的配置并刷新其语言文件；此前本模块替换了框架的重载步骤，两者都不会发生，
  修改 `config/remotebag.yml` 后只有重启才会生效（UltiKits/UltiRemoteBag#12）。
- 卸载本模块（`/upm uninstall UltiRemoteBag` 或关闭服务器）时仍会先保存所有已缓存的背包，之后现在还会注销本模块的命令，
  此前两条路径都不会注销；通过 `/upm uninstall` 卸载时现在还会注销本模块的监听器，此前它们会一直保持注册
  （UltiKits/UltiRemoteBag#12）。

### Removed

- Removed the module's own 'configuration reloaded' console line; UltiTools 6.3.0 logs one reload
  line per module.
- 移除了本模块自身的"配置已重载"控制台日志行；UltiTools 6.3.0 会为每个模块输出一行重载日志。
