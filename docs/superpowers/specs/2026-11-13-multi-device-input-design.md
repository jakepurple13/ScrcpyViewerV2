# Multi-device input

Date: 2026-11-13
Status: approved (mockup https://claude.ai/artifact/AEV6zuT1JVS9XRxa4GRaQt, "6 Multi-device input" tab)

## Goal

One mirror window leads. What the user does in it (taps, swipes, scrolls, typing, the nav buttons and quick actions)
also goes to the other mirror windows they pick. Taps land at the same spot as a percentage of each device's screen.
Input in a following window still goes only to that window's own device. Every state change animates; nothing snaps.

## Decisions already made with the user

- Positions map by percentage of the screen. When a follower's screen shape differs from the leader's, it shows
  "Layout differs" because taps may hit something else.
- Followed: taps, swipes, scrolls, typing (including paste), Back/Home/Recents/volume/rotate/power, quick actions.
  Not followed: file drops, APK installs, screenshots, recordings, the Shell, Apps and Files tabs.
- Any window can lead. Picking a new leader moves the link to it. Closing the leader stops following.
- No default keyboard shortcut. (A shortcut comes later with the Keyboard shortcuts settings page.)
- Nothing is saved across restarts.

## State (`ui/mirror/follow/InputFollowing.kt`)

A Koin singleton next to `MirrorController`:

```kotlin
data class FollowState(val leader: String? = null, val followers: Set<String> = emptySet())

class InputFollowing(controller: MirrorController, scope: CoroutineScope) {
    val state: StateFlow<FollowState>
    fun setFollowers(leader: String, followers: Set<String>)  // empty set = stop
    fun stop()
}
```

- Setting followers for a window that isn't the current leader makes it the leader and replaces the old link.
- A follower can't also be the leader. A serial whose session closes (it leaves `MirrorController.open`) is dropped
  from the followers. If the leader closes, the state resets to empty.
- A reconnect keeps the serial, so following survives it. Sessions are always looked up by serial at send time from
  `controller.open.value`, never cached.

## Sending (`ui/mirror/follow/FollowSender.kt`)

Pure fan-out logic, unit tested without Compose:

```kotlin
class FollowSender(
    sessions: () -> Map<String, ScrcpySession>,
    frameSize: (ScrcpySession) -> Size?,   // frames.value?.size ?: frameSize.value, like MirrorWindow does
    followerFlags: (String) -> FollowerFlags, // control / keyboard / mouse enabled for that window's options
)
fun FollowSender.send(followers: Set<String>, messages: List<ControlMessage>)
```

- Called from the leader window's `send` (MirrorWindow.kt, the `msgs.forEach(session::send)` lambda) after the
  leader's own send, only while that window is the leader and has followers.
- `InjectTouch` and `InjectScroll` carry a `Position` in the leader's frame pixels. Convert to a fraction of the
  leader's frame (`x / screenWidth`, `y / screenHeight`), then rebuild a `Position` with each follower's current frame
  size. A follower with no frame yet is skipped for that message.
- Messages without a position (`InjectKeycode`, `InjectText`, `SetClipboard`, `RotateDevice`, the nav keys) go
  verbatim.
- `UhidInput` / `UhidCreate` / `UhidDestroy` are never forwarded (they belong to the leader's own HID devices).
  Keyboard input for followers comes from the key path below instead.
- A follower whose options disable control, keyboard or mouse doesn't get those kinds of messages.
- `session.send` is a non-blocking `trySend`, so fan-out runs on the UI thread like today.

### Keyboard

`MirrorKeys` gets a second output, `followerSend: (List<ControlMessage>) -> Unit`, fed with plain
`InjectKeycode`/`InjectText` messages built from the same AWT key event with a follower-only `InputMapper` key path,
whatever the leader's own keyboard mode is (`keyboard=uhid` included). Paste sends the same `SetClipboard` to
followers. When the leader loses focus, followers get the same releases (`releaseAll`) the leader does.

### Pointer edge cases

- When the leader uses the UHID mouse (pointer capture), pointer input isn't followed. The leader's button shows the
  note "Mouse capture is on, so taps aren't sent to other windows".
- A press that started while following and is still down when following stops gets an `ACTION_UP` sent to those
  followers, so no device is left with a finger down.

### Quick actions

- The run path in `QuickActions` (`startRun` / `execute`) splits so that, after the leader's prompts and confirm
  dialog are answered, the substituted command runs on the leader and then on each follower's own `ShellRunner`.
  Followers never show prompts.
- Built-in toggles (`QuickToggle`) set each follower to the leader's resulting state instead of flipping it.
- The result line reads "Ran on 3 devices" or "Ran on 2 of 3 devices · Pixel 9 failed: <reason>".

## UI

### Leader button (title bar)

`TopStrip` (MirrorWindow.kt) gets a tonal button next to the copy button: `↻ Send input to…`, which becomes
`Sending to 2` while leading. When the strip is narrow it moves into the `⋮` overflow menu like the other items
(`OverflowMenu` gains an always-visible entry for it).

It opens a menu:

```
SEND INPUT TO
[ ] Pixel 9 · emulator
[ ] Pixel Tablet · emulator
───────────────
[ ] All mirror windows
```

- One row per other open mirror window (device title from `deviceTitle`), "All mirror windows" toggles every row.
- With only one window open, the menu has a single disabled line: "Open another mirror window to send input to it".
- The capture note above appears at the bottom when it applies.

### Follower window

- Title bar chip `Following Galaxy A15 5G`, plus `Layout differs` (warning colors) when the follower's aspect ratio
  differs from the leader's by more than 5%.
- The window border takes the accent color with a 3 dp glow while following.
- A tap that arrives from the leader shows the existing click ring (the show-touches ring the stage already draws) at
  the mapped point, so the user sees where it landed.

### Motion

| Change | Motion |
|---|---|
| Button label "Send input to…" ↔ "Sending to N" | `AnimatedContent` slide-up 200 ms, count ticks like other numbers |
| Menu open/close | the app's standard `DropdownMenu` motion |
| Window starts following | border color animates over 300 ms and one outward pulse of the glow (600 ms, `EmphasizedDecelerate`); chip enters with expand + fade 250 ms |
| Window stops following | border and glow fade 200 ms; chip shrinks + fades 200 ms |
| "Layout differs" appears/disappears | expand/shrink + fade 200 ms beside the chip |
| Followed tap | the existing click ring at the mapped point |
| Reduced motion | instant swaps or 150 ms fades, no pulse |

Size changes use `FastOutSlowInEasing` (EmphasizedDecelerate on size looks like a jump).

## Tests

- `InputFollowingTest`: set/replace leader, follower can't lead, closing a follower drops it, closing the leader
  resets, reconnect keeps following.
- `FollowSenderTest`: touch and scroll remapped by fraction (portrait leader → tablet follower, rotated follower),
  missing frame skipped, verbatim key/text/clipboard/rotate, UHID never forwarded, disabled control/keyboard/mouse
  respected, stuck press released on stop.
- `MirrorKeysTest`: follower output gets keycode/text in Mixed mode and in UHID mode; focus loss releases followers.
- Quick actions: command runs on each follower runner with the leader's values; toggles set, not flip; result line
  copy for all-ok and partial failure.
- Screenshot tests: leader title bar idle / sending, follower chip with and without "Layout differs".
- Manual: two devices (the Galaxy A15 plus a second phone or an emulator), tap, swipe, type, Back, a quick action.

## Out of scope

Element-based mapping (matching UI elements across layouts), multi-touch gestures, following file drops, a saved
link, a keyboard shortcut (comes with the shortcuts page).

## Risks

- Mirror window code is large (`MirrorWindow.kt`). Keep its edits to the title bar button, the `send` tee and the
  follower visuals; logic goes in new files under `ui/mirror/follow/`.
- Real-device check needs two devices at once (`cdm acquire --android 2`).
