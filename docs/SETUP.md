# Setup guide

## Firebase Authentication

1. Go to Firebase Console > Authentication > Sign-in method.
2. Enable Google provider.
3. Add support email.
4. In Project settings > Your apps, add Android app package `com.modernnavi.arenaai`.
5. Add SHA-1/SHA-256 for debug and release builds if Google sign-in fails.
6. Download `google-services.json` to `app/google-services.json`.

## AI backend configuration

The backend checks these environment variables in order to decide which providers can join the random arena:

- `GEMINI_API_KEY`
- `OPENAI_API_KEY`
- `ANTHROPIC_API_KEY`
- `MISTRAL_API_KEY`

With multiple keys, Random AI picks one provider at random. Arena Duel picks two providers where available.

## Firestore rules

Deploy with:

```bash
firebase deploy --only firestore:rules,firestore:indexes
```

Rules allow a signed-in user to read/write only their own path:

```text
users/{uid}/...
```

## Android package

Default app package:

```text
com.modernnavi.arenaai
```

If you rename the package, update these locations:

- `app/build.gradle.kts`
- `app/src/main/AndroidManifest.xml`
- Kotlin package declarations
- Firebase Android app configuration
