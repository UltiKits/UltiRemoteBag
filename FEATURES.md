# UltiRemoteBag — Feature Inventory

This document catalogues every operator- or player-visible function, command, content item and
configuration key in this repository, as read directly from source. It is an internal reference
for UAT execution and issue reconciliation — the public description of these features lives on
<https://doc.ultikits.com/>. Update this file in the same pull request as any feature change.

## Conventions

- **ID grammar:** `<repo-slug>.<area>.<action>`, dot-separated, every segment lowercase ASCII
  drawn from `[a-z0-9-]`. `<repo-slug>` is the repository name lowercased with no separators —
  `ultiremotebag` here. `<area>` is the feature section's slug. `<action>` is the verb.
  A `config` row is the one shape that exceeds three segments and is exempt from the
  lowercase-ASCII rule for its key-path suffix:
  `<repo-slug>.config.<file-stem>.<yml key path>`, the key path keeping its own dots and its own
  casing verbatim from the yml file — a config ID is a citation of the key, not a re-derived slug,
  so lowercasing it would make it un-greppable against its own source line. An ID changes only
  when the feature's identity changes, never on rewording. IDs are unique within a repository.
- **Kind**, exactly these eight values: `command`, `config`, `event`, `gui`, `scheduled`,
  `placeholder`, `persistence`, `gate`. This module has no `placeholder` rows (it consumes
  economy balances via `EconomyUtils`, it registers no PlaceholderAPI expansion of its own) and no
  `gate` rows (0 `@ConditionalOnConfig` sites, confirmed below) — both Kinds stay in the vocabulary
  for cross-repository consistency even though neither appears below.
- **Tier**, exactly three: `player`, `admin`, `internal`. Judged from what the feature is for,
  not from whether it carries a permission string.
- **Manual**, exactly three: `detailed`, `brief`, `none`.
- **Target**, exactly four: `player`, `console`, `both`, or `n/a` — the first three read straight
  off `@CmdTarget` for a `command` row; it is a property, not a tier. `n/a` is for every other
  Kind (`config`, `event`, `gate`, `gui`, `persistence`, `scheduled`, `placeholder`) — the concept
  of "who this targets" does not apply to a config key, a GUI page, or a background task the way
  it applies to a command.
- **Permission:** the literal node string, `none`, or `n/a`. `BagCommand` carries a class-level
  `@CmdExecutor(permission = "ultibag.use")`, and this framework's `PermissionValidator` checks
  the class-level (base) permission AND, separately, any method-level `@CmdMapping(permission =
  ...)` — both must be held, not either/or (confirmed by reading
  `abstracts/command/validation/validators/PermissionValidator.java` in the framework: `validate`
  checks `basePermission` first, then independently checks `method.getAnnotation(CmdMapping
  .class).permission()` if present, returning failure on either check). Six of this module's nine
  `@CmdMapping` sites additionally declare their own `permission()`; those rows' Permission cell
  therefore lists both nodes, joined by `AND`, to record the real cumulative requirement rather
  than only the method-level one. No `@CmdMapping` or the class-level `@CmdExecutor` sets
  `requireOp`, so no row below carries the `(requireOp=true)` suffix.
- **Source:** `ClassName#member` — the class and member that actually reads or applies the
  feature — for every Kind, `config` included: all 24 `config` rows below cite the reading
  member, or the config class's own field declaration when no reading member exists anywhere in
  this module's source (a `Kind: config` row is itself the evidence of that absence — see the
  `## Configuration` section note).
- **Row order:** by section, then by ID ascending within the section.
- **No manual prose:** no troubleshooting column, no explanatory paragraphs, no draft page text.
  A hazard noticed while reading becomes a negative checklist row, not a note here. Where a
  feature's actual runtime behaviour genuinely diverges from what the config key describes it as
  doing (a dead key, an unreachable code path), that fact is itself part of "what the feature
  does" and is stated here as a plain, sourced observation, with the filed issue number, never as
  advice on how to fix it.

### Reconciliation command family

The canonical form for counting an annotation site across this repository's real sources:

```bash
find <repo-root> -path '*/src/main/java/*' -name '*.java' -not -path '*/target/*' \
  -not -path '*/.worktrees/*' -print0 | xargs -0 grep -nE '^[[:space:]]*@AnnotationName\b' | wc -l
```

This form defeats three measured traps, each of which produces a wrong-but-plausible number
rather than an error:

1. **Multi-root repositories** — UltiBot's sources live under `ultibot-api/`, `ultibot-core/`
   and `ultibot-v1_21_R1/`, so a naive `<repo>/src/main/java` glob returns 0 for it, silently.
   This module is a single-root Maven project (`src/main/java` only), so this trap does not apply
   to it, but the robust `find` form is used regardless — the same command must work unmodified
   across all 18 repositories.
2. **Git worktrees and build output** — UltiEconomy carries `.worktrees/economy-v2/src/main/java`.
   This module carries no worktree directory.
3. **Javadoc and string literals** — requiring the annotation to start its own line (the
   `^[[:space:]]*@` anchor) is what defeats a javadoc mention or a warning-message string literal
   that merely contains the annotation's name as text. This module's naive (unanchored) and
   line-start counts are identical for every annotation kind measured below — no javadoc or
   string-literal false positive exists in this module's source — but the anchored form is still
   the one used, so the same command is trustworthy unmodified against every repository in the
   fan-out.

**Positive control:** the line-start form returns `@CmdExecutor` = 1, `@CmdMapping` = 9,
`@EventListener` = 1 (class), `@EventHandler` = 1 (handler method), `@Scheduled` = 1,
`@ConditionalOnConfig` = 0, `@ConfigEntity` = 1 (class), `@ConfigEntry` = 24 — confirmed by
reading `BagCommand.java` directly (9 `@CmdMapping` sites: bare, `<page>`, `save`, `see <player>`,
`see <player> <page>`, `create <player>`, `delete <player> <page>`, `clear <player> <page>`,
`list <player>`). The two-argument `see <player> <page>` mapping (`BagCommand.java:139`) is this
module's standing positive control — it is easy to conflate with the one-argument `see <player>`
mapping three lines above it (`BagCommand.java:115`), and both are checked by name, not merely by
count, below. This document's command-row count matches the `@CmdMapping` annotation-site count
exactly (9 against 9).

## Bag (player)

`BagCommand` — class-level `@CmdExecutor(alias = {"bag", "remotebag", "rb", "yunbag"}, permission
= "ultibag.use", description = ...)` (the `description` value in source is a Chinese-only string
meaning "Remote Bag System"), `@CmdTarget(PLAYER)`. Every sub-command below
requires `ultibag.use` at minimum (see the Permission convention note above); a player lacking it
never reaches `onCommand` at all — Bukkit's own command-tree filtering answers with "Unknown
command" before this class's code runs. A remote bag is virtual per-player cloud storage: pages of
inventory-sized item content, purchasable beyond the free default allotment, editable through a
GUI, and auto-saved on an interval.

| ID | Feature | Kind | How to reach | Permission | Target | Tier | Manual | Source |
|---|---|---|---|---|---|---|---|---|
| ultiremotebag.bag.open | Open the sender's own remote bag main page (paginated list of owned bag pages, with a purchase button if under the page limit and economy is enabled) | command | `/bag` | ultibag.use | player | player | brief | BagCommand#openMainPage |
| ultiremotebag.bag.open-page | Open a specific owned bag page directly by number, acquiring the owner lock; refuses with a range error if the page number is outside `1..maxPages`, or a not-found error if that page does not yet exist for this player | command | `/bag <page>` | ultibag.use | player | player | brief | BagCommand#openPage |
| ultiremotebag.bag.save | Manually persist the sender's currently cached bag pages to the database, independent of the auto-save interval or of closing a bag GUI | command | `/bag save` | ultibag.use | player | player | brief | BagCommand#saveBag |
| ultiremotebag.bag.main-gui | The paginated main page listing every owned bag (chest icon per page showing item/slot counts) plus, when under the page limit and Vault-backed economy is available, a purchase icon whose color and icon (minecart vs. barrier) reflect whether the sender can currently afford the next bag's price | gui | opened by `/bag` (`ultiremotebag.bag.open`) | n/a | n/a | player | brief | RemoteBagMainGUI#provideItems |
| ultiremotebag.bag.content-gui | The single bag page's content grid (45 slots, rows 1-5) plus a fixed toolbar (back, refresh, save, mode indicator, close) on row 6; item movement is allowed only in edit mode, refused with a message and sound in read-only mode | gui | opened by `/bag <page>` (owner, edit mode) or by an admin's `see`/`create`/`delete`(no)/`clear`(no) flow (owner or read-only, per the current lock state) | n/a | n/a | player | brief | RemoteBagContentGUI#setupContent |
| ultiremotebag.bag.cleanup-on-quit | On player quit, release every bag-page lock the quitting player held (owner or admin), persist their cached bag pages to the database, and evict them from the in-memory cache | event | quit the server as any player who has opened at least one remote bag this session | n/a | n/a | internal | none | BagListener#onPlayerQuit |
| ultiremotebag.bag.auto-save | Persist every currently cached (loaded) player's bag pages to the database on a fixed schedule; `auto_save_interval` is declared but has no effect on this cadence — the real period is a hardcoded 6000-tick (300s) `@Scheduled` period regardless of this key's value (UltiKits/UltiRemoteBag#13) | scheduled | runs automatically every 6000 ticks (300s, hardcoded) for every player with at least one bag page loaded into cache | n/a | n/a | internal | brief | RemoteBagService#autoSaveTask |
| ultiremotebag.bag.persistence | A bag page's item contents, once saved (via `/bag save`, closing the content GUI in edit mode, the auto-save task, or a clean player quit), survive a full server restart — `RemoteBagService#loadBagIfNeeded` re-queries `remote_bags` by `player_uuid` on first access after restart and deserializes each page's stored YAML back into an `ItemStack[]` | persistence | place an item in an owned bag page, save it (see `ultiremotebag.bag.save` or `ultiremotebag.bag.content-gui`'s close-saves-in-edit-mode behaviour), then restart the server and reopen the same page | n/a | n/a | player | detailed | RemoteBagService#saveBag, RemoteBagService#loadBagIfNeeded, RemoteBagData#RemoteBagData |
| ultiremotebag.bag.lock-not-persisted | A bag page's edit/read-only lock (`BagLockService#locks`, an in-memory `ConcurrentHashMap`) is never written to disk — a lock held at the moment of a server restart or crash (rather than a clean quit, which explicitly releases it via `ultiremotebag.bag.cleanup-on-quit`) simply ceases to exist on the next boot, with no expiry logic or persistence involved at all | persistence | hold an edit lock (open `/bag <page>` and leave the GUI open), then restart or crash the server without quitting first, then reopen the same page as a different session | n/a | n/a | internal | brief | BagLockService#locks |

## Admin

Admin sub-commands, all on the same `BagCommand` class (see `## Bag (player)` above for the
class-level annotation). Each mapping additionally declares its own method-level `permission()`,
checked ON TOP OF the class-level `ultibag.use` (see the Permission convention note) — an admin
therefore needs both `ultibag.use` and the specific admin permission for each action.

| ID | Feature | Kind | How to reach | Permission | Target | Tier | Manual | Source |
|---|---|---|---|---|---|---|---|---|
| ultiremotebag.admin.see | View another player's first (lowest-numbered) bag page in read-only or edit mode depending on the current lock state; refuses if the target has never played or has no bag pages at all | command | `/bag see <player>` | ultibag.use AND ultibag.admin.see | player | admin | brief | BagCommand#seePlayerBag |
| ultiremotebag.admin.see-page | View another player's specific bag page by number, same lock-state and permission rules as `.see` | command | `/bag see <player> <page>` | ultibag.use AND ultibag.admin.see | player | admin | brief | BagCommand#seePlayerBagPage |
| ultiremotebag.admin.create | Create a new bag page for a target player beyond their normal page limit, immediately persisted; refuses if the target has never played | command | `/bag create <player>` | ultibag.use AND ultibag.admin.create | player | admin | brief | BagCommand#createBag |
| ultiremotebag.admin.delete | Permanently delete a target player's specific bag page (and its row in `remote_bags`); refuses if the target has never played or if that page is currently held under an edit lock the admin cannot upgrade | command | `/bag delete <player> <page>` | ultibag.use AND ultibag.admin.delete | player | admin | brief | BagCommand#deleteBag |
| ultiremotebag.admin.clear | Empty the item contents of a target player's specific bag page in place (page itself is not deleted); same lock and never-played refusals as `.delete` | command | `/bag clear <player> <page>` | ultibag.use AND ultibag.admin.clear | player | admin | brief | BagCommand#clearBag |
| ultiremotebag.admin.list | List every bag page a target player owns, with per-page item and slot-usage counts and a total count; refuses if the target has never played | command | `/bag list <player>` | ultibag.use AND ultibag.admin.list | player | admin | brief | BagCommand#listBags |

## Configuration

Every `@ConfigEntry`-annotated field on this module's one `@ConfigEntity` class,
`RemoteBagConfig` (`config/remotebag.yml`, 24 keys total — matching the reconciliation table's own
`@ConfigEntry` count of 24 exactly).

**Seven of the twenty-four keys are declared and validated but never read by any production code
in this module — each is called out in its own row below with the filed issue number
(`UltiKits/UltiRemoteBag#13`–`#19`) rather than a claim that flipping it changes anything.**
Confirmed for each by `grep -rn <getterName> src/main/java`, returning no hit outside
`RemoteBagConfig` itself.

| ID | Feature | Kind | How to reach | Permission | Target | Tier | Manual | Source |
|---|---|---|---|---|---|---|---|---|
| ultiremotebag.config.remotebag.auto_save_interval | Declared as the auto-save task's interval in seconds; never read — the real cadence is a hardcoded 6000-tick (300s) `@Scheduled` period regardless of this key's value. Known product defect, `UltiKits/UltiRemoteBag#13` | config | `config/remotebag.yml: auto_save_interval (default: 300, has no effect, see UltiKits/UltiRemoteBag#13)` | n/a | n/a | admin | brief | RemoteBagConfig#autoSaveInterval (declared, never read outside this class) |
| ultiremotebag.config.remotebag.default_pages | Number of free bag pages a player has before hitting the page limit, used only when `permission_based_pages` is false or no `permission_prefix.N` node matches | config | `config/remotebag.yml: default_pages (default: 1)` | n/a | n/a | admin | brief | RemoteBagService#getPlayerMaxPages |
| ultiremotebag.config.remotebag.economy.base_price | Base price (in Vault currency) of the first purchased bag beyond the free allotment | config | `config/remotebag.yml: economy.base_price (default: 10000)` | n/a | n/a | admin | brief | RemoteBagService#calculatePrice |
| ultiremotebag.config.remotebag.economy.enabled | Enable the purchase-a-new-bag economy feature entirely; when false (or Vault is absent), a new bag page is created for free up to the page limit instead of being purchased | config | `config/remotebag.yml: economy.enabled (default: true)` | n/a | n/a | admin | brief | RemoteBagMainGUI#provideItems, RemoteBagService#purchaseBag |
| ultiremotebag.config.remotebag.economy.price_increase_enabled | Enable per-purchase price escalation; when false, every purchased bag costs exactly `economy.base_price` regardless of how many the player already owns | config | `config/remotebag.yml: economy.price_increase_enabled (default: true)` | n/a | n/a | admin | brief | RemoteBagService#calculatePrice |
| ultiremotebag.config.remotebag.economy.price_increase_rate | Per-bag price escalation rate applied as `basePrice * (1 + rate)^(n-1)` for the n-th purchased bag | config | `config/remotebag.yml: economy.price_increase_rate (default: 0.1)` | n/a | n/a | admin | brief | RemoteBagService#calculatePrice |
| ultiremotebag.config.remotebag.gui_title | Declared as a custom GUI title template with `{PAGE}`/`{MAX}` placeholders; never read — both bag GUI classes build their titles from hardcoded i18n keys (`gui_main_title`, `bag_name`) instead. Known product defect, `UltiKits/UltiRemoteBag#14` | config | `config/remotebag.yml: gui_title (default: a Chinese-language template meaning "Remote Bag, page {PAGE}/{MAX}", has no effect, see UltiKits/UltiRemoteBag#14)` | n/a | n/a | admin | brief | RemoteBagConfig#guiTitle (declared, never read outside this class) |
| ultiremotebag.config.remotebag.lock.notify_readonly_viewers | Declared as a toggle for whether a read-only viewer is notified when the bag's owner starts using it; `BagLockService#notifyReadOnlyAdmins` runs unconditionally whenever the owner acquires the lock, regardless of this key's value. Known product defect, `UltiKits/UltiRemoteBag#19` | config | `config/remotebag.yml: lock.notify_readonly_viewers (default: true, has no effect, see UltiKits/UltiRemoteBag#19)` | n/a | n/a | admin | brief | RemoteBagConfig#notifyReadonlyViewers (declared, never read outside this class) |
| ultiremotebag.config.remotebag.lock.timeout_seconds | Seconds an edit or admin lock may sit idle (holder not actively closing the GUI) before it is treated as expired and released to the next opener | config | `config/remotebag.yml: lock.timeout_seconds (default: 300)` | n/a | n/a | admin | brief | UltiRemoteBag#registerSelf (reads at boot via `lockService.setLockTimeout`), BagLockService#setLockTimeout |
| ultiremotebag.config.remotebag.max_pages | Hard ceiling on bag pages a player may ever hold, regardless of permission nodes or `default_pages` | config | `config/remotebag.yml: max_pages (default: 10)` | n/a | n/a | admin | brief | RemoteBagService#getPlayerMaxPages |
| ultiremotebag.config.remotebag.messages.bag_saved | Declared as the manual-save confirmation message; `/bag save` actually sends `plugin.i18n("bag_saved_manually")` instead, a separate string never derived from this key. Known product defect, `UltiKits/UltiRemoteBag#17` | config | `config/remotebag.yml: messages.bag_saved (default: a Chinese-language message meaning "Remote bag saved!", has no effect, see UltiKits/UltiRemoteBag#17)` | n/a | n/a | admin | brief | RemoteBagConfig#bagSavedMessage (declared, never read outside this class) |
| ultiremotebag.config.remotebag.messages.no_permission | Declared as a custom no-permission message; a permission failure is actually answered by the framework's own `PermissionValidator`, whose message is hardcoded Chinese i18n text unrelated to this key. Known product defect, `UltiKits/UltiRemoteBag#15` | config | `config/remotebag.yml: messages.no_permission (default: a Chinese-language message meaning "You do not have permission to use the remote bag!", has no effect, see UltiKits/UltiRemoteBag#15)` | n/a | n/a | admin | brief | RemoteBagConfig#noPermissionMessage (declared, never read outside this class) |
| ultiremotebag.config.remotebag.messages.page_locked | Declared as a page-locked message with a `{PAGE}` placeholder; never read anywhere — the actual blocked/read-only messages come from `BagOpenResult`'s own hardcoded strings. Known product defect, `UltiKits/UltiRemoteBag#16` | config | `config/remotebag.yml: messages.page_locked (default: a Chinese-language message meaning "You do not have permission to access page {PAGE}!", has no effect, see UltiKits/UltiRemoteBag#16)` | n/a | n/a | admin | brief | RemoteBagConfig#pageLockedMessage (declared, never read outside this class) |
| ultiremotebag.config.remotebag.permission_based_pages | Switch the page-limit calculation from the flat `default_pages` value to scanning `permission_prefix.N` nodes downward from `max_pages` for the first one the player holds | config | `config/remotebag.yml: permission_based_pages (default: true)` | n/a | n/a | admin | brief | RemoteBagService#getPlayerMaxPages |
| ultiremotebag.config.remotebag.permission_prefix | Permission-node prefix scanned (with an appended page-count integer) when `permission_based_pages` is true, e.g. `ultibag.pages.3` grants up to 3 pages | config | `config/remotebag.yml: permission_prefix (default: "ultibag.pages.")` | n/a | n/a | admin | brief | RemoteBagService#getPlayerMaxPages |
| ultiremotebag.config.remotebag.rows_per_page | Number of inventory rows (1-6) making up one bag page's content area; also fixes each page's slot capacity used for statistics and empty-content sizing | config | `config/remotebag.yml: rows_per_page (default: 6)` | n/a | n/a | admin | brief | RemoteBagMainGUI#createBagIcon, RemoteBagService#deserializeItems |
| ultiremotebag.config.remotebag.save_on_close | Declared as a toggle for whether closing the content GUI in edit mode saves; `RemoteBagContentGUI#onClose` calls `saveCurrentContents()` unconditionally whenever in edit mode, with no check of this key anywhere. Known product defect, `UltiKits/UltiRemoteBag#18` | config | `config/remotebag.yml: save_on_close (default: true, has no effect, see UltiKits/UltiRemoteBag#18)` | n/a | n/a | admin | brief | RemoteBagConfig#saveOnClose (declared, never read outside this class) |
| ultiremotebag.config.remotebag.sound.close | Sound effect (XSound-matched name; an unrecognized name is silently ignored) played when the content GUI closes in edit mode | config | `config/remotebag.yml: sound.close (default: BLOCK_CHEST_CLOSE)` | n/a | n/a | admin | brief | SoundUtil#playCloseSound |
| ultiremotebag.config.remotebag.sound.enabled | Master switch for every sound effect this module plays; when false, none of the other `sound.*` keys have any effect | config | `config/remotebag.yml: sound.enabled (default: true)` | n/a | n/a | admin | brief | SoundUtil#playSound |
| ultiremotebag.config.remotebag.sound.error | Sound effect played on a refused action (out-of-range page, blocked lock, invalid target, insufficient balance) | config | `config/remotebag.yml: sound.error (default: ENTITY_VILLAGER_NO)` | n/a | n/a | admin | brief | SoundUtil#playErrorSound |
| ultiremotebag.config.remotebag.sound.open | Sound effect played when either bag GUI opens | config | `config/remotebag.yml: sound.open (default: BLOCK_CHEST_OPEN)` | n/a | n/a | admin | brief | SoundUtil#playOpenSound |
| ultiremotebag.config.remotebag.sound.pitch | Pitch applied to every sound this module plays (0.5-2.0) | config | `config/remotebag.yml: sound.pitch (default: 1.0)` | n/a | n/a | admin | none | SoundUtil#playSound |
| ultiremotebag.config.remotebag.sound.purchase | Sound effect played on a successful bag purchase | config | `config/remotebag.yml: sound.purchase (default: ENTITY_PLAYER_LEVELUP)` | n/a | n/a | admin | brief | SoundUtil#playPurchaseSound |
| ultiremotebag.config.remotebag.sound.volume | Volume applied to every sound this module plays (0.0-1.0) | config | `config/remotebag.yml: sound.volume (default: 1.0)` | n/a | n/a | admin | none | SoundUtil#playSound |
