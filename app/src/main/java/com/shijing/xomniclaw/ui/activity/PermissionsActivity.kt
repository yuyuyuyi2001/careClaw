/**
 * OmniClaw Source Reference:
 * - ../xomniclaw/src/gateway/(all)
 *
 * OmniClaw adaptation: Android UI layer.
 */
package com.shijing.xomniclaw.ui.activity

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * 权限页代理：直接跳转到合并进主 app 的 observer 权限页，避免用户看到两层权限页。
 */
class PermissionsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            startActivity(Intent().apply {
                component = ComponentName(
                    "com.shijing.xomniclaw",
                    "com.shijing.xomniclaw.accessibility.PermissionActivity"
                )
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
            })
        } finally {
            finish()
        }
    }
}
