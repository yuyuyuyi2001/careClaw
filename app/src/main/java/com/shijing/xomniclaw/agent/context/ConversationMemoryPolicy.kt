package com.shijing.xomniclaw.agent.context

/**
 * Conversation memory policy shared by execution and UI layers.
 *
 * Product semantics:
 * - "Remembered turns" are user/assistant conversation turns (user-led),
 *   not internal AgentLoop execution iterations.
 * - We hard-preserve the most recent turns to keep user-facing continuity
 *   stable even when internal tool logs become large.
 */
object ConversationMemoryPolicy {
    /** Hard reserve for recent conversation turns in context. */
    const val HARD_KEEP_RECENT_DIALOG_TURNS = 12

    /**
     * Convert total conversation turns to user-visible "rememberable turns".
     * If the session has fewer turns than the hard reserve, the value equals
     * session turn count.
     */
    fun rememberableTurns(totalConversationTurns: Int): Int {
        return totalConversationTurns.coerceAtLeast(0).coerceAtMost(HARD_KEEP_RECENT_DIALOG_TURNS)
    }
}
