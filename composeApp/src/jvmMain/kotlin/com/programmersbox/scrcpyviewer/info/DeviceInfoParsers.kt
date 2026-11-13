package com.programmersbox.scrcpyviewer.info

/** Pure parsers for the device info commands. They match by key, ignore `\r`, and never throw. */
object DeviceInfoParsers {
    private fun clean(text: String) = text.replace("\r", "")

    private fun first(text: String, pattern: String): String? =
        Regex(pattern, RegexOption.MULTILINE).find(text)?.groupValues?.get(1)

    /** Splits output on `@@name` marker lines. */
    fun sections(output: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        var name: String? = null
        val body = mutableListOf<String>()
        fun flush() {
            name?.let { result[it] = body.joinToString("\n") }
            body.clear()
        }
        for (line in clean(output).lines()) {
            if (line.startsWith("@@")) {
                flush()
                name = line.removePrefix("@@").trim()
            } else if (name != null) {
                body += line
            }
        }
        flush()
        return result
    }

    fun battery(text: String): BatteryInfo? {
        val t = clean(text)
        if (first(t, """^\s*present:\s*(\w+)""") == "false") return null
        val raw = first(t, """^\s*level:\s*(-?\d+)""")?.toIntOrNull() ?: return null
        val scale = first(t, """^\s*scale:\s*(\d+)""")?.toIntOrNull()?.takeIf { it > 0 } ?: 100
        fun powered(key: String) = first(t, """^\s*$key powered:\s*(\w+)""") == "true"
        return BatteryInfo(
            level = (raw * 100 / scale).coerceIn(0, 100),
            status = when (first(t, """^\s*status:\s*(\d+)""")?.toIntOrNull()) {
                2 -> ChargeStatus.Charging
                3 -> ChargeStatus.Discharging
                4 -> ChargeStatus.NotCharging
                5 -> ChargeStatus.Full
                else -> ChargeStatus.Unknown
            },
            power = when {
                powered("AC") -> PowerSource.Ac
                powered("USB") -> PowerSource.Usb
                powered("Wireless") -> PowerSource.Wireless
                powered("Dock") -> PowerSource.Dock
                else -> PowerSource.None
            },
            temperatureC = first(t, """^\s*temperature:\s*(-?\d+)""")?.toIntOrNull()?.let { it / 10.0 },
            voltageMv = first(t, """^\s*voltage:\s*(\d+)""")?.toIntOrNull(),
            health = when (first(t, """^\s*health:\s*(\d+)""")?.toIntOrNull()) {
                2 -> BatteryHealth.Good
                3 -> BatteryHealth.Overheat
                4 -> BatteryHealth.Dead
                5 -> BatteryHealth.OverVoltage
                7 -> BatteryHealth.Cold
                else -> BatteryHealth.Unknown
            },
        )
    }

    fun memory(text: String): MemoryInfo? {
        val t = clean(text)
        fun kb(key: String) = first(t, """^$key:\s*(\d+)""")?.toLongOrNull()
        val total = kb("MemTotal") ?: return null
        val available = kb("MemAvailable") ?: ((kb("MemFree") ?: return null) + (kb("Cached") ?: 0L))
        return MemoryInfo(total, available)
    }

    fun storage(df: String, diskstats: String): StorageInfo? {
        val row = Regex("""^\S+\s+(\d+)\s+(\d+)\s+\d+""", RegexOption.MULTILINE).find(clean(df)) ?: return null
        val total = row.groupValues[1].toLongOrNull() ?: return null
        val used = row.groupValues[2].toLongOrNull() ?: return null
        return StorageInfo(total * 1024, used * 1024, breakdown(clean(diskstats)))
    }

    private fun breakdown(t: String): StorageBreakdown? {
        fun size(key: String) = first(t, """^$key Size:\s*(\d+)""")?.toLongOrNull()
        val parts = listOf("App", "App Data", "Photos", "Videos", "Audio", "Downloads", "System", "Other")
            .associateWith(::size)
        if (parts.values.all { it == null }) return null
        fun v(key: String) = parts[key] ?: 0L
        return StorageBreakdown(
            apps = v("App") + v("App Data"),
            media = v("Photos") + v("Videos") + v("Audio") + v("Downloads"),
            system = v("System"),
            other = v("Other"),
        )
    }

    fun display(wm: String, displayLine: String): DisplayInfo? {
        val w = clean(wm)
        val size = Regex("""Physical size:\s*(\d+)x(\d+)""").find(w) ?: return null
        val density = first(w, """Physical density:\s*(\d+)""")?.toIntOrNull() ?: return null
        val overrideSize = Regex("""Override size:\s*(\d+)x(\d+)""").find(w)
        val line = clean(displayLine)
        val hz = listOf("""renderFrameRate\s+([\d.]+)""", """refreshRate\s+([\d.]+)""", """fps=([\d.]+)""")
            .firstNotNullOfOrNull { first(line, it)?.toDoubleOrNull() }
        return DisplayInfo(
            widthPx = size.groupValues[1].toIntOrNull() ?: return null,
            heightPx = size.groupValues[2].toIntOrNull() ?: return null,
            densityDpi = density,
            overrideWidthPx = overrideSize?.groupValues?.get(1)?.toIntOrNull(),
            overrideHeightPx = overrideSize?.groupValues?.get(2)?.toIntOrNull(),
            overrideDensityDpi = first(w, """Override density:\s*(\d+)""")?.toIntOrNull(),
            refreshHz = hz,
        )
    }

    fun network(ip: String, wifi: String): NetworkInfo {
        val w = clean(wifi)
        return NetworkInfo(
            ssid = first(w, """SSID:\s*"([^"]*)"""")?.takeIf { it.isNotEmpty() },
            ipAddress = first(clean(ip), """inet\s+(\d{1,3}(?:\.\d{1,3}){3})/"""),
            linkMbps = first(w, """(?:^|,\s*)(?:mWifiInfo\s+)?Link speed:\s*(\d+)\s*Mbps""")?.toIntOrNull(),
        )
    }

    fun software(props: String): SoftwareInfo {
        val map = clean(props).lines().mapNotNull { line ->
            val i = line.indexOf('=')
            if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim().ifEmpty { null }
        }.toMap()
        val oneUi = map["ro.build.version.oneui"]?.toIntOrNull()?.takeIf { it > 0 }
            ?.let { "${it / 10000}.${it / 100 % 100}" }
        return SoftwareInfo(
            androidVersion = map["ro.build.version.release"],
            sdk = map["ro.build.version.sdk"]?.toIntOrNull(),
            securityPatch = map["ro.build.version.security_patch"],
            buildId = map["ro.build.display.id"],
            abi = map["ro.product.cpu.abi"],
            manufacturer = map["ro.product.manufacturer"],
            model = map["ro.product.model"],
            oneUiVersion = oneUi,
            kernel = map["kernel"],
        )
    }
}
