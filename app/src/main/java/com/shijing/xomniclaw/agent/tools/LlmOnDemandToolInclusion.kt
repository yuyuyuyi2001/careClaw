package com.shijing.xomniclaw.agent.tools

/**
 * 对一批「高 token 成本」的 Android 工具：默认**不**放进当轮 LLM 的 [tools] schema；
 * 仅当 [resolveInclusions] 根据用户话与系统提示判断可能用到时，再注入。
 *
 * 工具仍注册在 [AndroidToolRegistry] 中，[com.shijing.xomniclaw.agent.tools.ToolCallDispatcher] 可正常执行。
 */
object LlmOnDemandToolInclusion {

    val ON_DEMAND_LLM_TOOL_NAMES: Set<String> = setOf(
        // ===== Universal tools =====
        "read_file",
        "write_file",
        "edit_file",
        "list_dir",
        "exec",
        "web_fetch",
        "config_get",
        "config_set",

        // ===== Android / memory tools =====
        "device",
        "list_installed_apps",
        "start_activity",
        "stop",
        "log",
        "image_memory_search_entries",
        "schedule_app_task",
        "gallery_memory",
        "memory_evolution",
        "system_settings",
        "send_image",
        "memory_get",
        "memory_search",
        "schedule_task",
        "install_app",
        "copy_images_to_album",
        "list_gallery_images"
    )

    val ALL_ON_DEMAND_NAMES: Set<String> = ON_DEMAND_LLM_TOOL_NAMES

    fun isOnDemandTool(name: String): Boolean = name in ON_DEMAND_LLM_TOOL_NAMES

    /**
     * 轻量寒暄判断：
     * - 命中后上游会直接采用 `tools=[]`，避免「你好」这类输入还带全套工具 schema。
     */
    fun isGreetingOnlyMessage(userMessage: String): Boolean {
        val raw = userMessage.trim()
        if (raw.isBlank()) return false
        // 先去掉常见会话包裹（引用/标签/中括号标记），避免「[xxx] 你好」判成非寒暄。
        val stripped = raw
            .replace(Regex("""\[[^\]]{1,80}]"""), " ")
            .replace(Regex("""<(?:[^>\n]{1,80})>"""), " ")
            .trim()
        val normalized = stripped
            .lowercase()
            .replace(Regex("""[\s\p{Punct}，。！？、；：~～…]+"""), "")
        if (normalized.isBlank()) return false
        if (normalized in setOf("你好", "您好", "嗨", "哈喽", "hi", "hello", "hey", "早上好", "下午好", "晚上好")) {
            return true
        }
        // 允许「你好呀」「hello啊」这类极短尾缀寒暄
        if ((normalized.startsWith("你好") || normalized.startsWith("hello") || normalized.startsWith("hi")) &&
            normalized.length <= 6
        ) {
            return true
        }

        // 放宽：短句里仅包含寒暄词，不含任何任务操作词，也视为寒暄。
        val hasGreeting = normalized.contains("你好") ||
            normalized.contains("您好") ||
            normalized.contains("hello") ||
            normalized.contains("hi") ||
            normalized.contains("hey")
        val hasActionVerb = listOf(
            "打开", "执行", "设置", "发送", "安装", "截图", "点击", "读取", "写入",
            "open", "run", "set", "send", "install", "screenshot", "click", "read", "write"
        ).any { normalized.contains(it) }
        return hasGreeting && !hasActionVerb && normalized.length <= 20
    }

    /**
     * 返回**应当**在当轮 LLM `tools` 中额外包含的、按需类工具名集合。
     */
    fun resolveInclusions(systemPrompt: String, userMessage: String): Set<String> {
        val sys = systemPrompt
        val u = userMessage
        val uLow = u.lowercase()
        val sysLow = sys.lowercase()
        val low = (sys + "\n" + u).lowercase()
        if (isGreetingOnlyMessage(u)) return emptySet()
        val out = mutableSetOf<String>()

        if (wantsUniversalFileOps(u, uLow)) {
            out.add("read_file")
            out.add("write_file")
            out.add("edit_file")
            out.add("list_dir")
        }
        if (wantsUniversalExec(u, uLow)) out.add("exec")
        if (wantsUniversalWebFetch(u, uLow)) out.add("web_fetch")
        if (wantsUniversalConfigOps(u, uLow)) {
            out.add("config_get")
            out.add("config_set")
        }

        if (wantsDeviceOps(u, uLow)) out.add("device")
        if (wantsListInstalledApps(u, uLow)) out.add("list_installed_apps")
        if (wantsStartActivity(u, uLow)) out.add("start_activity")
        if (wantsStopOrLog(u, uLow)) {
            out.add("stop")
            out.add("log")
        }

        if (wantsImageMemorySearchEntries(sys, u, low)) out.add("image_memory_search_entries")
        if (wantsMemoryGetSearch(u, low, sys)) {
            out.add("memory_get")
            out.add("memory_search")
        }
        if (wantsSchedule(u, uLow)) {
            out.add("schedule_task")
            out.add("schedule_app_task")
        }
        if (wantsGalleryMemory(u, low, sys)) out.add("gallery_memory")
        if (wantsMemoryEvolution(u, low, sys)) out.add("memory_evolution")
        if (wantsSystemSettings(u, low, sys)) out.add("system_settings")
        if (wantsSendImage(u, low)) out.add("send_image")
        if (wantsInstallApp(u, low)) out.add("install_app")
        if (wantsListGallery(u, low)) out.add("list_gallery_images")
        if (wantsCopyImagesToAlbum(u, low)) out.add("copy_images_to_album")
        // 只要本轮已注入 Skills 目录（代表至少命中 1 个 skill），就强制给 read_file，
        // 保证模型可先读取对应 SKILL.md，而不是出现“推理想读 skill 但 tools 不可用”的断层。
        if (sysLow.contains("## skills (mandatory)")) {
            out.add("read_file")
        }

        return out
    }

    private fun wantsUniversalFileOps(u: String, low: String): Boolean {
        if (u.contains("文件") || u.contains("目录") || u.contains("路径") || u.contains("读") || u.contains("写") || u.contains("编辑")) return true
        if (low.contains("read_file") || low.contains("write_file") || low.contains("edit_file") || low.contains("list_dir")) return true
        if (low.contains("open ") && low.contains("file")) return true
        if (low.contains("list ") && (low.contains("dir") || low.contains("folder"))) return true
        return false
    }

    private fun wantsUniversalExec(u: String, low: String): Boolean {
        if (u.contains("命令") || u.contains("终端") || u.contains("shell") || u.contains("执行")) return true
        if (low.contains("powershell") || low.contains("bash") || low.contains("cmd ") || low.contains("exec(")) return true
        if (low.contains("exec tool") || low.contains("run command")) return true
        return false
    }

    private fun wantsUniversalWebFetch(u: String, low: String): Boolean {
        if (u.contains("网页") || u.contains("网址") || u.contains("链接") || u.contains("抓取")) return true
        if (low.contains("http://") || low.contains("https://")) return true
        if (low.contains("web_fetch") || low.contains("fetch url")) return true
        return false
    }

    private fun wantsUniversalConfigOps(u: String, low: String): Boolean {
        if (u.contains("配置") || u.contains("config") || (u.contains("设置") && (u.contains("读取") || u.contains("修改")))) return true
        if (low.contains("config_get") || low.contains("config_set")) return true
        if (low.contains("xomniclaw.json") || low.contains("channels.feishu") || low.contains("models.providers")) return true
        return false
    }

    private fun wantsDeviceOps(u: String, low: String): Boolean {
        if (u.contains("点击") || u.contains("滑动") || u.contains("输入") || u.contains("返回") || u.contains("主页")) return true
        if (u.contains("截图") || u.contains("界面") || u.contains("屏幕") || u.contains("控件")) return true
        if (u.contains("打开") && (u.contains("app") || u.contains("应用"))) return true
        if (low.contains("snapshot") || low.contains("screenshot") || low.contains("device")) return true
        if (low.contains("tap") || low.contains("swipe") || low.contains("scroll")) return true
        return false
    }

    private fun wantsListInstalledApps(u: String, low: String): Boolean {
        if (u.contains("已安装") || u.contains("应用列表") || u.contains("app 列表") || u.contains("有哪些应用")) return true
        if (low.contains("list_installed_apps") || low.contains("installed apps")) return true
        return false
    }

    private fun wantsStartActivity(u: String, low: String): Boolean {
        if (u.contains("activity") || u.contains("组件") || u.contains("component") || u.contains("启动页面")) return true
        if (low.contains("start_activity") || low.contains("am start")) return true
        return false
    }

    private fun wantsStopOrLog(u: String, low: String): Boolean {
        if (u.contains("停止") || u.contains("终止") || u.contains("取消任务")) return true
        if (u.contains("日志") || u.contains("记录")) return true
        if (low.contains("stop(") || low.contains("log(") || low.contains("tool stop")) return true
        return false
    }

    private fun wantsImageMemorySearchEntries(sys: String, u: String, low: String): Boolean {
        if (sys.contains("IMAGE-MEMORY", ignoreCase = true)) return true
        if (sys.contains("gallery-qa", ignoreCase = true) || sys.contains("gallery_qa", ignoreCase = true)) return true
        if (sys.contains("image_memory_search", ignoreCase = true) || low.contains("imagememory")) return true
        if (u.contains("图库") || u.contains("相册") || u.contains("图片记忆")) return true
        if (u.contains("照片") || u.contains("截图") || u.contains("拍过") || u.contains("拍摄")) return true
        if (u.lowercase().let { it.contains("image-memory") || it.contains("image-memories") || it.contains("gallery-qa") }) return true
        if (low.contains("image memory") || low.contains("image-memory")) return true
        return false
    }

    private fun wantsMemoryGetSearch(u: String, low: String, sys: String): Boolean {
        if (sys.contains("user-profile", ignoreCase = true) && sys.contains("memory/", ignoreCase = true)) return true
        if (low.contains("memory/") && (low.contains("user-profile") || low.contains("user_profile") || low.contains("image-memory") || low.contains("image-memories"))) return true
        if (u.contains("记忆") || u.contains("回忆") || u.contains("用户档案") || u.contains("档案")) return true
        if (u.contains("画像") || u.contains("偏好") || u.contains("待办")) return true
        if (u.contains("图库") || u.contains("相册") || u.contains("照片")) return true
        if (u.contains("搜") && (u.contains("记") || u.contains("文") || u.contains("档"))) return true
        if (u.contains("MEMORY", ignoreCase = true) && u.length < 200) return true
        if (low.contains("memory_get") || low.contains("memory_search") || low.contains("memory get")) return true
        if (u.contains("profile", ignoreCase = true) && (u.contains("记") || u.contains("用"))) return true
        if (u.contains("以前") || u.contains("之前说过") || u.contains("上次的")) return true
        if (u.contains("记住") && u.length < 80) return true
        return false
    }

    private fun wantsSchedule(u: String, userLow: String): Boolean {
        if (u.contains("定时") || u.contains("提醒") || u.contains("闹钟") || u.contains("预约")) return true
        if (u.contains("每天") || u.contains("每周") || u.contains("工作日") || u.contains("每隔") || u.contains("稍后")) return true
        if (u.contains("到点") || u.contains("明早") || u.contains("今晚") || u.contains("点叫我")) return true
        if (u.contains("执行") && (u.contains("点") || u.contains("时") || u.contains("分"))) return true
        // 只用用户本轮话术判断是否注入定时工具，避免已加载的 scheduled-automation skill 文本反向污染执行阶段。
        if (userLow.contains("schedule_task") || userLow.contains("schedule_app") || userLow.contains("alarm")) return true
        if (userLow.contains("remind me") || userLow.contains("set an alarm") || userLow.contains("cron")) return true
        return false
    }

    private fun wantsGalleryMemory(u: String, low: String, sys: String): Boolean {
        if (sys.contains("gallery-memory", ignoreCase = true) || sys.contains("gallery_memory", ignoreCase = true)) return true
        if (u.contains("同步") && (u.contains("图") || u.contains("相") || u.contains("片"))) return true
        if (u.contains("扫描") && (u.contains("相") || u.contains("片"))) return true
        if (u.contains("图库记忆") || u.contains("相册记忆")) return true
        if (u.contains("重建") && (u.contains("画像") || u.contains("记忆"))) return true
        if (u.contains("刷新") && (u.contains("记") || u.contains("相"))) return true
        if (low.contains("rebuild") && low.contains("profile")) return true
        if (u.contains("清除") && u.contains("图") && (u.contains("记") || u.contains("像"))) return true
        if (u.contains("reset") && (u.contains("图") || u.contains("相"))) return true
        if (u.contains("gallery.memory", ignoreCase = true)) return true
        if ((u.contains("IMAGE-MEMORY", ignoreCase = true) || u.contains("image-memories", ignoreCase = true)) && (u.contains("更") || u.contains("同"))) return true
        return false
    }

    private fun wantsMemoryEvolution(u: String, low: String, sys: String): Boolean {
        if (u.contains("全局记忆进化") || u.contains("更新全局记忆")) return true
        if (u.contains("任务记忆") || u.contains("使用习惯")) return true
        val isGalleryScopedMemory = u.contains("相册") ||
            u.contains("图库") ||
            u.contains("照片") ||
            u.contains("图片") ||
            u.contains("截图") ||
            u.contains("IMAGE-MEMORY", ignoreCase = true)
        val asksToUpdateMemory = (
            u.contains("更新") ||
                u.contains("刷新") ||
                u.contains("同步") ||
                u.contains("整理") ||
                u.contains("沉淀") ||
                u.contains("进化") ||
                u.contains("重建")
            ) && (u.contains("记忆") || u.contains("MEMORY", ignoreCase = true))
        // “给我更新一下记忆”这类泛化表达默认指全局任务记忆；
        // 明确提到相册/照片时交给 gallery_memory，避免两套更新流程混用。
        if (asksToUpdateMemory && !isGalleryScopedMemory) return true
        if (u.contains("MEMORY.md", ignoreCase = true) && (u.contains("更新") || u.contains("进化") || u.contains("沉淀"))) return true
        if (low.contains("memory_evolution")) return true
        if (sys.contains("memory_evolution", ignoreCase = true)) return true
        return false
    }

    private fun wantsSystemSettings(u: String, low: String, @Suppress("UNUSED_PARAMETER") sys: String): Boolean {
        if (u.contains("蓝牙") || u.contains("wifi", true) || u.contains("无线") || u.contains("無線") || u.contains("WLAN", true)) return true
        if (u.contains("飛行") || u.contains("飞行") || u.contains("空模式")) return true
        if (u.contains("亮度") || u.contains("音量") || u.contains("暗色") || u.contains("深色模式")) return true
        if (u.contains("定位") || u.contains("GPS", true) || u.contains("行動") || u.contains("移动数据")) return true
        if (u.contains("设置") && (u.contains("开") || u.contains("关"))) return true
        if (low.contains("airplane") || low.contains("bluetooth") || low.contains("system_settings")) return true
        return false
    }

    private fun wantsSendImage(u: String, low: String): Boolean {
        if (u.contains("飞书") || u.contains("Feishu", ignoreCase = true) || low.contains("lark")) return true
        if (u.contains("发图") || u.contains("发图片") || (u.contains("发") && u.contains("到") && (u.contains("群") || u.contains("人")))) return true
        if (u.contains("传图") || u.contains("上传图")) return true
        if (low.contains("send_image") || (low.contains("feishu") && u.contains("图"))) return true
        return false
    }

    private fun wantsInstallApp(u: String, low: String): Boolean {
        if (u.contains("安装") || u.contains("安裝") || u.contains(".apk")) return true
        if (u.contains("装应用") || u.contains("下載") && u.contains("包")) return true
        if (u.contains("下载") && (u.contains("包") || u.contains("安装"))) return true
        if (low.contains("install") && (low.contains("apk") || low.contains("app"))) return true
        if (u.contains("PackageInstaller", ignoreCase = true)) return true
        return false
    }

    private fun wantsListGallery(u: String, low: String): Boolean {
        if (u.contains("图库") || u.contains("相簿") || u.contains("相冊") || (u.contains("相册") && !u.contains("相册记忆"))) {
            if (u.contains("列") || u.contains("找") || u.contains("哪") || u.contains("什么") || u.contains("有") || u.contains("发我")) return true
            if (u.contains("最") && u.contains("近") && (u.contains("图") || u.contains("片"))) return true
            if (u.contains("今天") && u.contains("照")) return true
        }
        if (u.contains("照片") && (u.contains("找") || u.contains("看") || u.contains("有") || u.contains("选"))) return true
        if (u.contains("最近") && (u.contains("图") || u.contains("片") || u.contains("照"))) return true
        if (u.contains("截屏") && u.contains("发")) return false
        if (low.contains("list_gallery") || (low.contains("mediastore") && u.contains("图"))) return true
        return false
    }

    private fun wantsCopyImagesToAlbum(u: String, low: String): Boolean {
        if (u.contains("复制") && (u.contains("相") || u.contains("片") || u.contains("图"))) return true
        if (u.contains("新相册") || (u.contains("新建") && (u.contains("相") || u.contains("集")))) return true
        if (u.contains("整理") && (u.contains("片") || u.contains("相"))) return true
        if (u.contains("移到") && (u.contains("相") || u.contains("文"))) return true
        if (u.contains("Pictures", ignoreCase = true) && u.contains("相")) return true
        if (u.contains("一键成片") && u.contains("相")) return true
        if (low.contains("copy_images_to_album")) return true
        if (u.contains("文件夹") && (u.contains("相") || u.contains("片"))) return true
        return false
    }
}
