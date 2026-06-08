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

An example placeholder is provided at `app/google-services.json.example`.

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
