# Security policy

## Secrets

Never commit provider API keys, Firebase service account files, signing keys, or `.env` files.

If a token or API key is accidentally exposed, revoke it immediately and generate a new one.

## User data

Chat history is stored under `users/{uid}` in Firestore. The provided rules restrict access to the authenticated owner.

## API routing

The Android app calls only Firebase Cloud Functions. AI provider keys stay server-side.
