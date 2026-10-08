# Changelog

All notable changes to this project are documented in this file.
Format based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

本文件记录本项目的所有重要更改，格式基于 [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)。

## [Unreleased]

### Added

- **One bag page is edited on one server at a time** when several servers share one database. Opening a page
  for editing claims it in a new database table, `remote_bag_claims`; while another server holds the page it opens
  read-only on this server, for the owner and for administrators alike, with the line "This bag page is being
  edited on another server; it is open in read-only mode", and an administrator's `/bag clear` and `/bag delete`
  of that page are refused as for a page in use. The claim is released when the window closes, when its player
  quits and when the module stops. While the window is open, the server renews the claim on a background task, so a
  stalled main thread does not lose it; another server may take a claim over only after seeing it unrenewed for a
  full `lock.timeout_seconds` on its own clock, so a crashed server's claim lets go by itself and disagreeing clocks
  change nothing. If a claim is ever lost, the window turns read-only at once and gives back the items put in
  since its last save. On one server nothing changes: the owner still outranks an administrator. The table is
  created on the first start; no existing table changes and nothing is migrated. Known limitation: a writer that
  does not use the claim -- an older version of this module during a rolling upgrade, another plugin, a hand edit of
  the table -- can still change a page under an open window; an item taken out during that session can then exist
  twice (README, "Known limitations") (UltiKits/UltiRemoteBag#54).
- **多台服务器共享一个数据库时，同一背包页同一时间只能在一台服务器上编辑。** 打开编辑时会在新的数据库表 `remote_bag_claims` 中占用该页；
  另一台服务器占用期间，本服务器对所有者和管理员都以只读方式打开，并提示“该背包页正在另一台服务器上编辑，当前为只读模式”，管理员对该页的
  `/bag clear` 与 `/bag delete` 也会被拒绝。窗口关闭、玩家退出、模块停止时释放占用。窗口打开期间，服务器在后台任务中续期占用，主线程卡顿
  不会丢失占用；另一台服务器只有在自己的时钟上连续 `lock.timeout_seconds` 看到占用未续期后才能接手，因此崩溃服务器的占用会自行释放，
  各服务器时钟不一致也没有影响。若占用丢失，窗口立即变为只读，并归还自上次保存以来放入的物品。单台服务器上的规则不变。该表在首次启动时创建；
  现有表不变，也没有任何迁移。已知限制：不使用占用的写入者（滚动升级期间的旧版本模块、其他插件、手动编辑数据表）仍可能在窗口打开时改动该页，
  该会话中取出的物品可能出现两份（见 README“已知限制”）（UltiKits/UltiRemoteBag#54）。
- **A bag page survives a database that does not answer.** Every database call of the edit claim and of a page
  save is given up on after a sixth of `lock.timeout_seconds` (at most two seconds while the main thread waits),
  and one call that hangs no longer holds up the claims of other pages. If the claim's renewal fails or does not
  answer, the window turns read-only at once and what it shows is saved as soon as the database answers. If a save
  fails or does not answer -- at close, on the Save button, at quit or by `/bag save` -- the claim is kept, the
  changes stay in memory and the same save is retried in the background until it lands; meanwhile the page is
  read-only everywhere and a reopen on this server shows the kept changes. Nothing is given back while such a save
  can still land. Only a save the database refuses gives back the items put in (at the next join if the player has
  left). Module stop now saves the pages still open for editing, keeps trying kept saves for five seconds and logs,
  with their items, those it could not write. Every page save is fenced on the claim: one database transaction
  checks that the claim is still this server's and writes the page, so a save held up past another server's
  takeover writes nothing, and a save that reported an error is given back only when its receipt shows it was not
  written; at module stop that receipt is read behind any save still running on the database, and a save that
  cannot be decided in time is logged, not given back. `/bag clear` and `/bag delete` claim the page for their own action.
  Known limitation: a kept save is held in memory and a server crash loses it, as it loses an unsaved window
  (README, "Known limitations") (UltiKits/UltiRemoteBag#54).
- **数据库未响应时，背包页不再丢失改动。** 占用和保存的每次数据库调用最多等待 `lock.timeout_seconds` 的六分之一（主线程等待时最多两秒），
  一次挂起的调用不再耽误其他页面的占用续期。续期失败或未响应时，窗口立即变为只读，窗口中显示的内容在数据库恢复响应后立即保存。保存失败或未响应时——
  关闭窗口、点击保存按钮、退出或 `/bag save`——保留占用，改动保留在内存中，并在后台重试同一保存直到成功；期间该页在所有服务器上只读，
  在本服务器重新打开会显示保留的改动。只要这次保存仍可能完成，就不归还任何物品。
  只有被数据库拒绝的保存才归还放入的物品（玩家已离开时在其下次加入时归还）。模块停止时会保存仍在编辑中打开的背包页，对保留的保存再尝试五秒，
  无法写入的连同物品记录在日志中。每次保存都以占用为栅栏：同一个数据库事务确认占用仍属于本服务器并写入页面，因此在另一台服务器接手之后被耽搁的保存不会写入任何内容，
  报告了错误的保存只有在其记录表明未写入时才会被归还；模块停止时，该记录会在仍在数据库上运行的保存之后读取，无法及时判定的保存只记录日志、不归还。`/bag clear` 与 `/bag delete` 会为自身操作占用该页。已知限制：保留的保存在内存中，服务器崩溃会丢失它，
  与未保存的窗口相同（见 README“已知限制”）（UltiKits/UltiRemoteBag#54）。

### Fixed

- **Bag pages on servers that share one database.** A bag page changed on another server sharing the database is
  no longer overwritten by an old copy held on this server, so an item taken out there no longer comes back here
  (duplicated), and an item put in there is no longer lost. Each server kept every page of a player's bag in memory
  from the first read -- also after an administrator only looked at another player's bag with `/bag see` or
  `/bag list` -- and wrote the whole copy back: every cached page of that player on any save, and every cached page
  of every player when the module stopped. Now nothing is written from that copy. A page is saved only from its own
  window, only that page, and only if the stored page is still what the window read when it opened (or last saved);
  otherwise nothing is written, the console logs `Failed to update bag data`, the player sees the existing
  "The save did not reach the database" line, and the items put into the window since it read the page are given
  back to the player (what does not fit drops at their feet; one console line lists them). Creating, clearing and deleting a page are decided on what is stored
  at that moment, and write only that page. Stopping or unloading the module, quitting, and `/bag save` with no page
  open write nothing (every change is written when it is made; `/bag save` still answers `Bag saved manually!` when
  this server holds any of your pages). A copy of another player's bag is dropped when the administrator's window
  closes or the command is done, unless that player is on this server (UltiKits/UltiRemoteBag#54).
- **共享同一数据库的多台服务器上的背包页。** 另一台共享该数据库的服务器修改过的背包页，不会再被本服务器持有的旧副本覆盖：在那里取出的物品
  不会在这里重新出现（复制），在那里放入的物品也不会丢失。此前每台服务器从首次读取起就把玩家背包的每一页保存在内存里——包括管理员只是用
  `/bag see` 或 `/bag list` 查看了别人的背包之后——并整份写回：任意一次保存都会写回该玩家所有缓存页，模块停止时还会写回所有玩家的所有缓存页。
  现在不再从这份副本写入任何内容。背包页只能由它自己的窗口保存，只写这一页，并且仅当存储的内容仍是窗口打开时（或上次保存时）读到的内容时
  才写入；否则不写入，控制台记录 `Failed to update bag data`，玩家看到既有的“保存未写入数据库”提示，自窗口读取该页以来放入的物品
  归还给玩家（放不下的掉落在脚下；控制台记录一行列出这些物品）。创建、清空、删除背包页都以当时存储的内容
  为准，且只写这一页。停止或卸载模块、玩家退出、以及没有打开页面时的 `/bag save` 都不写入任何内容（每次改动在发生时就已写入；本服务器持有你的
  背包页时，`/bag save` 仍回复“背包已手动保存！”）。管理员查看他人背包的窗口关闭或命令结束后，该玩家背包的副本即被丢弃，除非该玩家就在本服务器上
  （UltiKits/UltiRemoteBag#54）。
- A bag save whose stored row has been deleted in the meantime is now reported as not saved. On a MySQL
  database shared by several servers, an administrator on another server who deletes a bag page at the moment
  this server saves it made that save write nothing while `/bag save` still answered that the bag was saved;
  now the save logs `Failed to update bag data` and the save is reported as failed (UltiKits/UltiRemoteBag#50;
  since UltiKits/UltiRemoteBag#54 a save writes only its own page).
- 已存储的背包行在保存时已被删除，现在会报告为未保存。多个服务器共用同一个 MySQL 数据库时，若另一台服务器上的管理员恰好在本服务器保存时
  删除了某一页背包，该次保存什么也没写入，`/bag save` 却仍回复背包已保存；现在会记录 `Failed to update bag data`，
  并且报告保存失败（UltiKits/UltiRemoteBag#50；自 UltiKits/UltiRemoteBag#54 起一次保存只写它自己的那一页）。

- `config/remotebag.yml` now writes its comments in the server's language. Thirteen comments (the `economy.*`,
  `sound.*` and `lock.*` settings) used to be Chinese-only, so a fresh install under `language: en` got a file
  with Chinese comments. Each is now a language-file key that the framework resolves in the server's `language`
  every time it writes the file, with an English and a Chinese entry in `lang/en.yml` and `lang/zh.yml`. On an
  existing server the comments the framework wrote on these thirteen settings, the ones earlier versions wrote
  included (also the two older comments above `lock.timeout_seconds`, release v1.0.0's and the later bilingual
  one, UltiKits/UltiRemoteBag#52), switch to the server's language at the next start, and after you change
  `language` and run a bare `/ul reload`; values are
  untouched, and a comment you wrote yourself is kept as you wrote it (UltiKits/UltiTools-Reborn#611)
  (UltiKits/UltiRemoteBag#48).
- `config/remotebag.yml` 的注释现在跟随服务器语言。此前有十三条注释（`economy.*`、`sound.*`、`lock.*` 各设置）只有中文，`language: en`
  的全新安装得到的文件注释是中文。现在每条注释都是一个语言文件键，框架每次写入文件时按服务器的 `language` 解析，`lang/en.yml` 与 `lang/zh.yml`
  各有英文和中文条目。已有服务器上框架在这十三个设置上写下的注释（包括旧版本写下的注释，`lock.timeout_seconds` 上方的两种旧注释——v1.0.0 版本的和之后的中英双语注释——也在内，
  UltiKits/UltiRemoteBag#52）会在下次启动时、以及你修改 `language` 并执行不带参数的 `/ul reload` 后切换为服务器语言；设置值不受影响，
  你自己写的注释保持原样（UltiKits/UltiTools-Reborn#611）（UltiKits/UltiRemoteBag#48）。

- When an administrator has deleted one of a player's middle bag pages (for example the player keeps pages 1
  and 3), the next-page icon, its price, the page limit and the page that is created now all use the same page
  number, the one after the highest page the player has (page 4 in the example). Before, the icon and the price
  counted pages instead and said "page 3" while page 4 was created, and at a limit of 3 the purchase took the
  money and then refused to create page 4. Now the icon is not offered when the next page number is above the
  limit, so nothing is charged for a page that cannot be created (UltiKits/UltiRemoteBag#46).
- 管理员删除了玩家中间的某一页背包后（例如玩家只剩第 1、3 页），下一页图标、价格、页数上限检查和实际创建的页码现在统一使用同一个页码：
  玩家现有最高页的下一页（例子中为第 4 页）。此前图标和价格按「页数」计算，显示「第 3 页」而实际创建的是第 4 页；页数上限为 3 时，购买会先扣款、
  再因无法创建第 4 页而失败。现在下一页页码超过上限时不再显示该图标，因此不会为无法创建的页扣款（UltiKits/UltiRemoteBag#46）。

- The module loads on UltiTools-API 6.3.0 again. Its start-up warning about settings removed in 6.3.0 that
  are still in `config/remotebag.yml` used to read the configuration object that 6.3.0 removes, so on 6.3.0
  the module was refused at load with a `NoSuchMethodError`. It now asks the framework whether each removed
  key is present in the file (`isPresentInFile`), with the same warnings in the same order and language as
  before: one per removed key still in the file, none for a file without them, and none for a file the
  framework could not parse (UltiKits/UltiRemoteBag#49).
- 本模块可再次在 UltiTools-API 6.3.0 上加载。此前启动时关于「6.3.0 已移除、但仍留在 `config/remotebag.yml` 中的设置」的警告读取的是
  6.3.0 移除的配置对象，因此在 6.3.0 上模块加载时被拒绝并抛出 `NoSuchMethodError`。现在改为向框架询问每个已移除的键是否出现在文件中
  （`isPresentInFile`），警告内容、顺序和语言与之前相同：文件中仍有的已移除键各一条，没有则不警告，框架无法解析的文件也不警告（UltiKits/UltiRemoteBag#49）。

- With `economy.enabled: false`, or no economy plugin installed, the bag list window (`/bag`) now shows
  a green `Create New Bag` icon while the player is under their page limit; clicking it adds the next page
  for free and answers `Created bag #<n>!`. Before, only the paid purchase icon existed, and it was hidden
  whenever the economy was off, so such a server had no way to add a bag page (UltiKits/UltiRemoteBag#25).
  If the economy comes on or goes off while the window is open, a click on either icon does nothing,
  answers `The price of a new bag has changed. Check the menu again.` and redraws the window, so a free
  icon never charges and a priced one never acts for free.
- 当 `economy.enabled: false` 或未安装经济插件时，背包列表窗口（`/bag`）在玩家未达页数上限时会显示绿色的「创建新背包」图标，
  点击即可免费添加下一页，并提示「已创建背包 #<n>！」。此前只有付费购买图标，且经济关闭时该图标隐藏，这样的服务器无法添加背包页（UltiKits/UltiRemoteBag#25）。
  窗口打开期间经济开启或关闭时，点击任一图标都不执行，提示「新背包的价格已变化，请重新查看菜单。」并刷新窗口：免费图标不会扣费，付费图标也不会免费生效。

- The administrator commands `/bag see`, `/bag create`, `/bag delete`, `/bag clear` and `/bag list`
  now accept a player who is online in their very first session: the target is found by exact name
  among online players first, and anyone else is found, as before, when the name has joined this server
  before. Before, all of them answered `Player not found` for an online first-time player, because they
  relied only on Bukkit's "played before" record, which is not written until the first session ends. A
  name that has never joined is still not found, and a partial name never matches an online player
  (UltiKits/UltiRemoteBag#30).
- 管理员命令 `/bag see`、`/bag create`、`/bag delete`、`/bag clear`、`/bag list` 现在接受首次进服、仍在线的玩家：先按完整名字在在线玩家中查找，
  其他玩家仍按「曾经进服」记录查找，与此前相同。此前这些命令只依赖 Bukkit 的「曾经进服」记录（首次会话结束前不会写入），对这样的玩家一律回复「找不到玩家」。
  从未进过服的名字仍然找不到，部分名字不会匹配在线玩家（UltiKits/UltiRemoteBag#30）。

- `/bag list <player>` and `/bag see <player>` now report a player with no stored bag page as having
  none (`No bags` / `Player <player> has no bags`), including after an administrator deletes every
  page; before, page 1 was always listed. A player's own `/bag` always offers page 1, stored or not, so
  a player who adds page 2 before ever using page 1 keeps page 1; `/bag create <player>` for a player
  with no stored page now creates page 1 (UltiKits/UltiRemoteBag#26).
- `/bag list <玩家>` 和 `/bag see <玩家>` 现在会如实报告没有已存储背包页的玩家（「没有背包」/「玩家 <玩家> 没有背包」），
  包括管理员删除全部背包页之后；此前总会列出第 1 页。玩家自己的 `/bag` 始终提供第 1 页（无论是否已存储），先添加第 2 页的玩家也不会失去第 1 页；
  对没有已存储背包页的玩家执行 `/bag create <玩家>` 现在会创建第 1 页（UltiKits/UltiRemoteBag#26）。

- In a read-only bag page, pressing a number key or the off-hand swap key over an empty slot now
  answers `Read-only mode, cannot move items` and plays the error sound like every other refused move;
  before, it was refused silently. Nothing could move either way (UltiKits/UltiRemoteBag#35).
- 只读背包页中，在空格子上按数字键或副手交换键时，现在会像其它被拒绝的移动一样提示「只读模式，无法移动物品」并播放错误音效；
  此前是静默拒绝。两种情况下物品都不会移动（UltiKits/UltiRemoteBag#35）。

- `lock.timeout_seconds` in `config/remotebag.yml` now follows `/ul reload UltiRemoteBag`: the lock
  service reads it each time it decides whether a lock can be reclaimed. Before, the value was copied
  once when the module loaded, so a changed timeout took effect only after a server restart
  (UltiKits/UltiRemoteBag#39).
- `config/remotebag.yml` 中的 `lock.timeout_seconds` 现在随 `/ul reload UltiRemoteBag` 生效：锁服务每次判断能否回收锁时都读取该值。
  此前该值只在模块加载时复制一次，修改后要重启服务器才生效（UltiKits/UltiRemoteBag#39）。

- Re-running `/bag <page>` or `/bag see <player> <page>` on a page you already have open no longer
  releases its lock, so two players can no longer edit the same page at once (UltiKits/UltiRemoteBag#41).
  These two commands, and `/bag see <player>`, now close whatever window you have open before they
  decide the lock, so a page you had open is saved and released first, also when the page you asked
  for is then refused.
- 修复：对已打开的页再次执行打开命令不再释放该页的锁，两名玩家不能再同时编辑同一页
  （UltiKits/UltiRemoteBag#41）。这两个命令以及 `/bag see <玩家>` 现在会在判断锁之前先关闭你当前打开的窗口，
  因此你原先打开的页会先保存并释放，即使你请求的页随后被拒绝也是如此。

- `language: en` now applies to the line shown when somebody else holds the bag page you open:
  "This bag is being used by <name>; it is open in read-only mode", "This bag is being used by
  <name>, please try again later" and "This bag is being edited by admin <name>, please try again
  later" were fixed Chinese text in every language (UltiKits/UltiRemoteBag#20). The `/bag` command's
  description (shown by `/help`) now follows `language` too.
- `language: zh` now applies to the console lines that were fixed English text: the module's
  enabled and disabled lines (which the language files already carried), the warning about a
  setting this version no longer reads, a skipped unreadable slot when a bag page is loaded, and a
  failed bag write or read. Their English wording is unchanged, except that the warning for a
  leftover `messages.page_locked` now says the someone-else-holds-the-page lines come from the
  language files, where it used to point at this issue as still open.
- `language: en` 现在对打开一个正被他人占用的背包页面时显示的那一行生效：「该背包正被 <名字> 使用中，当前为只读模式」
  「该背包正被 <名字> 使用中，请稍后再试」「该背包正被管理员 <名字> 编辑中，请稍后再试」原先在任何语言下都是写死的中文
  （UltiKits/UltiRemoteBag#20）。`/bag` 命令的描述（由 `/help` 显示）现在也跟随 `language`。
- `language: zh` 现在也对原先写死为英文的控制台日志生效：本模块的启用与禁用日志（语言文件里本来就有这两条）、
  关于本版本不再读取的设置的警告、加载背包页面时跳过无法读取的槽位，以及背包写入或读取失败。它们的英文措辞不变，
  只是残留的 `messages.page_locked` 的警告现在说明「页面被他人占用」的那几行来自语言文件，而不再指向本问题仍未解决。

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

- This version requires UltiTools 6.3.0 or later and declares `api-version: 630` in `plugin.yml`
  (it was `621`; the last release, 1.0.0, declared `620`). An older framework refuses the module
  before its start-up runs, with a warning that the UltiTools version is outdated; the refusal names
  the module by its `plugin.yml` `name:`, `UltiRemoteBag`. The README's framework minimum and its
  UltiTools-API badge now say UltiTools 6.3.0+, and its server and Java badges say Paper 1.21+ and
  Java 21+ (they said Minecraft 1.13–1.21 and Java 8+). Adding or replacing a module JAR needs a server restart; `/ul reload` does not load it
  (UltiKits/UltiTools-Reborn#544).
- 本版本需要 UltiTools 6.3.0 或更高版本，并在 `plugin.yml` 中声明 `api-version: 630`（原为 `621`；上一个发布版本 1.0.0 声明的是 `620`）。更早的框架会在模块的启动逻辑
  运行之前拒绝加载它，并给出 UltiTools 版本过旧的警告；拒绝信息以 `plugin.yml` 的 `name:` 即 `UltiRemoteBag` 指代本模块。
  README 中的框架最低版本和 UltiTools-API 徽章已改为 UltiTools 6.3.0+，服务端与 Java 徽章改为 Paper 1.21+、Java 21+
  （原为 Minecraft 1.13–1.21、Java 8+）。新增或替换模块 JAR 后需要重启服务器，`/ul reload` 不会加载它
  （UltiKits/UltiTools-Reborn#544）。

- `plugin.yml` now declares `identify-string: ultiremotebag`, the key of this module's entry in the
  UltiCloud catalogue. The framework's update check and `/upm update` skip a module that does not
  declare it, so this module now takes part in both: a later published version carrying the same key
  is reported at startup and can be installed with `/upm update` (UltiKits/UltiTools-Reborn#474).
- `plugin.yml` 现在声明 `identify-string: ultiremotebag`，即本模块在 UltiCloud 模块目录中的条目键。框架的更新检查和
  `/upm update` 会跳过未声明该键的模块，因此本模块现在会参与两者：带有同一键的更高发布版本会在启动时提示，
  并可用 `/upm update` 安装（UltiKits/UltiTools-Reborn#474）。

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

- `RemoteBagService#setBagPage`, `#saveBag` and `#saveAllBags` are removed. They wrote a cached copy of a player's pages
  over the stored ones, which on a shared database overwrote another server's changes. A plugin that changes a page
  reads it with `readPage` and saves it with `savePage`, which writes only if the stored page is still what was read
  (UltiKits/UltiRemoteBag#54).
- 移除 `RemoteBagService#setBagPage`、`#saveBag` 与 `#saveAllBags`。它们会把玩家背包页的缓存副本写回覆盖已存储的内容，在共享数据库上
  会覆盖其他服务器的修改。需要修改背包页的插件请用 `readPage` 读取、`savePage` 保存；仅当存储的内容仍是读取时的内容时才会写入
  （UltiKits/UltiRemoteBag#54）。
- The language-file entry `opening_bag` ("Opening bag #{0}...") from `lang/en.yml` and
  `lang/zh.yml`: no code ever displayed it.
- 从 `lang/en.yml` 与 `lang/zh.yml` 中移除语言文件条目 `opening_bag`（「正在打开背包 #{0}...」）：从未有任何代码显示它。

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
