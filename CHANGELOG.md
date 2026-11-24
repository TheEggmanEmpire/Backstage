# Changelog

## 1.0.2

- Black desktop recovery: before a task on the desktop (chat or scheduled), Backstage checks the desktop's picture and restarts the desktop when it runs but shows only black. During a task, the agent restarts it with `desktop_restart` as soon as a screenshot comes back black.
- The in-app desktop preview refreshes itself when it stays black, and has a **Refresh picture** button.
- Setup walks through the AI provider and desktop access step by step, with download buttons for Stellar and Shizuku Plus.
- ChatGPT sign-in shows numbered steps with a copyable code, clearer network errors and **Retry**, and lists the models your ChatGPT plan offers.
- Play build: the desktop's Start button names what is missing (Stellar or Shizuku Plus, its permission, or the desktop accessibility service) with buttons that fix it. Backstage-play-agent runs skill scripts and omp subagents.

## 1.0.1

- In-app updates: Backstage checks GitHub for a new release at start and once a day and notifies you. Settings → Agent → **Updates** shows the installed and latest version, **Check now** and **Update**.
- **Update** downloads the arm64 APK, verifies its SHA-256 and signing certificate, and installs it. If Backstage may not install apps yet, it opens Android's *Install unknown apps* setting and continues when you return.
- The update dialog on the Dextop Home screen installs the update directly instead of opening the browser.

## 1.0.0

First public release.
