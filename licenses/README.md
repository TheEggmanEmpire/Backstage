# licenses/

License material that must travel with the Backstage APK and its releases (see [`NOTICE.md`](../NOTICE.md)).

- [`native-libraries.tsv`](native-libraries.tsv): every native library the APK contains, with component, license and
  the license/notice texts it needs. `backstage/android/build.gradle.kts` (task `generateThirdPartyNotices`) packages
  it with NOTICE.md, LICENSE and all those texts as `assets/backstage/THIRD_PARTY_NOTICES.txt`;
  `scripts/lib/verify_apk.py` fails an APK whose libraries are not all listed there, and
  `scripts/tests/test_licenses.py` checks the table against upstream's prebuilt and CMake-built libraries.
- [`texts/`](texts): license texts of components Backstage adds that upstream's `third_party/` does not carry
  (Vosk/Kaldi/OpenFst/OpenBLAS/CLAPACK inside `libvosk.so`, JNA and its libffi inside `libjnidispatch.so`, the
  Apache-2.0 and LGPL-2.1 texts). Copied verbatim from the projects' own repositories.
- [`third-party-sources.tsv`](third-party-sources.tsv): source archives of the prebuilt third-party binaries, which
  `scripts/fetch-third-party-sources.sh` downloads (SHA-256 checked where pinned) and the release job attaches to
  every GitHub release.

When a native library is added (an upstream bump, a new dependency), add its row; `verify-apk.sh` and the unit test
name the missing file.
