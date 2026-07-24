# VNDS Android E-ink

An Android visual novel reader purpose-built for e-ink devices, reading the classic **VNDS**
(`.scr`) script format used by the original Nintendo DS "VNDS" engine, as well as (with more
limited support, see [NScripter/ONScripter support](#nscripteronscripter-support)) the
**NScripter/ONScripter** script format. Rather than embedding either of those engines, this app
reimplements small interpreters for them from scratch, and every part of the UI is designed around
e-ink's constraints: no animations by default, instant image/text swaps, a forced light theme, and
grayscale-friendly rendering.

> Built for readers who want their visual novel library on a Boox/Onyx-style e-ink tablet or phone,
> not a backlit screen.

## Screenshots

<table>
<tr>
<td><img src="screenshots/library.png" width="360" alt="Library screen with an imported novel"></td>
<td><img src="screenshots/reader.png" width="360" alt="Reader screen showing background art, a character sprite, and dialogue text"></td>
</tr>
<tr>
<td align="center">Library, with a story pack imported</td>
<td align="center">Reader mid-scene: background, character sprite, and dialogue</td>
</tr>
</table>

*(Story pack shown: [True Remembrance](https://en.wikipedia.org/wiki/True_Remembrance), a freeware
visual novel by Satomi Shiba, English translation by Insani — used here only to demonstrate the
reader; not bundled with this repo.)*

## Contents

- [Screenshots](#screenshots)
- [Features](#features)
- [Requirements](#requirements)
- [Building](#building)
- [Using the app](#using-the-app)
- [Supported VNDS script commands](#supported-vnds-script-commands)
- [NScripter/ONScripter support](#nscripteronscripter-support)
- [Completion guides](#completion-guides)
- [Project structure](#project-structure)
- [Contributing](#contributing)
- [License](#license)
- [Acknowledgments](#acknowledgments)

## Features

**E-ink first**
- No animations, fades, or transitions anywhere in the UI by default (toggleable) — every screen
  swap, image change, and text reveal is instant.
- Forced light theme and an optional grayscale color filter, regardless of system dark mode.
- Full-screen refresh (GC16-style flash) support on Onyx hardware to clear ghosting, both manual
  and automatic on scene changes.

**Library**
- Local-only library screen: nothing is scanned or re-imported automatically, everything is an
  explicit user action.
- Import a story pack three ways: a folder that may contain several packs, a folder that *is* one
  pack, or a single zip/7z archive of one pack.
- Auto-detects VNDS and NScripter/ONScripter packs alike at import time — no separate mode to pick;
  see [NScripter/ONScripter support](#nscripteronscripter-support) for how complete that support is.
- Optional VNDB linking per novel (manually entered VNDB id) for cover info, rating, and length,
  fetched from the public VNDB API.
- Export/import a novel's save data as a portable JSON file.

**Reader**
- Manual typewriter text reveal with a configurable speed (characters/second), skipped entirely in
  e-ink mode.
- Auto-advance with configurable reading speed (words/minute) and per-page pause.
- **Advance to next choice**: fast-forwards through the script without pausing for taps or scripted
  delays, without playing intermediate sound effects or music cues — only the last active music
  track (if any) actually plays once it stops.
- 24 manual save slots plus an always-current auto-managed "Resume" slot, presented as a paginated,
  e-ink-friendly grid.
- A **Variables** inspector: view and directly edit every current `setvar` (per-save) and `gsetvar`
  (global) script variable live, mid-story.
- A full text-log backlog, paginated by whole lines rather than raw scroll.
- Gamepad-friendly: volume keys and a paired controller's shoulder/select buttons advance text.

**Completion guides**

A structured, checkable walkthrough/completion tracker — routes, the choices that lead down them,
and the endings they lead to — importable as a JSON file per novel (see
[Completion guides](#completion-guides) below). Presented as a collapsible tree of checkboxes; your
progress is tracked independently of any save file, and the guide remembers which sections you had
expanded and your scroll position between visits.

Guides can also exist **standalone**, on their own dedicated "Guides" page (switchable to from the
library's ⋮ menu) — for reference material about a VN you haven't imported into this app. Add an
entry either by linking a VNDB id (for cover-style metadata) or by just typing a plain name, no
VNDB lookup required.

## Requirements

- Android 7.0 (API 24) or newer.
- Onyx/Boox e-ink hardware is fully supported but not required — everything works (with real
  animations, if you want them) on any Android device.

## Building

Standard Gradle project, single module (`app`).

```bash
# Debug APK
./gradlew assembleDebug

# Install on a connected device/emulator
./gradlew installDebug

# Unit tests
./gradlew testDebugUnitTest

# Instrumented tests (needs a device/emulator)
./gradlew connectedDebugAndroidTest

# Lint
./gradlew lint
```

`compileSdk`/`targetSdk` 37, `minSdk` 24, Java 11 source/target compatibility.

## Using the app

1. From the library screen, tap **+ Import novel** and choose how the pack is packaged (a tree that
   may hold multiple novels, a folder that *is* one novel, or a zip/7z archive).
2. Tap a novel to start reading. If it has any save data, you'll be asked whether to start fresh,
   resume, or load a specific save.
3. The reader's hamburger menu (⋮) has Save, Load, Advance to next choice, Settings, Variables, a
   completion Guide (if one's imported), a manual e-ink refresh (Onyx hardware only), and
   Library/Quit.
4. A novel's row menu on the library screen (⋮ on that row) offers VNDB linking, save export/import,
   and completion-guide import.
5. The library's own three-dot menu (top-right) can switch to the separate **Guides** page for
   standalone guides not tied to an imported novel.

## Supported VNDS script commands

The interpreter (`vnds/ScriptEngine.java`) supports the documented VNDS `.scr` command set:

| Command | Usage | Notes |
|---|---|---|
| `text` | `text string` | `@`-prefixed = non-blocking; `~` = blank line; `!` = blank + blocking tap |
| `bgload` | `bgload file [fadetime]` | Looks in `background/`; no fade on e-ink |
| `setimg` | `setimg file x y` | Looks in `foreground/`; layers stack, never replace |
| `sound` | `sound file times` | `times = -1` loops forever; `~` stops the current sound |
| `music` | `music file` | `~` stops music |
| `setvar` / `gsetvar` | `setvar name = /+/- value` | `setvar` is per-save; `gsetvar` is global |
| `choice` | `choice a\|b\|c` | Sets `selected` to the 1-based picked index |
| `if` / `fi` | `if var == value ...` | Flat (non-nested) conditional |
| `label` / `goto` | | Same-file jump |
| `jump` | `jump file.scr [label]` | Switches script file, optionally to a label |
| `delay` | `delay frames` | Real pause, unless e-ink's "instant delays" setting is on |
| `random` | `random var low high` | Inclusive range |

Unknown/unsupported commands are silently ignored, since real-world VNDS packs sometimes use
commands outside the documented set.

## NScripter/ONScripter support

Alongside VNDS, this app also has a second, independent interpreter
(`nscripter/NsScriptEngine.java` + `NsCommandDispatcher.java`) for the NScripter/ONScripter script
format — recognized automatically at import time from a plain-text `0.txt`/`00.txt` (or its
`.utf`-suffixed variants) or an obfuscated `nscript.dat`/`pscript.dat`/`nscr_sec.dat`, typically
alongside an `arc.nsa` asset archive.

**Support here is more limited than for VNDS, and not every NScripter/ONScripter novel will work
correctly.** This engine covers a core subset of real-world commands — dialogue and inline text
control codes, backgrounds/sprites, choices and the custom-select (`csel`/`cselbtn`) menu idiom,
sound/music, numeric/string variables and `if`/`for` control flow, `defsub`/`getparam`
pseudo-commands, and more — but real NScripter/ONScripter games vary widely in which commands,
extensions, and engine-specific quirks they lean on, and this reimplementation doesn't cover all of
them. As with the VNDS interpreter, an unrecognized command is silently skipped rather than crashing
the reader, so most scripts keep running even when something isn't fully supported — but depending
on what a given novel relies on, you may see things like unparsed script syntax showing up as
dialogue, a scene or menu that doesn't advance, or missing effects. If you run into one of these
with a specific novel, please open an issue with enough detail to reproduce it.

## Completion guides

A completion guide is a plain JSON file describing a VN's routes, the choices along each one, and
the endings they lead to — the kind of information a text walkthrough already contains, structured
so the app can render it as a checkable tree instead of a wall of text.

Import one from a novel's row menu ("Import guide…") to attach it to that novel, or from the
separate Guides page to keep a guide independent of any imported story. Either way, the *file*
itself is never modified — every checkbox you tick is tracked by the app separately, keyed to the
novel/entry, and is completely independent of any save slot.

The parser is deliberately permissive: only a top-level `"routes"` array is required, everything
else is optional, and unrecognized fields are simply ignored (so a richer, hand-maintained guide
degrades gracefully in older app versions, and vice versa). For the full JSON schema, a worked
example, and the complete field reference, see the
**[Completion Guide Format](https://github.com/davidgith1/vnds-android-eink/wiki/Completion-Guide-Format)**
wiki page.

## Project structure

- `app/src/main/java/com/example/vndsandroideink/` — the app itself (activities, dialogs, managers).
- `app/src/main/java/com/example/vndsandroideink/vnds/ScriptEngine.java` — the `.scr` interpreter,
  the one piece of the codebase with no Android dependency at all.
- `app/src/main/java/com/example/vndsandroideink/nscripter/` — the NScripter/ONScripter
  interpreter (see [NScripter/ONScripter support](#nscripteronscripter-support)).

## Contributing

Issues and pull requests are welcome. If you're changing the reader or importer, please test
against at least one real VNDS story pack of your own (and, if you're touching the `nscripter`
package, a real NScripter/ONScripter pack too) before submitting.

## License

[GNU General Public License v3.0](LICENSE) or later.

This is only the app's own code — it does not affect the licensing of any VNDS story pack, guide
file, or other content you import or use with it.

## Acknowledgments

- The original **VNDS** engine, created by Jake Probst and anoNL and documented/hosted at
  Digital-Haze (digital-haze.net).
- **[NScripter](https://nscripter.com/)**, created by Naoki Takahashi, and Ogapee's open-source
  **[ONScripter](https://onscripter.osdn.jp/onscripter.html)** clone of it, from which
  **[ONScripter-EN](https://github.com/Galladite27/ONScripter-EN)** descends — this app's own
  `nscripter` package is an independent reimplementation of that script format, consulted against
  ONScripter-EN's public source purely as a reference for real-world command semantics (see the
  LICENSE file's Third-Party Notices; no ONScripter-EN code is included in or distributed with this
  repository).
- [VNDB](https://vndb.org) for the metadata API used by the optional "Get info from VNDB" linking.
