package com.shijing.xomniclaw.deeplink

/**
 * 解析 `dumpsys activity activities` 输出中的 Intent 片段。
 *
 * 这里把纯字符串解析单独拆出来，方便后续单元测试覆盖复杂 ROM 输出。
 */
object DeeplinkIntentParser {
    data class CapturedIntentSpec(
        val action: String?,
        val dataUri: String?,
        val component: String?,
        val categories: List<String>,
        val flags: String?,
        val extras: Map<String, String>,
        val rawIntentLine: String,
        val hasExtrasMarker: Boolean
    ) {
        val amCommand: String
            get() = buildAmCommand(
                action = action,
                data = dataUri,
                component = component,
                categories = categories,
                extras = extras
            )
    }

    fun parseBlock(
        lines: List<String>,
        packageName: String,
        activityName: String
    ): CapturedIntentSpec? {
        val shortClass = activityName.substringAfterLast(".")
        var histLineIndex = -1

        // 先从 Hist 段开始找，避免误命中别的 Activity 的 Intent。
        for (index in lines.indices) {
            val line = lines[index].trim()
            if (line.contains("Hist") && (line.contains(shortClass) || line.contains(activityName))) {
                histLineIndex = index
                break
            }
        }

        if (histLineIndex < 0) {
            for (index in lines.indices) {
                val line = lines[index].trim()
                if (line.startsWith("Intent {") && (line.contains(packageName) || line.contains(shortClass))) {
                    return parseIntentAndExtras(lines, index, packageName, activityName)
                }
            }
            return null
        }

        val searchEnd = minOf(histLineIndex + 10, lines.lastIndex)
        for (index in (histLineIndex + 1)..searchEnd) {
            val line = lines[index].trim()
            if (line.startsWith("Intent {")) {
                return parseIntentAndExtras(lines, index, packageName, activityName)
            }
        }
        return null
    }

    fun buildAmCommand(
        action: String?,
        data: String?,
        component: String?,
        categories: List<String>,
        extras: Map<String, String>
    ): String {
        val commandParts = mutableListOf("am start")
        action?.let { commandParts.add("-a $it") }
        data?.let { commandParts.add("-d ${escapeForShell(it)}") }
        categories.forEach { commandParts.add("-c $it") }
        component?.let { commandParts.add("-n $it") }

        for ((key, value) in extras) {
            when {
                value == "true" || value == "false" -> commandParts.add("--ez $key $value")
                value.toLongOrNull() != null -> commandParts.add("--el $key $value")
                value.toIntOrNull() != null -> commandParts.add("--ei $key $value")
                value.toDoubleOrNull() != null -> commandParts.add("--ef $key $value")
                else -> commandParts.add("--es $key ${escapeForShell(value)}")
            }
        }
        return commandParts.joinToString(" ")
    }

    fun escapeForShell(value: String): String {
        val specialChars = setOf(
            '&', '%', '{', '}', '(', ')', '|', ';', '<', '>', '!', '$',
            '`', '"', ' ', '\\', '*', '?', '#'
        )
        if (value.none { it in specialChars }) {
            return value
        }
        return "'" + value.replace("'", "'\\''") + "'"
    }

    private fun parseIntentAndExtras(
        lines: List<String>,
        intentLineIndex: Int,
        packageName: String,
        activityName: String
    ): CapturedIntentSpec {
        val intentLine = lines[intentLineIndex].trim()
        val action = extractField(intentLine, "act")
        val dataUri = extractField(intentLine, "dat")
        val shortActivity = activityName.removePrefix(packageName)
        val component = extractField(intentLine, "cmp") ?: "$packageName/$shortActivity"
        val flags = extractField(intentLine, "flg")
        val categories = Regex("""cat=\[([^\]]+)\]""")
            .find(intentLine)
            ?.groupValues
            ?.get(1)
            ?.split(",")
            ?.map { it.trim() }
            ?: emptyList()
        val hasExtrasMarker = intentLine.contains("(has extras)")
        val extrasBlock = lines.subList(intentLineIndex, minOf(intentLineIndex + 50, lines.size))
        val extras = if (hasExtrasMarker) parseExtras(extrasBlock) else emptyMap()

        return CapturedIntentSpec(
            action = action,
            dataUri = dataUri,
            component = component,
            categories = categories,
            flags = flags,
            extras = extras,
            rawIntentLine = intentLine,
            hasExtrasMarker = hasExtrasMarker
        )
    }

    private fun parseExtras(block: List<String>): Map<String, String> {
        val extras = linkedMapOf<String, String>()
        val fullText = block.joinToString("\n")
        val bundleMatch = Regex("""Bundle\[\{(.+?)\}]""", RegexOption.DOT_MATCHES_ALL).find(fullText)
        if (bundleMatch != null) {
            parseBundleContent(bundleMatch.groupValues[1], extras)
        }

        if (extras.isEmpty()) {
            var inExtrasBlock = false
            for (line in block) {
                val trimmed = line.trim()
                if (
                    trimmed.startsWith("Extras:") ||
                    trimmed.startsWith("extras:") ||
                    trimmed.startsWith("extras={") ||
                    trimmed == "extras:Bundle[{"
                ) {
                    inExtrasBlock = true
                    continue
                }
                if (!inExtrasBlock) {
                    continue
                }
                if (
                    trimmed.startsWith("}") ||
                    trimmed.startsWith("]") ||
                    trimmed.isEmpty() ||
                    trimmed.startsWith("*") ||
                    trimmed.startsWith("TASK") ||
                    trimmed.startsWith("ACTIVITY")
                ) {
                    inExtrasBlock = false
                    continue
                }
                parseExtraLine(trimmed, extras)
            }
        }
        return extras
    }

    private fun parseExtraLine(
        line: String,
        extras: MutableMap<String, String>
    ) {
        val typedMatch = Regex("""^(\w[\w.]*)\s+\(\w+\)\s*=\s*(.+)$""").find(line)
        if (typedMatch != null) {
            val key = typedMatch.groupValues[1]
            if (!isMetadataField(key)) {
                extras[key] = typedMatch.groupValues[2].trim().removeSurrounding("\"")
            }
            return
        }

        val equalSignIndex = line.indexOf('=')
        if (equalSignIndex > 0) {
            val key = line.substring(0, equalSignIndex).trim()
            val value = line.substring(equalSignIndex + 1).trim()
            if (key.matches(Regex("""\w[\w.]*""")) && !isMetadataField(key)) {
                extras[key] = value
            }
        }
    }

    private fun parseBundleContent(
        content: String,
        extras: MutableMap<String, String>
    ) {
        var depth = 0
        val currentPair = StringBuilder()
        val pairs = mutableListOf<String>()

        for (char in content) {
            when (char) {
                '[', '{' -> {
                    depth++
                    currentPair.append(char)
                }

                ']', '}' -> {
                    depth--
                    currentPair.append(char)
                }

                ',' -> {
                    if (depth == 0) {
                        pairs.add(currentPair.toString().trim())
                        currentPair.clear()
                    } else {
                        currentPair.append(char)
                    }
                }

                '\n' -> {
                    // 忽略换行，只关心键值内容。
                }

                else -> currentPair.append(char)
            }
        }

        if (currentPair.isNotBlank()) {
            pairs.add(currentPair.toString().trim())
        }

        for (pair in pairs) {
            val equalSignIndex = pair.indexOf('=')
            if (equalSignIndex > 0) {
                val key = pair.substring(0, equalSignIndex).trim()
                val value = pair.substring(equalSignIndex + 1).trim()
                if (key.isNotEmpty() && !isMetadataField(key)) {
                    extras[key] = value
                }
            }
        }
    }

    private fun extractField(line: String, fieldName: String): String? {
        val regex = Regex("""$fieldName=(\S+)""")
        return regex.find(line)?.groupValues?.get(1)?.trimEnd(')')
    }

    private fun isMetadataField(key: String): Boolean {
        return key in setOf(
            "packageName", "processName", "launchedFromUid", "launchedFromPackage",
            "userId", "app", "mActivityComponent", "baseIntent", "realActivity",
            "taskAffinity", "mCallingUid", "mCallingPackage", "stateNotNeeded",
            "componentSpecified", "mActivityType", "frontOfTask", "isRootOfTask",
            "immersive", "mHasBeenVisible", "mClientVisibilityState",
            "compat", "labelRes", "icon", "theme", "config", "taskDescription",
            "mLastReportedConfigurations", "CurrentConfiguration"
        )
    }
}
