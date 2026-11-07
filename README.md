# UltiRemoteBag

[![UltiTools-API](https://img.shields.io/badge/UltiTools--API-6.3.0%2B-blue)](https://github.com/UltiKits/UltiTools-Reborn)

**UltiRemoteBag** 是 UltiTools-API 框架的一个插件模块，为 Minecraft 服务器提供虚拟云存储（远程背包）功能。玩家可以随时随地访问自己的远程背包，安全存储物品。

## ✨ 功能特性

### 核心功能

- 🎒 **多页背包系统** - 每个玩家可拥有多个背包页，每页默认 45 个槽位，可配置为 9–54 个槽位
- 💾 **Save on close / 关闭时保存** - Save contents when closing a page in edit mode. 编辑模式下关闭页面时保存内容。
- 🔐 **背包锁定机制** - 防止并发访问冲突，支持编辑/只读模式
- 📊 **物品统计** - 在 GUI 中显示物品数量和槽位占用

### 经济集成

- 💰 **Vault 经济支持** - 可配置的背包购买价格
- 📈 **价格递增** - 可选的价格递增公式：`basePrice × (1 + rate)^(n-1)`
- 💵 **余额显示** - 购买界面显示当前余额和价格

### 管理员功能
- 👁️ **查看玩家背包** - 管理员可查看任意玩家的背包
- ➕ **创建背包** - 为玩家创建新的背包页
- 🗑️ **删除背包** - 删除指定玩家的背包页
- 🧹 **清空背包** - 清空背包内容但保留页面
- 📋 **列出背包** - 查看玩家所有背包的概览

### 访问控制

- 🔒 **所有者优先** - 当所有者使用背包时，管理员自动进入只读模式
- 🔄 **模式切换** - 只读模式下可刷新检查是否可升级为编辑模式
- ⏱️ **锁超时** - 可配置的锁超时时间，防止死锁

### 用户体验

- 🔊 **音效反馈** - 可配置的打开、关闭、购买、错误音效
- 🎨 **现代化 GUI** - 美观的分页界面，清晰的状态指示
- 🌍 **多语言支持** - 内置中文和英文支持

## 📦 安装

### 依赖项
- **UltiTools-API 6.3.0+** - 核心框架（本模块声明 `api-version: 630`，更早的框架会拒绝加载它）
- **Vault** (可选) - 经济功能支持

### 安装步骤

1. 确保已安装 UltiTools-API
2. 将 `UltiRemoteBag.jar` 放入 `plugins/UltiTools/plugins/` 目录
3. 重启服务器（新增或替换模块 JAR 后必须重启；`/ul reload` 只重载配置，不会加载新的模块 JAR）
4. 编辑配置文件 `plugins/UltiTools/pluginConfig/UltiRemoteBag/config/remotebag.yml`

## Optional manual saving / 可选手动保存

Closing an edit page still saves it by default. To require an explicit save instead, set
`require_manual_save: true` in `config/remotebag.yml` and reload the module (UltiRemoteBag#37).

- A changed page **refuses to close**: Escape and Close reopen the **same inventory** on the next
  server tick and tell the viewer to save first. Its items and edit claim are retained, not replaced
  from an older database copy. Back and bag-navigation commands are also refused while it is unsaved.
- Click **Save**, or use `/bag save` for your own open page, then close. An unchanged or explicitly saved
  page can close without another write. An administrator editing someone else's bag must use that
  page's Save button; `/bag save` still affects only the sender's own bag.
- Quit, death, module disable and normal server stop cannot be refused, so they still save for safety.
  If a refused page cannot reopen (for example another plugin cancels opening), it is also safety-saved.
  Database failure and lost-claim handling retain the existing recovery behaviour described below.
- This is **close refusal, not durable escrow**. The unsaved editing window has the existing crash and
  database-outage limitations: save promptly. It never intentionally discards items merely because the
  close-save option is disabled.
- The removed `save_on_close` key stays ignored and warned, not an alias. An upgraded server retaining
  that formerly ignored key does not silently opt into new behaviour. Operator configuration values
  are not overwritten; reloading applies the new option to subsequent close/navigation attempts.

默认关闭编辑页仍会保存。启用 `require_manual_save: true` 并重载后，有改动的页面会**拒绝关闭**：按 Escape
或关闭按钮会在下一 tick 重开同一个物品栏并提示先保存，保留当前物品和编辑占用，不会从旧数据库内容重建。
返回按钮和背包导航命令同样会被拒绝。请先点击保存（自己的页面也可使用 `/bag save`）再关闭；未改动或已保存的
页面可以直接关闭，不会再写一次。管理员编辑他人背包时须使用该页保存按钮。退出、死亡、模块停用、正常停服及
无法重开时仍作安全保存，数据库故障和占用失效仍按既有恢复规则处理。这是拒绝关闭，不是持久暂存；未保存窗口
仍受既有崩溃/数据库故障限制，请及时保存。旧 `save_on_close` 仍被忽略并告警，不是别名；升级不会意外启用新模式，
也不会覆盖服主配置值。

## ⚙️ 配置说明

### remotebag.yml

```yaml
# 基础配置
default_pages: 1                # 新玩家默认背包页数 (1-100)
max_pages: 10                   # 最大背包页数 (1-100)
content_rows: 5                 # 每页储物行数 (1-6，每行9格，默认45格)
require_manual_save: false      # 未保存的页面拒绝关闭并自动重开；退出/停服安全保存

# 权限配置
permission_based_pages: true    # 是否基于权限决定页数
permission_prefix: "ultibag.pages."  # 权限前缀

# 经济配置（需要 Vault）
economy:
  enabled: true                 # 是否启用购买背包
  base_price: 10000             # 基础价格 (0-1000000000)
  price_increase_enabled: true  # 是否启用价格递增
  price_increase_rate: 0.1      # 递增比率 (0-10)

# 音效配置
sound:
  enabled: true                 # 是否启用音效
  open: "BLOCK_CHEST_OPEN"      # 打开音效
  close: "BLOCK_CHEST_CLOSE"    # 关闭音效
  purchase: "ENTITY_PLAYER_LEVELUP"  # 购买音效
  error: "ENTITY_VILLAGER_NO"   # 错误音效
  volume: 1.0                   # 音量 (0-1)
  pitch: 1.0                    # 音调 (0.5-2)

# 锁定配置
lock:
  timeout_seconds: 300          # 锁超时时间（秒，10-3600）
  notify_readonly_viewers: true # 通知只读查看者
```

### Page capacity / 每页容量

`content_rows` controls real storage capacity, from 1 to 6 rows (9–54 slots), default 5 (45 slots).
Edit `plugins/UltiTools/pluginConfig/UltiRemoteBag/config/remotebag.yml`, then run
`ul reload UltiRemoteBag` from the console. Newly opened windows use the new size; windows already
open keep their captured layout until closed. Invalid file values refuse module loading with a validation error; the operator's
file is not repaired or overwritten. The removed `rows_per_page` key remains ignored and warned about:
old values do not activate the new feature.

The last window row is always buttons, never storage. Up to five content rows fit above it. At six
content rows, use **Next content screen** and **Previous content screen** (toolbar columns 7 and 1)
to move between slots 0–44 and 45–53 of the SAME bag page. Navigation keeps unsaved edits and the same
edit reservation; Save, close, `/bag save`, quit and module disable all save the whole page, not just
its current screen. Unused cells on a final screen are protected fillers, not storage.

**Shrinking never drops stored items.** Each page retains enough whole rows to expose its highest
occupied slot. A yellow message explains its retained and configured capacity, and the main menu's
`Slots Used` denominator reports that actual retained capacity. Remove items from the extra rows and
close/reopen to shrink that page. For example, changing 5 rows to 2 keeps an item at slot 44 accessible
on a later content screen; once it is removed, the next open uses 18 slots. The loader still reads
all existing slots 0–53, including the formerly invisible legacy 45–53 band; those slots now appear on
later screens and survive saving. No schema migration or automatic item transfer is performed.

**Do not downgrade to 2.0.0 once any page has used a sixth row.** 2.0.0 shows and saves only slots 0–44,
so items in slots 45–53 would not be shown there and its next save of that page drops them. Before rolling
back, empty the sixth row of every page on this version.

With `require_manual_save: true`, the unsaved-change check covers every content screen of the page: a
change on a screen you are not looking at still refuses the close. Switching content screens is not
leaving the page and is never refused.

`content_rows` 设置真实容量（1–6 行，每行 9 格，默认 5 行）。重载后只影响新打开的窗口，已打开的窗口不会被截断。
底行始终是工具栏，超过单屏的内容用工具栏第 2、8 格的箭头切换，仍是同一个背包页，不另收费。
缩容时保留已有物品所在的额外行，聊天会说明原因，主页分母显示实际保留容量；取出额外行的物品后关闭再打开才缩容。
旧的 `rows_per_page` 仍无效，不会覆盖或迁移你写的配置。
**使用过第 6 行后不要降级到 2.0.0**：2.0.0 只显示并保存 0–44 号槽位，45–53 号槽位的物品在 2.0.0 中不会显示，并会在其下一次保存该页时丢失；回退前请先在本版本中清空每页的第 6 行。
启用 `require_manual_save: true` 时，未保存改动的检查覆盖整页所有内容屏，未显示的屏上有改动同样拒绝关闭；页内切换内容屏不算离开，不会被拒绝。

## 📜 命令

### 玩家命令

| 命令 | 描述 | 权限 |
|------|------|------|
| `/bag` | 打开背包主页 | `ultibag.use` |
| `/bag <页码>` | 打开指定页背包 | `ultibag.use` |
| `/bag save` | 手动保存背包 | `ultibag.use` |

### 管理员命令

| 命令 | 描述 | 权限 |
|------|------|------|
| `/bag see <玩家> [页码]` | 查看玩家背包 | `ultibag.admin.see` |
| `/bag create <玩家>` | 为玩家创建背包 | `ultibag.admin.create` |
| `/bag delete <玩家> <页码>` | 删除玩家背包页 | `ultibag.admin.delete` |
| `/bag clear <玩家> <页码>` | 清空玩家背包页 | `ultibag.admin.clear` |
| `/bag list <玩家>` | 列出玩家所有背包 | `ultibag.admin.list` |

### 命令别名

- `/bag`, `/remotebag`, `/rb`, `/yunbag`

## 🔑 权限节点

### 玩家权限

| 权限 | 描述 | 默认 |
|------|------|------|
| `ultibag.use` | 使用远程背包 | true |
| `ultibag.pages.1` | 拥有 1 页背包 | true |
| `ultibag.pages.2` | 拥有 2 页背包 | false |
| `ultibag.pages.3` | 拥有 3 页背包 | false |
| `ultibag.pages.N` | 拥有 N 页背包 | false |

### 管理员权限

| 权限 | 描述 | 默认 |
|------|------|------|
| `ultibag.admin.*` | 所有管理员权限 | op |
| `ultibag.admin.see` | 查看玩家背包 | op |
| `ultibag.admin.create` | 创建玩家背包 | op |
| `ultibag.admin.delete` | 删除玩家背包 | op |
| `ultibag.admin.clear` | 清空玩家背包 | op |
| `ultibag.admin.list` | 列出玩家背包 | op |

## 🏗️ 架构设计

### 项目结构

```
com.ultikits.plugins.remotebag/
├── UltiRemoteBag.java          # 插件主类
├── commands/
│   └── BagCommand.java         # 命令执行器
├── config/
│   └── RemoteBagConfig.java    # 配置类
├── entity/
│   ├── RemoteBagData.java      # 数据实体
│   ├── BagLockInfo.java        # 锁信息
│   └── BagOpenResult.java      # 打开结果
├── enums/
│   ├── LockType.java           # 锁类型 (OWNER/ADMIN)
│   └── AccessMode.java         # 访问模式 (EDIT/READ_ONLY)
├── gui/
│   ├── RemoteBagMainGUI.java   # 主页 GUI
│   └── RemoteBagContentGUI.java # 内容 GUI
├── listener/
│   └── BagListener.java        # 事件监听器
├── service/
│   ├── RemoteBagService.java   # 背包服务
│   └── BagLockService.java     # 锁定服务
└── util/
    └── SoundUtil.java          # 音效工具
```

### 锁定机制

UltiRemoteBag 实现了一套完整的并发访问控制机制：

```
┌─────────────────────────────────────────────────────────┐
│                    背包访问流程                          │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  所有者访问自己的背包:                                    │
│  ┌─────────┐    ┌──────────┐    ┌─────────────┐        │
│  │ 请求访问 │───▶│ 检查锁状态 │───▶│ 编辑模式打开 │        │
│  └─────────┘    └──────────┘    └─────────────┘        │
│                       │                                 │
│                       ▼ (管理员持有锁)                   │
│                 ┌───────────┐                           │
│                 │ 阻止访问   │                           │
│                 └───────────┘                           │
│                                                         │
│  管理员访问他人背包:                                      │
│  ┌─────────┐    ┌──────────┐    ┌─────────────┐        │
│  │ 请求访问 │───▶│ 检查锁状态 │───▶│ 编辑模式打开 │        │
│  └─────────┘    └──────────┘    └─────────────┘        │
│                       │                                 │
│                       ▼ (所有者持有锁)                   │
│                 ┌───────────┐                           │
│                 │ 只读模式   │                           │
│                 └───────────┘                           │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

**优先级规则：**

- 所有者 (OWNER) 拥有最高优先级
- 管理员 (ADMIN) 在所有者使用时只能只读访问
- 同一时间只有一个用户可以编辑

### Several servers sharing one database / 多台服务器共享一个数据库

With MySQL shared by several servers (UltiKits/UltiRemoteBag#54):

- **One bag page is edited on one server at a time.** Opening a page for editing claims it in the database
  table `remote_bag_claims`; while another server holds the page, it opens read-only on this server, for the
  owner and for administrators alike ("This bag page is being edited on another server; it is open in read-only
  mode"). On one server nothing changes: the owner still outranks an administrator. Across servers the first
  server to claim the page edits it, owner or not.
- **Release and renewal.** The claim is released when the window closes, when its player quits, and when the
  module stops (or, while a save is still being written -- see below -- as soon as it is). While a window is open,
  its server renews the claim on a background task every third of `lock.timeout_seconds` (default 300), adding one
  to the claim's counter; a stalled main thread does not stop it, and one database call that hangs does not hold up
  the renewal of any other page.
- **Expiry does not depend on clocks.** Another server may take a claim over only after it has seen the same
  counter for a full `lock.timeout_seconds` on its own clock. A crashed server's claim is taken over one timeout
  after another server first sees it; a running server keeps its claim however far the servers' clocks disagree.
- **No stale copies.** A page is written only by its own window, only if the stored page is still what the
  window read; nothing is written from a cached copy at quit, at shutdown or by `/bag save` without an open page.
  If a save is refused, the items put into the window since it last read or saved the page are given back to the
  player (what does not fit drops at their feet). If the claim is ever lost, the window turns read-only at once
  and gives those items back.
- **Page-read timeouts.** Reading a content page on open or Refresh uses the storage pool and waits at most a sixth
  of `lock.timeout_seconds`, capped at two seconds. An opening that times out is refused with "The bag could not be
  loaded. Please try again later." and releases the lock/claim acquired by that opening. A Refresh that times out
  leaves every displayed item unchanged, keeps the same window read-only and says "The bag could not be refreshed.
  The current contents are unchanged and this view is read-only." Clicks and drags cannot move items into or out of
  that page, and Save, close, quit and module stop do not write that display back. A late read result is discarded;
  an unknown result is never treated as an empty page. These messages are translated in both shipped languages.
- **When the database does not answer.** Every database call of the claim and of a page save is given up on after a
  sixth of `lock.timeout_seconds` (at most two seconds while the main thread waits); a call given up on may still
  land later, and its answer is still used.
  - If a renewal fails or does not answer, the window turns read-only at once ("This server could not confirm its
    reservation of this bag page with the database ..."). What it shows is kept and saved as soon as the claim is
    confirmed again; nothing is given back, because that save can still land. A window whose claim has not been
    confirmed for half the timeout turns read-only by itself at the next click, even if the renewal task is stuck.
  - If a save fails or does not answer (at close, on the Save button, at quit, or by `/bag save`), the claim is kept
    and renewed, the page's content stays in memory, and the same conditional save is retried in the background
    until it lands. Meanwhile the page is read-only on every server; reopening it on this server shows the kept
    content. Kept saves survive the player quitting and reopening; when the module stops it keeps trying for five
    seconds and logs, with their items, the saves it could not write. With a database that does not answer at all,
    module disable's storage waits can therefore take up to about 11 seconds: up to two seconds stopping renewal,
    five seconds shared by the flush stages, two seconds deciding uncertain saves, and two seconds releasing claims.
    Already-running background releases share that final release deadline; the module waits for them before stopping
    the storage pool, but a database call that still does not answer can leave a claim until its normal timeout.
  - **Every save is fenced on the claim.** One database transaction first moves the claim's counter on, only if the
    claim still carries this server's session and the counter it last confirmed, then writes the page only if it
    is still what the window read, and records the save as written. On SQLite and MySQL a save therefore lands only
    while this server holds the page's claim, however late it runs: once another server has taken the page over, a
    save that was held up writes nothing. Whether a save that reported a database error had in fact been written is
    read from that record, never guessed: the retry and the abandon after a lost claim read it only after the save's
    transaction has ended (the claim row's lock orders them), and when the module stops it is read in a transaction
    that first locks the claim row, so it waits for a save still running on the database (MySQL's `socketTimeout`
    can give up on a save the database then commits). A save that cannot be decided before the stop's deadline is
    not given back; it is logged with its items as undecided.
  - Only a save the database refuses (the stored page is not what the window read) gives back the items put in. If
    the player has left, they get them at their next join on this server.
  - Set a socket or statement timeout on your MySQL connection (for example `socketTimeout` in the JDBC URL) if your
    server's network can leave a connection half-open: the framework does not set one, and a call on such a
    connection otherwise waits for TCP keep-alive. This module never waits for it, but the pooled connection stays
    busy until it returns. A connection lost in the middle of a save also leaves the page's claim row locked on the
    database until the database drops that session, so no server can take the page over meanwhile. MySQL's
    `wait_timeout` (default 28800 s, eight hours) is what ends such a dead session and frees its locks: set it
    moderately. `innodb_lock_wait_timeout` only limits how long other servers wait for the lock on each attempt.
- **Upgrading** needs no migration: the `remote_bag_claims` table is created on the first start, no existing
  table changes, and a page without a claim row is free.

#### Known limitations

When several servers share one database, the module keeps every item in exactly one place in normal play, and
across crashes, restarts and a database that stops answering. A few rare situations remain in which an item can be
lost or can exist twice. They are listed here so you can avoid them.

**What to do:** keep the shared database responsive and on a low-latency link to every server; set `socketTimeout`
and a moderate `wait_timeout` on MySQL (see above); upgrade every server sharing the database together; do not edit
the bag tables by hand while servers are running.

- **Something other than this module changes a page while a window has it open**: a server still on an older
  version of this module during a rolling upgrade, another plugin writing `remote_bags`, or a hand edit of the
  table. The window's save is refused and the items put in go back to the player, but an item taken out in that
  session can exist twice.
- **The database is very slow while acquiring an edit claim.** If acquiring that claim waits more than about two
  seconds, the page opens read-only provided its subsequent content read succeeds (a content-read timeout refuses
  the opening instead). If that slow call then completes after the player has quickly
  reopened the same page, the reopened window can lose its claim. In rare cases an item the player takes out of
  it can then exist twice. If an exceptional same-server claim reaches the defensive refusal for another local
  session, its read-only notice also says "being edited on another server"; that notice alone does not prove a second
  server is involved. The ordinary local owner/administrator lock rules prevent this handoff.
- **One server is cut off from a database the other servers still reach, for a full `lock.timeout_seconds`** (or
  the whole server process freezes for that long). Another server takes the page over. The cut-off server's window
  turns read-only when its first renewal fails, and the items put in are given back once the database answers.
  Items taken out between its last save and the moment it turned read-only can exist twice; that is at most
  about half `lock.timeout_seconds` after the cut.
- **The server stops or crashes during a database outage while a save is still waiting to be written** (accepted by
  the maintainer). A save not written by the end of the stop's five-second flush, or pending at a crash: its put-in
  items are lost and its taken-out items can exist twice, all logged with the items. The one exception is a stop
  that can still tell the save was not written while the player is online: then the put-in items go back. In
  practice this is at most one page per player, because a player has one page open at a time and no page opens
  while the database does not answer. With a database that does not answer at all, a stop can take up to about 11
  seconds.
- **Items owed to a player who left** (their save was refused after they quit) are held in memory until they join
  this server again. A restart loses them; each is logged with the player, the page and the items when it becomes
  owed and again at module stop.
- **A JDBC driver call that never returns can outlive module unload.** Giving up on the call or stopping the module
  does not forcibly stop the driver. Its daemon storage thread can keep the old module's classloader in memory until
  the call returns, even if the module is loaded again. Configure driver-level timeouts such as MySQL's `socketTimeout`;
  if stuck calls accumulate across unloads, restore database connectivity and restart the server rather than repeatedly
  unload and load the module.
- **Bag-list reads remain synchronous.** The content page's read is bounded, but listing a player's pages (including
  the existence check before `/bag <page>` or `/bag see`) still reads synchronously; a slow list query can block the
  main thread before the content window is opened. Refresh has no cooldown and each attempt may wait up to the
  content-read deadline. Keep the database responsive and configure driver timeouts. A failed read is not evidence
  that the stored page is empty; do not delete or replace its stored data to work around an outage.
- **Another server's stale edit claim is not cleared at startup.** After a crash, a page may say "This bag page is
  being edited on another server; it is open in read-only mode" even though that server has stopped. Startup does
  not delete that claim: normal takeover still requires observing its unchanged counter for a full
  `lock.timeout_seconds`. Prefer waiting for that normal takeover. There is no in-game claim-cleanup command:
  `/bag clear` clears items and `/bag delete` deletes a bag page, not a stale claim. If manual clearing is necessary,
  first confirm the server that held this claim is stopped and will not resume the old editing session (`holder_run`
  is a generated run UUID, not a hostname; if you cannot identify the holder, stop every database-sharing server). Pause all
  servers sharing the database and back it up. Inspect `remote_bag_claims` for the exact page id `<owner UUID>:<page>`
  (`RemoteBagEditClaim#idOf`), verify its `holder_run` and `holder_token`, then delete only that matching row:
  `DELETE FROM remote_bag_claims WHERE id='<owner UUID>:<page>' AND holder_run='<confirmed stopped run>' AND holder_token='<verified token>';`.
  Do not delete `remote_bags` or sweep the claims table. Save receipts use session-token ids in that same table,
  not page ids; they are a separate class of record and are not covered by this manual edit-claim procedure.
- **Crash-left save receipts are retained.** Receipt rows in `remote_bag_claims` can remain after a process crashes.
  Startup does not sweep them: a live server may still need a receipt to decide an uncertain save after another server
  took the page over. Do not delete these internal rows while any server sharing the database may still need them.
- **A purchase races with page creation on another server** (UltiKits/UltiRemoteBag#55). If an administrator on
  another server sharing the database creates the same player's next page after the purchase reads their pages
  but before it creates the new page, the purchase can fail after charging the player, without an automatic refund
  or an additional page. The page the other server created is not overwritten. Avoid creating pages for a player
  on another server while that player is purchasing a page; if this happens, check the player's balance and stored
  pages and reconcile the charge through your economy provider's administrative commands.
- **Two small technical notes.** On MySQL, comparing a page's stored contents follows the column's collation, which
  ignores letter case. On the JSON backend, which belongs to one server, the claim check and the page write are
  not one transaction; with one server that changes nothing.

页面读取超时：打开内容页时最多等待 `lock.timeout_seconds` 的六分之一（上限两秒）；超时拒绝打开，提示「背包加载失败，请稍后重试。」，释放本次取得的锁与占用。刷新超时则提示「背包刷新失败，当前显示的物品保持不变，此视图现为只读。」，当前窗口与物品不变，不接受物品移动，保存、关闭、退出和停用均不写回该显示；迟到的读取结果被丢弃。补充限制：背包列表及打开前的页面存在性检查仍会同步等待数据库，刷新按钮没有冷却时间。请保持数据库低延迟并设置驱动超时；不要因读取失败而删除或替换背包数据。另一台服务器崩溃遗留的编辑占用不在启动时自动删除，页面可能仍提示另一服务器正在编辑并以只读方式打开；通常应等待观察到计数器不变满一个 `lock.timeout_seconds` 后正常接手。没有游戏内清理占用命令，`/bag clear` 和 `/bag delete` 操作的是物品或背包页。确需手动清理时，先确认占用来源服务器已停止且不会恢复旧会话，暂停共享该库的所有服务器并备份，核对 `remote_bag_claims` 中 id 为 `<所有者 UUID>:<页码>` 的行及其 `holder_run`、`holder_token`，只按上方带三项条件的 DELETE 删除这一行。不要删除 `remote_bags`，不要清空占用表，也不要把会话 token 作 id 的保存回执当成页面编辑占用。模块卸载不能强制结束永不返回的 JDBC 调用，其后台线程可能继续持有旧模块的类加载器；如果反复卸载后出现此类堆积，修复数据库连接后重启服务器，不要反复卸载再加载。崩溃遗留的保存回执不会在启动时清理，因为其他仍在运行的服务器可能需要它来判定保存是否成功，不能仅因页面已被其他服务器接手就删除回执。停用时已经发出的后台释放与其他释放共享最后的等待期限；仍不响应的数据库调用可能让占用保留到正常超时。

多台服务器共享 MySQL 时（UltiKits/UltiRemoteBag#54）：同一背包页同一时间只能在一台服务器上编辑——打开编辑时在数据库表
`remote_bag_claims` 中占用该页；另一台服务器占用期间，本服务器对所有者和管理员都以只读方式打开。单台服务器上的规则不变（所有者优先于
管理员）；跨服务器时先占用者编辑。窗口关闭、玩家退出、模块停止时释放占用；窗口打开期间，服务器在后台任务中每过三分之一
`lock.timeout_seconds` 续期一次（计数器加一），主线程卡顿不会中断续期。过期不依赖时钟：另一台服务器只有在自己的时钟上连续
`lock.timeout_seconds` 看到同一计数器值后才能接手；崩溃服务器的占用在另一台服务器首次看到它的一个超时后被接手。背包页只由它自己的窗口
写入，且仅当存储内容仍是窗口读取时的内容；保存被拒绝时，自上次读取或保存以来放入窗口的物品归还给玩家（放不下的掉落在脚下）；若占用丢失，
窗口立即变为只读并归还这些物品。数据库未响应时：占用和保存的每次数据库调用最多等待 `lock.timeout_seconds` 的六分之一（主线程等待时最多两秒），
放弃等待的调用仍可能稍后完成，其结果仍会被采用；续期失败或未响应时窗口立即变为只读，窗口内容被保留，占用恢复确认后立即保存，不归还物品；
保存失败或未响应时保留占用并继续续期，内容保留在内存中，在后台重试同一条件写入直到成功，期间该页在所有服务器上只读，在本服务器重新打开会显示
保留的内容；只有被数据库拒绝的保存才归还放入的物品（玩家已离开时在其下次加入本服务器时归还）。保留的保存在玩家退出和重新打开后仍然有效；模块停止时
再尝试五秒，无法写入的连同物品记录在日志中；数据库完全不响应时，模块停用最多可能耗时约 11 秒。每次保存都以占用为栅栏：同一个数据库事务先在占用仍属于
本服务器的会话且计数器仍是其最后确认的值时推进计数器，再在页面仍是窗口读取时的内容时写入页面，并记录这次保存已写入；因此在 SQLite 和 MySQL 上，
保存只会在本服务器持有该页占用时生效，无论它多晚执行——另一台服务器接手之后，被耽搁的保存不会写入任何内容；报告了数据库错误的保存是否实际已写入，
从这条记录中读取而不是猜测：重试和占用丢失后的放弃都只在该保存的事务结束后读取（占用行的锁保证这一顺序）；模块停止时，在先锁定占用行的事务中读取，因此会等待仍在数据库上运行的保存（MySQL 的 `socketTimeout` 可能放弃一个随后被数据库提交的保存）。在停止期限内无法判定的保存不会被归还，而是连同物品作为未判定记录在日志中。如果服务器网络可能留下半开连接，请为 MySQL 连接设置套接字或语句超时（例如 JDBC URL 中的
`socketTimeout`），框架本身不设置；保存进行中断开的连接还会让该页的占用行在数据库上保持锁定，直到数据库丢弃该会话，期间任何服务器都无法接手该页——结束这种失效会话并释放其锁的是 MySQL 的 `wait_timeout`（默认 28800 秒，即八小时），请将其设为适中的值；`innodb_lock_wait_timeout` 只限制其他服务器每次等待该锁的时长。升级无需迁移。已知限制（均为罕见情况，均会记录日志）。运维建议：让共享数据库保持响应迅速、与每台服务器之间低延迟；为 MySQL 设置 `socketTimeout` 和适中的 `wait_timeout`；共享数据库的所有服务器一起升级；服务器运行时不要手动编辑背包数据表。一、本模块以外的写入者（滚动升级期间仍运行旧版本模块的服务器、写 `remote_bags` 的其他插件、手动编辑数据表）在窗口打开时改动该页：保存被拒绝、放入的物品归还，但该会话中取出的物品可能出现两份。二、取得编辑占用时数据库非常慢：占用调用等待超过约两秒后，只有随后的页面内容读取成功才会只读打开（内容读取超时则拒绝打开）；如果这次缓慢的占用调用在玩家迅速重新打开同一页之后才完成，重新打开的窗口可能失去占用，在罕见情况下玩家从中取出的物品可能出现两份。三、某台服务器与其他服务器仍可访问的数据库断开整整一个 `lock.timeout_seconds`（或整个服务器进程冻结这么久）：另一台服务器接手该页；断开的服务器在第一次续期失败时窗口变为只读，数据库恢复后归还放入的物品，自上次保存到变为只读之间（断开后最多约半个超时）取出的物品可能出现两份。四、在数据库故障期间停止或崩溃，而某个保存仍在等待写入（维护者已接受）：到停止时五秒冲刷结束仍未写入的保存、或崩溃时待写入的保存，放入的物品丢失、取出的物品可能出现两份，均连同物品记录在日志中；例外：停止时仍能判定其未写入且玩家在线，则归还放入的物品；实际上每名玩家最多一页；数据库完全不响应时，停止最多约 11 秒。五、欠离线玩家的物品保存在内存中直到其再次加入本服务器，重启会丢失，欠下时和模块停止时都会连同玩家、页码与物品记录在日志中。六、两条技术说明：MySQL 上页面内容的比较遵循列的排序规则（不区分大小写）；JSON 存储只属于一台服务器，其占用检查与页面写入不在同一个事务中，单台服务器上没有影响。

## 🔧 开发者 API

### 获取服务实例

```java
// 通过 IoC 容器获取服务
RemoteBagService bagService = plugin.getContext().getBean(RemoteBagService.class);
BagLockService lockService = plugin.getContext().getBean(BagLockService.class);
```

### 操作背包数据

```java
// 加载背包
bagService.loadBagIfNeeded(playerUuid);

// 获取背包页列表
List<Integer> pages = bagService.getPlayerBagPages(playerUuid);

// 获取背包内容（只读缓存，用于显示）
ItemStack[] contents = bagService.getBagPage(playerUuid, pageNum);

// 读取一页（从数据库）并保存修改：仅当存储的内容仍是读取时的内容才写入，否则返回 null
// Read one page from the database and save a change to it: written only if the stored page is
// still what was read; otherwise nothing is written and null comes back (UltiKits/UltiRemoteBag#54)
RemoteBagService.PageRead read = bagService.readPage(playerUuid, pageNum);
RemoteBagService.PageRead written = bagService.savePage(playerUuid, pageNum, newContents, read);
```

缓存只读，从不写回数据库；`setBagPage`、`saveBag`、`saveAllBags` 已移除（UltiKits/UltiRemoteBag#54）。
The cache is read-only and never written back; `setBagPage`, `saveBag` and `saveAllBags` were removed
(UltiKits/UltiRemoteBag#54).

### 锁定操作

```java
// 所有者打开背包
BagOpenResult result = lockService.ownerOpen(ownerUuid, pageNum, player);

// 管理员打开背包
BagOpenResult result = lockService.adminOpen(ownerUuid, pageNum, admin);

// 检查结果
if (result.isEditMode()) {
    // 编辑模式
} else if (result.isSuccess()) {
    // 只读模式
    AccessMode mode = result.getAccessMode();
} else {
    // 被阻止
    String message = result.getMessage();
}

// 释放锁
lockService.release(ownerUuid, pageNum, holderUuid);

// 玩家退出时释放所有锁
lockService.releaseAll(playerUuid);
```

### 打开 GUI

```java
// 打开主页
new RemoteBagMainGUI(player, bagService, lockService, config).open();

// 打开内容页
new RemoteBagContentGUI(player, ownerUuid, pageNum, 
    bagService, lockService, config, accessMode).open();
```

## 📊 数据存储

背包数据支持三种存储方式（由 UltiTools-API 配置）：

| 存储方式 | 特点 |
|---------|------|
| **JSON** | 文件存储，人类可读，适合小型服务器 |
| **SQLite** | 本地数据库，良好性能，推荐默认选项 |
| **MySQL** | 远程数据库，适合大型服务器和跨服同步 |

### 数据表结构

```sql
CREATE TABLE ulti_remote_bag (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    player_uuid VARCHAR(36) NOT NULL,
    page_number INT NOT NULL,
    contents TEXT,
    last_updated BIGINT,
    UNIQUE KEY uk_player_page (player_uuid, page_number)
);
```

## 🌍 多语言

支持的语言：
- 🇨🇳 简体中文 (zh)
- 🇺🇸 English (en)

语言文件位置：`plugins/UltiTools/pluginConfig/UltiRemoteBag/lang/`

官方语言文件（`zh.yml`、`en.yml`）归 UltiTools 所有：与本版本自带内容不同的官方文件会在每次启动和每次模块重载时被恢复，原文件保留为 `.bak`。要自定义消息或添加翻译：在同一目录中把官方文件复制为以其语言代码加连字符开头的文件（例如 `en-ja.yml`），修改或翻译副本，并在 `plugins/UltiTools/config.yml` 中设置 `language: en-ja`（整个服务器唯一的语言设置）。副本中缺少的条目使用文件名开头那种官方语言的文本；文件名为其他形式时（例如 `ja.yml`），缺少的条目显示英文，并记录一条警告（UltiKits/UltiTools-Reborn#616）。

## 📝 更新日志

### 未发布 / Unreleased

- 需要 UltiTools-API 6.3.0 或更高版本（`api-version: 630`）

### v1.0.0 (2026-02-15)

- 🎉 初始版本发布

#### 功能

- ✨ 背包锁定机制 - 所有者优先，管理员只读模式
- ✨ 管理员命令 - see, create, delete, clear, list
- ✨ 音效系统 - 可配置的操作音效
- ✨ 经济集成 - Vault 支持，价格递增公式
- ✨ GUI - 使用 BasePaginationPage 框架
- ✨ 物品统计 - 显示物品数量和槽位占用
- 🔧 服务层分离，数据缓存
- 🐛 并发访问保护；玩家退出时释放其持有的锁

## ❓ FAQ

**Q: 如何增加玩家可用的背包页数？**
> 有两种方式：
> 1. 修改 `max_pages` 配置增加全局上限
> 2. 启用 `permission_based_pages` 并给玩家相应权限

**Q: Why does a player's own bag window still offer page 1 after an administrator deletes only page 1? / 管理员只删除了第 1 页后，玩家自己的背包窗口为什么仍然显示第 1 页？**
> By design (UltiKits/UltiRemoteBag#47). A player's own window always offers page 1, whether or not anything is stored on it, because page 1 is every player's default page; deleting a page removes its stored row and items, not the player's right to open page 1. The next page a player adds is always numbered one past their highest page, so after pages 1 and 3 the next one is page 4 (UltiKits/UltiRemoteBag#46).
> 这是设计如此（UltiKits/UltiRemoteBag#47）：玩家自己的窗口始终提供第 1 页，无论其中是否有存储内容，因为第 1 页是所有玩家的默认页；删除背包页只会删除该页的存储记录和物品，不会取消玩家打开第 1 页的权利。玩家新增的下一页页码始终是其最高页的下一页，因此只剩第 1、3 页时，下一页是第 4 页（UltiKits/UltiRemoteBag#46）。

**Q: 管理员查看背包时为什么是只读的？**
> 当背包所有者正在使用时，管理员会自动进入只读模式。等所有者关闭后，点击刷新按钮即可切换为编辑模式。

**Q: 数据存储在哪里？**
> 取决于 UltiTools-API 的配置。默认使用 SQLite，数据库文件是 `plugins/UltiTools/sqliteDB/UltiRemoteBag.db`。

**Q: 如何备份玩家背包数据？**

> - SQLite: 备份 `sqliteDB/UltiRemoteBag.db` 文件
> - MySQL: 备份 `ulti_remote_bag` 表
> - JSON: 备份 `data/UltiRemoteBag/` 目录下的 JSON 文件

## 🤝 贡献

欢迎提交 Issue 和 Pull Request！

- GitHub: [UltiKits/UltiTools-Reborn](https://github.com/UltiKits/UltiTools-Reborn)
- 问题反馈: [Issues](https://github.com/UltiKits/UltiTools-Reborn/issues)

## 📄 许可证

本项目采用 [MIT License](LICENSE) 开源协议。

---

**Made with ❤️ by UltiKits Team**
