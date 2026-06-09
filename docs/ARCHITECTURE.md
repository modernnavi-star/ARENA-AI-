# Arena AI Android + Server Architecture

This repository contains the Android client and the embedded Arena server code required to run the app.

## Runtime flow

```text
Android App
  -> Firebase Auth Google login
  -> Firestore user profile/history/workspace
  -> Firebase Callable Function sendArenaPrompt
  -> Provider router: Gemini / OpenAI / Claude / Mistral / Arena Embedded fallback
  -> Chat response + workspace artifacts
```

## Android app modules

- `MainActivity.kt` — app entry point
- `ArenaAiApp.kt` — Compose UI for login, chat, history, workspace, settings
- `ArenaViewModel.kt` — auth state, chat sending, fallback response engine, history/workspace sync
- `Models.kt` — UI state, model selection, chat messages, workspace artifacts
- `Theme.kt` — dark Arena-style theme

## Server modules

- `functions/index.js` — Firebase Functions server
  - `sendArenaPrompt` authenticated callable endpoint
  - random model routing
  - arena duel mode
  - provider adapters
  - embedded fallback AI engine
  - workspace export generation
- `firestore.rules` — per-user security rules for all user data
- `firebase.json` — deploy configuration

## Supported model routing

The server automatically enables providers if API keys are present:

- `GEMINI_API_KEY`
- `OPENAI_API_KEY`
- `ANTHROPIC_API_KEY`
- `MISTRAL_API_KEY`

If no provider keys are configured, the server still responds using the embedded Arena fallback engine. This prevents the app from breaking while production keys are being added.

## Workspace exports

For every response, the app/server can create:

- Markdown `.md`
- Plain text `.txt`
- HTML / PDF-ready `.html`
- JSON `.json`

The HTML file can be opened in a browser and printed/saved as PDF.

## Firestore structure

```text
users/{uid}
users/{uid}/chats/{chatId}
users/{uid}/chats/{chatId}/messages/{messageId}
users/{uid}/artifacts/{artifactId}
```

## Deployment

Manual server deployment workflow is included:

```text
.github/workflows/deploy-firebase.yml
```

Required GitHub secrets:

```text
FIREBASE_PROJECT_ID
FIREBASE_SERVICE_ACCOUNT_JSON
```

Optional AI provider secrets:

```text
GEMINI_API_KEY
OPENAI_API_KEY
ANTHROPIC_API_KEY
MISTRAL_API_KEY
```

The Android APK build workflow is:

```text
.github/workflows/ci.yml
```
