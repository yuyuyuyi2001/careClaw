"""Incremental UI sensing — detects when a new snapshot looks like the previous one.

Algorithm:
  1. SHA-256 hash fast-path: if identical hash -> no change.
  2. difflib.SequenceMatcher slow-path: if ratio >= threshold -> no substantial change.
  3. A max_consecutive_skips safety valve forces a reasoning round even if no change detected.

Note: `agent_logic` still injects the **full** observe-tool result into the chat when the UI is
similar, and may prepend a hint — each snapshot still advances `snapshot_id` on the device side.
"""

import hashlib
import difflib


class IncrementalSensor:
    def __init__(self, similarity_threshold=0.95, max_consecutive_skips=3):
        self.threshold = similarity_threshold
        self.max_consecutive_skips = max_consecutive_skips
        self._last_hash = None
        self._last_text = None
        self._skip_count = 0

    def check_ui_change(self, snapshot_text):
        """Return True if UI has substantial change and LLM reasoning is needed."""
        current_hash = hashlib.sha256(snapshot_text.encode("utf-8")).hexdigest()

        # Fast path: exact hash match
        if current_hash == self._last_hash:
            self._skip_count += 1
            if self._skip_count >= self.max_consecutive_skips:
                self._skip_count = 0
                return True  # force reasoning after N consecutive skips
            return False

        # Slow path: difflib comparison
        if self._last_text is not None:
            ratio = difflib.SequenceMatcher(
                None, self._last_text, snapshot_text
            ).ratio()
            if ratio >= self.threshold:
                self._skip_count += 1
                if self._skip_count >= self.max_consecutive_skips:
                    self._skip_count = 0
                    self._update_cache(snapshot_text, current_hash)
                    return True  # force reasoning
                return False

        # Substantial change detected
        self._update_cache(snapshot_text, current_hash)
        self._skip_count = 0
        return True

    def should_skip_reasoning(self, tool_name, tool_result):
        """Convenience wrapper: returns True if reasoning should be SKIPPED.

        Only applies to observe-type tool results (snapshot / screenshot).
        """
        if tool_name not in ("device", "screenshot", "get_view_tree"):
            return False
        if tool_name == "device":
            # tool_result is the raw content; we just hash it all
            pass
        return not self.check_ui_change(tool_result)

    def reset(self):
        self._last_hash = None
        self._last_text = None
        self._skip_count = 0

    def _update_cache(self, text, hash_val):
        self._last_text = text
        self._last_hash = hash_val
