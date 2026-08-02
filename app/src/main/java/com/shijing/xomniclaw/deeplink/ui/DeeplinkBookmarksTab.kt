package com.shijing.xomniclaw.deeplink.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shijing.xomniclaw.deeplink.DeeplinkBookmarkStore
import com.shijing.xomniclaw.deeplink.DeeplinkLauncher
import com.shijing.xomniclaw.deeplink.DeeplinkSkillExporter
import com.shijing.xomniclaw.deeplink.RootShellExecutor
import com.shijing.xomniclaw.deeplink.model.DeeplinkBookmark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Deeplink 收藏页。
 *
 * 页面本身只负责浏览和回放收藏，不直接承载录制逻辑；
 * 录制入口仍挂在视觉输入菜单里，保证用户原有路径不被打断。
 */
@Composable
fun DeeplinkBookmarksTab(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bookmarks by DeeplinkBookmarkStore.bookmarks().collectAsState()
    var rootAvailable by remember { mutableStateOf(false) }
    var rootChecked by remember { mutableStateOf(false) }

    val refreshRootState: () -> Unit = {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                RootShellExecutor.hasRootAccess(forceRefresh = true)
            }
            rootAvailable = result
            rootChecked = true
        }
    }

    LaunchedEffect(Unit) {
        refreshRootState()
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Deeplink 收藏",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "先在视觉输入里启动“轨迹录制（支持 Deeplink 收藏）”，" +
                                    "进入目标页面后点击悬浮窗收藏按钮，这里会保存可一键直达的页面记录。",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        IconButton(onClick = refreshRootState) {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新 root 状态")
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    AssistChip(
                        onClick = refreshRootState,
                        label = {
                            Text(
                                if (!rootChecked) {
                                    "正在检测 root"
                                } else if (rootAvailable) {
                                    "root 已开启"
                                } else {
                                    "root 不可用"
                                }
                            )
                        }
                    )
                }
            }
        }

        if (rootChecked && !rootAvailable) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        text = "当前设备没有 root，按照你的要求，这个功能页不会对普通场景开放。请先确保 `su -c id` 可用。",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        } else if (bookmarks.isEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "还没有收藏记录。开始轨迹录制后，走到目标页面并点击悬浮窗里的收藏按钮，就会在这里生成 Deeplink 收藏。",
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        } else {
            items(bookmarks, key = { it.id }) { bookmark ->
                DeeplinkBookmarkCard(
                    bookmark = bookmark,
                    onLaunch = {
                        when (val result = DeeplinkLauncher.launch(context, bookmark)) {
                            DeeplinkLauncher.LaunchResult.RootSuccess -> {
                                Toast.makeText(context, "已通过 root 直达目标页", Toast.LENGTH_SHORT).show()
                            }

                            DeeplinkLauncher.LaunchResult.DirectIntentSuccess -> {
                                Toast.makeText(context, "已通过普通 Intent 尝试打开目标页", Toast.LENGTH_SHORT).show()
                            }

                            is DeeplinkLauncher.LaunchResult.Failed -> {
                                Toast.makeText(context, result.reason, Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    onExportSkill = {
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                DeeplinkSkillExporter.export(bookmark)
                            }
                            Toast.makeText(
                                context,
                                if (result.success) "✅ ${result.message}，下次说「${result.skillName}」即可直达"
                                else "❌ ${result.message}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    },
                    onDelete = {
                        DeeplinkBookmarkStore.remove(bookmark.id)
                    }
                )
            }
        }
    }
}

@Composable
private fun DeeplinkBookmarkCard(
    bookmark: DeeplinkBookmark,
    onLaunch: () -> Unit,
    onExportSkill: () -> Unit,
    onDelete: () -> Unit
) {
    val timeFormatter = remember {
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = bookmark.appName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = bookmark.displayName,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                Text(
                    text = timeFormatter.format(Date(bookmark.createdAt)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = bookmark.statusText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = bookmark.deeplinkSummary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = bookmark.shortActivityName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                IconButton(onClick = onLaunch) {
                    Icon(Icons.Default.OpenInNew, contentDescription = "一键直达")
                }
                IconButton(onClick = onExportSkill) {
                    Icon(Icons.Default.SaveAlt, contentDescription = "导出为 Skill")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "删除收藏")
                }
            }
        }
    }
}
