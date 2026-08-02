---
name: scheduled-automation
description: Schedule app automation tasks such as opening an app and performing actions at a specific time.
metadata:
  {
    "xomniclaw": {
      "always": false,
      "emoji": "⏰",
      "skillKey": "scheduled-automation",
      "version": "1.0.0",
      "category": "automation"
    }
  }
---

# Scheduled Automation

Use this skill when the user wants the phone to do something **later**, **every day**, **on specific weekdays**, **on workdays**, or **at a fixed minute interval**.

## Extraction Workflow

For app-centric natural-language requests, use this two-stage workflow:

1. LLM extraction:
   - extract `app_name`
   - extract `operation`
   - extract `schedule_phrase`
2. Rule parsing:
   - let `schedule_app_task` parse `schedule_phrase`
   - convert it into `repeat`, `daily_time`, `days_of_week`, or `interval_minutes`
3. Ambiguity check:
   - if `app_name`, `operation`, or `schedule_phrase` is still ambiguous
   - let the tool return clarification questions first instead of creating the wrong task

Important boundary:

- This skill is only for creating, listing, or cancelling future schedules.
- When an existing schedule fires, do **not** call `schedule_app_task` again.
- The saved execution instruction must be immediate and one-shot, e.g. `打开小红书，然后搜索新闻总结后发给我`.
- Never keep words such as `每天晚上8点`, `每周三`, `定时`, or `到点` inside `operation`; those belong only in `schedule_phrase`.

Example:

- user request: `每周三早上10点打开小红书搜新闻并总结`
- LLM extraction:
  - `app_name = 小红书`
  - `operation = 搜新闻并总结`
  - `schedule_phrase = 每周三早上10点`
- rule parsing result:
  - `repeat = weekly`
  - `days_of_week = [3]`
  - `daily_time = 10:00`

Typical requests:

- "每天晚上12点打开微信给张三发消息"
- "每天中午12点打开小红书搜索 AI 新闻并总结"
- "每周三早上10点打开小红书搜索 AI 新闻并总结"
- "每个工作日上午10点打开企业微信提醒我打卡"
- "每隔45分钟打开某个 App 检查一次状态"
- "明天早上8点自动打开企业微信提醒我打卡"
- "帮我定时打开某个 APP 去做某事"
- "每天晚上扫描相册并更新用户画像"

## Preferred Tool

### `schedule_app_task`

Use `schedule_app_task` as the **high-level first choice** for natural-language scheduling requests about apps and follow-up operations.

It is better than the lower-level `schedule_task` when the request is naturally phrased as:

- at what time
- open which app
- then do what

### Recommended mapping

If the user says:

- "每天晚上12点打开微信去做 X 操作"

Prefer extracting fields first, then call:

```json
{
  "action": "create",
  "task_name": "daily-wechat-task",
  "app_name": "微信",
  "operation": "做 X 操作",
  "schedule_phrase": "每天晚上12点"
}
```

## Tool Parameters

### `schedule_app_task`

- `action`: `create` | `list` | `cancel`
- `task_name`: task display name
- `app_name`: target app name, e.g. `微信`
- `package_name`: optional package name for higher precision
- `operation`: what to do after opening the app
- `schedule_phrase`: preferred natural-language schedule phrase extracted by the LLM, e.g. `每周三早上10点`
- `repeat`: `daily` | `once` | `weekly` | `workday` | `interval`
- `time_text`: backward-compatible alias of `schedule_phrase`
- `days_of_week`: used with `repeat=weekly`, e.g. `["mon", "wed"]` or `["周三"]`
- `interval_minutes`: used with `repeat=interval`, e.g. `30` or `45`
- `run_at`: one-time target time
- `delay_seconds`: one-time delay
- `task_id`: used when cancelling

## When to fall back to `schedule_task`

Use lower-level `schedule_task` when:

- the task is not app-centric
- the user wants to schedule a generic agent instruction
- the execution target is not just "open app then do something"
- the task is a memory maintenance workflow such as gallery syncing or profile rebuilding

### Example: schedule gallery memory sync

If the user says:

- "每天晚上 10 点扫描相册并更新用户画像"

Prefer using `schedule_task` with a clear instruction that tells the agent to use `gallery_memory`:

```json
{
  "action": "create",
  "task_name": "daily-gallery-memory-sync",
  "instruction": "使用 gallery_memory 工具同步相册中的新增图片记忆，并更新用户画像。",
  "repeat": "daily",
  "daily_time": "22:00"
}
```

## Best Practices

1. Prefer `schedule_app_task` for "定时打开 App 并操作" requests.
2. Preserve the user's original business intent inside `operation`.
3. If the target app is ambiguous, ask for clarification or include `package_name`.
4. For first-time setup, remind the user to verify the task once on a real device.
5. When the task is critical, suggest checking exact alarm permission and background restrictions.
6. If the extracted fields still look vague, do not force task creation; let the tool ask follow-up questions first.
7. Creation-time query and execution-time instruction are different: creation uses `schedule_phrase`; execution must contain only the action to perform now.

## Example Commands

### Daily task

```json
{
  "action": "create",
  "task_name": "daily-xhs-news",
  "app_name": "小红书",
  "operation": "搜索 AI 新闻，并总结前三条结果",
  "schedule_phrase": "每天中午12点"
}
```

### Weekly task

```json
{
  "action": "create",
  "task_name": "weekly-xhs-news",
  "app_name": "小红书",
  "operation": "搜索 AI 新闻，并总结前三条结果",
  "schedule_phrase": "每周三早上10点"
}
```

### Workday task

```json
{
  "action": "create",
  "task_name": "workday-checkin",
  "app_name": "企业微信",
  "operation": "提醒我打卡",
  "schedule_phrase": "每个工作日上午10点"
}
```

### Fixed interval task

```json
{
  "action": "create",
  "task_name": "interval-status-check",
  "app_name": "小红书",
  "operation": "检查一次首页热点并总结",
  "schedule_phrase": "每隔45分钟"
}
```

### One-time task

```json
{
  "action": "create",
  "task_name": "wechat-reminder-tonight",
  "app_name": "微信",
  "operation": "给张三发送消息：明天记得交日报",
  "repeat": "once",
  "delay_seconds": 600
}
```

### Cancel task

```json
{
  "action": "cancel",
  "task_id": "your-task-id"
}
```

## Verification Hint

After creating a task, you can use `schedule_app_task(action="list")` to confirm:

- task exists
- next trigger time is correct
- generated instruction matches the user's intent
