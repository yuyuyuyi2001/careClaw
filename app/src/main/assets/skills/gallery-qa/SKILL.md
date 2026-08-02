---
name: gallery-qa
description: Consume the full memory/IMAGE-MEMORY.md file for all gallery-image questions and operations instead of using retrieval-first search.
metadata:
  {
    "xomniclaw": {
      "always": false,
      "emoji": "🖼️",
      "skillKey": "gallery-qa",
      "version": "1.0.0",
      "category": "memory"
    }
  }
---

# Gallery QA

Use this skill for all gallery-image consumption tasks, including:

- `今天我拍了什么照片`
- `我最近拍过什么`
- `我拍过和猫有关的照片吗`
- `帮我把所有截图整理出来`
- `把猫主题图片复制到某个文件夹`

This skill is the primary consumer of `memory/IMAGE-MEMORY.md`.
It does **not** maintain or rebuild image memories. If the user explicitly asks to scan, sync, refresh, or rebuild image memories, use `gallery-memory` instead.

## Source Of Truth

The source of truth is:

- `memory/IMAGE-MEMORY.md`

Each entry is compact and should be read as one whole item:

- title line: the image filename
- `time`: capture or creation time
- `album`: where the image lives in the gallery (MediaStore bucket / album display name)
- `summary`: short semantic description

## Core Workflow

When this skill applies, follow this workflow strictly:

1. Load the full `memory/IMAGE-MEMORY.md` file into context with `memory_get`.
2. Treat the entire file as the candidate set.
3. Read the entries directly from top to bottom and find the entries relevant to the user request.
4. For question answering, summarize the matched entries in natural language.
5. For operations such as copy / move / add to folder / add to album, use the filename in each matched entry for later execution. Use `album` when the user asks which album or folder the image came from.

## Hard Rules

1. Do **not** start with retrieval or search when the full compact `IMAGE-MEMORY.md` file can be loaded directly.
2. Do **not** treat `image_memory_search_entries` as the primary path. It is legacy.
3. Do **not** use `find` as the primary topic filter when `IMAGE-MEMORY.md` already exists.
4. Do **not** guess a wildcard filename prefix from the first few entries.
5. Do **not** call `gallery_memory` unless the user explicitly asks to sync, scan, refresh, or rebuild image memories.

## Answering Rules

1. Do not answer with only file paths, line numbers, or entry ids.
2. Use the `summary` field as the main semantic evidence.
3. Use `time` when the user asks about today / yesterday / recent / date-scoped photos.
4. If multiple images match, group or summarize them clearly.
5. If the user asks for `所有` / `全部` / `都`, inspect the whole file before finalizing.

## Operation Rules

For operations such as:

- `把猫主题的图片复制到某个文件夹`
- `把所有截图整理到一个相册`
- `把发票照片都归档`

follow this exact order:

1. load the full `memory/IMAGE-MEMORY.md`
2. identify the matching entries from filename / time / album / summary
3. extract the filenames of the matched entries
4. use those filenames for later copy/move/add operations

## Output Style

Prefer concise natural language.

Good answer style:

- `今天你拍了两张照片，一张是桌面附近的宠物照片，另一张是带文字内容的页面截图。`

Bad answer style:

- `在 memory/IMAGE-MEMORY.md 的第 120-140 行找到了结果。`
