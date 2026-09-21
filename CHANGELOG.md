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
- Loading a bag page no longer destroys it when `rows_per_page` is set below 5. The page is read
  back at whatever size its stored slots need, so an item saved from one of the content GUI's
  higher slots survives; previously the load threw out of bounds, the failure was swallowed, and
  every item on that page was silently lost. A single unreadable entry in a stored page (a
  non-numeric or negative slot key, or one beyond the 54 slots that `rows_per_page`'s own 1-6 range
  makes addressable) is now skipped with a `WARNING` naming the page and the key, instead of costing
  the whole page. This does not change how many rows the GUI shows — it still
  shows and saves 45 slots whatever `rows_per_page` holds, which is still open as
  UltiKits/UltiRemoteBag#24 (UltiKits/UltiRemoteBag#24).
- An administrator no longer takes an edit lock from an owner who has the page open, however long that
  owner idles. `lock.timeout_seconds` reclaims a lock whose holder's session ended without releasing
  it; it is not a lease a present holder has to renew. Previously the lock was handed over once the
  configured timeout elapsed even though the owner was still looking at the page, and the owner's next
  save then wrote a snapshot taken before the administrator existed over the row — destroying whatever
  the administrator had added, or restoring an item the administrator had taken out so that it existed
  twice. The administrator is now told why their view is read-only instead of being downgraded
  silently (UltiKits/UltiRemoteBag#34).
- A save whose database write fails now says so instead of confirming a save. The page's own Save
  button, `/bag save` and closing the page in edit mode all reported success whenever the copy into
  memory succeeded, even when the write to the database did not, so an edit that existed only in
  memory was reported as stored and then lost on the next restart. "Nothing to save" and "the save
  failed" are also told apart now, because they ask different things of the operator
  (UltiKits/UltiRemoteBag#34).
- The read-only Refresh button now clears a slot the owner has emptied since you opened the view.
  Previously it drew only what was stored and left everything else as it was, so a refreshed view
  could keep showing an item the owner had already taken out (UltiKits/UltiRemoteBag#34).
- A bag page no longer overrides another plugin's decision to cancel a click or a drag. Previously
  editing your own page cleared a cancellation an anti-cheat or region plugin had already set
  (UltiKits/UltiRemoteBag#34).
- A hand-edited or foreign-written stored page whose slot key is written `+5` or `05` no longer
  silently drops an item: such a key parses to the same slot as its plain form and one of the two
  items disappeared with no warning. It is now skipped with the same warning as any other unreadable
  key, and those warnings now carry this module's own log prefix like every other line it emits
  (UltiKits/UltiRemoteBag#34).
- A drag confined to your own inventory is no longer refused while you are viewing somebody else's
  bag read-only. Read-only guards the bag, not your own inventory, which is already how clicking
  behaves. And a drag that IS refused now says why, in both modes, instead of failing silently
  (UltiKits/UltiRemoteBag#34).
- `/bag save` now reports only a save it actually performed. With nothing cached to save — a fresh
  login that has not opened a page — it says so instead of confirming a write that did not happen, and
  it no longer persists every cached page twice when it flushed an open page (UltiKits/UltiRemoteBag#34).
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
- 当 `rows_per_page` 被设为小于 5 时，加载背包页不再会销毁该页。读取时会按照所存槽位实际需要的大小还原，
  因此从内容界面较高槽位保存的物品能够保留；此前加载会数组越界，该失败被静默吞掉，该页中的所有物品都会悄无声息地
  丢失。所存页面中单个无法读取的条目（非数字、负数，或超出 `rows_per_page` 自身 1-6 取值所能寻址的 54 个槽位）现在只会被跳过，
  并输出一条指明页码与键名的 `WARNING`，而不再让整页作废。此改动不改变界面显示的行数——无论 `rows_per_page` 为何值，界面仍显示并保存 45 个槽位，
  这一点仍未解决（UltiKits/UltiRemoteBag#24）。
- 管理员不再从正打开该页的所有者手中夺取编辑锁，无论所有者空闲多久。`lock.timeout_seconds` 用于回收持有者会话
  异常结束而未释放的锁，并非持有者在场时仍需续期的租约。此前只要配置的超时时间到达，即使所有者仍在查看该页，锁
  也会被转交，而所有者随后的保存会把管理员出现之前的快照写回该行——销毁管理员放入的物品，或把管理员取出的物品
  还原，导致其存在两份。现在管理员会被告知其视图为何是只读，而不再被静默降级（UltiKits/UltiRemoteBag#34）。
- 数据库写入失败的保存现在会明确报错，而不再确认保存成功。此前只要写入内存缓存成功，页面自身的保存按钮、
  `/bag save` 以及在编辑模式下关闭页面都会报告成功，即使数据库写入并未成功，因此仅存在于内存中的改动会被
  报告为已保存，并在下次重启后丢失。现在也会区分「没有需要保存的内容」与「保存失败」，因为二者要求操作者
  采取的行动不同（UltiKits/UltiRemoteBag#34）。
- 只读模式下的刷新按钮现在会清空所有者在你打开视图后已取空的槽位。此前它只绘制已存储的内容，其余槽位保持原样，
  因此刷新后的视图可能仍显示所有者早已取走的物品（UltiKits/UltiRemoteBag#34）。
- 背包页不再覆盖其他插件取消点击或拖拽的决定。此前编辑自己的背包页会清除反作弊或领地插件已设置的取消标记
  （UltiKits/UltiRemoteBag#34）。
- 手工编辑或由外部写入的存档页中，写成 `+5` 或 `05` 的槽位键不再静默丢弃物品：这类键会解析成与其普通形式相同的
  槽位，导致两个物品中的一个无声消失。现在它会与其他无法读取的键一样被跳过并给出警告，且这些警告会带上本模块自身
  的日志前缀，与它输出的其他每一行一致（UltiKits/UltiRemoteBag#34）。
- 以只读方式查看他人背包时，完全在自己背包内进行的拖拽不再被拒绝：只读保护的是背包本身，而不是查看者自己的
  背包——点击操作原本就是如此。并且被拒绝的拖拽现在会在两种模式下都说明原因，而不再静默失败
  （UltiKits/UltiRemoteBag#34）。
- `/bag save` 现在只在确实完成保存时才如此报告。当没有任何缓存内容可保存时（例如刚登录且尚未打开过背包页），
  它会明确说明，而不再确认一次并未发生的写入；并且在刷新了打开的页面后，不再把每个缓存页重复保存两次
  （UltiKits/UltiRemoteBag#34）。

### Removed

- Removed the module's own 'configuration reloaded' console line; UltiTools 6.3.0 logs one reload
  line per module.
- 移除了本模块自身的"配置已重载"控制台日志行；UltiTools 6.3.0 会为每个模块输出一行重载日志。
