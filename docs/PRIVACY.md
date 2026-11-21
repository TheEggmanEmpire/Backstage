# Backstage privacy policy

Last updated: 21 November 2026. Applies to Backstage.
Publisher: TheEggmanEmpire. Source and contact: [project repository](https://github.com/TheEggmanEmpire/Backstage).
Do not post private information in a public GitHub issue.

## What Backstage does with information

Backstage provides an Android desktop based on Dextop and an optional AI assistant. There is no Backstage account,
Backstage cloud backend, advertising service, or Backstage telemetry. Backstage does not sell personal data.
The absence of a Backstage backend does **not** mean that no data leaves your device: features you enable can
communicate directly with providers, websites, other apps, and MCP clients or servers you choose.

### Desktop and optional agent control

Backstage's accessibility service provides both the desktop and agent screen control, after disclosure and consent.

When you enable agent control and start a task, the agent can receive visible text, controls, window/app identity,
and, when vision is enabled, screenshots of the selected display. Screen content may contain messages, contact
details, documents, location, financial or health information, and other sensitive information from other apps.
The agent sends relevant context to your selected AI endpoint to produce responses and determine actions. You
choose the desktop or phone target. Turning off the relevant accessibility service stops its screen access.
Desktop privilege setup prefers Stellar, then Shizuku, then Dextop's embedded wireless debugging; these are local
privilege mechanisms, not Backstage cloud services.

### AI providers and user-chosen endpoints

Your requests, relevant conversation context, tool results and enabled vision images go directly to the endpoint
you configure. Available provider configurations include Anthropic, OpenAI, Google Gemini, OpenRouter, DeepSeek,
Venice, OpenCode Go, Ollama and custom OpenAI-compatible endpoints. An aggregator or proxy may forward data to
additional providers. Your API key is sent to the endpoint configured for that provider for authentication.
Backstage does not independently receive these requests or keys.

Provider retention, logging, training, processing locations and deletion procedures depend on your provider,
endpoint and account terms. Backstage cannot promise that a provider discards requests or never uses them for
training. Review the actual endpoint operator's privacy policy before use. Self-hosting may keep AI processing
on your device or local network, depending on where that endpoint runs.

### Optional device features and connections

- **Voice:** microphone access is used for voice input and optional wake-word detection. Wake-word recognition
  uses Vosk locally after downloading a model from alphacephei.com. Push-to-talk prefers Android on-device
  recognition when configured and available, but can fall back to the installed speech recognition service;
  that service may send audio to its operator. Spoken replies use the installed text-to-speech engine, which
  may process text remotely. Transcribed task text may be sent to the selected AI endpoint.
- **Device tools:** with applicable permissions and user-enabled tools, tasks can access location, contacts,
  calendar events, installed-app information and device status. Relevant results can enter AI context or be
  returned to an authorized MCP client. Grant only permissions needed for your tasks.
- **Search and web:** searches go to your selected search service (including optional Kagi or a custom engine).
  Opening links, importing skills from URLs/GitHub, provider model discovery, and downloading optional models
  contact the relevant hosts. Weather lookups can send a place or coordinates to OpenWeatherMap. Update
  checks contact the project's GitHub releases; desktop Linux-image downloads contact their hosting services.
  Remote hosts can receive your IP address and ordinary request metadata.
- **MCP and automation:** the optional local HTTP MCP server uses a bearer token and binds to loopback by
  default. Enabling LAN access allows token holders on the network to call enabled tools and receive their
  results. Configured remote MCP servers receive the tool arguments you authorize and return results that
  may enter AI context. Local broadcasts and automation integrations can also exchange task data. Disable
  unused connections and keep tokens private.
- **Desktop integrations:** Dextop's Cast and other optional integrations communicate with the chosen devices
  and their service operators. Apps run inside the desktop have their own privacy practices.

### Reporting an AI response

Long-press an assistant response or question in the main or popup chat and choose **Report response**. Choose a
category and optionally add a note. **Save report** stores a local flag without sending it anywhere. Reports
contain a generated report ID, time, optional task reference, category, the selected response and your note.
They do not automatically include the rest of the conversation, screenshots, settings, API keys or MCP tokens.
The response or note can themselves contain sensitive information; inspect the preview before sharing.
Very long responses are explicitly marked as truncated in the stored report and preview.

After saving, you can share the exact preview through Android's share sheet, email an address you configure,
or separately email your provider's reporting address if you configure one. The maintainer address defaults
to blank, so the default action opens the share sheet. There is no automatic submission, provider moderation
API, or delivery receipt. Email/share applications and recipients process the data under their own policies.
Opening an email composer or share sheet does not prove a report was sent. **Saved reports** in the same
message menu lets you reopen, share or delete local reports. Recipient addresses are stored locally too.
Deleting a local report does not delete a copy you already sent to someone else.

## Local storage, retention and deletion

Settings, chat threads, task history, agent memory, schedules and imported skills are stored in app-private
storage. Backstage attempts to encrypt API keys and integration tokens with an Android Keystore key. The current
implementation also accepts legacy plaintext secrets and falls back to storing plaintext if Keystore encryption
is unavailable. Do not assume every saved secret or chat/history file is separately encrypted in addition to
Android's device protection. History retention depends on
your settings. Reports are stored in app-private `noBackupFilesDir/ai-reports`, excluded from Android backup,
until you delete them or clear the app's data/uninstall it. At most 100 reports can be saved; the app asks you
to delete reports when full instead of silently deleting them.

You can delete history/threads and memory using their in-app controls, delete saved reports from their menu,
or remove all local Backstage data through Android app settings. Settings/skills exports and shared files are
copies you control; delete those separately. Backstage's settings export excludes chats, task history and AI
reports. Platform backup or device transfer of other app data depends on the shipped manifest and Android
settings; reports use the explicitly excluded directory. Data already sent to a provider, MCP peer, mail
recipient or another app is subject to that recipient's retention and deletion procedures.

HTTPS provider connections encrypt data in transit. Custom endpoints, local Ollama and the built-in MCP HTTP
server can use unencrypted HTTP; do not assume all connections are encrypted. Use trusted networks or a
protected tunnel and never expose the MCP server publicly without appropriate transport protection.

## Contact, children and policy changes

Backstage is a general productivity app and is not designed specifically for children. Do not submit other people's private data without
appropriate permission. Contact the publisher through the project repository for privacy/deletion requests; use provider support for provider-held data. Changes to data practices require
an updated policy, disclosures and, where applicable, renewed consent.
