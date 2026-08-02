"""Tool-loop detection — port of ToolLoopDetection.kt.

Detects four loop patterns:
  1. generic_repeat       – same tool+args repeated many times
  2. known_poll_no_progress – polling tool with identical results
  3. ping_pong            – two tools alternating with no progress
  4. global_circuit_breaker – hard ceiling on identical no-progress outcomes
"""

import hashlib
import json
from collections import deque

TOOL_CALL_HISTORY_SIZE = 30
WARNING_THRESHOLD = 10
CRITICAL_THRESHOLD = 20
GLOBAL_CIRCUIT_BREAKER_THRESHOLD = 30

KNOWN_POLL_TOOLS = {"wait", "wait_for_element", "command_status"}


class LoopDetectionResult:
    NO_LOOP = "no_loop"
    WARNING = "warning"
    CRITICAL = "critical"

    def __init__(self, level=None, detector=None, count=0, message="", warning_key=None):
        self.level = level  # None | WARNING | CRITICAL
        self.detector = detector
        self.count = count
        self.message = message
        self.warning_key = warning_key

    @property
    def is_loop(self):
        return self.level is not None

    @property
    def is_critical(self):
        return self.level == self.CRITICAL


_NO_LOOP = LoopDetectionResult()


class _ToolCallRecord:
    __slots__ = ("tool_name", "args_hash", "result_hash", "timestamp", "tool_call_id")

    def __init__(self, tool_name, args_hash, result_hash=None, timestamp=None, tool_call_id=None):
        self.tool_name = tool_name
        self.args_hash = args_hash
        self.result_hash = result_hash
        self.timestamp = timestamp
        self.tool_call_id = tool_call_id


def _stable_stringify(value):
    if value is None:
        return "null"
    if isinstance(value, str):
        return json.dumps(value, ensure_ascii=False)
    if isinstance(value, (int, float)):
        return str(value)
    if isinstance(value, bool):
        return str(value).lower()
    if isinstance(value, dict):
        entries = ",".join(
            f"{json.dumps(str(k))}:{_stable_stringify(v)}"
            for k, v in sorted(value.items(), key=lambda kv: str(kv[0]))
        )
        return "{" + entries + "}"
    if isinstance(value, (list, tuple)):
        items = ",".join(_stable_stringify(v) for v in value)
        return "[" + items + "]"
    return json.dumps(value, ensure_ascii=False)


def _digest_stable(value):
    serialized = _stable_stringify(value)
    return hashlib.sha256(serialized.encode("utf-8")).hexdigest()


def hash_tool_call(tool_name, params):
    params_hash = _digest_stable(params)
    return f"{tool_name}:{params_hash}"


def hash_tool_outcome(tool_name, params, result, error=None):
    if error is not None:
        return "error:" + _digest_stable(str(error))
    return _digest_stable(result[:500] if result else "")


class LoopDetector:
    """Stateful session-level loop detector."""

    def __init__(self):
        self.history = deque(maxlen=TOOL_CALL_HISTORY_SIZE)
        self.reported_warnings = set()

    def detect(self, tool_name, params):
        """Pre-execution detection. Returns LoopDetectionResult."""
        current_hash = hash_tool_call(tool_name, params)

        # 1. Global circuit breaker
        no_progress = self._get_no_progress_streak(tool_name, current_hash)
        if no_progress["count"] >= GLOBAL_CIRCUIT_BREAKER_THRESHOLD:
            wk = f"global:{tool_name}:{current_hash}:{no_progress['latest_result_hash']}"
            return LoopDetectionResult(
                level=LoopDetectionResult.CRITICAL,
                detector="global_circuit_breaker",
                count=no_progress["count"],
                message=(
                    f"CRITICAL: {tool_name} has repeated identical no-progress outcomes "
                    f"{no_progress['count']} times. Session execution blocked by global circuit breaker."
                ),
                warning_key=wk,
            )

        # 2. Known poll no progress
        is_poll = tool_name in KNOWN_POLL_TOOLS
        if is_poll and no_progress["count"] >= CRITICAL_THRESHOLD:
            wk = f"poll:{tool_name}:{current_hash}:{no_progress['latest_result_hash']}"
            return LoopDetectionResult(
                level=LoopDetectionResult.CRITICAL,
                detector="known_poll_no_progress",
                count=no_progress["count"],
                message=(
                    f"CRITICAL: Called {tool_name} with identical arguments and no progress "
                    f"{no_progress['count']} times. Session execution blocked."
                ),
                warning_key=wk,
            )
        if is_poll and no_progress["count"] >= WARNING_THRESHOLD:
            wk = f"poll:{tool_name}:{current_hash}:{no_progress['latest_result_hash']}"
            if wk not in self.reported_warnings:
                self.reported_warnings.add(wk)
                return LoopDetectionResult(
                    level=LoopDetectionResult.WARNING,
                    detector="known_poll_no_progress",
                    count=no_progress["count"],
                    message=(
                        f"WARNING: You have called {tool_name} {no_progress['count']} times with "
                        f"identical arguments and no progress. Stop polling."
                    ),
                    warning_key=wk,
                )

        # 3. Ping-pong
        pp = self._get_ping_pong_streak(current_hash)
        if pp["count"] >= CRITICAL_THRESHOLD and pp["no_progress_evidence"]:
            wk = f"pingpong:{pp['paired_signature']}:{current_hash}"
            return LoopDetectionResult(
                level=LoopDetectionResult.CRITICAL,
                detector="ping_pong",
                count=pp["count"],
                message=(
                    f"CRITICAL: You are alternating between repeated tool-call patterns "
                    f"({pp['count']} consecutive calls) with no progress. Session blocked."
                ),
                warning_key=wk,
            )
        if pp["count"] >= WARNING_THRESHOLD:
            wk = f"pingpong:{pp['paired_signature']}:{current_hash}"
            if wk not in self.reported_warnings:
                self.reported_warnings.add(wk)
                return LoopDetectionResult(
                    level=LoopDetectionResult.WARNING,
                    detector="ping_pong",
                    count=pp["count"],
                    message=(
                        f"WARNING: You are alternating between repeated tool-call patterns "
                        f"({pp['count']} consecutive calls). This looks like a ping-pong loop."
                    ),
                    warning_key=wk,
                )

        # 4. Generic repeat
        if not is_poll:
            recent_count = sum(
                1 for r in self.history
                if r.tool_name == tool_name and r.args_hash == current_hash
            )
            if recent_count >= WARNING_THRESHOLD:
                wk = f"generic:{tool_name}:{current_hash}"
                if wk not in self.reported_warnings:
                    self.reported_warnings.add(wk)
                    return LoopDetectionResult(
                        level=LoopDetectionResult.WARNING,
                        detector="generic_repeat",
                        count=recent_count,
                        message=(
                            f"WARNING: You have called {tool_name} {recent_count} times "
                            f"with identical arguments."
                        ),
                        warning_key=wk,
                    )

        return _NO_LOOP

    def record_call(self, tool_name, params, tool_call_id=None):
        import time
        rec = _ToolCallRecord(
            tool_name=tool_name,
            args_hash=hash_tool_call(tool_name, params),
            timestamp=time.time(),
            tool_call_id=tool_call_id,
        )
        self.history.append(rec)

    def record_outcome(self, tool_name, params, result, error=None, tool_call_id=None):
        result_hash = hash_tool_outcome(tool_name, params, result, error)
        if result_hash is None:
            return
        args_hash = hash_tool_call(tool_name, params)

        matched = False
        for i in range(len(self.history) - 1, -1, -1):
            rec = self.history[i]
            if tool_call_id and rec.tool_call_id != tool_call_id:
                continue
            if rec.tool_name != tool_name or rec.args_hash != args_hash:
                continue
            if rec.result_hash is not None:
                continue
            rec.result_hash = result_hash
            matched = True
            break

        if not matched:
            import time
            rec = _ToolCallRecord(
                tool_name=tool_name,
                args_hash=args_hash,
                result_hash=result_hash,
                timestamp=time.time(),
                tool_call_id=tool_call_id,
            )
            self.history.append(rec)

    # ------------------------------------------------------------------
    def _get_no_progress_streak(self, tool_name, args_hash):
        streak = 0
        latest_result_hash = None
        for i in range(len(self.history) - 1, -1, -1):
            rec = self.history[i]
            if rec.tool_name != tool_name or rec.args_hash != args_hash:
                continue
            if rec.result_hash is None:
                continue
            if latest_result_hash is None:
                latest_result_hash = rec.result_hash
                streak = 1
                continue
            if rec.result_hash != latest_result_hash:
                break
            streak += 1
        return {"count": streak, "latest_result_hash": latest_result_hash}

    def _get_ping_pong_streak(self, current_signature):
        history = list(self.history)
        if not history:
            return {"count": 0, "paired_signature": None, "no_progress_evidence": False}

        last = history[-1]
        other_signature = None
        for i in range(len(history) - 2, -1, -1):
            if history[i].args_hash != last.args_hash:
                other_signature = history[i].args_hash
                break

        if other_signature is None:
            return {"count": 0, "paired_signature": None, "no_progress_evidence": False}

        alternating_count = 0
        for i in range(len(history) - 1, -1, -1):
            expected = last.args_hash if alternating_count % 2 == 0 else other_signature
            if history[i].args_hash != expected:
                break
            alternating_count += 1

        if alternating_count < 2:
            return {"count": 0, "paired_signature": None, "no_progress_evidence": False}

        if current_signature != other_signature:
            return {"count": 0, "paired_signature": None, "no_progress_evidence": False}

        tail_start = max(0, len(history) - alternating_count)
        first_hash_a = None
        first_hash_b = None
        no_progress = True
        for i in range(tail_start, len(history)):
            rec = history[i]
            if rec.result_hash is None:
                no_progress = False
                break
            if rec.args_hash == last.args_hash:
                if first_hash_a is None:
                    first_hash_a = rec.result_hash
                elif first_hash_a != rec.result_hash:
                    no_progress = False
                    break
            elif rec.args_hash == other_signature:
                if first_hash_b is None:
                    first_hash_b = rec.result_hash
                elif first_hash_b != rec.result_hash:
                    no_progress = False
                    break
            else:
                no_progress = False
                break

        if first_hash_a is None or first_hash_b is None:
            no_progress = False

        return {
            "count": alternating_count + 1,
            "paired_signature": other_signature,
            "no_progress_evidence": no_progress,
        }
