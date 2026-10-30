import dev.nucleusframework.desktop.application.dsl.ReleaseChannel
import dev.nucleusframework.desktop.application.dsl.ReleaseType
import dev.nucleusframework.desktop.application.dsl.TargetFormat
import dev.nucleusframework.desktop.application.tasks.AbstractElectronBuilderPackageTask
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.koinCompiler)
    alias(libs.plugins.nucleus)
}

// Single source of truth: feeds packageVersion and the generated APP_VERSION constant shown in the window title.
// Local update tests only: -PappVersion=1.6.1 builds a "newer" package.
val appVersion = (findProperty("appVersion") as String?) ?: "1.7.0"

val generateAppVersion = tasks.register("generateAppVersion") {
    val version = appVersion
    val outDir = layout.buildDirectory.dir("generated/appVersion/kotlin")
    inputs.property("version", version)
    outputs.dir(outDir)
    doLast {
        val file = outDir.get().file("com/programmersbox/scrcpyviewer/AppVersion.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            package com.programmersbox.scrcpyviewer

            internal const val APP_VERSION = "$version"

            """.trimIndent()
        )
    }
}

val hostOs: String = System.getProperty("os.name").lowercase()

val hostClassifier: String = run {
    val os = hostOs
    val arch = System.getProperty("os.arch").lowercase()
    val isArm = arch == "aarch64" || arch == "arm64"
    when {
        os.contains("mac") -> if (isArm) "macosx-arm64" else "macosx-x86_64"
        os.contains("win") -> "windows-x86_64"
        else -> "linux-x86_64"
    }
}

kotlin {
    jvmToolchain(21)
    jvm()

    sourceSets {
        jvmMain { kotlin.srcDir(generateAppVersion) }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(libs.kotlinx.coroutines.swing)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.androidx.navigation3.ui)
            implementation(libs.material3.adaptive)
            implementation(libs.material3.adaptive.layout)
            implementation(libs.qrose)
            implementation(libs.jewel.decorated.window)
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.core)
            implementation(libs.koin.annotations)
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)
            implementation(libs.nucleus.updater)
            val ffmpeg = libs.versions.ffmpeg.get()
            val javacpp = libs.versions.javacpp.get()
            implementation("org.bytedeco:ffmpeg:$ffmpeg")
            implementation("org.bytedeco:ffmpeg:$ffmpeg:$hostClassifier")
            implementation("org.bytedeco:javacpp:$javacpp")
            implementation("org.bytedeco:javacpp:$javacpp:$hostClassifier")
        }
        jvmTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

// Jewel's icon libraries pull in IntelliJ's coroutines fork, a different module with the same classes but missing newer
// methods (Job.cancel$default). Both jars shipped and the fork won, crashing on cancel; use the one real coroutines.
configurations.configureEach {
    resolutionStrategy.dependencySubstitution {
        substitute(module("org.jetbrains.intellij.deps.kotlinx:kotlinx-coroutines-core-jvm"))
            .using(module("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:${libs.versions.coroutines.get()}"))
    }
}

// Run / packaging tasks (run, runDistributable, hot-reload runs, package*, create*Distributable, *RuntimeImage,
// suggestRuntimeModules, prepareAppResources), matched on the requested names with any ":project:" prefix dropped.
val needsAppRuntime: Boolean = gradle.startParameter.taskNames.any { requested ->
    val name = requested.substringAfterLast(':')
    listOf("run", "package", "Distributable", "RuntimeImage", "suggestRuntimeModules", "prepareAppResources")
        .any { name.contains(it, ignoreCase = true) }
}

// Packaging is Nucleus' (a fork of Compose Desktop's that packages through electron-builder, so it needs Node.js).
// The Compose plugin stays applied for the compiler, dependencies and hot reload, but must not get an
// `application { }` block of its own.
nucleus.application {
    mainClass = "com.programmersbox.scrcpyviewer.MainKt"
    // The custom title bar (Jewel DecoratedWindow) needs JetBrains Runtime, and Jewel 0.41 is Java 25 bytecode,
    // so the app runs on JBR 25; Gradle auto-provisions it via foojay. Compilation stays on jvmToolchain(21).
    // javaHome is a plain String, so resolve JBR 25 only when a run or packaging task is requested; tests,
    // CI test jobs and IDE sync then never download it.
    if (needsAppRuntime) {
        javaHome = javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(25))
            vendor.set(JvmVendorSpec.JETBRAINS)
        }.get().metadata.installationPath.asFile.absolutePath
    }
    // JDK 25 warns when JNI libraries (skiko, javacpp / ffmpeg) load without this.
    jvmArgs += "--enable-native-access=ALL-UNNAMED"
    // It also warns when javacpp's indexers call the terminally deprecated sun.misc.Unsafe memory methods.
    jvmArgs += "--sun-misc-unsafe-memory-access=allow"
    // Local update tests only: -PupdateFeed=http://127.0.0.1:<port> bakes a loopback update feed into the package
    // and -PdataDir an isolated data dir. See docs/nucleus-migration-report.md.
    (findProperty("updateFeed") as String?)?.let { jvmArgs += "-Dscrcpyviewer.updateFeed=$it" }
    (findProperty("dataDir") as String?)?.let { jvmArgs += "-Dscrcpyviewer.dataDir=$it" }
    nativeDistributions {
        // One updatable installer per OS. macOS also ships a ZIP: Nucleus' updater replaces the .app from it, and
        // the DMG is for first installs. Zip would build on every host, so the formats are picked per host.
        when {
            hostOs.contains("mac") -> targetFormats(TargetFormat.Dmg, TargetFormat.Zip)
            hostOs.contains("win") -> targetFormats(TargetFormat.Nsis)
            else -> targetFormats(TargetFormat.Deb)
        }
        // Never change packageName (or add windows/linux packageName overrides beyond the one below). It derives the
        // Windows electron-builder appId `com.app.ScrcpyViewer`, which NSIS uses to recognise an existing install, so a
        // change installs a second copy instead of upgrading. It is also Nucleus' app id on Windows (and Linux, via
        // linux.packageName), which names the folder of its update marker.
        packageName = "ScrcpyViewer"
        packageVersion = appVersion
        // For example ScrcpyViewer-1.6.0-mac-arm64.dmg; the plugin adds -nsis before .exe. Nucleus' updater picks
        // the file by extension (and that suffix) and by the arch in the name.
        artifactName = "ScrcpyViewer-\${version}-\${os}-\${arch}.\${ext}"
        modules("java.instrument", "java.management", "jdk.unsupported", "java.net.http")
        appResourcesRootDir.set(layout.projectDirectory.dir("bundled"))
        // Writes latest-mac.yml / latest.yml / latest-linux.yml next to the installers. Nothing is uploaded from
        // Gradle (publishMode stays Never); the release job in CI publishes them.
        publish {
            github {
                enabled = true
                owner = "jakepurple13"
                repo = "ScrcpyViewerV2"
                channel = ReleaseChannel.Latest
                releaseType = ReleaseType.Release
            }
        }
        macOS {
            // The id jpackage derived from the main class until 1.5.0. Keeping it keeps macOS privacy grants
            // (Local Network) across updates.
            bundleID = "com.programmersbox.scrcpyviewer"
            iconFile.set(project.file("icons/icon.icns"))
            // Without these, macOS Local Network privacy silently blocks the bundled adb server from reaching
            // phones over Wi-Fi ("No route to host") and from discovering them through mDNS.
            infoPlist {
                extraKeysRawXml = """
                    <key>NSLocalNetworkUsageDescription</key>
                    <string>Scrcpy Viewer connects to Android devices on your network for wireless debugging.</string>
                    <key>NSBonjourServices</key>
                    <array>
                        <string>_adb-tls-pairing._tcp</string>
                        <string>_adb-tls-connect._tcp</string>
                    </array>
                """.trimIndent()
            }
        }
        windows {
            iconFile.set(project.file("icons/icon.ico"))
            // Per-user one-click install under %LOCALAPPDATA%\Programs, so installing an update needs no UAC prompt.
            nsis {
                oneClick = true
                perMachine = false
            }
        }
        // Debian package names must be lowercase.
        linux {
            packageName = "scrcpyviewer"
            iconFile.set(project.file("icons/icon.png"))
            // electron-builder's deb target needs a maintainer; without one it requires an author email instead.
            debMaintainer = "jakepurple13 <jakepurple13@users.noreply.github.com>"
        }
    }
}

val scrcpyServerUrl = "https://github.com/Genymobile/scrcpy/releases/download/v5.0/scrcpy-server-v5.0"
// SHA-256 of scrcpy-server v5.0 (matches the GitHub release asset digest).
val scrcpyServerSha256 = "26cbc9ad0aced6c2282455bef4fb43462605c1f8758c74b4ab1dbf818c229daa"

// platform-tools zip platform -> (bundled folder, files to keep from platform-tools/)
val platformToolsTargets = listOf(
    Triple("darwin", "macos-arm64", listOf("adb", "NOTICE.txt")),
    Triple("windows", "windows-x64", listOf("adb.exe", "AdbWinApi.dll", "AdbWinUsbApi.dll", "NOTICE.txt")),
    Triple("linux", "linux-x64", listOf("adb", "NOTICE.txt")),
)

val bundledDir = layout.projectDirectory.dir("bundled")
val downloadCacheDir = layout.buildDirectory.dir("bundled-downloads")

val downloadBundledTools = tasks.register("downloadBundledTools") {
    group = "build setup"
    description = "Downloads scrcpy-server 5.0 and Android platform-tools adb into composeApp/bundled."
    val serverOut = bundledDir.file("common/scrcpy-server")
    val adbOutputs = platformToolsTargets.flatMap { (_, folder, files) ->
        files.map { bundledDir.file("$folder/$it") }
    }
    outputs.files(serverOut, adbOutputs)
    // Up to date when every output file exists.
    outputs.upToDateWhen { (listOf(serverOut) + adbOutputs).all { it.asFile.isFile } }
    val cache = downloadCacheDir
    val serverUrl = scrcpyServerUrl
    val serverSha = scrcpyServerSha256
    val targets = platformToolsTargets
    val root = bundledDir
    val isWindowsHost = System.getProperty("os.name").lowercase().contains("win")
    doLast {
        fun download(url: String, dest: File) {
            dest.parentFile.mkdirs()
            URI(url).toURL().openStream().use { input ->
                dest.outputStream().use { input.copyTo(it) }
            }
        }

        val server = serverOut.asFile
        download(serverUrl, server)
        val actual = MessageDigest.getInstance("SHA-256").digest(server.readBytes())
            .joinToString("") { "%02x".format(it) }
        if (actual != serverSha) {
            server.delete()
            error("scrcpy-server SHA-256 mismatch: expected $serverSha but got $actual")
        }

        for ((os, folder, keep) in targets) {
            val zip = cache.get().asFile.resolve("platform-tools-latest-$os.zip")
            download("https://dl.google.com/android/repository/platform-tools-latest-$os.zip", zip)
            val outDir = root.asFile.resolve(folder).also { it.mkdirs() }
            ZipFile(zip).use { zf ->
                for (name in keep) {
                    val entry = zf.getEntry("platform-tools/$name") ?: error("$name missing from $zip")
                    val out = outDir.resolve(name)
                    zf.getInputStream(entry).use { ins -> out.outputStream().use { ins.copyTo(it) } }
                    // Executable bit is meaningless on Windows; skip it there.
                    if (!isWindowsHost && !name.contains('.')) out.setExecutable(true)
                }
            }
        }
    }
}

tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(downloadBundledTools) }

// Nucleus 2.5.18 writes the package.json electron-builder reads its version from only when the file is missing, so
// after an appVersion change an incremental build names the files and latest-*.yml after the old version. Fixed
// upstream after 2.5.18 (Nucleus 85204103); remove this when upgrading. Clean CI builds never hit it.
tasks.withType<AbstractElectronBuilderPackageTask>().configureEach {
    val packageJson = destinationDir.file("package.json")
    doFirst { packageJson.get().asFile.delete() }
}

// Every JavaExec task (run, hot-reload, IDE-generated run tasks) gets the tools and the dev lookup dir.
tasks.withType<JavaExec>().configureEach {
    dependsOn(downloadBundledTools)
    systemProperty("scrcpyviewer.bundledDir", bundledDir.asFile.absolutePath)
}

// Offscreen screenshot tests are slow; run them only with -Pscreenshots.
// Real-device tests (*DeviceTest) need attached devices; run them only with -PdeviceTests.
tasks.matching { it.name == "jvmTest" }.configureEach {
    val screenshots = project.hasProperty("screenshots")
    val deviceTests = project.hasProperty("deviceTests")
    (this as Test).filter {
        if (screenshots) includeTestsMatching("*ScreenshotTest")
        if (deviceTests) includeTestsMatching("*DeviceTest")
        if (!screenshots) excludeTestsMatching("*ScreenshotTest")
        if (!deviceTests) excludeTestsMatching("*DeviceTest")
    }
    if (deviceTests) {
        systemProperty("scrcpyviewer.bundledDir", layout.projectDirectory.dir("bundled").asFile.absolutePath)
        project.findProperty("deviceSerials")?.let { systemProperty("scrcpyviewer.deviceSerials", it.toString()) }
        project.findProperty("audioSeconds")?.let { systemProperty("scrcpyviewer.audioSeconds", it.toString()) }
        testLogging { showStandardStreams = true }
        outputs.upToDateWhen { false }
    }
}
