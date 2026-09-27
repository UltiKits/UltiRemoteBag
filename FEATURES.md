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
  feature — for every Kind, `config` included: all 17 `config` rows below cite the reading
  member. Until 6.3.0 seven of them had no reading member anywhere in this module's source and
  cited the config class's own field declaration instead; six of those keys have since been
  removed and one wired, so no row below is in that shape any more (see the `## Configuration`
  section note).
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
`@EventListener` = 1 (class), `@EventHandler` = 1 (handler method), `@Scheduled` = 0,
`@ConditionalOnConfig` = 0, `@ConfigEntity` = 1 (class), `@ConfigEntry` = 17 — confirmed by
reading `BagCommand.java` directly (9 `@CmdMapping` sites: bare, `<page>`, `save`, `see <player>`,
`see <player> <page>`, `create <player>`, `delete <player> <page>`, `clear <player> <page>`,
`list <player>`). The two-argument `see <player> <page>` mapping (`BagCommand.java:139`) is this
module's standing positive control — it is easy to conflate with the one-argument `see <player>`
mapping three lines above it (`BagCommand.java:115`), and both are checked by name, not merely by
count, below. This document's command-row count matches the `@CmdMapping` annotation-site count
exactly (9 against 9).

## Bag (player)

`BagCommand` — class-level `@CmdExecutor(alias = {"bag", "remotebag", "rb", "yunbag"}, permission
= "ultibag.use", description = "command_description")` (a language-file key: `Remote bag system`
under `language: en`), `@CmdTarget(PLAYER)`. Every sub-command below
requires `ultibag.use` at minimum (see the Permission convention note above); a player lacking it
never reaches `onCommand` at all — Bukkit's own command-tree filtering answers with "Unknown
command" before this class's code runs. A remote bag is virtual per-player cloud storage: pages of
inventory-sized item content, purchasable beyond the free default allotment, editable through a
GUI, and persisted by every action that changes it.

| ID | Feature | Kind | How to reach | Permission | Target | Tier | Manual | Source |
|---|---|---|---|---|---|---|---|---|
| ultiremotebag.bag.open | Open the sender's own remote bag main page (paginated list of owned bag pages, with a purchase button if under the page limit and economy is enabled) | command | `/bag` | ultibag.use | player | player | brief | BagCommand#openMainPage |
| ultiremotebag.bag.open-page | Open a specific owned bag page directly by number, acquiring the owner lock; refuses with a range error if the page number is outside `1..maxPages`, or a not-found error if that page does not yet exist for this player. The sender's open inventory is closed before the lock is decided — also when the lock is then refused, so a page the sender had open is saved and released either way — and re-running it on a page the sender already has open therefore saves that page, releases its lock and takes the lock again: the page stays locked by its owner (`UltiKits/UltiRemoteBag#41`) | command | `/bag <page>` | ultibag.use | player | player | brief | BagCommand#openPage |
| ultiremotebag.bag.save | Manually persist the sender's bag pages to the database. Flushes the sender's currently open content page first when that page is in edit mode AND is a page of the sender's own bag (`RemoteBagContentGUI#flushOpenEditPage`, which resolves the open page through the GUI library's own player-to-page map and then compares the sender against the page's `ownerUuid`), so an item placed since the page was opened is persisted without closing it or clicking its Save button. Two pages are deliberately not flushed by this command: a read-only page, whose live inventory is another player's bag being viewed, and another player's bag opened in edit mode through `/bag see` — there the page's viewer IS the sender while its owner is not, so only the owner comparison rules it out (an admin's edits to such a page are still written by that page's own Save button and by closing it). It reports only a save it performed: a flush already persists the whole of that player's cache, so the cache write is the ELSE branch rather than a second unconditional pass (running both re-queried, re-serialized and re-timestamped every cached page twice), and with nothing cached at all -- a fresh login that has not opened a page -- it says `msg_nothing_to_save` instead of claiming a write that did not happen. If the open page does not write -- another holder has taken it (`msg_save_refused_lock_taken`), or the database write failed (`msg_save_failed`) -- that page says so itself and this command reports nothing. With nothing open, the two ways the cache write can fail are told apart via `RemoteBagService#hasCachedPages`: nothing cached says `msg_nothing_to_save`, a failed write says `msg_save_failed`. `saveBag` reports true only when EVERY cached page reached the database, so a page whose update threw is never counted as saved. Before `UltiKits/UltiRemoteBag#22` this command persisted only what had already reached the service cache | command | `/bag save` | ultibag.use | player | player | detailed | BagCommand#saveBag, RemoteBagContentGUI#flushOpenEditPage, RemoteBagService#saveBag |
| ultiremotebag.bag.main-gui | The paginated main page listing every owned bag (chest icon per page showing item/slot counts, the slot count as `Slots Used: x/45` against the fixed page capacity — the denominator used to be `rows_per_page * 9` and rendered `45/54` at that key's own default, `UltiKits/UltiRemoteBag#24`) plus, when under the page limit and Vault-backed economy is available, a purchase icon whose color and icon (minecart vs. barrier) reflect whether the sender can currently afford the next bag's price | gui | opened by `/bag` (`ultiremotebag.bag.open`) | n/a | n/a | player | brief | RemoteBagMainGUI#provideItems |
| ultiremotebag.bag.content-gui | The single bag page's content grid (45 slots, rows 1-5) plus a fixed toolbar (back, refresh, save, mode indicator, close) on row 6. In edit mode the 45 content slots behave like a chest: click, shift-click, number-key swap and drag all move items. In read-only mode every one of those is cancelled, and an attempt that carries an item is additionally answered with a red `msg_readonly_no_move` line and `sound.error` when it targets the page itself or is a shift-click from the viewer's own inventory. A refused DRAG always says why — `msg_readonly_no_move` for a read-only drag touching the window, `msg_cannot_drag_toolbar` for an edit-mode drag reaching the toolbar row — because a cancelled drag runs no icon action and moves nothing, so silence would be indistinguishable from a broken build. Both modes scope the refusal to THIS page's window: a drag confined to the viewer's own inventory is allowed, matching the click path's deliberate policy that read-only guards the bag and not the viewer's own inventory. The toolbar row is not movable in either mode, so no icon, including read-only's disabled save icon, can be picked up out of it, while clicking one still runs its action. That guard keys on the clicked slot, so the two vanilla actions that are NOT confined to it are handled separately: a `COLLECT_TO_CURSOR` double-click sweeps the whole top inventory and a player-side `MOVE_TO_OTHER_INVENTORY` scans it for a destination, so either is refused with `msg_toolbar_item_conflict` when the item it carries `isSimilar` to a toolbar icon — otherwise the icon could be collected out of the row (and regenerated on the next open, duplicating it) or the player's stack could merge into it and be lost, since `saveCurrentContents` only serialises slots 0-44. Read-only's Refresh button redraws every content slot, empty ones included, so a slot the owner has emptied since the page opened is shown as empty rather than keeping the item that used to be there. The cancel decision is the boolean `RemoteBagContentGUI#onClick`/`#onDrag` returns, which the GUI library's `mc.obliviate.inventory.InvListener` turns into `setCancelled` — `true` allows, `false` refuses, measured from `obliviate-invs` 4.3.0 bytecode; this page returned the inverse at every branch until `UltiKits/UltiRemoteBag#27`. Because the library applies `setCancelled(false)` for an allow, which CLEARS an earlier handler's cancellation rather than declining to add one, both entry points answer "refuse" for an event another plugin has already cancelled: this page decides for itself and does not reverse an anti-cheat or region plugin's decision. Closing the page in edit mode always writes it — `save_on_close`, which claimed to gate that and never did, was removed rather than wired, because the off branch destroyed items the player had dragged in (`UltiKits/UltiRemoteBag#18`, feature request `UltiKits/UltiRemoteBag#37`) | gui | opened by `/bag <page>` (owner, edit mode) or by an admin's `see`/`create`/`delete`(no)/`clear`(no) flow (owner or read-only, per the current lock state) | n/a | n/a | player | brief | RemoteBagContentGUI#setupContent, RemoteBagContentGUI#onClick, RemoteBagContentGUI#onDrag |
| ultiremotebag.bag.cleanup-on-quit | On player quit, release every bag-page lock the quitting player held (owner or admin), call `saveBag` (a no-op re-save in normal play, because every cache-write path already persists in the same action, `UltiKits/UltiRemoteBag#23`; it does still write a page whose earlier database write failed, and since `UltiKits/UltiRemoteBag#13` removed the scheduled auto-save this and module unload are the two remaining places that retry one), and evict their pages from `bagCache` via `clearCache`. The lock-release effect cannot be isolated from `RemoteBagContentGUI#onClose`'s OWN independent lock release, since quitting always closes any open GUI first and `onClose` releases the same lock regardless of this listener; `clearCache` is the one effect unique to this listener (`onClose` never calls it), and is what this event's checklist row actually verifies | event | quit the server as any player who has opened at least one remote bag this session | n/a | n/a | internal | detailed | BagListener#onPlayerQuit |
| ultiremotebag.bag.persistence | A bag page's item contents, once saved (the content GUI's own Save button, closing it in edit mode, or `/bag save` — which flushes the sender's own open edit-mode page as of `UltiKits/UltiRemoteBag#22`), survive a full server restart — `RemoteBagService#loadBagIfNeeded` re-queries `remote_bags` by `player_uuid` on first access after restart and deserializes each page's stored YAML back into an `ItemStack[]` | persistence | place an item in an owned bag page, click the content GUI's own Save button (or close the GUI while in edit mode), then restart the server and reopen the same page | n/a | n/a | player | detailed | RemoteBagContentGUI#saveCurrentContents, RemoteBagService#loadBagIfNeeded, RemoteBagData#RemoteBagData |
| ultiremotebag.bag.lock-not-persisted | A bag page's edit/read-only lock (`BagLockService#locks`, an in-memory `ConcurrentHashMap`) is never written to disk — a lock held at the moment of a server restart or crash (rather than a clean quit, which explicitly releases it via `ultiremotebag.bag.cleanup-on-quit`) simply ceases to exist on the next boot, with no expiry logic or persistence involved at all | persistence | hold an edit lock (open `/bag <page>` and leave the GUI open), then restart or crash the server without quitting first, then reopen the same page as a different session | n/a | n/a | internal | brief | BagLockService#locks |

## Admin

Admin sub-commands, all on the same `BagCommand` class (see `## Bag (player)` above for the
class-level annotation). Each mapping additionally declares its own method-level `permission()`,
checked ON TOP OF the class-level `ultibag.use` (see the Permission convention note) — an admin
therefore needs both `ultibag.use` and the specific admin permission for each action.

| ID | Feature | Kind | How to reach | Permission | Target | Tier | Manual | Source |
|---|---|---|---|---|---|---|---|---|
| ultiremotebag.admin.see | View another player's first (lowest-numbered) bag page in read-only or edit mode depending on the current lock state; refuses if the target has never played. The code path also refuses with `player_no_bags` if the target has zero bag pages, but that state is UNREACHABLE — `RemoteBagService#getPlayerBagPages` fabricates a virtual page 1 whenever the cache holds no real pages, so every seen player always shows at least one. Known product defect, `UltiKits/UltiRemoteBag#26`. Like `/bag see <player> <page>`, it closes the sender's open inventory before the lock is decided (`UltiKits/UltiRemoteBag#41`) | command | `/bag see <player>` | ultibag.use AND ultibag.admin.see | player | admin | detailed | BagCommand#seePlayerBag |
| ultiremotebag.admin.see-page | View another player's specific bag page by number, same lock-state and permission rules as `.see`. Like `/bag <page>`, it closes the sender's open inventory before the lock is decided, so an administrator re-running it on a page they hold in edit mode keeps the ADMIN lock (`UltiKits/UltiRemoteBag#41`) | command | `/bag see <player> <page>` | ultibag.use AND ultibag.admin.see | player | admin | brief | BagCommand#seePlayerBagPage |
| ultiremotebag.admin.create | Create a new bag page for a target player beyond their normal page limit, immediately persisted; refuses if the target has never played | command | `/bag create <player>` | ultibag.use AND ultibag.admin.create | player | admin | brief | BagCommand#createBag |
| ultiremotebag.admin.delete | Permanently delete a target player's specific bag page (and its row in `remote_bags`); refuses if the target has never played or if that page is currently held under an edit lock the admin cannot upgrade | command | `/bag delete <player> <page>` | ultibag.use AND ultibag.admin.delete | player | admin | brief | BagCommand#deleteBag |
| ultiremotebag.admin.clear | Empty the item contents of a target player's specific bag page in place (page itself is not deleted); same lock and never-played refusals as `.delete` | command | `/bag clear <player> <page>` | ultibag.use AND ultibag.admin.clear | player | admin | brief | BagCommand#clearBag |
| ultiremotebag.admin.list | List every bag page a target player owns, with per-page item and slot-usage counts and a total count; refuses if the target has never played. The code path also has a `no_bags` empty-list branch, but it is UNREACHABLE for the same reason as `ultiremotebag.admin.see` — `getPlayerBagPages` never actually returns an empty list. Known product defect, `UltiKits/UltiRemoteBag#26` | command | `/bag list <player>` | ultibag.use AND ultibag.admin.list | player | admin | detailed | BagCommand#listBags |

## Lifecycle Hooks

`UltiRemoteBag#onUnregister()` is the extension-point hook the framework's `final`
`UltiToolsPlugin#unregisterSelf()` invokes when this module is unloaded (`/upm uninstall UltiRemoteBag`,
or server shutdown). It runs first; the framework then unregisters this module's commands and
listeners. Before `UltiKits/UltiRemoteBag#12`'s lifecycle-hook migration this module overrode
`unregisterSelf()` itself, so `/upm uninstall UltiRemoteBag` skipped both command and listener
unregistration. Server shutdown was unaffected: the framework ran its own command and listener cleanup
there independently of the override.

This module no longer declares any `@Scheduled` method, so the framework's inability to cancel one on
`/upm uninstall` (`UltiKits/UltiTools-Reborn#503`) no longer reaches it: `RemoteBagService#autoSaveTask`
and the `auto_save_interval` key that claimed to set its period were both removed
(`UltiKits/UltiRemoteBag#13`, `UltiKits/UltiRemoteBag#23`).

This module declares no `onReload()` hook. `/ul reload UltiRemoteBag` runs only the framework's own
reload steps: configuration reload (`ConfigManager#reloadConfigs` re-initialises the same
`RemoteBagConfig` bean the services hold, so a live-read key such as `max_pages` takes effect
immediately), language refresh, `@ConditionalOnConfig` drift report (this module has no gate), and the
framework's `Module 'UltiRemoteBag' reloaded.` line. Before the migration this module replaced
`reloadSelf()` with a log-only override, so neither the configuration nor the language catalogue was
reloaded; that override and its `UltiRemoteBag configuration reloaded!` console line were removed.
Keys applied only once at boot (`lock.timeout_seconds`, read by `UltiRemoteBag#registerSelf`) still
need a restart — `lock.notify_readonly_viewers` is read live and its sibling in the same `lock.`
block is not, an asymmetry tracked as `UltiKits/UltiRemoteBag#39`. `/ul reload` and `/upm uninstall` are the framework's own commands, not `@CmdMapping`
sites in this repository, so no `command`-Kind row is added for either. All three rows below are
framework-invoked and therefore `event`-Kind — the third, `ultiremotebag.lifecycle.removed-key-warning`,
is not a lifecycle hook at all but the module's own load-time report, and sits here because load,
reload and unload are what this section covers.

| ID | Feature | Kind | How to reach | Permission | Target | Tier | Manual | Source |
|---|---|---|---|---|---|---|---|---|
| ultiremotebag.lifecycle.reload | Re-read `config/remotebag.yml` into the live `RemoteBagConfig` bean when the module is reloaded, so a key read on every use (e.g. `max_pages` in `RemoteBagService#getPlayerMaxPages`) follows the edited file without a restart; the module adds no reload work or log line of its own | event | `/ul reload UltiRemoteBag` (framework `reloadSelf()`: config reload, language refresh, drift report, reload line) | n/a | n/a | admin | brief | UltiToolsPlugin#reloadSelf, RemoteBagService#getPlayerMaxPages |
| ultiremotebag.lifecycle.removed-key-warning | At module load, log one `WARNING` per setting that 6.3.0 removed and that is still present in the operator's `config/remotebag.yml`, naming the module, the file and the key, and saying where that setting's job went instead, in the server's `language` (the text is the language file's `removed_key_warning` and one `removed_key_reason_*` entry per key). The seven are `auto_save_interval`, `gui_title`, `messages.no_permission`, `messages.page_locked`, `messages.bag_saved`, `save_on_close` and `rows_per_page` (`UltiKits/UltiRemoteBag#13`, `#14`, `#15`, `#16`, `#17`, `#18`, `#23`, `#24`). It exists because removing a key from the code does not remove it from anybody's file: the framework writes a missing key's default in on first boot and never deletes a key it no longer declares, so an operator who had edited one of the seven would otherwise see no trace of the removal at all. A fresh install has none of the seven and logs nothing; a key this module still reads is not reported, so a warning means a residual key and not merely boot | event | start the server (or `/upm install`) with a `config/remotebag.yml` that still holds one of the seven keys | n/a | n/a | admin | brief | UltiRemoteBag#registerSelf, RemovedConfigKeys#warnIfStillPresent |
| ultiremotebag.lifecycle.unload | When the module is unloaded, write every cached player's bag pages to the database (`RemoteBagService#saveAllBags`), then log the language file's `bag_disabled` (`UltiRemoteBag has been disabled!` under `language: en`). In routine play this rewrites what is already stored, because every cache-write path already saves synchronously (`UltiKits/UltiRemoteBag#23`); its distinguishing effect is that a page still held in `bagCache` overwrites its database row, including a row changed outside the module since the page was loaded, and stamps `last_updated` at the moment of the unload. Since `UltiKits/UltiRemoteBag#13` removed the scheduled auto-save, this hook is the only thing that writes an idle player's row, which is what makes that effect observable on its own | event | unload the module at runtime, e.g. `/upm uninstall UltiRemoteBag` from the console (the framework calls `unregisterSelf()`, which invokes this hook first) | n/a | n/a | internal | brief | UltiRemoteBag#onUnregister, RemoteBagService#saveAllBags |

## Configuration

Every `@ConfigEntry`-annotated field on this module's one `@ConfigEntity` class,
`RemoteBagConfig` (`config/remotebag.yml`, 17 keys total — matching the reconciliation table's own
`@ConfigEntry` count of 17 exactly).

**Every key below is read by production code, and does what it says.** Seven keys were removed in
6.3.0 and one wired. Six of the seven were dead — declared, validated and read by nothing
(`auto_save_interval`, `gui_title`, `messages.no_permission`, `messages.page_locked`,
`messages.bag_saved`, `save_on_close` — `UltiKits/UltiRemoteBag#13`, `#14`, `#15`, `#16`, `#17`,
`#18`, `#23`); the seventh, `rows_per_page`, was read but did not control what it claimed to
(`UltiKits/UltiRemoteBag#24`). The one wired is `lock.notify_readonly_viewers`
(`UltiKits/UltiRemoteBag#19`).

Two of those removals began as something else and were reversed by the maintainer on 2026-09-23,
which is worth recording because the reasons generalise. `save_on_close` was wired first: the branch
it switched off had no way to return the window's contents, so turning it off destroyed them —
wiring a dead key only removes the defect when the branch the key switches off is itself safe, and
that branch had never been walked precisely because the key was dead (feature request
`UltiKits/UltiRemoteBag#38` covers the capability, `#37` the close-time one). `rows_per_page` had its
declaration narrowed first, and the narrowed declaration was still false: a page's capacity is fixed
at 45 by the content window, while the key was a floor on the loaded array and the denominator of the
main GUI's "Slots Used" lore, so at its own default a full page rendered `45/54`. When the declaration
and the behaviour are both wrong, correcting the declaration only moves the lie into the
documentation. Confirmed for each remaining key by
`grep -rn <getterName> src/main/java`, which now returns at least one hit outside `RemoteBagConfig`
itself; the control for that query is `grep -rn getBagSavedMessage src/main/java`, which returns
nothing at all because the field is gone.

A removed key is not removed from an operator's file — the framework never deletes a key it no
longer declares — so all seven are still on disk on every server that has run this module. See
`ultiremotebag.lifecycle.removed-key-warning` below for what the module now says about that.

| ID | Feature | Kind | How to reach | Permission | Target | Tier | Manual | Source |
|---|---|---|---|---|---|---|---|---|
| ultiremotebag.config.remotebag.default_pages | Fallback page limit used ONLY when `permission_based_pages` is true AND the player holds no `permission_prefix.N` node at all; consulted only inside that branch, never when `permission_based_pages` is false | config | `config/remotebag.yml: default_pages (default: 1)` | n/a | n/a | admin | brief | RemoteBagService#getPlayerMaxPages |
| ultiremotebag.config.remotebag.economy.base_price | Base price (in Vault currency) of the first purchased bag beyond the free allotment | config | `config/remotebag.yml: economy.base_price (default: 10000)` | n/a | n/a | admin | brief | RemoteBagService#calculatePrice |
| ultiremotebag.config.remotebag.economy.enabled | Enable the purchase-a-new-bag economy feature. `RemoteBagService#purchaseBag` has a free-creation fallback when this is false (or Vault is absent), but that fallback is UNREACHABLE in practice: the purchase icon — the only UI trigger for `purchaseBag()` at all — is itself omitted by `RemoteBagMainGUI#provideItems` unless economy is BOTH enabled AND Vault-available. Disabling this key therefore does not let players create free pages; it removes their only way to create any page beyond the initial allotment. Known product defect, `UltiKits/UltiRemoteBag#25` | config | `config/remotebag.yml: economy.enabled (default: true, disabling it removes rather than frees the purchase path, see UltiKits/UltiRemoteBag#25)` | n/a | n/a | admin | detailed | RemoteBagMainGUI#provideItems, RemoteBagService#purchaseBag |
| ultiremotebag.config.remotebag.economy.price_increase_enabled | Enable per-purchase price escalation; when false, every purchased bag costs exactly `economy.base_price` regardless of how many the player already owns | config | `config/remotebag.yml: economy.price_increase_enabled (default: true)` | n/a | n/a | admin | brief | RemoteBagService#calculatePrice |
| ultiremotebag.config.remotebag.economy.price_increase_rate | Per-bag price escalation rate applied as `basePrice * (1 + rate)^(n-1)` for the n-th purchased bag | config | `config/remotebag.yml: economy.price_increase_rate (default: 0.1)` | n/a | n/a | admin | brief | RemoteBagService#calculatePrice |
| ultiremotebag.config.remotebag.lock.notify_readonly_viewers | Whether an administrator viewing a bag page read-only is told when the owner starts using it again. When true (the shipped default, and what happened unconditionally before `UltiKits/UltiRemoteBag#19` wired this key), `BagLockService#notifyReadOnlyAdmins` sends each read-only viewer the `msg_owner_started_using` line as the owner takes the lock. When false, no line is sent. The read-only session is still recorded either way — the key decides whether the viewer is told, not whether the module tracks them. Read on every notification rather than cached at load, so `/ul reload UltiRemoteBag` applies a change without a restart | config | `config/remotebag.yml: lock.notify_readonly_viewers (default: true)` | n/a | n/a | admin | brief | BagLockService#notifyReadOnlyAdmins |
| ultiremotebag.config.remotebag.lock.timeout_seconds | Seconds before a lock whose holder's session ended without releasing it is reclaimed and released to the next opener. It is a recovery mechanism, NOT a lease a present holder has to renew: a lock is never reclaimed while its holder is online with that page open, however long they idle, so an administrator does not take edit authority from an AFK owner who is still looking at the page (`BagLockService#isReclaimable`, which asks `RemoteBagContentGUI#isPageOpenBy`). Presence is read live at the moment of the check -- the GUI library's own player-to-page registry plus the holder's live `InventoryView` -- and never stored as a flag, because a flag that leaked on a crash, a reload or a force-close would make the lock immortal. After a crash or a reload there is no open view, so the timeout applies as it always did. The administrator is told why they got read-only (`msg_owner_has_page_open`) rather than being downgraded silently. Before this, the lock expired on wall-clock age alone and nothing closed or downgraded the owner's still-open page, so the owner's eventual save wrote a snapshot taken before the administrator existed over the administrator's committed edits | config | `config/remotebag.yml: lock.timeout_seconds (default: 300)` | n/a | n/a | admin | detailed | UltiRemoteBag#registerSelf (reads at boot via `lockService.setLockTimeout`), BagLockService#setLockTimeout, BagLockService#isReclaimable, RemoteBagContentGUI#isPageOpenBy |
| ultiremotebag.config.remotebag.max_pages | Hard ceiling on bag pages a player may ever hold. When `permission_based_pages` is false this is the player's page limit OUTRIGHT (returned directly, `default_pages` never consulted); when true it is both the top of the `permission_prefix.N` scan range and the ceiling any matched node can return | config | `config/remotebag.yml: max_pages (default: 10)` | n/a | n/a | admin | brief | RemoteBagService#getPlayerMaxPages |
| ultiremotebag.config.remotebag.permission_based_pages | When false, every player's page limit is `max_pages` outright. When true, scan `permission_prefix.N` nodes downward from `max_pages` to `1` for the first one the player holds, falling back to `default_pages` only if none match — the FALSE branch does not fall back to `default_pages` at all, it returns `max_pages` directly | config | `config/remotebag.yml: permission_based_pages (default: true)` | n/a | n/a | admin | detailed | RemoteBagService#getPlayerMaxPages |
| ultiremotebag.config.remotebag.permission_prefix | Permission-node prefix scanned (with an appended page-count integer) when `permission_based_pages` is true, e.g. `ultibag.pages.3` grants up to 3 pages | config | `config/remotebag.yml: permission_prefix (default: "ultibag.pages.")` | n/a | n/a | admin | brief | RemoteBagService#getPlayerMaxPages |
| ultiremotebag.config.remotebag.sound.close | Sound effect (XSound-matched name; an unrecognized name is silently ignored) played when the content GUI closes in edit mode | config | `config/remotebag.yml: sound.close (default: BLOCK_CHEST_CLOSE)` | n/a | n/a | admin | brief | SoundUtil#playCloseSound |
| ultiremotebag.config.remotebag.sound.enabled | Master switch for every sound effect this module plays; when false, none of the other `sound.*` keys have any effect | config | `config/remotebag.yml: sound.enabled (default: true)` | n/a | n/a | admin | brief | SoundUtil#playSound |
| ultiremotebag.config.remotebag.sound.error | Sound effect played ONLY on the specific refusals that call `SoundUtil.playErrorSound` directly: a page blocked by another holder's lock (`BagCommand#openPage`/`#openAdminBagPage`'s blocked branch), a failed purchase (insufficient balance, `RemoteBagMainGUI#createPurchaseIcon`), the read-only-mode "cannot move/save" refusals in `RemoteBagContentGUI`, AND a read-only viewer's failed refresh-to-edit attempt (`RemoteBagContentGUI#createRefreshButton`'s refresh-button handler, when the owner still holds the lock, plays the error sound immediately before sending `msg_owner_still_using`). It does NOT play for the out-of-range page check or any never-played-target refusal (`player_not_found`) — both of those `return` directly without a sound call | config | `config/remotebag.yml: sound.error (default: ENTITY_VILLAGER_NO)` | n/a | n/a | admin | detailed | SoundUtil#playErrorSound |
| ultiremotebag.config.remotebag.sound.open | Sound effect played when either bag GUI opens | config | `config/remotebag.yml: sound.open (default: BLOCK_CHEST_OPEN)` | n/a | n/a | admin | brief | SoundUtil#playOpenSound |
| ultiremotebag.config.remotebag.sound.pitch | Pitch applied to every sound this module plays (0.5-2.0) | config | `config/remotebag.yml: sound.pitch (default: 1.0)` | n/a | n/a | admin | none | SoundUtil#playSound |
| ultiremotebag.config.remotebag.sound.purchase | Sound effect played on a successful bag purchase | config | `config/remotebag.yml: sound.purchase (default: ENTITY_PLAYER_LEVELUP)` | n/a | n/a | admin | brief | SoundUtil#playPurchaseSound |
| ultiremotebag.config.remotebag.sound.volume | Volume applied to every sound this module plays (0.0-1.0) | config | `config/remotebag.yml: sound.volume (default: 1.0)` | n/a | n/a | admin | none | SoundUtil#playSound |
