# Security policy

## Secrets

Never commit provider API keys, Firebase service account files, release signing keys, or `.env` files. The repository includes a non-secret debug-only PKCS12 certificate at `app/ci-debug.p12` only to make CI debug APK fingerprints stable for Firebase testing; do not use it for production releases.

If a token or API key is accidentally exposed, revoke it immediately and generate a new one.

## User data

Chat history is stored under `users/{uid}` in Firestore. The provided rules restrict access to the authenticated owner.

## API routing

The Android app calls only Firebase Cloud Functions. AI provider keys stay server-side.
