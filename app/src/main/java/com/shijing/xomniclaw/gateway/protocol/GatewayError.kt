/**
 * OmniClaw Source Reference:
 * - ../xomniclaw/src/gateway/(all)
 *
 * OmniClaw adaptation: gateway server and RPC methods.
 */
package com.shijing.xomniclaw.gateway.protocol

/**
 * Gateway error exception
 */
class GatewayError(
    val code: String,
    message: String,
    val details: Any? = null
) : Exception(message)
