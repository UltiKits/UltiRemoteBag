# UltiRemoteBag

[![UltiTools Module](https://img.shields.io/badge/UltiTools-Module-blue)](https://github.com/UltiKits/UltiTools-Reborn)
[![Minecraft Version](https://img.shields.io/badge/Minecraft-1.13--1.21-green)](https://www.spigotmc.org/)
[![Java](https://img.shields.io/badge/Java-8+-orange)](https://www.oracle.com/java/)

**UltiRemoteBag** 是 UltiTools-API 框架的一个插件模块，为 Minecraft 服务器提供虚拟云存储（远程背包）功能。玩家可以随时随地访问自己的远程背包，安全存储物品。

## ✨ 功能特性

### 核心功能

- 🎒 **多页背包系统** - 每个玩家可拥有多个背包页，每页 45 个槽位
- 💾 **自动保存** - 支持定时自动保存和关闭时保存
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
- **UltiTools-API 6.2.1+** - 核心框架
- **Vault** (可选) - 经济功能支持

### 安装步骤

1. 确保已安装 UltiTools-API
2. 将 `UltiRemoteBag.jar` 放入 `plugins/UltiTools/plugins/` 目录
3. 重启服务器或执行 `/ultitools reload`
4. 编辑配置文件 `plugins/UltiTools/UltiRemoteBag/config.yml`

## ⚙️ 配置说明

### config.yml

```yaml
# 基础配置
max-pages: 5                    # 最大背包页数
default-pages: 1                # 默认背包页数
rows-per-page: 5                # 每页行数 (1-6)
auto-save-interval: 300         # 自动保存间隔（秒），0 为禁用
save-on-close: true             # 关闭时保存

# 权限配置
permission-based-pages: false   # 是否基于权限决定页数
permission-prefix: "ultibag.pages."  # 权限前缀

# 经济配置
economy:
  enabled: true                 # 是否启用经济系统
  base-price: 1000              # 基础价格
  price-increase:
    enabled: true               # 是否启用价格递增
    rate: 0.5                   # 递增比率 (50%)

# 音效配置
sounds:
  enabled: true                 # 是否启用音效
  open: "BLOCK_CHEST_OPEN"      # 打开音效
  close: "BLOCK_CHEST_CLOSE"    # 关闭音效
  purchase: "ENTITY_PLAYER_LEVELUP"  # 购买音效
  error: "ENTITY_VILLAGER_NO"   # 错误音效
  volume: 1.0                   # 音量
  pitch: 1.0                    # 音调

# 锁定配置
lock:
  timeout: 300                  # 锁超时时间（秒）
  notify-readonly-viewers: true # 通知只读查看者
```

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
    content. A save that landed although the database reported an error counts as saved. Kept saves survive the
    player quitting and reopening; when the module stops it keeps trying for five seconds and logs, with their
    items, the saves it could not write.
  - Only a save the database refuses (the stored page is not what the window read) gives back the items put in. If
    the player has left, they get them at their next join on this server.
  - Set a socket or statement timeout on your MySQL connection (for example `socketTimeout` in the JDBC URL) if your
    server's network can leave a connection half-open: the framework does not set one, and a call on such a
    connection otherwise waits for TCP keep-alive. This module never waits for it, but the pooled connection stays
    busy until it returns.
- **Upgrading** needs no migration: the `remote_bag_claims` table is created on the first start, no existing
  table changes, and a page without a claim row is free.

#### Known limitations

- A writer that does not use the claim can still change a page while a window has it open: a server running an
  older version of this module (for example during a rolling upgrade -- upgrade every server together), another
  plugin writing `remote_bags`, or someone editing the table by hand (`sqlite3`, a MySQL client). The window's
  save is then refused and its put-in items are given back, but an item taken out of the page during that session
  stays with the player while the other writer's page may still hold it: that item can exist twice.
- A server cut off from the database for a full `lock.timeout_seconds` while another server still reaches it, or
  frozen as a whole (its background threads included) for that long, has its claim taken over. Its window turned
  read-only when the first renewal failed, so nothing is edited after that; when the database answers again, the
  claim is found lost and the items put in during the session are given back, with the same limitation for items
  taken out before the cut. A single database call that hangs for longer than the timeout and then lands can land
  after another server took the claim over.
- A save that is still being retried is held in memory: a server crash loses it, as it loses an unsaved window,
  and so does a module stop that cannot write it within five seconds (logged with its items; the items put in go
  back to the player if they are online and no write is still running). Items owed to a player who left are also
  held in memory until they join again.
- On MySQL the comparison of a page's stored contents follows the column's collation, which ignores letter case.

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
再尝试五秒，无法写入的连同物品记录在日志中。如果服务器网络可能留下半开连接，请为 MySQL 连接设置套接字或语句超时（例如 JDBC URL 中的
`socketTimeout`），框架本身不设置。升级无需迁移。已知限制：不使用占用的写入者（滚动升级期间仍运行旧版本模块的服务器、写 `remote_bags`
的其他插件、手动编辑数据表）仍可能在窗口打开时改动该页——保存会被拒绝、放入的物品会归还，但该会话中取出的物品留在玩家身上，而对方写入的页面
可能仍含有它，从而出现两份；与数据库断开整整一个超时而另一台服务器仍可访问，或整个服务器进程（包括后台线程）冻结这么久时，占用会被接手——
窗口在第一次续期失败时已变为只读，数据库恢复后发现占用丢失，归还会话中放入的物品，断开前取出的物品同样可能出现两份；一次挂起超过超时时间后才完成的数据库调用
可能在另一台服务器接手之后写入；仍在重试的保存保存在内存中，服务器崩溃会丢失它（与未保存的窗口相同），模块停止时五秒内无法写入的也会丢失（连同物品记录在
日志中；若玩家在线且没有仍在进行的写入，放入的物品归还给玩家）；欠离线玩家的物品同样保存在内存中，直到其再次加入。

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

### v2.0.0 (2026-01-12)

**重大更新 - 完全重构**

#### 新增功能

- ✨ 背包锁定机制 - 所有者优先，管理员只读模式
- ✨ 管理员命令 - see, create, delete, clear, list
- ✨ 音效系统 - 可配置的操作音效
- ✨ 经济集成 - Vault 支持，价格递增公式
- ✨ 现代化 GUI - 使用 BasePaginationPage 框架
- ✨ 物品统计 - 显示物品数量和槽位占用

#### 改进
- 🔧 使用 UltiTools-API 6.x GUI 框架重构
- 🔧 服务层分离，更清晰的架构
- 🔧 完善的错误处理和用户反馈
- 🔧 优化的数据缓存机制

#### 修复

- 🐛 修复并发访问可能导致的数据丢失
- 🐛 修复玩家退出时锁未释放的问题

### v1.0.0

- 🎉 初始版本发布

## ❓ FAQ

**Q: 如何增加玩家可用的背包页数？**
> 有两种方式：
> 1. 修改 `max-pages` 配置增加全局上限
> 2. 启用 `permission-based-pages` 并给玩家相应权限

**Q: Why does a player's own bag window still offer page 1 after an administrator deletes only page 1? / 管理员只删除了第 1 页后，玩家自己的背包窗口为什么仍然显示第 1 页？**
> By design (UltiKits/UltiRemoteBag#47). A player's own window always offers page 1, whether or not anything is stored on it, because page 1 is every player's default page; deleting a page removes its stored row and items, not the player's right to open page 1. The next page a player adds is always numbered one past their highest page, so after pages 1 and 3 the next one is page 4 (UltiKits/UltiRemoteBag#46).
> 这是设计如此（UltiKits/UltiRemoteBag#47）：玩家自己的窗口始终提供第 1 页，无论其中是否有存储内容，因为第 1 页是所有玩家的默认页；删除背包页只会删除该页的存储记录和物品，不会取消玩家打开第 1 页的权利。玩家新增的下一页页码始终是其最高页的下一页，因此只剩第 1、3 页时，下一页是第 4 页（UltiKits/UltiRemoteBag#46）。

**Q: 管理员查看背包时为什么是只读的？**
> 当背包所有者正在使用时，管理员会自动进入只读模式。等所有者关闭后，点击刷新按钮即可切换为编辑模式。

**Q: 数据存储在哪里？**
> 取决于 UltiTools-API 的配置。默认使用 SQLite，数据库文件在 `plugins/UltiTools/data/` 目录。

**Q: 如何备份玩家背包数据？**

> - SQLite: 备份 `ultitools.db` 文件
> - MySQL: 备份 `ulti_remote_bag` 表
> - JSON: 备份 `data/` 目录下的 JSON 文件

## 🤝 贡献

欢迎提交 Issue 和 Pull Request！

- GitHub: [UltiKits/UltiTools-Reborn](https://github.com/UltiKits/UltiTools-Reborn)
- 问题反馈: [Issues](https://github.com/UltiKits/UltiTools-Reborn/issues)

## 📄 许可证

本项目采用 [MIT License](LICENSE) 开源协议。

---

**Made with ❤️ by UltiKits Team**
