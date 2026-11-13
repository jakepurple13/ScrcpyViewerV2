# Multi-device Input Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let one mirror window send its taps, swipes, scrolls, typing, nav buttons and quick actions to other open mirror windows, mapped by percent of screen, with animated leader/follower visuals.

**Architecture:** `InputFollowing` (Koin singleton) holds who leads and who follows. `FollowSender` remaps and fans out the leader's `ControlMessage`s to follower sessions looked up by serial from `MirrorController.open`. `MirrorKeys` gains a follower output that always produces plain keycode/text messages. `QuickActions` runs the substituted command on follower `ShellRunner`s. `MirrorWindow` wires it together and draws the leader button, follower chip, border glow and click rings.

**Tech Stack:** Kotlin 2.3 JVM, Compose Multiplatform 1.12 Material 3, kotlinx.coroutines, Koin annotations, kotlin.test, the repo's screenshot test support.

**Spec:** `docs/superpowers/specs/2026-11-13-multi-device-input-design.md`

## Global Constraints

- Followed: InjectTouch, InjectScroll (remapped), InjectKeycode, InjectText, SetClipboard, RotateDevice, nav keys, quick actions. Never forwarded: UhidInput/UhidCreate/UhidDestroy, file drops, APK installs, screenshots, recordings, Shell/Apps/Files tabs.
- Remap: fraction = leader frame px / leader frame size; follower Position = fraction × follower's current frame size (`frames.value?.size ?: frameSize.value`). No frame → skip that follower for that message.
- Sessions are looked up by serial at send time from `MirrorController.open.value`; never cached (reconnect swaps sessions).
- "Layout differs" when aspect ratios differ by more than 5%.
- Copy (exact): "Send input to…", "Sending to N", "SEND INPUT TO", "All mirror windows", "Open another mirror window to send input to it", "Following <device title>", "Layout differs", "Mouse capture is on, so taps aren't sent to other windows", "Ran on N devices", "Ran on K of N devices · <device> failed: <reason>".
- Motion (spec table): label slide-up 200 ms; follow start border 300 ms + one glow pulse 600 ms `EmphasizedDecelerate`; chip expand+fade 250 ms in, 200 ms out; reduced motion (`systemReducesMotion()`) → instant or 150 ms fade; size changes use `FastOutSlowInEasing`. Nothing snaps.
- Keep `MirrorWindow.kt` edits small; new logic under `ui/mirror/follow/`.
- Tests: `./gradlew :composeApp:jvmTest --tests '<pattern>'`; screenshots add `-Pscreenshots`. One Gradle build at a time; if the Kotlin daemon OOMs add `-Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.jvmargs=-Xmx4g`.

## Review Focus

1. Leader closes mid-drag: followers must get ACTION_UP, nothing left pressed. Test in Task 2 (`release`) and Task 4 wiring.
2. A follower reconnects (new session object, same serial): following continues and messages reach the new session. Test in Task 1 and Task 2.
3. Follower rotated or different aspect: taps land at the same percentage of its frame. Test in Task 2.
4. Leader in `keyboard=uhid`: followers still get typing as keycode/text, and never receive UHID reports. Test in Task 3.
5. A quick action fails on one follower: the leader and other followers still run; the result line names the failure. Test in Task 5.

---

### Task 1: Follow state

**Files:**
- Create: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/ui/mirror/follow/InputFollowing.kt`
- Modify: DI (`di/AppModule.kt`) to provide it as a singleton on the app scope, next to `MirrorController`
- Test: `composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/ui/mirror/follow/InputFollowingTest.kt`

**Interfaces:**
- Produces: `data class FollowState(val leader: String? = null, val followers: Set<String> = emptySet())`; `class InputFollowing(open: StateFlow<Map<String, ScrcpySession>>, scope: CoroutineScope)` with `val state: StateFlow<FollowState>`, `fun setFollowers(leader: String, followers: Set<String>)`, `fun stop()`, `fun roleOf(serial: String): FollowRole` where `sealed interface FollowRole { None; Leader(followers: Set<String>); Follower(leader: String) }`.

- [ ] **Step 1: Failing tests** (fake `open` MutableStateFlow; Unconfined or test dispatcher):
  - `setFollowers makes a leader` → state(leader "A", {"B","C"}); roleOf("B") == Follower("A").
  - `empty followers stops` → state == FollowState().
  - `new leader replaces old link` → A leads {B}; then C sets {A} → state(C, {A}).
  - `leader is never its own follower` → setFollowers("A", {"A","B"}) → followers {B}.
  - `closing a follower drops it` → remove B from open → followers {C}; removing the last follower → FollowState().
  - `closing the leader resets` → remove A → FollowState().
  - `reconnect keeps following` → replace B's session object under the same key → state unchanged.
  - `followers must be open` → setFollowers("A", {"Z"}) where Z isn't open → FollowState().
- [ ] **Step 2: Run** `./gradlew :composeApp:jvmTest --tests '*InputFollowingTest*'` → FAIL.
- [ ] **Step 3: Implement.** Prune on every `open` emission.
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(follow): input follow state`.

### Task 2: Fan-out sender

**Files:**
- Create: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/ui/mirror/follow/FollowSender.kt`
- Test: `composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/ui/mirror/follow/FollowSenderTest.kt`

**Interfaces:**
- Consumes: `ControlMessage`, `Position` (scrcpy/ControlMessage.kt).
- Produces: `data class FollowerFlags(val control: Boolean = true, val keyboard: Boolean = true, val mouse: Boolean = true)`; `class FollowSender(private val target: (serial: String) -> FollowTarget?)` where `class FollowTarget(val frameSize: Size?, val flags: FollowerFlags, val send: (ControlMessage) -> Unit)`; `fun send(followers: Set<String>, messages: List<ControlMessage>)`; `fun release(followers: Set<String>)` (sends ACTION_UP at the last forwarded position to any follower with a press in progress); `fun remap(position: Position, to: Size): Position`; `fun layoutDiffers(a: Size, b: Size): Boolean` (aspect differs by > 5%).

- [ ] **Step 1: Failing tests:**
  - `touch remaps by fraction` → leader Position(540, 1170, 1080, 2340) to follower 1600×2560 → Position(800, 1280, 1600, 2560).
  - `scroll remaps by fraction` → same mapping for InjectScroll; hscroll/vscroll unchanged.
  - `follower without frame is skipped`.
  - `key text clipboard rotate go verbatim` → InjectKeycode, InjectText, SetClipboard, RotateDevice equal to input.
  - `uhid is never forwarded` → UhidInput/UhidCreate/UhidDestroy produce no sends.
  - `disabled flags filter` → control=false sends nothing; keyboard=false drops keycode/text/clipboard; mouse=false drops touch/scroll.
  - `release after down sends up` → DOWN then `release` → follower gets ACTION_UP at the last remapped position; after an UP, `release` sends nothing.
  - `target looked up per call` → swap the target's `send` between calls (reconnect) → second message reaches the new one.
  - `layoutDiffers` → 1080×2340 vs 1080×2424 false; vs 1600×2560 true.
- [ ] **Step 2: Run** `./gradlew :composeApp:jvmTest --tests '*FollowSenderTest*'` → FAIL.
- [ ] **Step 3: Implement.** Track per-follower pressed state from forwarded DOWN/UP.
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(follow): fan out leader input by screen percentage`.

### Task 3: Keyboard output for followers

**Files:**
- Modify: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/ui/mirror/MirrorKeys.kt`
- Test: `composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/ui/mirror/MirrorKeysTest.kt`

**Interfaces:**
- Produces: `MirrorKeys` constructor param `followerSend: (List<ControlMessage>) -> Unit = {}`; it receives, for each key event the window handles as device input, plain `InjectKeycode`/`InjectText` from a follower-only key path (Mixed inject mode), whatever the leader's keyboard mode; paste sends the same `SetClipboard`/`InjectText` to it; focus-loss releases go to it too. Zoom keys, capture keys and sheet-focused keys never reach it.

- [ ] **Step 1: Failing tests** (extend `MirrorKeysTest` with a second `followerSent` list): Mixed mode letter → follower gets the same keycode/text as leader; UHID mode letter → leader gets UhidInput, follower gets InjectKeycode/InjectText and no UhidInput; paste → follower gets SetClipboard; zoom key → follower gets nothing; focus loss after a held key → follower gets the key UP.
- [ ] **Step 2: Run** `./gradlew :composeApp:jvmTest --tests '*MirrorKeysTest*'` → FAIL.
- [ ] **Step 3: Implement** with a second `InputMapper` key path (keys only) owned by `MirrorKeys`.
- [ ] **Step 4: Run** → PASS (existing MirrorKeysTest cases unchanged).
- [ ] **Step 5: Commit** `feat(follow): plain key output for following windows`.

### Task 4: Mirror window wiring and visuals

**Files:**
- Create: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/ui/mirror/follow/FollowControls.kt` (leader button + menu, follower chip, border glow modifier, motion specs)
- Modify: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/ui/mirror/MirrorWindow.kt` (send tee, MirrorKeys followerSend, release on stop/close/focus loss, TopStrip button + overflow entry, follower chip, border, click ring for followed taps)
- Modify: `Main.kt` / wherever `MirrorWindow` is called, to pass `InputFollowing`
- Test: `composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/ui/mirror/follow/FollowControlsTest.kt`; extend `ui/MirrorScreenshotTest.kt`

**Interfaces:**
- Consumes: Task 1 `InputFollowing`/`FollowRole`, Task 2 `FollowSender`/`FollowTarget`/`FollowerFlags`/`layoutDiffers`, Task 3 `followerSend`.
- Produces: `@Composable fun SendInputButton(role: FollowRole, others: List<FollowCandidate>, captureOn: Boolean, onChange: (Set<String>) -> Unit, compact: Boolean)`, `data class FollowCandidate(val serial: String, val title: String)`, `@Composable fun FollowingChip(leaderTitle: String, layoutDiffers: Boolean)`, `fun Modifier.followGlow(active: Boolean, reduceMotion: Boolean): Modifier`, a `followerRings: SharedFlow<Pair<String, Offset>>`-style hook (or equivalent) so a follower window can show the click ring at a followed tap's frame position.

- [ ] **Step 1: Failing tests** in `FollowControlsTest` (Compose test harness like `ui/devices/info/DetailTabMotionTest.kt`): button label "Send input to…" idle and "Sending to 2" leading; menu lists other windows and "All mirror windows" checks all; single-window menu shows the disabled hint; capture note shows when `captureOn`; chip shows "Following Galaxy A15 5G" and "Layout differs" only when flagged; reduced motion → no pulse.
- [ ] **Step 2: Run** `./gradlew :composeApp:jvmTest --tests '*FollowControlsTest*'` → FAIL.
- [ ] **Step 3: Implement** `FollowControls.kt`, then wire `MirrorWindow.kt`: after the leader's own send, `followSender.send(followers, msgs)` when this window leads (pointer path only when not in UHID mouse capture); `followerSend` from `MirrorKeys` to the sender; `release` when following stops, the leader window closes or loses focus; button in `TopStrip` next to the copy button and an always-visible overflow entry when narrow; follower windows show the chip, `followGlow` border and click rings for followed taps.
- [ ] **Step 4: Run** the focused tests, then `./gradlew :composeApp:jvmTest --tests '*Mirror*'` and `./gradlew :composeApp:jvmTest -Pscreenshots --tests '*MirrorScreenshotTest*'` after adding captures `mirror follow leader idle`, `mirror follow leader sending`, `mirror follow follower`, `mirror follow follower layout differs`. Open the PNGs and check them.
- [ ] **Step 5: Commit** `feat(follow): send input to other mirror windows`.

### Task 5: Quick actions on followers

**Files:**
- Modify: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/ui/mirror/QuickActions.kt` (split `execute` so the substituted command runs on extra runners; toggles set followers to the leader's resulting state)
- Modify: `MirrorWindow.kt` only to pass follower runners/titles into `rememberQuickActions`
- Test: extend `composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/ui/mirror/QuickActionsCustomTest.kt` and `QuickActionsTest.kt`

**Interfaces:**
- Consumes: Task 1 state (follower serials), per-serial `ShellRunner(adb, serial)` and device titles.
- Produces: `QuickActions` gains `followers: () -> List<FollowRunner>` with `data class FollowRunner(val title: String, val runner: ShellRunner)`; result text helper `fun followResult(total: Int, failures: List<Pair<String, String>>): String`.

- [ ] **Step 1: Failing tests:** custom action with a prompt → after answering, command with the same values runs on leader and on 2 follower fake runners; one follower throws `IOException("device offline")` → leader and other follower still run, result "Ran on 2 of 3 devices · Pixel 9 failed: device offline"; all ok → "Ran on 3 devices"; built-in toggle switched on by the leader → followers get the "on" command even if their own state reads "on" already; no followers → unchanged behavior and no result suffix.
- [ ] **Step 2: Run** `./gradlew :composeApp:jvmTest --tests '*QuickActions*'` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(follow): run quick actions on following windows`.
