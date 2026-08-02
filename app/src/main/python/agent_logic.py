"""Core Agent Loop logic — Python-side entry point called from Kotlin via Chaquopy.

The Kotlin AgentLoop.kt acts as a thin bridge: it creates a KotlinBridge object and
passes it here.  All business logic (iteration control, context management, loop
detection, incremental sensing, nudge injection, error tracking) lives in this file.

Public entry:  run_agent(bridge, system_prompt, user_message, context_history_json,
                         reasoning_enabled, max_iterations, context_window_tokens)
Returns: JSON string of {"final_content": str, "tools_used": [str], "iterations": int,
                          "messages": [...]}
"""

import json
import time
import re

from loop_detector import LoopDetector, LoopDetectionResult
from context_manager import enforce_context_budget, HARD_KEEP_RECENT_DIALOG_TURNS
from session_logger import SessionLogger
from utils import (
    is_likely_premature_plan_text,
    is_observe_ui_tool_call,
    is_mutating_ui_tool_call,
    parse_tool_args_json,
    estimate_context_chars,
    TEXT_ONLY_CONTINUATION_NUDGE,
    MAX_TEXT_ONLY_CONTINUATION_NUDGES,
)

MAX_CONSECUTIVE_ERRORS = 3

_REASONING_TAG_RE = re.compile(
    r"<(think|thinking|reasoning|reflection|inner_thoughts)>.*?</\1>",
    re.DOTALL | re.IGNORECASE,
)

# 将聊天态识别提示集中管理，避免业务关键词散落在逻辑分支中难以维护。
_CHAT_SNAPSHOT_INPUT_MARKERS_RAW = ("输入框",)
_CHAT_SNAPSHOT_INPUT_MARKERS_LOWER = ("editable=true", "edittext")
_CHAT_SNAPSHOT_SEND_MARKERS_RAW = ("发送",)
_CHAT_SNAPSHOT_SEND_MARKERS_LOWER = ("send",)


def _strip_reasoning_tags(text):
    if not text:
        return text
    return _REASONING_TAG_RE.sub("", text).strip()


def _format_reasoning_for_log(text, max_chars=4000):
    """将推理内容压平成单行并限长，避免日志被换行切碎或无限膨胀。"""
    if not text:
        return ""
    normalized = re.sub(r"\s+", " ", str(text)).strip()
    if len(normalized) <= max_chars:
        return normalized
    # 超长时截断并附带标记，保证主日志可读且可快速定位前半段关键推理。
    return normalized[: max_chars - 1] + "…"


def _safe_get_skill_selection_trace(bridge):
    """从 KotlinBridge 读取技能筛选摘要；桥接版本不支持时静默降级。"""
    try:
        if hasattr(bridge, "get_skill_selection_trace"):
            trace = bridge.get_skill_selection_trace()
            if trace:
                return str(trace).strip()
    except Exception:
        # 兼容老版本 bridge：不因调试日志能力缺失影响主流程。
        pass
    return ""


def _is_snapshot_ref_error(err: str) -> bool:
    """Detect typical snapshot/ref-related failures where visual fallback is desired."""
    if not err:
        return False
    msg = err.lower()
    patterns = [
        "已过期",
        "ref '",
        "ref \"",
        "不存在于最近一次 snapshot",
        "无障碍服务未开启",
        "无障碍服务未启用",
        "获取 ui 树失败",
        "accessibility",
        "dumpviewtree failed",
    ]
    return any(p.lower() in msg for p in patterns)


_SEND_HINTS = ("发送", "发 送", "send")


def _is_send_like_device_tap(fn_name, args):
    if fn_name != "device":
        return False
    if str(args.get("action", "")).lower() != "act":
        return False
    if str(args.get("kind", "")).lower() != "tap":
        return False
    target = str(args.get("target", "")).strip().lower()
    if target and any(h in target for h in _SEND_HINTS):
        return True
    return False


def _detect_chat_snapshot_flags(result_content):
    """
    识别 snapshot 返回中与聊天页相关的轻量特征。

    返回:
    - in_chat_context: 是否看起来处于聊天上下文
    - input_seen: 是否看到输入框特征
    - send_seen: 是否看到发送特征
    """
    text = result_content or ""
    lower = text.lower()

    input_seen = any(marker in text for marker in _CHAT_SNAPSHOT_INPUT_MARKERS_RAW) or any(
        marker in lower for marker in _CHAT_SNAPSHOT_INPUT_MARKERS_LOWER
    )
    send_seen = any(marker in text for marker in _CHAT_SNAPSHOT_SEND_MARKERS_RAW) or any(
        marker in lower for marker in _CHAT_SNAPSHOT_SEND_MARKERS_LOWER
    )
    in_chat_context = input_seen or send_seen
    return in_chat_context, input_seen, send_seen


def _update_runtime_state_after_tool(runtime_state, fn_name, args, result_success, result_content, metadata):
    """根据工具返回更新轻量运行态，避免 agent 在同一任务内丢失上下文。"""
    if not result_success:
        return

    if fn_name != "device":
        return

    action = str(args.get("action", "")).lower()
    if action == "snapshot":
        # 不再依赖 package 判断：只要成功执行 snapshot，就视为完成一次发送后观察。
        if runtime_state.get("pending_send_verify"):
            runtime_state["pending_send_verify"] = False
            runtime_state["last_send_verified_at_iter"] = runtime_state.get("iteration", 0)

        # 通过 UI 文本特征识别是否进入聊天上下文，避免前台包名瞬时抖动误判。
        in_chat_context, input_seen, send_seen = _detect_chat_snapshot_flags(result_content)
        if in_chat_context:
            runtime_state["in_feishu_chat"] = True
            if input_seen:
                runtime_state["feishu_input_seen"] = True
            if send_seen:
                runtime_state["feishu_send_seen"] = True
    elif action == "act":
        kind = str(args.get("kind", "")).lower()
        if kind == "type" and runtime_state.get("in_feishu_chat"):
            runtime_state["typed_in_feishu_chat"] = True
        if kind == "tap" and (
            _is_send_like_device_tap(fn_name, args) or bool(metadata.get("requires_post_send_snapshot_verify"))
        ):
            runtime_state["pending_send_verify"] = True
            runtime_state["last_send_tap_iter"] = runtime_state.get("iteration", 0)


def _build_runtime_state_hint(runtime_state):
    """构造短状态提示，提醒模型延续当前任务而非回到起点。"""
    hints = []

    if runtime_state.get("in_feishu_chat"):
        hints.append("已进入飞书会话上下文，不要重复从“打开飞书”开始")
    if runtime_state.get("typed_in_feishu_chat"):
        hints.append("本轮会话已执行过输入，优先继续发送与验收，不要重新输入覆盖")
    if runtime_state.get("pending_send_verify"):
        hints.append("刚执行发送动作，下一步必须先观察（snapshot/screenshot）确认消息已发出")

    if not hints:
        return None
    return "系统状态记忆：" + "；".join(hints) + "。"


def _msg(role, content, tool_calls=None, tool_call_id=None, name=None):
    """统一消息构建：基础字段 + 可选字段。"""
    message = {"role": role, "content": content or ""}
    optional_fields = {
        "tool_calls": tool_calls,
        "tool_call_id": tool_call_id,
        "name": name,
    }
    for key, value in optional_fields.items():
        if value:
            message[key] = value
    return message


def _build_system_prompt_with_self_counting(system_prompt, reasoning_enabled):
    """
    Python 侧不再注入额外规则，避免产生多条 system 消息。
    规则统一维护在 assets/bootstrap（由 Kotlin 侧构建 system prompt 时加载）。
    """
    return (system_prompt or "").strip()


def _build_initial_messages(system_prompt, reasoning_enabled, context_history_json, user_message, logger):
    """构建会话初始 messages，减少 run_agent 主流程样板代码。"""
    messages = []
    effective_system_prompt = _build_system_prompt_with_self_counting(system_prompt, reasoning_enabled)
    messages.append(_msg("system", effective_system_prompt))
    logger.write(f"✅ System prompt added ({len(effective_system_prompt)} chars)")

    try:
        ctx_history = json.loads(context_history_json) if context_history_json else []
    except Exception:
        ctx_history = []

    if ctx_history:
        # thinking 与 Kotlin 侧一致：只存盘与 UI 恢复，不进入模型上文
        filtered = [m for m in ctx_history if m.get("role") not in ("system", "thinking")]
        messages.extend(filtered)
        logger.write(f"✅ Context history added: {len(filtered)} messages")

    messages.append(_msg("user", user_message))
    logger.write(f"✅ User message: {user_message}")
    return messages


def _is_stopped_error(err_msg):
    return "__AGENT_STOPPED__" in (err_msg or "")


def _accumulate_llm_usage(totals, usage_obj):
    """
    将 Kotlin call_llm 返回中的 usage（OpenAI 风格）累加到本轮 Agent 合计。
    totals: dict，含 prompt、completion、total 三键，原地修改。
    """
    if not usage_obj or not isinstance(usage_obj, dict):
        return
    pt = int(usage_obj.get("prompt_tokens") or 0)
    ct = int(usage_obj.get("completion_tokens") or 0)
    tt = usage_obj.get("total_tokens")
    if tt is not None:
        ttn = int(tt)
    else:
        ttn = pt + ct
    totals["prompt"] += pt
    totals["completion"] += ct
    totals["total"] += ttn


# ---------------------------------------------------------------------------
# Main entry — called from Kotlin
# ---------------------------------------------------------------------------

def run_agent(
    bridge,
    system_prompt,
    user_message,
    context_history_json,
    reasoning_enabled,
    max_iterations,
    context_window_tokens,
):
    """Main Python-side agent loop.

    Args:
        bridge: KotlinBridge object with methods call_llm, execute_tool, emit_progress,
                get_tool_definitions, strip_reasoning_tags.
        system_prompt: str
        user_message: str
        context_history_json: JSON string — list of Message dicts
        reasoning_enabled: bool
        max_iterations: int
        context_window_tokens: int

    Returns:
        JSON string with keys: final_content, tools_used, iterations, messages
    """
    logger = SessionLogger()
    logger.init_session(user_message)

    loop_detector = LoopDetector()
    error_tracker = []

    logger.write("========== Agent Loop 开始 (Python) ==========")
    logger.write(f"Max iterations: {max_iterations}")
    logger.write(f"Reasoning: {'enabled' if reasoning_enabled else 'disabled'}")
    skill_trace = _safe_get_skill_selection_trace(bridge)
    if skill_trace:
        logger.write("🧩 Skill Selection Trace (from Kotlin):")
        for line in skill_trace.splitlines():
            logger.write(f"  {line}")

    # --- Build message list ---
    messages = _build_initial_messages(
        system_prompt=system_prompt,
        reasoning_enabled=reasoning_enabled,
        context_history_json=context_history_json,
        user_message=user_message,
        logger=logger,
    )

    # --- Loop state ---
    tools_used = []
    final_content = None
    text_only_nudges = 0
    runtime_state = {}
    # 多轮 LLM 调用的 token 累加（依赖 Kotlin bridge 在每轮 JSON 中附带 usage）
    usage_totals = {"prompt": 0, "completion": 0, "total": 0}

    # max_iterations==0 时 range(1,1) 为空，for 不会给 iteration 赋值，文末记录/JSON 会 NameError，故先设默认值。
    iteration = 0
    for iteration in range(1, max_iterations + 1):
        iter_start = time.time()
        runtime_state["iteration"] = iteration
        logger.write(f"========== Iteration {iteration} ==========")

        # Check stop signal from Kotlin at the top of each iteration
        if bridge.is_stop_requested():
            logger.write("🛑 已收到用户停止信号，退出循环")
            final_content = "已按用户请求停止。"
            break

        # Emit iteration progress
        bridge.emit_progress("iteration", json.dumps({"number": iteration}))

        # --- Context management ---
        enforce_context_budget(messages, context_window_tokens)

        # 注入短状态记忆（仅本轮 LLM 上文，不入库）：否则会以「用户消息」写入会话，
        # 主界面会出现多条重复的“系统状态记忆”气泡。
        state_hint = _build_runtime_state_hint(runtime_state)
        payload_messages = messages
        if state_hint:
            payload_messages = messages + [_msg("user", state_hint)]
            logger.write(f"🧭 Runtime state hint injected (non-persistent): {state_hint}")

        # --- LLM call ---
        bridge.emit_progress("thinking", json.dumps({"iteration": iteration}))
        llm_start = time.time()

        try:
            response_json = bridge.call_llm(json.dumps(payload_messages), reasoning_enabled, iteration)
        except Exception as e:
            err_msg = str(e)
            # User-requested stop: exit cleanly
            if _is_stopped_error(err_msg):
                logger.write("🛑 LLM 调用时收到停止信号，退出循环")
                final_content = "已按用户请求停止。"
                break

            logger.write(f"❌ LLM 调用失败: {err_msg}")

            if _track_error(error_tracker, err_msg):
                final_content = f"任务失败: 连续 {MAX_CONSECUTIVE_ERRORS} 次相同错误 - {err_msg}"
                break

            if "timeout" in err_msg.lower():
                messages.append(_msg("user", "系统提示: LLM 调用超时，请尝试简化任务或分步执行"))
                continue

            final_content = f"❌ 执行出错\n\n**错误信息**: {err_msg}"
            break

        llm_duration_ms = int((time.time() - llm_start) * 1000)

        try:
            response = json.loads(response_json)
        except Exception:
            logger.write(f"❌ 无法解析 LLM 响应 JSON")
            final_content = "❌ 内部错误：无法解析 LLM 响应"
            break

        _accumulate_llm_usage(usage_totals, response.get("usage"))
        logger.write(f"✅ LLM 响应已收到 [耗时: {llm_duration_ms}ms]")

        # Reasoning
        thinking = response.get("thinking_content")
        if thinking:
            logger.write(f"🧠 Reasoning ({len(thinking)} chars)")
            logger.write(f"🧠 Reasoning Content: {_format_reasoning_for_log(thinking)}")
            bridge.emit_progress(
                "reasoning",
                json.dumps({"content": thinking[:2000], "llm_duration": llm_duration_ms}),
            )

        tool_calls = response.get("tool_calls") or []
        content = response.get("content")

        # --- Branch: has tool calls ---
        if tool_calls:
            logger.write(f"Function calls: {len(tool_calls)}")

            # Block reply: emit intermediate text
            intermediate_text = (content or "").strip()
            if intermediate_text:
                logger.write(f"📤 Block reply: {intermediate_text[:200]}...")
                bridge.emit_progress(
                    "block_reply",
                    json.dumps({"text": intermediate_text, "iteration": iteration}),
                )

            # 解析工具参数仅一次：同时用于 assistant message 与实际执行。
            parsed_tool_calls = []
            assistant_tool_calls = []
            for tc in tool_calls:
                normalized_args = parse_tool_args_json(tc.get("arguments", "{}"))
                args_json = json.dumps(normalized_args, ensure_ascii=False)
                parsed_tool_calls.append({
                    "id": tc["id"],
                    "name": tc["name"],
                    "args": normalized_args,
                    "args_json": args_json,
                })
                assistant_tool_calls.append({
                    "id": tc["id"],
                    "name": tc["name"],
                    "arguments": args_json,
                })
            messages.append(_msg("assistant", content, tool_calls=assistant_tool_calls))

            saw_observe = False
            saw_mutate = False
            total_exec_ms = 0
            should_stop = False

            def stop_tool_loop(content_to_set, log_message=None):
                """统一工具循环内的停止状态同步，避免重复赋值分支。"""
                nonlocal should_stop, final_content
                if log_message:
                    logger.write(log_message)
                should_stop = True
                final_content = content_to_set

            for tc in parsed_tool_calls:
                fn_name = tc["name"]
                args = tc.get("args", {})
                args_json = tc.get("args_json", "{}")
                tc_id = tc["id"]

                logger.write(f"🔧 Function: {fn_name}")
                logger.write(f"   Args: {args_json}")

                # --- Loop detection (pre-execution) ---
                det = loop_detector.detect(fn_name, args)
                if det.is_loop:
                    level_icon = "🚨" if det.is_critical else "⚠️"
                    logger.write(f"{level_icon} Loop detected: {det.detector} (count: {det.count})")

                    if det.is_critical:
                        messages.append(_msg("tool", det.message, tool_call_id=tc_id, name=fn_name))
                        bridge.emit_progress(
                            "loop_detected",
                            json.dumps({
                                "detector": det.detector, "count": det.count,
                                "message": det.message, "critical": True,
                            }),
                        )
                        stop_tool_loop(f"Task failed: {det.message}", "🛑 Critical loop, stopping")
                        break

                    # Warning: inject message but skip tool
                    messages.append(_msg("tool", det.message, tool_call_id=tc_id, name=fn_name))
                    bridge.emit_progress(
                        "loop_detected",
                        json.dumps({
                            "detector": det.detector, "count": det.count,
                            "message": det.message, "critical": False,
                        }),
                    )
                    continue

                # Record call
                loop_detector.record_call(fn_name, args, tool_call_id=tc_id)
                tools_used.append(fn_name)

                bridge.emit_progress(
                    "tool_call",
                    json.dumps({"name": fn_name, "arguments": args}),
                )

                # --- Execute tool (callback to Kotlin) ---
                exec_start = time.time()
                try:
                    result_json = bridge.execute_tool(fn_name, args_json)
                except Exception as e:
                    err_str = str(e)
                    if _is_stopped_error(err_str):
                        stop_tool_loop("已按用户请求停止。", "🛑 工具执行时收到停止信号，退出循环")
                        break
                    result_json = json.dumps({"success": False, "content": err_str})

                exec_ms = int((time.time() - exec_start) * 1000)
                total_exec_ms += exec_ms

                try:
                    result = json.loads(result_json)
                except Exception:
                    result = {"success": False, "content": result_json}

                result_success = result.get("success", False)
                result_content = result.get("content", str(result))
                result_metadata = result.get("metadata") or {}
                if not isinstance(result_metadata, dict):
                    result_metadata = {}

                logger.write(f"   Result: {result_success}, {result_content[:200]}")
                logger.write(f"   ⏱️ {exec_ms}ms")

                _update_runtime_state_after_tool(
                    runtime_state=runtime_state,
                    fn_name=fn_name,
                    args=args,
                    result_success=result_success,
                    result_content=result_content,
                    metadata=result_metadata,
                )

                # For snapshot/observe tools: print the full accessibility XML tree to the log.
                # 由于 screenshot(action="screenshot") 的返回内容本身已经在 Result 行打印，
                # 并不是真正的 XML tree，这里仅对 snapshot 等真正的无障碍树结果启用该块日志。
                if result_success and is_observe_ui_tool_call(fn_name, args):
                    # device(action="screenshot") 现在主要用于视觉 grounding，不再重复打印整体结果。
                    if not (fn_name == "device" and str(args.get("action", "")).lower() == "screenshot"):
                        logger.write("--- [ACCESSIBILITY TREE START] ---")
                        logger.write(result_content)
                        logger.write("--- [ACCESSIBILITY TREE END] ---")

                # Error tracking
                if not result_success:
                    err = f"{fn_name}: {result_content}"
                    if _track_error(error_tracker, err):
                        should_stop = True
                        final_content = f"任务失败: 连续 {MAX_CONSECUTIVE_ERRORS} 次相同错误 - {err}"
                        messages.append(_msg("tool", final_content, tool_call_id=tc_id, name=fn_name))
                        break

                    # Heuristic: any snapshot/ref-related failures should recommend
                    # using screenshot+query for visual grounding on the next iteration.
                    if fn_name == "device" and _is_snapshot_ref_error(result_content):
                        logger.write("📸 检测到 snapshot/ref 相关错误，建议下一轮优先使用 screenshot+query 做像素级视觉校验。")
                        hint = (
                            "系统建议：上一步依赖 snapshot/ref 的操作失败。"
                            "下一轮请先 device(action=\"snapshot\") 刷新 ref；若仍不足，再使用 device(action=\"screenshot\", query=\"与任务相关的控件描述\") "
                            "做视觉定位，然后基于最新 snapshot 中的 ref 继续操作。"
                        )
                        messages.append(_msg("tool", hint, tool_call_id=tc_id, name=fn_name))
                else:
                    error_tracker.clear()

                if result_success and is_observe_ui_tool_call(fn_name, args):
                    saw_observe = True

                if result_success and is_mutating_ui_tool_call(fn_name, args):
                    saw_mutate = True

                # Record outcome
                loop_detector.record_outcome(
                    fn_name, args, result_content,
                    error=None if result_success else Exception(result_content),
                    tool_call_id=tc_id,
                )

                # Add tool result message
                result_str = result_content if isinstance(result_content, str) else json.dumps(result)
                messages.append(_msg("tool", result_str, tool_call_id=tc_id, name=fn_name))

                bridge.emit_progress(
                    "tool_result",
                    json.dumps({"name": fn_name, "result": result_str[:500], "exec_duration": exec_ms}),
                )

                # Stop tool
                if fn_name == "stop":
                    stopped = result.get("metadata", {}).get("stopped", False)
                    if stopped:
                        stop_tool_loop(result_content)
                        break

            if should_stop:
                break

            iter_ms = int((time.time() - iter_start) * 1000)
            logger.write(f"⏱️ 本轮迭代总耗时: {iter_ms}ms (LLM: {llm_duration_ms}ms, 执行: {total_exec_ms}ms)")
            bridge.emit_progress(
                "iteration_complete",
                json.dumps({
                    "number": iteration, "iteration_duration": iter_ms,
                    "llm_duration": llm_duration_ms, "exec_duration": total_exec_ms,
                }),
            )
            continue

        # --- Branch: no tool calls — possible final answer or premature plan ---
        stripped = _strip_reasoning_tags(content) if content else content

        should_nudge = (
            stripped
            and tools_used
            and text_only_nudges < MAX_TEXT_ONLY_CONTINUATION_NUDGES
            and is_likely_premature_plan_text(stripped)
        )

        if should_nudge:
            text_only_nudges += 1
            logger.write(
                f"🔁 无 tool_calls 但文本仍像未完成任务"
                f"（续跑提示 {text_only_nudges}/{MAX_TEXT_ONLY_CONTINUATION_NUDGES}）"
            )
            messages.append(_msg("assistant", stripped))
            messages.append(_msg("user", TEXT_ONLY_CONTINUATION_NUDGE))
            if stripped.strip():
                bridge.emit_progress(
                    "block_reply",
                    json.dumps({"text": stripped, "iteration": iteration}),
                )
            iter_ms = int((time.time() - iter_start) * 1000)
            bridge.emit_progress(
                "iteration_complete",
                json.dumps({
                    "number": iteration, "iteration_duration": iter_ms,
                    "llm_duration": llm_duration_ms, "exec_duration": 0,
                }),
            )
            continue

        # Final answer
        final_content = stripped
        messages.append(_msg("assistant", final_content))
        logger.write(f"Final content received")
        logger.write(f"Content: {(final_content or '')[:500]}")
        break

    # --- Post-loop ---
    if final_content is None and iteration >= max_iterations:
        logger.write(f"Max iterations ({max_iterations}) reached")
        final_content = f"达到最大迭代次数 ({max_iterations})，任务未完成。建议将任务拆分为更小的步骤。"

    if final_content is None:
        final_content = "无响应"

    logger.write("========== Agent Loop 结束 (Python) ==========")
    logger.write(f"Iterations: {iteration}")
    logger.write(f"Tools used: {', '.join(tools_used)}")
    logger.write(
        f"Token usage (LLM 累加, prompt+completion+total 按轮次加总): "
        f"prompt={usage_totals['prompt']} completion={usage_totals['completion']} total={usage_totals['total']}"
    )

    logger.finalize(iteration, tools_used, len(final_content))

    return json.dumps({
        "final_content": final_content,
        "tools_used": list(set(tools_used)),
        "iterations": iteration,
        "messages": messages,
        "usage": {
            "prompt_tokens": usage_totals["prompt"],
            "completion_tokens": usage_totals["completion"],
            "total_tokens": usage_totals["total"],
        },
    }, ensure_ascii=False)


def _track_error(tracker, error_message):
    """Record error; return True if MAX_CONSECUTIVE_ERRORS identical errors reached."""
    tracker.append(error_message)
    if len(tracker) > MAX_CONSECUTIVE_ERRORS:
        tracker.pop(0)
    if len(tracker) >= MAX_CONSECUTIVE_ERRORS:
        return all(e == error_message for e in tracker)
    return False
