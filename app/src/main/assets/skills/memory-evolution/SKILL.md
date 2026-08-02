---
name: memory-evolution
description: Process pending X-OmniClaw task memories, update MEMORY.md, and rebuild the compact USER-PROFILE.md.
metadata:
  {
    "xomniclaw": {
      "always": false,
      "emoji": "🧠",
      "skillKey": "memory-evolution",
      "version": "1.0.0",
      "category": "memory"
    }
  }
---

# Memory Evolution

Use this skill when the user asks to:

- update global memory
- evolve task memory
- summarize task experience into the user profile
- inspect memory evolution status
- configure or verify the daily memory update mechanism

This skill maintains task-derived memories. Gallery-derived memories are maintained by `gallery-memory`.

## Memory Files

- `MEMORY.md`: task memory from X-OmniClaw usage, including habits, reusable workflows, failures, and project context.
- `memory/IMAGE-MEMORY.md`: gallery memory from photos and screenshots.
- `memory/USER-PROFILE.md`: compact user profile loaded by default. It should be rebuilt from both `MEMORY.md` and `IMAGE-MEMORY.md`.

## Preferred Tool

### `memory_evolution`

Use `memory_evolution` as the direct tool for this workflow.

Supported actions:

- `run`: process pending task memory events, update `MEMORY.md`, and rebuild `memory/USER-PROFILE.md`
- `status`: inspect the latest evolution run status and pending event count

## Recommended Calls

### Run evolution

```json
{
  "action": "run"
}
```

### Check status

```json
{
  "action": "status"
}
```

## Daily Update Pattern

The app owns a managed scheduled task named `全局记忆进化`.

Its instruction should be equivalent to:

`使用 memory_evolution 工具执行全局记忆进化：处理待沉淀的 X-OmniClaw 任务记忆，更新 MEMORY.md，并重建 USER-PROFILE.md。`

## Best Practices

1. Do not update `MEMORY.md` directly during every normal task. Normal tasks should only append pending events.
2. Keep `USER-PROFILE.md` compact because it is loaded by default for all tasks.
3. Put reusable task lessons and workflows in `MEMORY.md`; put only distilled user habits and key preferences in `USER-PROFILE.md`.
4. Do not duplicate gallery details in `USER-PROFILE.md`; summarize preferences, recent activity, and stable interests from `IMAGE-MEMORY.md`.
