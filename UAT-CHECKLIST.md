# UltiRemoteBag — UAT Checklist

This document is the executable companion to `FEATURES.md`: one row per feature stating the
steps to exercise it and the observable truth that proves it works. It is an internal reference
for real-machine verification, not user-facing documentation.

> Batches are dispatched at 60 rows or fewer, and a batch never spans two repositories. There are
> exactly two legitimate exits to `human-uat-pending`: a row needing the pixel layer while the
> real-client harness is not ready, and a row needing personal credentials. Every other row must
> reach `pass`, `fail`, or `blocked`. This repository's meaningful rows generally need only ONE
> player, except the lock-arbitration rows (`ultiremotebag.admin.see-page.neg-owner-blocked-by-admin`,
> `ultiremotebag.admin.see.neg-readonly-then-owner-blocked`), which need a second, OP'd account to
> act as the admin.

## Conventions

- **Columns:** `ID`, `Preconditions`, `Steps`, `Expected`, `Layer`, `Covers`.
- **ID:** cites its `FEATURES.md` ID verbatim. A negative case suffixes the checklist ID only,
  as `.neg-<slug>` — a negative case still tests the same feature, so the base ID is unchanged.
- **Layer**, copied verbatim from Laojun's own `ultitools-real-client-uat` skill so no
  translation step exists at dispatch time: `protocol`, `java-client`, `os-input`, `pixel`,
  `server`, `human`.
- **Human-authenticated-session rows (D-27b):** a row whose Steps can only be exercised through
  the maintainer's own authenticated UltiCloud panel session carries the fixed Preconditions
  phrase `maintainer-authenticated UltiCloud panel session (personal credentials)` and Layer
  `human`. This repository has no such row — it has no panel-capability surface of its own — so
  none is currently affected; the convention is stated here for template consistency.
- **Expected** must name an observable truth — an exact chat line, a log line, a database row,
  an inventory slot — and never the words "it works".
- **Covers** back-references a Phase 9 GUI-excluded class name; left blank when no such class
  applies. `RemoteBagMainGUI` and `RemoteBagContentGUI` are this module's two entries in that
  register (`.planning/phases/09-module-ecosystem-readiness-and-test-coverage/gui-exclusions/
  UltiRemoteBag.md`), so every pixel-layer row exercising either GUI names it in this column.
- A row whose Preconditions name a prior row must appear after that row in file order — asserted
  mechanically: for every row, every checklist ID cited in its Preconditions cell must have a
  strictly smaller line number in this file than the row citing it (sweep class 8, D-27a).
- **Config-per-file rule (D-06):** one checklist row per `@ConfigEntity`-annotated class, never
  one row per key. This module ships exactly one `@ConfigEntity` (`RemoteBagConfig`,
  `config/remotebag.yml`), so exactly one config row exists below (ID suffixed `-yml`,
  `ultiremotebag.config.remotebag-yml`), aggregating every per-key
  `ultiremotebag.config.remotebag.*` row rather than citing a single one of them. This module
  ships no Maven-filtered (build-time) config file, so the build-time-property clause of this
  rule does not apply to any row below.
- This repository has no `config.yml` `language` key of its own — `plugin.i18n(...)` resolves
  through the framework's own global `language` setting (`zh`/`en`), and this module supports
  both (`UltiRemoteBag#supported` returns `["zh", "en"]`). Every row below whose Expected quotes a
  literal in-game line therefore carries the precondition `language: en` set in
  `plugins/UltiTools/config.yml`, matching the exact English text in `lang/en.yml` — **except**
  the read-only/blocked lock messages, which `BagOpenResult` builds directly with hardcoded
  Simplified Chinese text, ignoring the `language` setting entirely (a known product defect, see
  the two `.neg-owner-blocked-by-admin`/`.neg-readonly-then-owner-blocked` rows below, both citing
  the same filed issue, `UltiKits/UltiRemoteBag#20`). Those two rows quote the actual (Chinese)
  meaning in English prose rather than the literal characters, per this document's English-only
  rule; a real dispatch reads the exact characters from the cited source line rather than from
  this document.
- **Row order in this repository deliberately departs from strict ID-ascending** wherever strict
  alphabetical order would place a row citing another row's ID before that cited row (D-27a
  forbids this outright) — dependency order takes precedence over alphabetical order per feature
  section; alphabetical order is used only as the tiebreaker among rows with no such dependency.
- `plugin.i18n(...)` in this framework is a raw dictionary lookup with **no** `MessageFormat`
  processing — every Expected line below that quotes an i18n-templated message reproduces
  `lang/en.yml`'s literal placeholder substitution (`{0}`, `{1}`) rather than a re-derived phrase.

## Bag (player)

| ID | Preconditions | Steps | Expected | Layer | Covers |
|---|---|---|---|---|---|
| ultiremotebag.bag.open | `language: en`; sender holds `ultibag.use` | Run `/bag` | `RemoteBagMainGUI` opens (see `ultiremotebag.bag.main-gui` below) | pixel | RemoteBagMainGUI |
| ultiremotebag.bag.open-page | `language: en`; sender holds `ultibag.use`; the sender owns at least page `1` (any fresh player does, per `RemoteBagService#getPlayerBagPages`'s empty-list fallback to page `1`) | Run `/bag 1` | `RemoteBagContentGUI` opens for page 1 in edit mode (see `ultiremotebag.bag.content-gui` below) — the lock is acquired for the sender as OWNER | pixel | RemoteBagContentGUI |
| ultiremotebag.bag.open-page.neg-out-of-range | `language: en`; sender's max page count is the shipped default 1 (fresh player, `permission_based_pages: true`, no `ultibag.pages.N` node held) | Run `/bag 2` | Chat line reads `Page 2 out of range, max page is 1` (red) — `BagCommand#openPage`'s range check refuses before attempting to open anything | server | |
| ultiremotebag.bag.open-page.neg-not-exist | `language: en`; `permission_based_pages: false` and `default_pages: 2` (raises the range to 2 without creating page 2); sender is a fresh player who has never opened a bag GUI (existing pages therefore fall back to the singleton `[1]` per `RemoteBagService#getPlayerBagPages`) | Run `/bag 2` | Chat line reads `Bag #2 does not exist` (red, `bag_not_exist`) — `BagCommand#openPage`'s range check (1..2) passes, but the existence check fails because page 2 was never actually created | server | |
| ultiremotebag.bag.main-gui | `language: en`; the sender owns exactly one bag page (fresh player, shipped `max_pages: 10` default so the sender is under the page limit); Vault installed, `economy.enabled: true` (shipped default), and the sender's Vault balance is at least `economy.base_price` (10000, shipped default) | Run `/bag` | A 6-row inventory opens titled `<player> 's Remote Bags` (gold), listing exactly one chest icon (item/slot-usage lore) for the owned page, plus a GREEN minecart purchase icon (the balance precondition guarantees the affordable branch of `RemoteBagMainGUI#createPurchaseIcon` — a balance below the price would instead show a RED barrier icon, not exercised by this row); the previous/next navigation buttons plus a red `Close` button appear on the bottom row; the configured `sound.open` sound plays | pixel | RemoteBagMainGUI |
| ultiremotebag.bag.content-gui | `language: en`; the sender owns at least one bag page (run `ultiremotebag.bag.open-page` above first, or purchase one via `ultiremotebag.bag.main-gui` above); `sound.enabled: true` (shipped default) | Run `/bag <page>` for an owned, currently-unlocked page | A 6-row inventory opens titled `Bag #<page>` (gold), with any previously placed items visible in slots 0-44; the bottom row (slots 45-53) shows, in order: a back button (yellow, `Back`), two background fillers, a background filler (read-only-only refresh slot, hidden in edit mode), a green save button (`Save`), a green mode indicator (`Edit Mode`), two background fillers, and a red close button (`Close`); the configured `sound.open` sound plays | pixel | RemoteBagContentGUI |
| ultiremotebag.bag.save | `language: en`; sender owns at least one bag page, NOT currently open in any GUI (close it first, or run this from a fresh login) | Run `/bag save` | Chat line reads `Bag saved manually!` (green, `bag_saved_manually`) — flushes the existing service cache; does NOT copy a currently-open content GUI's live inventory into that cache, `UltiKits/UltiRemoteBag#22` (see `.neg-open-gui-ignored` below) | server | |
| ultiremotebag.bag.save.neg-open-gui-ignored | `language: en`; sender owns at least one bag page, opened via `ultiremotebag.bag.open-page` above, with a NEW distinguishable item (e.g. a single `DIAMOND`) placed into slot 0, GUI still open, Save button NOT yet clicked | Run `/bag save` (from the chat, while the GUI remains open), then close the GUI WITHOUT clicking its own Save button (e.g. press Escape), then reopen the same page | The `DIAMOND` is GONE — `/bag save` persisted only what was already in the service cache before the item was placed; closing without the GUI's own save discards the placement entirely, despite the earlier `/bag save` reporting success. Known product defect, `UltiKits/UltiRemoteBag#22` | server | |
| ultiremotebag.bag.persistence | `language: en`; a distinguishable item (e.g. a single `DIAMOND`) placed into slot 0 of an owned bag page, then persisted via the content GUI's OWN Save button (NOT `/bag save` — see `ultiremotebag.bag.save`'s own defect note above) | Click the content GUI's Save button, then restart the server completely (not `/ul reload`), then log back in and run `/bag <page>` for the same page | The `DIAMOND` is present in slot 0, exactly as left before the restart — `RemoteBagContentGUI#saveCurrentContents` copied the GUI's live inventory into the service cache and `RemoteBagService#loadBagIfNeeded` re-queried `remote_bags`, deserializing the stored YAML back into the same slot | server | |
| ultiremotebag.bag.auto-save | none | Read `RemoteBagService.java`, `RemoteBagContentGUI.java` directly: every call site of `setBagPage` (`RemoteBagContentGUI#saveCurrentContents`, `RemoteBagService#createBagPage`, `RemoteBagService#clearBagPage`) is followed, in the SAME method, by a synchronous `saveBag` call — confirm by reading each of the three call sites in turn | `autoSaveTask`'s own body is correct (it would persist a dirty cache entry if one existed), but no reachable code path through this module's command/GUI surface ever leaves the cache dirty for it to catch — this is a static source confirmation (protocol layer), not a live 300-second wait, because no live setup can produce the dirty-but-unsaved precondition the task is meant to act on. Known product defect, `UltiKits/UltiRemoteBag#23` | protocol | |
| ultiremotebag.bag.cleanup-on-quit | `language: en`; the player has run `ultiremotebag.bag.open-page` above and is holding the OWNER lock on that page, GUI still open (no item placement needed — only the lock-release effect is independently testable, per `UltiKits/UltiRemoteBag#23`) | Quit the server (disconnect, not kick) while still holding the lock | `BagListener#onPlayerQuit` released every lock the quitting player held — confirmed by a second, OP'd admin running `ultiremotebag.admin.see` above against the same page afterward and receiving EDIT mode (not read-only/blocked) immediately, with no stale lock | server | |
| ultiremotebag.bag.lock-not-persisted | none | Read `BagLockService.java` directly: `private final Map<String, BagLockInfo> locks = new ConcurrentHashMap<>();` is a plain runtime field with no `@Table`, no `DataOperator`, and no file I/O anywhere in this class (grep the class for each of `@Table`, `DataOperator`, and `getOperator` — each returns 0 hits) | No persistence mechanism exists for `locks` at all — every held lock is silently discarded on any non-clean shutdown (crash, kill -9) and reconstructed empty on the next boot, unlike a clean quit, which explicitly releases the quitting player's locks via `ultiremotebag.bag.cleanup-on-quit` before the map would otherwise be discarded anyway | protocol | |

## Admin

| ID | Preconditions | Steps | Expected | Layer | Covers |
|---|---|---|---|---|---|
| ultiremotebag.admin.clear | `language: en`; admin holds `ultibag.use` AND `ultibag.admin.clear`; target player has an unlocked bag page containing at least one item | Run `/bag clear <target> <page>` | Chat line reads `Cleared bag #<page> of player <target>` (green, `admin_bag_cleared`); the target's bag page, when next opened, shows an empty content area (items removed, page itself still exists) | server | |
| ultiremotebag.admin.clear.neg-locked | `language: en`; admin holds `ultibag.use` AND `ultibag.admin.clear`; the target player currently holds the OWNER edit lock on the page (target has it open in edit mode) | Run `/bag clear <target> <page>` | Chat line reads `Bag is in use, cannot clear` (red, `bag_in_use_cannot_clear`) — `BagLockService#canUpgradeToEdit` refused because the owner's lock has not expired; the page's contents are UNCHANGED | server | |
| ultiremotebag.admin.create | `language: en`; admin holds `ultibag.use` AND `ultibag.admin.create`; target player has played before | Run `/bag create <target>` | Chat line reads `Created bag #<N> for player <target>` (green, `admin_bag_created`), where `<N>` is one past the target's highest existing page number; `/bag list <target>` immediately afterward shows the new page | server | |
| ultiremotebag.admin.create.neg-never-played | `language: en`; admin holds `ultibag.use` AND `ultibag.admin.create`; `<name>` has never played on this server | Run `/bag create <name>` | Chat line reads `Player not found: <name>` (red, `player_not_found`) | server | |
| ultiremotebag.admin.delete | `language: en`; admin holds `ultibag.use` AND `ultibag.admin.delete`; target player has an unlocked bag page that exists | Run `/bag delete <target> <page>` | Chat line reads `Deleted bag #<page> of player <target>` (green, `admin_bag_deleted`); `/bag list <target>` immediately afterward no longer lists that page, and its `remote_bags` row is gone | server | |
| ultiremotebag.admin.delete.neg-locked | `language: en`; admin holds `ultibag.use` AND `ultibag.admin.delete`; the target player currently holds the OWNER edit lock on the page | Run `/bag delete <target> <page>` | Chat line reads `Bag is in use, cannot delete` (red, `bag_in_use_cannot_delete`); the page still exists afterward | server | |
| ultiremotebag.admin.list | `language: en`; admin holds `ultibag.use` AND `ultibag.admin.list`; target player owns at least one bag page with a known item/slot count | Run `/bag list <target>` | Chat starts with `=== <target> 's Bag List ===` (gold), then one line per page reading `  #<N> - <itemCount> items, <stackCount> slots` (yellow `#N`, white separator), then ends with `Total <count> bags` (gold) | server | |
| ultiremotebag.admin.list.neg-no-bags | `language: en`; admin holds `ultibag.use` AND `ultibag.admin.list`; target player has played but every one of their bag pages has been deleted via `ultiremotebag.admin.delete` first | Run `/bag list <target>` | The `No bags`/`Total 0 bags` empty-state branch is UNREACHABLE — `RemoteBagService#getPlayerBagPages` fabricates a virtual page 1 whenever the cache holds no real pages, so the output instead shows exactly one page (`#1 - 0 items, 0 slots`) and `Total 1 bags`, never the empty-state text. Known product defect, `UltiKits/UltiRemoteBag#26` | server | |
| ultiremotebag.admin.see | `language: en`; admin holds `ultibag.use` AND `ultibag.admin.see`; target player owns at least one bag page, currently NOT locked by anyone | Run `/bag see <target>` | `RemoteBagContentGUI` opens for the target's lowest-numbered page in EDIT mode (the admin acquires an ADMIN lock, since none existed) — same layout as `ultiremotebag.bag.content-gui` | pixel | RemoteBagContentGUI |
| ultiremotebag.admin.see-page | `language: en`; admin holds `ultibag.use` AND `ultibag.admin.see`; target player owns the specific page requested | Run `/bag see <target> <page>` | `RemoteBagContentGUI` opens for that exact page (same lock-acquisition rule as `ultiremotebag.admin.see`) | pixel | RemoteBagContentGUI |
| ultiremotebag.admin.see-page.neg-owner-blocked-by-admin | `language: en`; admin holds `ultibag.use` AND `ultibag.admin.see`; a SECOND admin already holds the ADMIN edit lock on the target's page (that second admin ran `ultiremotebag.admin.see-page` first and left the GUI open) | As the FIRST admin, run `/bag see <target> <page>` | The message is the actual (Simplified Chinese) text `BagOpenResult.blocked` builds for `LockType.ADMIN` — in English, "This bag is being edited by admin `<name>`, please try again later"; no GUI opens. A known product defect: this refusal text ignores the server's `language` setting entirely, `UltiKits/UltiRemoteBag#20` | server | |
| ultiremotebag.admin.see.neg-never-played | `language: en`; admin holds `ultibag.use` AND `ultibag.admin.see`; `<name>` has never played on this server | Run `/bag see <name>` | Chat line reads `Player not found: <name>` (red, `player_not_found`) | server | |
| ultiremotebag.admin.see.neg-no-bags | `language: en`; admin holds `ultibag.use` AND `ultibag.admin.see`; target has played but every page deleted first via `ultiremotebag.admin.delete` | Run `/bag see <target>` | The `player_no_bags` refusal is UNREACHABLE for the same reason as `ultiremotebag.admin.list.neg-no-bags` — `RemoteBagContentGUI` instead opens for the fabricated virtual page 1 in EDIT mode. Known product defect, `UltiKits/UltiRemoteBag#26` | pixel | RemoteBagContentGUI |
| ultiremotebag.admin.see.neg-readonly-then-owner-blocked | `language: en`; admin holds `ultibag.use` AND `ultibag.admin.see`; the target player (owner) currently holds the OWNER lock on the page (the owner runs `ultiremotebag.bag.open-page` above and leaves the GUI open) | As the admin, run `/bag see <target>` while the owner still has the page open, then attempt to pick up or place an item in the resulting GUI's content area (slots 0-44) | `RemoteBagContentGUI` opens for the admin in READ-ONLY mode — the actual message shown to the admin is the (Simplified Chinese) text `BagOpenResult.readOnlyMode` builds, meaning "This bag is being used by `<owner>`, currently read-only"; a known product defect that this text ignores `language`, `UltiKits/UltiRemoteBag#20`. The item-move attempt is refused (event cancelled) with a red chat line, English text "Read-only mode, cannot move items"; the toolbar shows a disabled save button (red stained-glass, `Save (Disabled)`) whose click sends a red refusal ("Currently in read-only mode, cannot save changes") rather than saving anything. Separately, if the owner then closes their GUI and the admin clicks the refresh button, the admin's view upgrades to edit mode and a green chat line reads "Owner has exited, switching to edit mode..." | pixel | RemoteBagContentGUI |

## Configuration

One row per `@ConfigEntity` class (D-06's config-per-file rule), not per key: `RemoteBagConfig`
(`config/remotebag.yml`, 24 keys), matching `FEATURES.md`'s `## Configuration` section exactly.
This row confirms every key is present at its documented default, then flips one representative
interval-style key and observes the behaviour follow — **except the seven keys `FEATURES.md`
documents as having no observable effect** (`auto_save_interval`, `gui_title`,
`messages.no_permission`, `messages.page_locked`, `messages.bag_saved`, `save_on_close`,
`lock.notify_readonly_viewers`), which this row deliberately does NOT attempt to exercise for an
effect, per the same "do not test a key with no effect" convention the framework's own
`UAT-CHECKLIST.md` applies to `ultipanel.logging.batch.interval`.

| ID | Preconditions | Steps | Expected | Layer | Covers |
|---|---|---|---|---|---|
| ultiremotebag.config.remotebag-yml | Fresh `config/remotebag.yml` at its shipped default | Load the file; confirm all 24 keys listed under `FEATURES.md`'s `## Configuration` section are present at their documented defaults; then set `lock.timeout_seconds: 10` (the minimum allowed by its own `@Range(min=10, max=3600)` — a value below 10 is rejected by config validation, not merely slow) and restart the server (`BagLockService#setLockTimeout` is called only once, from `UltiRemoteBag#registerSelf` at boot — a `/uchat reload`-style config reload does NOT re-apply this value). After the restart, have the owner open a bag page (acquiring the edit lock) WITHOUT closing it, wait at least 11 seconds, then have an admin run `ultiremotebag.admin.see` against the same page. Do NOT vary `auto_save_interval`, `gui_title`, `messages.no_permission`, `messages.page_locked`, `messages.bag_saved`, `save_on_close`, or `lock.notify_readonly_viewers` expecting an observable effect — none of the seven has one (see each key's own `FEATURES.md` row and filed issue) | All 24 keys present at their documented defaults before the change; after lowering `lock.timeout_seconds` to 10 AND restarting, and waiting past it, the admin's `/bag see <target>` acquires the EDIT lock directly (owner's now-expired lock is discarded by `BagLockService#adminOpen`'s expiry check) rather than falling back to read-only mode, proving the lowered timeout took effect | server | |
