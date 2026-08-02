---
name: gallery-memory
description: Sync gallery images into long-term memory files and rebuild the user profile from photo-derived memories.
metadata:
  {
    "xomniclaw": {
      "always": false,
      "emoji": "🖼️",
      "skillKey": "gallery-memory",
      "version": "1.0.0",
      "category": "memory"
    }
  }
---

# Gallery Memory

Use this skill when the user wants to:

- scan new images from the phone gallery
- extract memories from screenshots or photos
- rebuild a user profile from image memories
- schedule periodic gallery memory updates

Do not use this skill as the first choice for questions like `今天我拍了什么照片` or `我最近拍过什么截图`.
For image-question answering or gallery-image operations based on existing `memory/IMAGE-MEMORY.md`, prefer `gallery-qa`.

This skill is responsible for **producing and maintaining** image memories.
It is not the primary consumer for gallery-image questions or operations once `memory/IMAGE-MEMORY.md` already exists.
When image memories are already available:

- `gallery-qa` should consume them
- the full `memory/IMAGE-MEMORY.md` file should be loaded into context
- later execution should rely on the filename stored in each entry, not wildcard filename guesses

## Preferred Tool

### `gallery_memory`

Use `gallery_memory` as the direct tool for this workflow.

Supported actions:

- `sync`: scan new gallery images, summarize them, and rewrite `memory/IMAGE-MEMORY.md` in compact format (filename, time, album, summary)
- `build_profile`: rebuild `memory/USER-PROFILE.md` from existing memories
- `status`: inspect cursor and output file availability
- `reset_cursor`: clear the incremental scan cursor
- `clear_image_memories`: reset `memory/IMAGE-MEMORY.md` to the initial template
- `clear_user_profile`: reset `memory/USER-PROFILE.md` to the initial template
- `reset_all`: clear both files and reset the scan cursor

## Recommended Calls

### Sync and rebuild profile

```json
{
  "action": "sync",
  "max_images": 10,
  "update_profile": true
}
```

### Only rebuild profile

```json
{
  "action": "build_profile"
}
```

### Check status

```json
{
  "action": "status"
}
```

## Scheduling Pattern

To run this periodically, combine it with `schedule_task`.

Example idea:

- create a scheduled task
- set `instruction` to a natural-language request that clearly tells the agent to use `gallery_memory`

Example instruction:

`使用 gallery_memory 工具同步相册中的新增图片记忆，并更新用户画像。`

## Best Practices

1. Start with a small `max_images` value for the first real-device verification.
2. The maintained `IMAGE-MEMORY.md` format should stay compact: filename, time, album (image source bucket), summary, and a clear separator between entries.
3. Keep each image summary short enough that `gallery-qa` can load the entire file into context.
4. Prefer `sync` before `build_profile`, so the profile reflects the newest image memories.
5. If the user asks for daily or hourly gallery syncing, combine this skill with `scheduled-automation`.
6. If the user mentions privacy concerns, remind them that sensitive content should be filtered and the feature should only run with explicit authorization.
