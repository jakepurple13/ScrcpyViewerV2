import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeMultiplatform)
}

val hostClassifier: String = run {
    val os = System.getProperty("os.name").lowercase()
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
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(libs.kotlinx.coroutines.swing)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.androidx.navigation3.ui)
            implementation(libs.material3.adaptive)
            implementation(libs.material3.adaptive.layout)
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

compose.desktop {
    application {
        mainClass = "com.programmersbox.scrcpyviewer.MainKt"
        // Release tasks (packageReleaseDmg, runRelease, ...) shrink with ProGuard; plain tasks don't.
        buildTypes.release.proguard {
            isEnabled.set(true)
            // Shrink only. Obfuscation hurts stack traces; the optimizer breaks Navigation3 (VerifyError in NavDisplay).
            obfuscate.set(false)
            optimize.set(false)
            configurationFiles.from(project.file("proguard-rules.pro"))
        }
        nativeDistributions {
            // From suggestRuntimeModules: JavaCPP and coroutines need these on top of the defaults.
            modules("java.instrument", "java.management", "jdk.unsupported")
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "ScrcpyViewer"
            packageVersion = "1.1.0"
            appResourcesRootDir.set(layout.projectDirectory.dir("bundled"))
            macOS { iconFile.set(project.file("icons/icon.icns")) }
            windows { iconFile.set(project.file("icons/icon.ico")) }
            // Debian package names must be lowercase.
            linux {
                packageName = "scrcpyviewer"
                iconFile.set(project.file("icons/icon.png"))
            }
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
