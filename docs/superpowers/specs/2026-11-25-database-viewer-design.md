# Database viewer: design

Approved mockup: https://claude.ai/artifact/S8GVopvgNtQhcX6ihZZcVT (version 3). Decisions from 2026-11-25.

## 1. What it is

A **Data** tab in the mirror window's quick actions sheet. The user picks an app, then one of its SQLite
databases. They can browse and edit rows, run any SQL, and read the schema. Edits are staged and written back
to the phone in one step. The view can also open in its own window, like Logcat.

Tab order becomes: **Actions, Apps, Files, Data, Shell, Links, Logcat**. The enum entry is `SheetTab.Database`;
its segment label is "Data".

## 2. Access

| Phone state | How the app's files are reached | Which apps |
|---|---|---|
| Not rooted | `run-as <pkg>` (cwd is the app's data dir) | debuggable apps only |
| Rooted (`su`) | `su -c '<cmd>'` in `/data/data/<pkg>` | every app |
| adb root (`id -u` prints `0`) | plain shell in `/data/data/<pkg>` | every app |

- **Debuggable:** read from `dumpsys package packages` (`pkgFlags=[ … DEBUGGABLE … ]`). It's part of the same
  grep that already reads versions.
- **Root detection:** once per device.
  - `id -u` printing `0` means adb root.
  - Otherwise `command -v su` printing a path means su is available. Detection never runs `su`, because
    running it makes Magisk show a prompt on the phone.
- **Asking for root:** happens on the first user action that needs it: opening a release app's databases, or
  clicking the root chip.
  - The command tried is `su -c 'id -u'`, then `su 0 sh -c 'id -u'`. The first one that prints `0` sets the su
    style.
  - While it runs, the chip reads "Asking for root…". If it fails, the chip shows "Root denied".
- **Root chip in the sheet header** (beside the "Quick actions" title):
  - Not rooted (outlined).
  - Root available (outlined; click to ask).
  - Asking for root…
  - Rooted (warning container).
  - adb root (warning container).
  - Root denied (outlined).
  - A tooltip explains each state.

## 3. Data model: pull, edit locally, write back

1. **List:** `ls -l databases` through the access wrapper. Keep regular files. Drop `-wal`, `-shm` and
   `-journal` companions, but remember which ones exist and their sizes.
2. **Pull:** stream `cat databases/<db>`, plus `-wal` and `-shm` if present, through `openExecStream` into
   `<temp>/<serial>/<pkg>/pulled/`.
   - Record each file's `stat -c %s:%Y` as the pull fingerprint.
   - Open the pulled copy with sqlite-jdbc and run `PRAGMA wal_checkpoint(TRUNCATE)`.
   - Copy the result to `work.db` and `pristine.db`.
3. **Edit:** every staged change runs on `work.db` right away. Each statement is logged in order, and that
   log is what Review SQL shows. Staged count = log size. Discard = copy `pristine.db` over `work.db` and clear
   the log.
4. **Write to device:**
   1. `pidof <pkg>`.
   2. If the app is running, the dialog says it will be force-stopped and offers "Open again afterwards"
      (checked). If it isn't running, the dialog offers a plain "Write".
   3. On confirm: `am force-stop` (only if running).
   4. Fingerprint check. If the device files changed since the pull, a second dialog: "<App> changed this
      database since it was pulled. Writing replaces those changes." with Pull again / Write anyway.
   5. Checkpoint `work.db` and close it.
   6. `adb push` to `/data/local/tmp/scrcpyvwr-<random>.db`.
   7. `cat /data/local/tmp/… > databases/<db>` through the wrapper. `cat >` keeps the target's owner and
      SELinux label.
   8. `rm -f databases/<db>-wal databases/<db>-shm`, then remove the temp file.
   9. Relaunch the app if asked.
   10. `work.db` becomes the new `pristine.db`, and the log clears.
5. **Refresh:** pulls again. It's blocked with a toast while changes are staged.

Local SQLite: `org.xerial:sqlite-jdbc`. The packaged runtime adds the `java.sql` module.

## 4. UI

- **Data tab:**
  - **Top bar:** app picker, access chip (`run-as` / `root · su` / `adb root`), database picker, sync status
    (Pulling via run-as… / Pulled HH:mm / Writing… / Written HH:mm), refresh, and "Open in its own window".
  - **Body:** `AdaptiveListDetail`, with tables on the left and the detail on the right. The detail has
    sub-tabs Browse / Query / Structure.
  - **Browse:** a WHERE field, + Row, and a grid with sticky headers.
    - Double-click a cell to edit it. Enter saves, Esc cancels.
    - Right-click a row to toggle delete.
    - Edited cells tint primaryContainer.
  - **Query:** SQL editor with highlighting, ⌘↵ / Ctrl+↵ runs, sample chips, and a results grid or a
    "N rows changed, staged" message.
  - **Structure:** `CREATE` statement, indexes, file path, journal mode, and how it was opened.
  - **Staged bar:** springs up from the bottom with "N changes staged in <db>", Review SQL, Discard and Write to
    device.
  - **Locked state:** for a release app on a phone without root. Explains why and offers "Show debuggable apps",
    which opens the Apps tab with the Debuggable filter.
- **Apps tab:**
  - **Filter:** a fourth chip, `AppFilter.Debuggable`, with its count.
  - **Card badge:** a DEBUG badge (tertiary container) under SYS.
  - **Options sheet:**
    - A third InfoBox, "Data access": "Debuggable · run-as", "Through root (su)" or "Release build · locked".
      The value scrolls with `Modifier.basicMarquee()` when it doesn't fit.
    - The "Show in Logcat" button is joined by a "Databases" button: two tonal buttons in a row, stacked when
      compact.
    - "Databases" is disabled for locked apps, with the reason under it. It opens the Data tab, or raises the
      window, with that app picked.
- **Own window:** `DatabaseWindow`, 900×640, min size 360×360.
  - While it's popped out, the Data tab shows "Databases is open in its own window" with Show window / Move
    back here.
  - The Data segment shows the popped-out marker, like Logcat's.

## 5. Motion

Every state change animates, per the repo's motion rules:
- Tab and sub-tab content slides on its axis.
- The table selection uses `SelectionHighlight`.
- List-detail pushes and pops.
- Grid rows rise in.
- Edited cells fade their tint.
- The staged bar enters and leaves on a spring.
- The sync and root chip text uses an AnimatedContent swap.
- The options sheet's Data access value cross-fades when root changes.
- Filter counts roll.
- The pop-out window fades like Logcat's.
