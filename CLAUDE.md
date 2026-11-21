# Backstage

These rules hold for every change, by anyone (orchestrator, workers, CI jobs that commit). They are checked by
`scripts/check-publication.sh` (CI) and a local pre-commit hook.

## Public repository

This repository is public. Commit only code, build files, tests and the user-facing files: `README.md`,
`CHANGELOG.md`, `NOTICE.md`, `CLAUDE.md`, `LICENSE`, `licenses/`, `docs/PRIVACY.md`,
`docs/assets/`, `docs/screenshots/`.

- Docs stay local. Never commit internal or AI-written docs: design notes, architecture, development/release
  procedures, upstream notes, test reports, review notes, plans, hand-off reports. They live on disk only
  (`.git/info/exclude`, `.internal/`, `build/`). A new internal doc goes into `.git/info/exclude` before it is created.
- Tracked files never point at local-only docs.
- Never commit signing keys, passwords, tokens, or material derived from other apps.
- Before every commit: check `git status` for new `.md` files and new files outside code; ask the user if unsure.
- Commit on `main` only, no other branches, and push every commit to `origin main` right away.

## Code, comments and tests

- Comments describe what the code does now and why. Nothing else.
- No history: no old versions ("since 0.9", "until 1.0.0"), no "was", "now", "no longer", "new in".
- No process: nothing about reviews, findings, model names, "not yet verified", task or spec IDs.
- No other apps as the reference: Backstage's behaviour is described as its own design, never as a copy of or
  "compatible with" another agent app.
- Test names say what they test (`UpdateVerificationTest`), never where they came from (`ReviewMinorsTest`,
  `…Round3Test`).

## Release notes

Each version's `## <version>` section in `CHANGELOG.md` is copied by CI onto the GitHub release page.

- Only what changed for the user, in concise technical terms: features, fixes, behaviour changes, new permissions.
- Never mention the repository, its history, versioning decisions, the release process, CI, tests, tooling or reviews.
- No filler, no justification. One short paragraph or a few bullets.
- 1.0.0 reads exactly: "First public release."
- Automatic Dextop updates are pre-releases with exactly "Dextop updated to vX.Y.Z."; the README badge is live and
  never edited.
