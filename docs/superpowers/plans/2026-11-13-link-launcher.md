# Link Launcher Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Links tab in the mirror sheet that opens links/intents on the device, shows the `am start -W` result, keeps per-device history with global stars, and can save a link as a quick action.

**Architecture:** Pure `links/` model (kind detection, command builder, result parser), `LinkStore` on `JsonFile`, a `LinksState` holder owned by `QuickActions`, and `LinksTab` composables; the tab bar gains an icon-only mode for six tabs.

**Tech Stack:** Kotlin 2.3 JVM, Compose Multiplatform 1.12 Material 3, Koin, kotlinx.serialization, kotlin.test.

**Spec:** `docs/superpowers/specs/2026-11-13-link-launcher-design.md`

## Global Constraints

- Tab order Actions, Apps, Shell, Files, Links, Logcat; `Settings.linksWidth = 400`.
- Icon-only tab bar when a segment < 56 dp; tooltips; crossfade 200 ms.
- Copy (exact): "Links", "Link or intent", "Web link", "<scheme>: link", "Intent URI", "Not a link", "Open with", "Let Android choose", "Before opening", "Nothing", "Force-stop the app (cold start)", "Clear the task (--activity-clear-task)", "Intent details", "Action", "Category", "Extra", "Open on device", "Opening…", "Save as quick action", "Opened in <label> · <cold|warm> start <n> ms", "No app on this device opens this link", " with <app>", "History", "Clear", "Open again", "No app found", "Open <host or scheme>".
- History cap 50 per device; stars global; dedupe link+package.
- Motion table in the spec is binding; nothing snaps; size tweens FastOutSlowIn; reduced motion via `systemReducesMotion()`.
- Tests `./gradlew :composeApp:jvmTest --tests '<pattern>'` (+ `-Pscreenshots`). One Gradle build at a time; if the Kotlin daemon OOMs add `-Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.jvmargs=-Xmx4g`.

## Review Focus

1. Shell injection: links with quotes, spaces, `$`, `;` must be single-quoted safely in both the run and the preview. Task 1.
2. A slow or hung `am start` must not freeze the tab; button returns after the 30 s timeout with an error result. Task 3.
3. Six tabs at the narrowest sheet width: no clipped labels or overlapping badges. Task 4.
4. Switching devices/windows: history shown is that device's; stars appear in every window. Task 2/3.
5. Corrupt links.json: app still opens, error is shown once, file backed up. Task 2.

---

### Task 1: Link model

**Files:** Create `links/LinkKind.kt`, `links/LinkCommand.kt`, `links/LaunchResult.kt`; Tests `links/LinkModelTest.kt`.
**Produces:** `enum LinkKind { Web, Scheme, Intent, NotALink }` + `fun linkKind(text): Pair<LinkKind, String>` (label); `data class LinkRequest(link, packageName?, before: Before { None, ForceStop, ClearTask }, action?, category?, extra: Extra?)`; `fun deviceCommand(r): String` (no adb prefix) and `fun previewCommand(serial, r): String`; `data class LaunchResult(ok, packageName?, launchState?, totalMs?, errorLine?)` + `fun parseAmStart(output): LaunchResult`.
- [ ] Tests: each kind; command with/without package, category, extra types, clear task, force-stop prefix only with a package, intent URI without -a/-c; quoting of `'`, spaces, `$`, `;`, `&`; parser on ok COLD/WARM/HOT output, the "unable to resolve Intent" error, empty output.
- [ ] Implement; run; commit `feat(links): link kind, command and result parsing`.

### Task 2: LinkStore

**Files:** Create `links/LinkStore.kt`; DI; Tests `links/LinkStoreTest.kt`.
**Produces:** `@Serializable LinkEntry(link, packageName?, appLabel?, ok, detail, at)`; `class LinkStore(file, scope)` with `state: StateFlow<LinksData(history, starred)>`, `record(serial, entry)`, `star(entry, on)`, `clear(serial)`, `error: StateFlow<String?>`.
- [ ] Tests: record per device newest first; dedupe moves to top; cap 50; star global and survives clear; corrupt file → empty + backup + error; persists across instances.
- [ ] Implement; run; commit `feat(links): link history store`.

### Task 3: Links state and run

**Files:** Create `ui/mirror/links/LinksState.kt`; Modify `ui/mirror/QuickActions.kt` (SheetTab.Links, events, wiring runner/apps/store), `state/AppSettings.kt` (`linksWidth`), `ui/mirror/SheetWidths.kt`; Tests `ui/mirror/links/LinksStateTest.kt`, AppSettingsTest, SheetWidthTest.
**Produces:** `class LinksState(serial, runner, store, apps, scope)` with fields (text, packageName, before, detailsOpen, action, category, extra), derived `kind`, `preview`, `running`, `result: LaunchResult?`, `history` (this device + starred), and `open()`, `reopen(entry)`, `fill(entry)`, `toggleStar(entry)`, `clear()`; `QuickActionEvent.Links(...)` variants; Save-as-quick-action event that emits an `ActionsRequest` with a template (add the request variant/plumbing in ActionsNavigation and suppress the template banner for it).
- [ ] Tests: open success records ok entry with label; failure records not-ok; running flag and 30 s timeout → error result; star/unstar; clear keeps stars; another serial sees its own history but shared stars; save request carries the command.
- [ ] Implement; run `--tests '*Links*' --tests '*QuickActions*' --tests '*SheetWidth*' --tests '*AppSettingsTest*'`; commit `feat(links): links state, running and saving`.

### Task 4: Links tab UI and six-tab bar

**Files:** Create `ui/mirror/links/LinksTab.kt`; Modify `QuickActionsSheet.kt` (SheetContent case, subtitle, `SheetTabs` icon mode); Tests `ui/mirror/links/LinksTabTest.kt`, `SheetTabsTest` (six tabs, icon mode under 56 dp), `MirrorScreenshotTest` captures (Links idle, success, failure, details open; light/dark; narrow sheet tab bar).
- [ ] Implement per the spec layout and motion table; tests for each motion row (no single-frame jumps), Esc handling in fields, `reportsSheetFocus()` on dropdowns.
- [ ] Run tests + screenshots (open each PNG); commit `feat(links): links tab`.
