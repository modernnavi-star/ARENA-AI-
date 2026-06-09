# ARENA AI

Arena AI is a native Android starter app for an **Arena-like AI assistant**. Users sign in with Google/Gmail, send complex tasks, route prompts to a random configured AI model or a two-model duel, and keep chat/search history backed up in Firebase Firestore.

## What is included

- Native Android app built with **Kotlin + Jetpack Compose**
- **Google/Gmail login** through Firebase Authentication
- User profile sync to Firestore
- Chat UI with:
  - Random AI mode
  - Arena Duel mode
  - Saved history screen
  - Search history filter
  - Cloud backup/restore after login
- Firebase Cloud Functions backend that keeps AI API keys off the phone
- Optional AI providers:
  - Gemini
  - OpenAI
  - Claude/Anthropic
  - Mistral
- Firestore security rules so users can only access their own data

## Architecture

```text
Android App
  -> Firebase Auth Google Sign-In
  -> Callable Firebase Cloud Function: sendArenaPrompt
  -> Random/duel AI model router
  -> Firestore backup under users/{uid}/chats/{chatId}/messages/{messageId}
```

The Android app never stores provider API keys. All AI calls happen in Cloud Functions.

## Repository structure

```text
app/                  Android app
functions/            Firebase Cloud Functions backend
firestore.rules       Firestore per-user security rules
firebase.json         Firebase project config
firestore.indexes.json
```

## Quick setup

### 1. Firebase project

1. Create a Firebase project.
2. Enable **Authentication > Sign-in method > Google**.
3. Enable **Firestore Database**.
4. Enable **Cloud Functions**.
5. Add an Android app with package name:

```text
com.modernnavi.arenaai
```

6. Download `google-services.json` and place it here:

```text
app/google-services.json
```

An example placeholder is provided at `app/google-services.json.example`. The APK built only with this placeholder is a demo build and **Google sign-in will not work** until a real Firebase config is used.

7. Add the debug APK signing fingerprints below to your Firebase Android app:

```text
SHA-1:   51:5A:59:D7:8E:04:4E:C6:93:D6:38:89:5C:D9:FC:C0:9E:E7:8E:58
SHA-256: 67:3F:82:CF:D9:75:16:C6:E9:CA:A1:D6:77:4F:F2:AA:88:72:CA:EB:C9:01:30:CD:BA:D9:66:40:B5:22:95:EC
```

These fingerprints match the committed non-secret `app/ci-debug.p12` used for debug APK builds.

### 2. Configure AI provider keys

For local emulator use:

```bash
cp functions/.env.example functions/.env
```

Then add one or more provider keys:

```bash
GEMINI_API_KEY=...
OPENAI_API_KEY=...
ANTHROPIC_API_KEY=...
MISTRAL_API_KEY=...
```

If no provider key is configured, the backend still runs and returns a setup message while saving user history.

### 3. Install backend dependencies

```bash
cd functions
npm install
npm run lint
```

### 4. Deploy Firebase backend

```bash
firebase login
firebase use YOUR_FIREBASE_PROJECT_ID
firebase deploy --only functions,firestore:rules,firestore:indexes
```

### 5. Build Android app

Open this repository in Android Studio, sync Gradle, then run the `app` module on a device/emulator.

You can also build from terminal after adding `google-services.json` if Gradle is installed:

```bash
gradle :app:assembleDebug
```

Alternatively, open the project in Android Studio and let it create/use the Gradle wrapper for your machine.

## Build a GitHub APK with working Google sign-in

The GitHub workflow can create the APK artifact. For Google sign-in to work in that artifact:

1. In Firebase, add the SHA-1/SHA-256 fingerprints listed above to the Android app.
2. Download the updated `google-services.json` from Firebase.
3. Add one GitHub repository secret:

```text
GOOGLE_SERVICES_JSON_B64
```

Set it to the base64 value of your real `google-services.json`:

```bash
base64 -w 0 app/google-services.json
```

Then run/push the workflow again. The artifact `arena-ai-debug-apk` will contain `arena-ai-debug.apk` with real Google login config.

## Main Firestore data model

```text
users/{uid}
  uid
  displayName
  email
  photoUrl
  createdAt
  lastSeenAt

users/{uid}/chats/{chatId}
  title
  mode
  modelUsed
  lastMessage
  createdAt
  updatedAt

users/{uid}/chats/{chatId}/messages/{messageId}
  role: user | assistant
  content
  modelUsed
  candidates
  createdAt
```

## Security notes

- Do not commit `google-services.json` if your team treats it as private.
- Do not commit `.env` files or provider API keys.
- Firestore rules restrict each user to their own `users/{uid}` tree.
- Provider API calls are made only from Cloud Functions.

## Next feature ideas

- Let users vote for the better duel answer.
- Add model preference settings.
- Add export/delete-account flows.
- Add image/file prompt support.
- Add subscription/rate limits.

## Complete Arena server included

The repository now includes both sides of the app:

- Android client
- Firebase Functions Arena server
- Firestore rules
- Workspace export system
- Manual deploy workflow

See:

```text
docs/ARCHITECTURE.md
.github/workflows/deploy-firebase.yml
```

If AI provider API keys are not configured, the server and Android app both include an embedded Arena fallback engine so the app still responds and exports files instead of failing.
