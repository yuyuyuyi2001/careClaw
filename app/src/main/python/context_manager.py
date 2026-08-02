"""Context pruning / compaction — port of AgentLoop.kt's 6 context management methods.

All methods operate on a list of message dicts:
    {"role": str, "content": str, "tool_calls": list|None, "tool_call_id": str|None, "name": str|None}
"""

from utils import (
    compact_text_with_marker,
    estimate_context_chars,
    is_likely_premature_plan_text,
)

# Constants aligned with OmniClaw DEFAULT_CONTEXT_PRUNING_SETTINGS
SOFT_TRIM_RATIO = 0.3
HARD_CLEAR_RATIO = 0.5
MIN_PRUNABLE_TOOL_CHARS = 50_000
KEEP_LAST_ASSISTANTS = 3
SOFT_TRIM_MAX_CHARS = 4_000
SOFT_TRIM_HEAD_CHARS = 1_500
SOFT_TRIM_TAIL_CHARS = 1_500
HARD_CLEAR_PLACEHOLDER = "[Old tool result content cleared]"
HARD_KEEP_RECENT_DIALOG_TURNS = 5

TOOL_LOG_SOFT_MAX_CHARS = 1_600
TOOL_LOG_HARD_MAX_CHARS = 500
INTERMEDIATE_ASSISTANT_SOFT_MAX_CHARS = 700
INTERMEDIATE_ASSISTANT_HARD_MAX_CHARS = 280
DIALOG_MESSAGE_HARD_MAX_CHARS = 2_200

def _truncate_middle(text, max_len=52):
    """路径/URI 中间省略，降低 token。"""
    if text is None:
        return ""
    s = str(text)
    if len(s) <= max_len:
        return s
    head = max(max_len // 2 - 2, 8)
    tail = max_len - head - 3
    if tail <= 0:
        return s[:max_len]
    return s[:head] + "..." + s[-tail:]


def _compact_old_ui_tree_content(content):
    """Compress historical snapshots to a tiny summary while keeping the header."""
    first_line = (content or "").splitlines()[0].strip() if content else ""
    header = first_line or "[snapshot summary unavailable]"
    return (
        f"{header}\n"
        "[历史 UI tree 已压缩：仅保留最近一次完整 snapshot，"
        "前面的界面树已省略以节省上下文。]"
    )


def _is_critical_ui_tree_tool_result(msg):
    """Preserve full UI observation payloads for the model.

    `device(action="snapshot")` returns the current UI tree and refs. If this
    payload is compacted into a head/tail marker, the model may lose critical
    refs or structural hints and then operate on the wrong element.
    """
    if msg.get("role") != "tool":
        return False

    tool_name = (msg.get("name") or "").strip()
    content = msg.get("content") or ""

    if tool_name in ("get_view_tree", "screenshot"):
        return True

    if tool_name == "device" and "[snapshot_id=" in content:
        return True

    return False


def _find_latest_critical_ui_tree_index(messages):
    """Only the most recent full UI tree should remain in model context."""
    for i in range(len(messages) - 1, -1, -1):
        if _is_critical_ui_tree_tool_result(messages[i]):
            return i
    return -1


def _find_last_tool_message_index(messages):
    """时间线上最后一条 tool 消息的索引；用于保留最近一次 skill / 工具输出全文。"""
    for i in range(len(messages) - 1, -1, -1):
        if messages[i].get("role") == "tool":
            return i
    return -1


def _first_non_system_index(messages):
    for i, m in enumerate(messages):
        if m["role"] != "system":
            return i
    return len(messages)


def _find_keep_boundary_index(messages, keep_count):
    """Index before which we can prune. Keep last N assistant messages untouched."""
    count = 0
    for i in range(len(messages) - 1, -1, -1):
        if messages[i]["role"] == "assistant":
            count += 1
            if count >= keep_count:
                return i
    return 0


def _count_conversation_turns(messages):
    return sum(1 for m in messages if m["role"] == "user")


def _find_preserve_start_for_recent_turns(messages, keep_turns):
    if keep_turns <= 0:
        return _first_non_system_index(messages)
    seen = 0
    for i in range(len(messages) - 1, -1, -1):
        if messages[i]["role"] == "user":
            seen += 1
            if seen >= keep_turns:
                return i
    return _first_non_system_index(messages)


# ---- public API ----

def prune_old_tool_results(messages, context_window_tokens):
    """Soft-trim / hard-clear old large tool results."""
    budget_chars = int(context_window_tokens * 4 * 0.75)
    current_chars = estimate_context_chars(messages)
    usage_ratio = current_chars / max(budget_chars, 1)

    if usage_ratio < SOFT_TRIM_RATIO:
        return 0, 0

    keep_after = _find_keep_boundary_index(messages, KEEP_LAST_ASSISTANTS)
    trimmed = 0
    cleared = 0

    for i in range(min(keep_after, len(messages))):
        msg = messages[i]
        if msg["role"] != "tool":
            continue
        content = msg.get("content") or ""
        if len(content) < MIN_PRUNABLE_TOOL_CHARS:
            continue

        if usage_ratio >= HARD_CLEAR_RATIO:
            messages[i] = {**msg, "content": HARD_CLEAR_PLACEHOLDER}
            cleared += 1
        else:
            if len(content) > SOFT_TRIM_MAX_CHARS:
                head = content[:SOFT_TRIM_HEAD_CHARS]
                tail = content[-SOFT_TRIM_TAIL_CHARS:]
                omitted = len(content) - SOFT_TRIM_HEAD_CHARS - SOFT_TRIM_TAIL_CHARS
                messages[i] = {
                    **msg,
                    "content": f"{head}\n\n[...{omitted} chars trimmed...]\n\n{tail}",
                }
                trimmed += 1

    return trimmed, cleared


def prioritize_tool_and_intermediate_compaction(messages, keep_turns, aggressive=False):
    """Compact tool logs and intermediate assistant text."""
    if not messages:
        return 0, 0
    preserve_start = _find_preserve_start_for_recent_turns(messages, keep_turns)
    latest_ui_tree_index = _find_latest_critical_ui_tree_index(messages)
    # 最后一条 tool 不做「tool log compacted」截断，避免刚执行完的 skill 在 UI/上下文里被压成省略标记
    last_tool_index = _find_last_tool_message_index(messages)
    tool_max = TOOL_LOG_HARD_MAX_CHARS if aggressive else TOOL_LOG_SOFT_MAX_CHARS
    asst_max = INTERMEDIATE_ASSISTANT_HARD_MAX_CHARS if aggressive else INTERMEDIATE_ASSISTANT_SOFT_MAX_CHARS

    compacted_tool = 0
    compacted_asst = 0

    for i in range(len(messages)):
        msg = messages[i]
        role = msg["role"]
        content = msg.get("content") or ""

        if role == "tool":
            # UI tree / snapshot belongs to the model's working memory, not to
            # disposable debug logs. Keep only the latest full snapshot intact;
            # older snapshots are reduced to a short summary to save context.
            if _is_critical_ui_tree_tool_result(msg):
                if i == latest_ui_tree_index:
                    continue
                messages[i] = {
                    **msg,
                    "content": _compact_old_ui_tree_content(content),
                }
                compacted_tool += 1
                continue
            # 跳过最近一次 tool 的字符截断（与 UI snapshot 保留策略独立）
            if i == last_tool_index:
                continue
            if len(content) > tool_max:
                messages[i] = {
                    **msg,
                    "content": compact_text_with_marker(content, tool_max, "tool log compacted"),
                }
                compacted_tool += 1

        elif role == "assistant":
            has_tc = bool(msg.get("tool_calls"))
            looks_intermediate = has_tc or is_likely_premature_plan_text(content)
            should_compact = i < preserve_start or aggressive
            if looks_intermediate and should_compact and len(content) > asst_max:
                messages[i] = {
                    **msg,
                    "content": compact_text_with_marker(
                        content, asst_max, "intermediate assistant compacted"
                    ),
                }
                compacted_asst += 1

    return compacted_tool, compacted_asst


def compact_preserved_dialog_contents(messages, keep_turns):
    preserve_start = _find_preserve_start_for_recent_turns(messages, keep_turns)
    compacted = 0
    for i in range(preserve_start, len(messages)):
        msg = messages[i]
        if msg["role"] in ("user", "assistant"):
            content = msg.get("content") or ""
            if len(content) > DIALOG_MESSAGE_HARD_MAX_CHARS:
                messages[i] = {
                    **msg,
                    "content": compact_text_with_marker(
                        content, DIALOG_MESSAGE_HARD_MAX_CHARS, "dialog content compacted"
                    ),
                }
                compacted += 1
    return compacted


def _remove_oldest_turn_chunk_before(messages, preserve_start):
    first_ns = _first_non_system_index(messages)
    if first_ns >= preserve_start:
        return False

    chunk_start = -1
    for i in range(first_ns, preserve_start):
        if messages[i]["role"] == "user":
            chunk_start = i
            break
    if chunk_start < 0:
        chunk_start = first_ns

    chunk_end = preserve_start
    for i in range(chunk_start + 1, preserve_start):
        if messages[i]["role"] == "user":
            chunk_end = i
            break
    if chunk_end <= chunk_start:
        return False

    del messages[chunk_start:chunk_end]
    return True


def aggressive_trim_messages(messages, budget_chars, keep_turns):
    """Three-stage aggressive trim when over budget."""
    max_history_budget = int(budget_chars * 0.5)
    total = estimate_context_chars(messages)
    if total <= max_history_budget:
        return

    # Stage A: compact tool + intermediate
    prioritize_tool_and_intermediate_compaction(messages, keep_turns=keep_turns, aggressive=True)
    if estimate_context_chars(messages) <= max_history_budget:
        return

    # Stage B: drop old turn chunks
    iters = 0
    while estimate_context_chars(messages) > max_history_budget and iters < 30:
        ps = _find_preserve_start_for_recent_turns(messages, keep_turns)
        if not _remove_oldest_turn_chunk_before(messages, ps):
            break
        iters += 1

    # Stage C: compact preserved dialog content
    if estimate_context_chars(messages) > max_history_budget:
        compact_preserved_dialog_contents(messages, keep_turns)


def enforce_context_budget(messages, context_window_tokens):
    """Top-level context management pipeline mirroring AgentLoop.kt."""
    budget_chars = int(context_window_tokens * 4 * 0.75)
    keep_turns = HARD_KEEP_RECENT_DIALOG_TURNS

    # Step 1.5: compact first
    prioritize_tool_and_intermediate_compaction(messages, keep_turns=keep_turns, aggressive=False)

    # Step 2: prune old tool results
    prune_old_tool_results(messages, context_window_tokens)

    # Step 4: aggressive trim if needed
    total = estimate_context_chars(messages)
    if total > budget_chars:
        aggressive_trim_messages(messages, budget_chars, keep_turns=keep_turns)
