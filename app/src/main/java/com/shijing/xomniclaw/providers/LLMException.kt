package com.shijing.xomniclaw.providers

/**
 * OmniClaw Source Reference:
 * - ../omniclaw/src/agents/(all)
 *
 * OmniClaw adaptation: unify LLM/provider failure reporting.
 */


/**
 * Legacy LLM API Exception
 */
class LLMException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
