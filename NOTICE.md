# NOTICE

**Backstage** is a modified version of **Dextop**.

- Dextop — Copyright © NarYuki and the Dextop contributors — <https://github.com/NarYuki/Dextop>
- Licensed under the **GNU General Public License, version 3 or (at your option) any later version**
  (GPL-3.0-or-later). The full license text is in [`LICENSE`](LICENSE) (identical to
  [`upstream/dextop/LICENSE`](upstream/dextop/LICENSE)).

## Modification notice (GPL-3.0 §5a)

Modified by **TheEggmanEmpire**, continuously from 2026-11-01 (first modified version) to 2026-11-21 (1.0.0, the
first public release, whose single commit replaces the earlier private history), and on the dates recorded in this
repository's git history since then.

What was changed relative to Dextop:

| Where | What |
|---|---|
| [`upstream/dextop/`](upstream/dextop) | Unmodified copy of Dextop at the commit (and git tree id) recorded in [`upstream/UPSTREAM.env`](upstream/UPSTREAM.env). |
| [`patches/`](patches) | Every change to Dextop's own source files, as numbered diffs applied at build time: background/mini-window/in-app desktop presentation and the hook that lets the agent use Dextop's accessibility service (0001), fork identity — per-package shell paths and Car Companion trust pinned to this build (0002), Shizuku-first privilege fallback (0003), no Google analytics/update SDKs (0004), the Backstage shell on Dextop's home screen — "Agent" destination, live desktop player, app name and icon (0005), native Backstage update checks and installation entry point (0007). |
| [`scripts/inject.sh`](scripts/inject.sh) | At build time appends one `apply(from = …)` line each to Dextop's `android/settings.gradle.kts` and `android/app/build.gradle.kts` (Backstage library, identity, overlays). |
| [`backstage/`](backstage) | New code: the Backstage AI agent library, the Kotlin glue compiled into Dextop, the Gradle integration script and resource/manifest overlays (one launcher icon, "Backstage", which opens Dextop's home screen with the agent as its "Agent" destination; Backstage name and icon; application id `com.eggman.backstage`; Dextop's accessibility service configured as the app's only one, for the desktop and the agent; the Car Companion relay removed). |
| [`scripts/`](scripts), [`.github/`](.github) | Build, patch and upstream-update tooling. |

The application is distributed as "Backstage" under its own application id so it can be installed next to
the official Dextop; it is not an official Dextop build and is not endorsed by the Dextop authors.

## Corresponding source

The corresponding source of every Backstage APK is this repository at the tag or commit it was built from:
the vendored Dextop sources, the patches, the Backstage sources and the scripts that control compilation
(`scripts/build-apk.sh`, `scripts/prepare-work-tree.sh`, `scripts/inject.sh`, `backstage/dextop-settings.gradle`,
`backstage/dextop-integration.gradle`, `toolchain.env`, Dextop's Gradle files and `tool/` scripts).

Every APK names that commit: the asset `assets/backstage/THIRD_PARTY_NOTICES.txt` starts with
"Corresponding source: <repository> at commit <id>" (also `BuildConfig.SOURCE_REVISION` of the agent library);
a revision ending in `-dirty` was built from uncommitted changes and is a local test build, not a distribution.
`scripts/build-apk.sh release` refuses a checkout with uncommitted or untracked files.

## Third-party components

### Native libraries

Every native library in the APK (`lib/<abi>/*.so`, `assets/**/*.so`) is listed with its component, license and
license texts in [`licenses/native-libraries.tsv`](licenses/native-libraries.tsv). The build turns that table into
`assets/backstage/THIRD_PARTY_NOTICES.txt` inside the APK: this file, the GPL-3.0 text, one entry per library and
every license and notice text in full (`scripts/lib/verify_apk.py` fails an APK with a library missing from it).
The texts come from [`upstream/dextop/third_party/`](upstream/dextop/third_party) (versions and source locations in
its [`README.md`](upstream/dextop/third_party/README.md)) and, for code Backstage adds, from
[`licenses/texts/`](licenses/texts):

- Vosk 0.3.75 (`libvosk.so`, Apache-2.0), which statically links Kaldi and OpenFst (Apache-2.0), OpenBLAS 0.3.20
  (BSD-3-Clause) and CLAPACK 3.2.1 (BSD-3-Clause);
- JNA 5.18.1 (`libjnidispatch.so`, Apache-2.0 or LGPL-2.1-or-later), which statically links libffi (MIT).

### Prebuilt third-party binaries

Some native code is redistributed unchanged as prebuilt binaries that upstream Dextop did not build from the sources
in its tree. Their corresponding source exists only on third-party servers, so every Backstage release mirrors it
as release assets (listed in [`licenses/third-party-sources.tsv`](licenses/third-party-sources.tsv), downloaded and
checked by `scripts/fetch-third-party-sources.sh`, attached with `SOURCES-SHA256SUMS`):

| Binary | Corresponding source |
|---|---|
| `libvulkan_dextop_turnip.so` (DD-Turnip v0.2.0: Mesa MIT, patch set GPL-3.0) | `DD-Turnip-v0.2.0-source.tar.xz` (Droid-Deck/Drivers-CI release DD-Turnip-v0.2.0): Mesa `bffd919`, the patches and build scripts |
| Linux GL/EGL/Zink bundle (Java resources, Mesa MIT) | `DD-Turnip-v0.3.3-source.tar.xz` (Mesa `c90ff43`) plus upstream's `tool/patch_linux_gpu_runtime.py` and `tool/build_linux_gpu_runtime.sh` |
| `libpulse.so`, `libpulseaudio.so`, `libpulsecommon-13.0.so`, `libpulsecore-13.0.so`, `libprotocol-native.so` (PulseAudio 13, LGPL-2.1-or-later) | prebuilt files of DroidDeck commit `8aa1fdaf708eb44ef7cff7e493bda946989af511` (`DroidDeck-8aa1fdaf….tar.gz`) and PulseAudio 13.0 (`pulseaudio-13.0.tar.xz`) |
| `module-native-protocol-tcp.so`, `module-directaudio-native-sink.so` | PulseAudio v13.0 and `upstream/dextop/third_party/deck-audio/` (built by upstream) |
| `libffi.so` (MIT) | DroidDeck `8aa1fdaf` |
| `libsndfile.so`, `libltdl.so` (LGPL-2.1-or-later) | prebuilt files of DroidDeck `8aa1fdaf`; **the exact libsndfile and libtool/libltdl source versions are not identified** by upstream Dextop (`third_party/deck-audio/NOTICE.md`) or DroidDeck. Until upstream names them or they are rebuilt from known sources, the DroidDeck tree is the best available source reference, and Backstage cannot fully meet LGPL-2.1 §6 for these two files. |
| `libdextop_blsession.so`, DroidDeck session scripts and preload, PRoot patches, adrenotools | DroidDeck commit `26ca374c47641fb8db8d1711c188ddb36a4a7877` (`DroidDeck-26ca374….tar.gz`) and the copies in `upstream/dextop/third_party/` |

DroidDeck session scripts (GPL-3.0, <https://github.com/Droid-Deck/DroidDeck>): upstream Dextop packages them
as APK resources but its `.gitignore` keeps part of them out of git. `scripts/prepare-work-tree.sh` restores
them at build time with upstream's own `tool/sync_droiddeck_linuxfs.sh` from DroidDeck at the commit upstream
pins in `android/app/src/main/resources/moe/n4tsu/dextop/linux/droiddeck/source.env`; that DroidDeck commit
is part of the corresponding source of the APK.

### Google Play version: native libraries rebuilt with 16 KB page alignment

This applies to the Google Play version only. Google Play rejects native libraries without 16 KB ELF page alignment,
and upstream Dextop's prebuilt PulseAudio stack and DD-Turnip driver use 4 KB. The Google Play version therefore
packages Backstage's own builds of these files instead, made by `scripts/build-native-16k.sh` from the sources below
(pinned by SHA-256 in that script and in [`licenses/third-party-sources.tsv`](licenses/third-party-sources.tsv)) into
`backstage/native-16k/`. The GitHub version packages upstream's prebuilt files listed above, unchanged.

| Binary | Corresponding source |
|---|---|
| `libvulkan_dextop_turnip.so` (DD-Turnip v0.2.0: Mesa MIT, patch set GPL-3.0) | `DD-Turnip-v0.2.0-source.tar.xz` (Droid-Deck/Drivers-CI release DD-Turnip-v0.2.0): Mesa `bffd919` with the DD-Turnip patch set, built with the options of its `scripts/build_android.sh` |
| `libpulse.so`, `libpulseaudio.so`, `libpulsecommon-13.0.so`, `libpulsecore-13.0.so`, `libprotocol-native.so`, `module-native-protocol-tcp.so` (PulseAudio 13.0, LGPL-2.1-or-later) | `pulseaudio-13.0.tar.xz` with `scripts/native-16k/pulseaudio-13.0-atomic-cast.patch` (PulseAudio commit `e4450d8b`) |
| `module-directaudio-native-sink.so` | `upstream/dextop/third_party/deck-audio/` built against the PulseAudio 13.0 build above |
| `libsndfile.so` (LGPL-2.1-or-later) | `libsndfile-1.0.31.tar.bz2` |
| `libltdl.so` (LGPL-2.1-or-later) | `libtool-2.4.7.tar.xz` (`libltdl/`) |

### OMP runtime (GitHub flavor only)

The bundled versions and artifact SHA-256s are in [`upstream/omp-termux/UPSTREAM.env`](upstream/omp-termux/UPSTREAM.env).
[`licenses/omp-runtime.tsv`](licenses/omp-runtime.tsv) adds the following
notices and upstream license texts to the same `assets/backstage/THIRD_PARTY_NOTICES.txt` mechanism:

- **omp** (`@oh-my-pi/pi-coding-agent`, <https://github.com/can1357/oh-my-pi>), MIT; its license and the dependency
  license files shipped by npm remain inside `omp-tree.tgz`, and its MIT text is also in the notices asset.
- **omp-termux** (<https://github.com/hmeqo/omp-termux>), MIT; the tool, installer, README and LICENSE are vendored
  verbatim at the pinned commit. The MIT text is included in the notices asset.
- **Bun** (<https://github.com/oven-sh/bun>), MIT for Bun itself, plus its bundled third parties. The upstream
  `LICENSE.md` for the pinned release is included in the notices asset; it identifies the linked libraries,
  polyfills and their licenses, including LGPL JavaScriptCore/WebKit, and gives the upstream relinking instructions.
- **Termux bootstrap packages** (<https://github.com/termux/termux-packages>), various licenses including GPL.
  Their full per-package license and copyright texts ship inside the unchanged bootstrap at `share/doc` and
  `share/LICENSES`. `scripts/fetch-third-party-sources.sh` adds the termux-packages source archive for the exact
  `TERMUX_BOOTSTRAP_TAG` to each release's source mirror. That archive contains the package recipes, patches and
  upstream source locations; it is not a claim that every upstream package source tarball is inside it.

### Libraries

Dart/Flutter package licenses are shown in the app (Settings → App information → Licenses in the Dextop UI) and
are in `assets/flutter_assets/NOTICES.Z`. The Backstage agent library's own dependencies (AndroidX, Kotlin, OkHttp,
Shizuku API, HiddenApiBypass, Vosk, JNA) are Apache-2.0/MIT licensed (JNA dual Apache-2.0/LGPL-2.1); see
`backstage/android/build.gradle.kts` for the exact list.

### Closed-source Google SDKs inherited from upstream Dextop

The APK is **not** made only of free software. Upstream Dextop depends on closed-source Google client libraries,
distributed under Google's own terms (Android Software Development Kit License / Google APIs Terms of Service), not
under an open-source license. Backstage removes the ones it does not need (`patches/0004-no-google-sdks.patch`) and
keeps one:

| Upstream dependency | Closed-source Google artifacts in the APK | Used by Backstage |
|---|---|---|
| `com.google.android.gms:play-services-cast-framework:21.5.0` (`upstream/dextop/android/app/build.gradle.kts`) | `play-services-cast-framework`, `play-services-cast`, `play-services-base`, `play-services-basement`, `play-services-tasks`, `play-services-flags` | Yes: Dextop's cast feature (the desktop on a Chromecast). `MirrorService` and the Cast options provider are compiled against it, so dropping it would mean removing that feature from Dextop. Besides the cast picker, `MirrorService` also obtains the Cast context when a desktop session ends (to stop a running cast), which binds to Google Play services on the device. |

Removed by `patches/0004-no-google-sdks.patch` (not in Backstage APKs; `scripts/lib/verify_apk.py` flags them if
they ever reappear in the dex):

| Upstream dependency | Closed-source Google artifacts it brought in | Replacement |
|---|---|---|
| Flutter `firebase_core` + `firebase_analytics` (`upstream/dextop/pubspec.yaml`), Gradle plugin `com.google.gms.google-services` | `firebase-analytics`, `play-services-measurement` (`-api`, `-impl`, `-base`, `-sdk`, `-sdk-api`), `play-services-ads-identifier`, `play-services-stats` (and the open-source Firebase client core) | None: `AppAnalytics` is a no-op; nothing is collected. The manifest overlay still removes the measurement components and deactivates collection, as a guard (`backstage/overlay/AndroidManifest.github.xml`). |
| Flutter `in_app_update` (`upstream/dextop/pubspec.yaml`) | Google Play Core `app-update`, `core-common` | Backstage's native updater checks GitHub releases, verifies downloads and opens Android's installation approval; patch 0007 connects Dextop's Home to it. |

These Google libraries are not System Libraries, and their source is not available, so their corresponding source
cannot be provided; upstream Dextop distributes the same combination. **Additional permission under GNU GPL
version 3 section 7** (granted by TheEggmanEmpire for the code it authored in this repository — the `backstage/`
tree, the patches and the scripts; it cannot and does not change the terms of Dextop's own code): if you modify
this Program, or any covered work, by linking or combining it with the Google Play services client libraries named
above, or with the Firebase or Google Play Core client libraries that earlier versions of this Program included (or
a modified version of those libraries), the licensors of this Program grant you additional permission to convey the
resulting work. Corresponding Source for a non-source form of such a combination shall not include the source code
for the parts of those libraries.
