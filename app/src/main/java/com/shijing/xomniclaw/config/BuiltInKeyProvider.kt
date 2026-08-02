package com.shijing.xomniclaw.config

/**
 * OmniClaw Source Reference:
 * - ../omniclaw/src/config/(all)
 *
 * OmniClaw adaptation: built-in/provider key resolution for local Android runtime.
 */


import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 历史上曾提供内置 OpenRouter Key，因 OpenRouter 端会频繁使共享 Key 失效并返回 401 `User not found`，
 * 现**不再**在包内预置任何 Key。`getKey()` 恒为 null，请用户在应用「模型配置」或 `xomniclaw.json` 中填写自己的 `sk-or-v1-...`。
 *
 * 若需再次在工程内预置（仅内部测试），可执行 [encrypt] 将密文写入 [ENCRYPTED_KEY]。
 */
object BuiltInKeyProvider {

    // 置空 = 不注入任何内置 OpenRouter Key，避免使用已失效的共享凭据
    private const val ENCRYPTED_KEY = ""

    // Derived from package signature + app-specific salt (not a secret by itself,
    // security comes from the obfuscation + ProGuard + native layer)
    private val K = byteArrayOf(
        0x41, 0x6E, 0x64, 0x72, 0x6F, 0x69, 0x64, 0x46,
        0x6F, 0x72, 0x43, 0x6C, 0x61, 0x77, 0x4B, 0x65,
        0x79, 0x50, 0x72, 0x6F, 0x76, 0x69, 0x64, 0x65,
        0x72, 0x53, 0x65, 0x63, 0x72, 0x65, 0x74, 0x21
    )
    // = "OmniClawKeyProviderSecret!" in ASCII (32 bytes = AES-256)

    fun getKey(): String? {
        if (ENCRYPTED_KEY.isEmpty()) return null
        return try {
            val data = Base64.decode(ENCRYPTED_KEY, Base64.NO_WRAP)
            if (data.size < 13) return null // min: 12 IV + 1 byte

            val iv = data.copyOfRange(0, 12)
            val ciphertext = data.copyOfRange(12, data.size)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val keySpec = SecretKeySpec(K, "AES")
            val gcmSpec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)

            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Helper to encrypt a new key. Run from unit test or main():
     * ```
     * val encrypted = BuiltInKeyProvider.encrypt("sk-or-v1-xxx")
     * println(encrypted) // paste into ENCRYPTED_KEY
     * ```
     */
    fun encrypt(plainKey: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(K, "AES")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec)

        val iv = cipher.iv // 12 bytes auto-generated
        val ciphertext = cipher.doFinal(plainKey.toByteArray(Charsets.UTF_8))

        val result = iv + ciphertext
        return Base64.encodeToString(result, Base64.NO_WRAP)
    }
}
