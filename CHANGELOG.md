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
  non-numeric or negative slot key, or one at or beyond slot 54) is now skipped with a `WARNING`
  naming the page and the key, instead of costing the whole page. Slots 45 to 53 are read but are
  not shown: the window holds 45, so an item stored in that band is invisible and is deleted by the
  first save of that page. Nothing this module writes can land there — it saves exactly 45 slots —
  so only a hand-edited or foreign-written row can hold one, and such a row should be repaired
  before the page is opened. The GUI has always shown and saved 45 slots, and
  `rows_per_page` has since been removed entirely, so a page's capacity is now that fixed 45 and
  nothing claims otherwise; see the `Changed` and `Removed` entries below
  (UltiKits/UltiRemoteBag#24).
- An administrator no longer takes an edit lock from an owner who has the page open, however long that
  owner idles. `lock.timeout_seconds` reclaims a lock whose holder's session ended without releasing
  it; it is not a lease a present holder has to renew. Previously the lock was handed over once the
  configured timeout elapsed even though the owner was still looking at the page, and the owner's next
  save then wrote a snapshot taken before the administrator existed over the row — destroying whatever
  the administrator had added, or restoring an item the administrator had taken out so that it existed
  twice. The administrator is now told why their view is read-only instead of being downgraded
  silently (UltiKits/UltiRemoteBag#34).
- A toolbar button can no longer be collected out of a bag page, or have one of your items merged
  into it. Two vanilla actions are not confined to the slot you clicked — a double-click sweeps
  matching stacks out of the whole page, and a shift-click from your own inventory looks anywhere in
  the page for somewhere to put the stack — so if you were holding an item identical to one of the
  buttons, the button could be taken (and reappear when the page was reopened, duplicating it) or
  your item could vanish into the button row and be lost when the page closed. Both are refused now,
  with a line saying why. Holding such an item is realistic on a server upgrading from a version
  where the buttons could be picked up (UltiKits/UltiRemoteBag#34).
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
- `lock.notify_readonly_viewers` now decides whether an administrator viewing a bag read-only is
  told when the owner starts using it again. Previously the line was sent whatever the setting
  said, so `lock.notify_readonly_viewers: false` did nothing; now it is honoured. The declared
  default is unchanged at `true`, which is what every server has been doing, so no server's
  behaviour changes until its operator turns it off. The setting is read each time rather than at
  startup, so `/ul reload UltiRemoteBag` applies a change without a restart. Its neighbour
  `lock.timeout_seconds` is NOT like that — it is still copied once when the module loads and needs
  a full restart, which is unchanged behaviour and is tracked as UltiKits/UltiRemoteBag#39
  (UltiKits/UltiRemoteBag#19).
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
  丢失。所存页面中单个无法读取的条目（非数字、负数，或等于及超过第 54 格）现在只会被跳过，
  并输出一条指明页码与键名的 `WARNING`，而不再让整页作废。第 45 至 53 格会被读取但不会被显示：
  窗口只有 45 格，因此存在该区间的物品不可见，并会在该页的第一次保存时被删除。本模块写入的任何
  内容都不会落在那里——它恰好保存 45 格——因此只有手改或由外部写入的行才会带有这样的条目，
  这样的行应在打开该页之前修复。界面一直显示并保存 45 个槽位，而 `rows_per_page` 已被整个移除，
  因此一页的容量就是这固定的 45 格，也再没有任何地方声称其他数字；见下文 `Changed`
  与 `Removed` 条目（UltiKits/UltiRemoteBag#24）。
- 管理员不再从正打开该页的所有者手中夺取编辑锁，无论所有者空闲多久。`lock.timeout_seconds` 用于回收持有者会话
  异常结束而未释放的锁，并非持有者在场时仍需续期的租约。此前只要配置的超时时间到达，即使所有者仍在查看该页，锁
  也会被转交，而所有者随后的保存会把管理员出现之前的快照写回该行——销毁管理员放入的物品，或把管理员取出的物品
  还原，导致其存在两份。现在管理员会被告知其视图为何是只读，而不再被静默降级（UltiKits/UltiRemoteBag#34）。
- 现在无法再把工具栏按钮从背包页中取走，也无法把自己的物品并入按钮中。有两种原版操作并不局限于你点击的那一格——
  双击会把整页中相同的物品全部收拢到光标上，从自己背包 Shift 点击则会在整页中寻找可放置的位置——因此如果你手持
  与某个按钮完全相同的物品，该按钮可能被取走（重新打开页面后又会重新生成，从而复制），或者你的物品会被并入按钮行
  并在关闭页面时丢失。现在两者都会被拒绝，并说明原因。从按钮可被取走的旧版本升级而来的服务器上，玩家持有这类物品
  是现实存在的情况（UltiKits/UltiRemoteBag#34）。
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
- `lock.notify_readonly_viewers` 现在真的决定以只读方式查看背包的管理员，是否会在所有者重新使用时收到提示。
  此前无论该设置为何都会发送该提示，`lock.notify_readonly_viewers: false` 毫无作用；现在它会被遵守。声明的默认值
  仍为 `true`，也就是所有服务器一直在做的事，因此在运维主动关掉它之前，没有任何服务器的行为会变化。
  该设置每次通知时实时读取而非在启动时缓存，因此 `/ul reload UltiRemoteBag` 无需重启即可生效。同一区块的
  `lock.timeout_seconds` 并非如此——它仍在模块加载时被拷贝一次，需要完整重启；这是未变的行为，
  已作为 UltiKits/UltiRemoteBag#39 跟踪（UltiKits/UltiRemoteBag#19）。

### Changed

- The main menu's `Slots Used: x/y` line now reports a page's real capacity. A full page reads
  `Slots Used: 45/45`. It used to read `Slots Used: 45/54` at the shipped settings — promising nine
  slots a player could never fill — and `Slots Used: 45/18` on a server that had lowered
  `rows_per_page`, reporting more used than the page was said to hold. The denominator was that
  setting; a page's capacity has always been a fixed 45 (UltiKits/UltiRemoteBag#24).
- 主界面的 `Slots Used: x/y` 那一行现在报的是背包页的真实容量，装满一页显示 `Slots Used: 45/45`。
  此前在出厂设置下它显示 `Slots Used: 45/54`——承诺了九个玩家永远填不上的槽位——而在调低了
  `rows_per_page` 的服务器上显示 `Slots Used: 45/18`，即已用数超过它声称的容量。分母原本就是那个设置；
  而一页的容量一直是固定的 45 格（UltiKits/UltiRemoteBag#24）。

### Removed

- Removed the module's own 'configuration reloaded' console line; UltiTools 6.3.0 logs one reload
  line per module.
- `auto_save_interval` never took effect and has been removed; it can be deleted from existing
  files. It named the period of a scheduled auto-save while the task ran on a fixed 300 seconds
  whatever the setting said, and that task is removed with it: every write to a bag page already
  persists it in the same action, so the task had nothing to catch. One real effect goes with it --
  when a database write failed, the task's next run retried it. Quitting and unloading the module
  still retry it, so on any clean stop the edit is still written — later than before, at the next
  quit or unload rather than within five minutes. It is not a retry in every case: if the server is
  killed, loses power, or is otherwise stopped without running those paths while a failed write is
  still only in memory, that edit is now lost where the five-minute retry would probably have
  caught it. One more observable effect goes with the task, for anyone watching the database from
  outside: it used to rewrite `last_updated` on every cached page every 300 seconds whether or not
  the page had changed, so an idle player's row kept ticking. It now only moves when the page is
  actually written. Nothing in this module reads the column, so there is no functional consequence,
  but a tool using it as a liveness signal will see it stop advancing
  (UltiKits/UltiRemoteBag#13, UltiKits/UltiRemoteBag#23).
- `gui_title` never took effect and has been removed; it can be deleted from existing files. A bag
  page's title comes from this module's language files, key `bag_name` -- which is why an English
  server already shows an English title and editing this setting changed nothing
  (UltiKits/UltiRemoteBag#14).
- `messages.no_permission` never took effect and has been removed; it can be deleted from existing
  files. A permission refusal comes from UltiTools' own translated message, so it already follows
  the server's `language` setting (UltiKits/UltiRemoteBag#15).
- `messages.page_locked` never took effect and has been removed; it can be deleted from existing
  files. Asking for a page you may not open is answered from this module's language files, key
  `page_out_of_range`; the page limit that refusal reports is the permission-derived one, so it is
  the same situation this setting described, and it is already translated. If what you were trying
  to change is the line shown when somebody **else** is holding the page, that is a different
  message: it is a hardcoded Chinese literal in the code, follows no language setting, and is
  tracked as UltiKits/UltiRemoteBag#20 — still open, and not fixed by this removal
  (UltiKits/UltiRemoteBag#16).
- `messages.bag_saved` never took effect and has been removed; it can be deleted from existing
  files. `/bag save` confirms with this module's language files, key `bag_saved_manually`
  (UltiKits/UltiRemoteBag#17).
- A server whose `config/remotebag.yml` still holds any of the settings removed above now logs one
  warning per key at startup, naming the module, the file and the key, and saying where that
  setting's job went instead. Removing a key from the code does not remove it from anybody's file,
  so without this an operator who had edited one of them would see no trace of the removal at all
  (UltiKits/UltiRemoteBag#13, #14, #15, #16, #17, #18, #23).
- `save_on_close` never took effect and has been removed; it can be deleted from existing files.
  Closing a bag page in edit mode always saves it, which is what this module has always done, so no
  server's behaviour changes. It was briefly wired instead, and that was reversed: with the setting
  off, an item the player had dragged into the window was destroyed — it had already left their own
  inventory, and nothing handed it back. Wanting a page that only saves on demand is reasonable and
  is recorded as UltiKits/UltiRemoteBag#37, with the constraint that any implementation must return
  the window's contents when it declines to save (UltiKits/UltiRemoteBag#18).
- `rows_per_page` has been removed; it can be deleted from existing files. A bag page holds a fixed
  45 slots, and nothing an operator could set ever changed that. The setting was a floor on the
  array a page is loaded with, the size of a newly created or cleared page, and the denominator of
  the `Slots Used` line — never a cap, and never the size of the window, which the code fixes at
  45 in both directions. Its only visible effect was that denominator, and it was wrong: see the
  `Changed` entry above. Nothing an operator has stored is affected, because what a page holds does
  not change. Making capacity genuinely configurable is wanted and is recorded as
  UltiKits/UltiRemoteBag#38 (UltiKits/UltiRemoteBag#24).
- 移除了本模块自身的"配置已重载"控制台日志行；UltiTools 6.3.0 会为每个模块输出一行重载日志。
- `auto_save_interval` 从来没有生效，现已移除；可从现有配置文件中删除。它声称设定定时自动保存的周期，
  而该任务无论该值为何都固定为 300 秒；该任务一并移除：对背包页的每一次写入都已在同一动作中持久化，
  它无事可做。随之消失的一个真实效果：数据库写入失败时，该任务的下一次运行会重试。玩家退出与模块卸载
  仍会重试，因此在任何一次正常停机下该修改仍会被写入，只是时机延后到下一次退出或卸载，而不再是五分钟之内。
  这并非在所有情形下都会重试：若服务器被强行终止、断电，或以其他方式在未走那两条路径的情况下停止，
  而一次失败的写入当时只存在内存中，那次修改就会丢失——而五分钟的重试很可能会赶上它。
  还有一个可观察效果一并消失，对从外部监看数据库的人而言：该任务原本每 300 秒就会把每个缓存页的
  `last_updated` 重写一遍，无论内容是否变化，因此空闲玩家的行也一直在跳。现在它只在页面真的被写入时才变。
  本模块没有任何地方读取该列，因此没有功能影响，但把它当作活跃信号的外部工具会发现它不再前进
  （UltiKits/UltiRemoteBag#13、UltiKits/UltiRemoteBag#23）。
- `gui_title` 从来没有生效，现已移除；可从现有配置文件中删除。背包页的标题来自本模块的语言文件（键 `bag_name`）——
  这也是英文服务器本来就显示英文标题、修改该设置毫无效果的原因（UltiKits/UltiRemoteBag#14）。
- `messages.no_permission` 从来没有生效，现已移除；可从现有配置文件中删除。权限拒绝由 UltiTools 自身的已翻译消息给出，
  本来就会跟随服务器的 `language` 设置（UltiKits/UltiRemoteBag#15）。
- `messages.page_locked` 从来没有生效，现已移除；可从现有配置文件中删除。请求一个无权打开的页面由本模块的语言文件
  回答（键 `page_out_of_range`）；该拒绝所报的页数上限就是按权限推导出来的那个，因此与该设置所描述的是同一情形，
  且已经是翻译过的。若你想改的是**别人**正持有该页时显示的那一行，那是另一条消息：它是代码里写死的
  中文字面量，不跟随任何语言设置，已作为 UltiKits/UltiRemoteBag#20 单独跟踪——仍未关闭，也不会因本次移除而修复
  （UltiKits/UltiRemoteBag#16）。
- `messages.bag_saved` 从来没有生效，现已移除；可从现有配置文件中删除。`/bag save` 的确认消息来自本模块的
  语言文件（键 `bag_saved_manually`）（UltiKits/UltiRemoteBag#17）。
- `save_on_close` 从来没有生效，现已移除；可从现有配置文件中删除。编辑模式下关闭背包页总是会保存，
  这就是本模块一直在做的事，因此没有任何服务器的行为会变化。曾经改为把它接线，该决定已被推翻：
  设为关闭时，玩家拖进窗口的物品会被销毁——它已经离开了玩家自己的背包，而没有任何路径把它退回。
  想要一个只在手动时保存的页面是合理的，已记在 UltiKits/UltiRemoteBag#37，并附上约束：任何实现在不保存时
  必须把窗口内容退还给玩家（UltiKits/UltiRemoteBag#18）。
- `rows_per_page` 已移除；可从现有配置文件中删除。一个背包页固定为 45 格，运维能设的任何值
  都从未改变这一点。该设置是加载页面时数组尺寸的下限、新建或清空页面的尺寸，以及 `Slots Used`
  那一行的分母——从来不是上限，也从来不是窗口大小，窗口在两个方向上都被代码固定为 45。
  它唯一可见的效果就是那个分母，而那个分母是错的，见上文 `Changed` 条目。运维已存的任何东西
  都不受影响，因为一页能装多少并未改变。想要真正可配置的容量是合理的，已记在
  UltiKits/UltiRemoteBag#38（UltiKits/UltiRemoteBag#24）。
- 若服务器的 `config/remotebag.yml` 中仍留有上述任何一个被移除的设置，现在启动时会逐键输出一条警告，
  点名模块、文件与该键，并说明该设置的职责转到了哪里。从代码中删键并不会从任何人的文件中删键，
  若无此警告，改过其中一个设置的运维将完全看不到任何移除的痕迹
  （UltiKits/UltiRemoteBag#13、#14、#15、#16、#17、#18、#23）。
