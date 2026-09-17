# Changelog

All notable changes to this project are documented in this file.
Format based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

本文件记录本项目的所有重要更改，格式基于 [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)。

## [Unreleased]

### Fixed

- Reloading or unloading this module now actually runs the framework's own steps, which were
  previously silently skipped (UltiKits/UltiRemoteBag#12). `/ul reload UltiRemoteBag` now reloads
  the configuration, refreshes the language catalogue and reports `@ConditionalOnConfig` drift.
  Unloading the module (for example `/upm uninstall UltiRemoteBag`) still saves every cached bag
  first, and then unregisters the module's commands and listeners.
- 重载或卸载本模块时，现在会真正执行框架自身的步骤，此前这些步骤会被静默跳过（UltiKits/UltiRemoteBag#12）。
  `/ul reload UltiRemoteBag` 现在会重载配置、刷新语言文件并报告 `@ConditionalOnConfig` 漂移；
  卸载本模块（例如 `/upm uninstall UltiRemoteBag`）时仍会先保存所有已缓存的背包，然后注销本模块的命令和监听器。

### Removed

- Removed the module's own 'configuration reloaded' console line; UltiTools 6.3.0 logs one reload
  line per module.
- 移除了本模块自身的"配置已重载"控制台日志行；UltiTools 6.3.0 会为每个模块输出一行重载日志。
