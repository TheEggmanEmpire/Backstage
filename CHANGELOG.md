# Changelog

## 1.0.4

- New **Free models** provider: free models from KiloCode and OVHcloud, no account or key. It can be switched off in Settings → AI provider. These providers may log prompts and use them for training.
- **Free Auto** sends each request to the best enabled free model, ranked by intelligence score, context window and vision, and moves to the next one when a model is rate limited or fails (up to 3 times). Requests with screenshots skip text-only models. Settings shows the current order.
- A fresh install starts with Free models and Free Auto, so tasks work before any provider is set up. Existing installations keep their provider; Free models start switched off there.
- Model lists in Settings and setup show each model's context window and whether it accepts images.

## 1.0.3

- Fixed updates failing with "INSTALL_FAILED_INTERNAL_ERROR: Session files in use": Android's install confirmation opens only once per update, either directly or from the notification, never both.
- The update box in Settings → App info shows the new version as soon as one is found, also when the daily background check found it. It refreshes when the app returns to the foreground and when the page opens.

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
