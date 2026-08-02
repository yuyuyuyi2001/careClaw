/**
 * OmniClaw Source Reference:
 * - ../omniclaw/src/agents/(all)
 *
 * OmniClaw adaptation: Kotlin extensions.
 */
package com.shijing.xomniclaw.ext

import com.tencent.mmkv.MMKV


fun OmniClawMMKV(): MMKV = MMKV.defaultMMKV(MMKV.MULTI_PROCESS_MODE, "OmniClaw")!!

val mmkv by lazy { OmniClawMMKV() }