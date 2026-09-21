# Changelog

All notable changes to this project are documented in this file.
Format based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

本文件记录本项目的所有重要更改，格式基于 [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)。

## [Unreleased]

### Fixed

- `/ul reload UltiRemoteBag` now reloads this module's configuration and refreshes its language
  catalogue; previously this module replaced the framework's reload step, so neither happened and
  an edited `config/remotebag.yml` took effect only after a restart (UltiKits/UltiRemoteBag#12).
- Unloading this module with `/upm uninstall UltiRemoteBag` still saves every cached bag first;
  afterwards the module's commands are now really removed and its listeners stop firing, where
  previously both stayed active until the server restarted (UltiKits/UltiRemoteBag#12).
- Viewing another player's bag read-only (`/bag see <player>` while that player is holding the page
  open) no longer lets the viewer move items. A normal click, a shift-click in either direction, a
  number-key swap and a drag are all refused now, and the greyed-out Save icon can no longer be
  picked up out of the toolbar. Previously every one of these went through while the "Read-only
  mode, cannot move items" refusal was still being shown, so an item could be taken out of another
  player's bag and duplicated (UltiKits/UltiRemoteBag#27).
- Editing your own bag page now works for every gesture: clicking, shift-clicking and number-key
  swapping within the page, and dragging. Previously every click on one of the page's own 45 content
  slots was cancelled and so was every drag, so an item could only be put in by shift-clicking it
  from your own inventory, and could never be rearranged or taken back out by clicking
  (UltiKits/UltiRemoteBag#27).
- The toolbar icons on the bottom row of a bag page (Back, Refresh, Save, the mode indicator, the
  fillers and Close) can no longer be picked up as items, in either mode; clicking one still
  performs its action (UltiKits/UltiRemoteBag#27).
- `/bag save` now stores an item placed into a bag page that is still open. Previously it saved
  only what had already reached the plugin's cache — which happens when the page's own Save button
  is clicked, or when it is closed in edit mode — so an item placed and then saved with `/bag save`
  was reported as saved and then lost on the next restart (UltiKits/UltiRemoteBag#22).
- `/ul reload UltiRemoteBag` 现在会重载本模块的配置并刷新其语言文件；此前本模块替换了框架的重载步骤，两者都不会发生，
  修改 `config/remotebag.yml` 后只有重启才会生效（UltiKits/UltiRemoteBag#12）。
- 通过 `/upm uninstall UltiRemoteBag` 卸载本模块时仍会先保存所有已缓存的背包；之后本模块的命令现在会被真正移除，
  其监听器也不再触发，此前两者都会保持生效，直到服务器重启（UltiKits/UltiRemoteBag#12）。
- 以只读方式查看他人背包（该玩家正打开该页时执行 `/bag see <player>`）现在真的无法移动物品：普通点击、
  两个方向的 Shift 点击、数字键交换与拖拽全部被取消，置灰的保存图标也不再能被拿走。此前这些操作都会生效，
  而"只读模式，无法移动物品"的拒绝提示仍会照常显示，因此可以把他人背包中的物品取走并复制
  （UltiKits/UltiRemoteBag#27）。
- 编辑自己的背包页现在对所有操作都有效：页内点击、Shift 点击、数字键交换以及拖拽。此前该页自身 45 个内容
  格上的每一次点击以及每一次拖拽都会被取消，物品只能通过从自己背包 Shift 点击塞进去，且无法用点击整理或取回
  （UltiKits/UltiRemoteBag#27）。
- 背包页底行的工具栏图标（返回、刷新、保存、模式指示、填充格与关闭）在两种模式下都不再能被当作物品拿走；
  点击它们仍会执行各自的功能（UltiKits/UltiRemoteBag#27）。
- `/bag save` 现在会保存放入仍处于打开状态的背包页中的物品。此前它只保存已经进入插件缓存的内容（点击该页
  自身的保存按钮，或在编辑模式下关闭该页时才会进入缓存），因此放入物品后立刻执行 `/bag save`，会收到已保存
  的提示，但该物品会在下次重启后丢失（UltiKits/UltiRemoteBag#22）。

### Removed

- Removed the module's own 'configuration reloaded' console line; UltiTools 6.3.0 logs one reload
  line per module.
- 移除了本模块自身的"配置已重载"控制台日志行；UltiTools 6.3.0 会为每个模块输出一行重载日志。
