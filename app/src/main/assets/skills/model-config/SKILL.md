---
name: model-config
description: Configure model providers and default model selection in X-OmniClaw. Use when the user asks to switch models, set a default model, or add or edit provider configs (including self-hosted OpenAI-compatible endpoints as a new `models.providers` key). For X-OmniClaw, model settings live in /sdcard/.xomniclaw/xomniclaw.json under agent.defaultModel and models.providers.
---

# Model Config

For X-OmniClaw, model configuration is stored in:

- `/sdcard/.xomniclaw/xomniclaw.json`

Relevant paths:

- `agent.defaultModel`
- `models.providers.<providerName>.baseUrl`
- `models.providers.<providerName>.apiKey`
- `models.providers.<providerName>.api`
- `models.providers.<providerName>.authHeader`
- `models.providers.<providerName>.models`

## Workflow

1. Read `/sdcard/.xomniclaw/xomniclaw.json` first.
2. Check current `agent.defaultModel` and existing providers under `models.providers`.
3. If the target provider already exists, update only the necessary fields.
4. If the target provider does not exist, create a provider entry under `models.providers`.
5. For self-hosted or other OpenAI-compatible endpoints, add a **new** `models.providers.<yourKey>` entry (any stable key name) with:
   - `api: "openai-completions"`
   - provider-specific `baseUrl`
   - `apiKey`
   - one or more model entries in `models`
6. Update `agent.defaultModel` to the requested model.
7. Preserve unrelated providers and settings.
8. If asked to verify, send a simple test prompt after config is saved.

## Notes

- Do not overwrite the full config if only one provider/model needs to change.
- If the user provides a compatible endpoint/key, add it as a new provider block under `models.providers` with `api: "openai-completions"` (or another supported API type per app).
- Keep model IDs exact.
