# POOP AI API

## Deploy
Deploy this repository as a Vercel project with the repository root as the project root.

Add this Environment Variable in Vercel Project Settings:
- `OPENROUTER_API_KEY`: the project's OpenRouter API key (server-side secret).

Redeploy after adding the variable. Never place this key in Android source, assets, or GitHub.

The Android app calls `POST /api/chat` with:
`{ "installId": "<UUID>", "messages": [{ "role": "user", "content": "..." }] }`

## Important before public launch
The included in-memory rate limiter is best-effort per serverless instance only. It is not a production abuse-prevention system. Add a shared Redis/KV store, account/device identity, quotas, monitoring, privacy disclosures, and abuse controls before opening the service publicly. OpenRouter free model availability and quotas are not guaranteed; the project owner may incur costs if paid routing is ever enabled.
