package com.programmersbox.scrcpyviewer.update

import com.programmersbox.scrcpyviewer.APP_VERSION
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.updater.DownloadProgress
import dev.nucleusframework.updater.NucleusUpdater
import dev.nucleusframework.updater.UpdateEvent
import dev.nucleusframework.updater.UpdateInfo
import dev.nucleusframework.updater.UpdateResult
import dev.nucleusframework.updater.provider.GenericProvider
import dev.nucleusframework.updater.provider.GitHubProvider
import dev.nucleusframework.updater.provider.UpdateProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.core.annotation.Provided
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration

/**
 * The part of Nucleus' updater that [UpdateManager] uses, plus the release notes Nucleus' metadata lacks. Given to
 * Koin at start; tests give one that never touches the network ([NucleusUpdater] is a final class).
 */
@Provided
interface UpdateBackend {
    /**
     * Whether Nucleus can install an update into this copy of the app. False in dev runs (`./gradlew run`) and in
     * builds that are not one of the published installers.
     */
    val canInstall: Boolean

    suspend fun check(): UpdateResult

    /**
     * The version in the published update metadata, or null when it cannot be read. Used when [check] finds a newer
     * release with no file for this OS or architecture, to still offer "Download from GitHub".
     */
    suspend fun latestVersion(): String?

    /** The GitHub release body for [version], or null on any failure (no notes are shown then). */
    suspend fun releaseNotes(version: String): String?

    /** Downloads and verifies (SHA-512) the installer; the last emission carries the file. */
    fun download(info: UpdateInfo): Flow<DownloadProgress>

    /** Starts Nucleus' detached install script and exits the JVM. Does not return when the script starts. */
    fun installAndRestart(file: File)

    /** Starts Nucleus' detached install script and exits the JVM, without reopening the app afterwards. */
    fun installAndQuit(file: File)

    /** The `old -> new` marker Nucleus writes before installing, removed once read. */
    fun consumeUpdateEvent(): UpdateEvent?
}

/**
 * [NucleusUpdater] reading GitHub releases (`latest-mac.yml`, `latest.yml`, `latest-linux.yml`). The
 * `scrcpyviewer.updateFeed` system property points it at another folder of update metadata instead (HTTPS, or HTTP
 * on loopback), for local update tests.
 *
 * [cacheDir] holds the last downloaded installer, which Nucleus keeps so the next update can download only the
 * blocks that changed. Release notes come from the GitHub API at [apiBase] (the `scrcpyviewer.updateApiBase`
 * system property overrides it).
 */
class NucleusUpdateBackend(
    private val cacheDir: Path,
    private val feed: String? = System.getProperty("scrcpyviewer.updateFeed")?.takeIf { it.isNotBlank() },
    private val repo: String = updateRepo(),
    private val apiBase: String = System.getProperty("scrcpyviewer.updateApiBase") ?: "https://api.github.com",
) : UpdateBackend {
    private val http: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    private val provider: UpdateProvider = feed?.let(::GenericProvider)
        ?: GitHubProvider(owner = repo.substringBefore('/'), repo = repo.substringAfter('/'))

    private fun updater(type: String?): NucleusUpdater {
        val cache = cacheDir.toFile()
        return NucleusUpdater {
            currentVersion = APP_VERSION
            provider = this@NucleusUpdateBackend.provider
            cacheDir = cache
            httpClient = http
            if (type != null) executableType = type
        }
    }

    /** Knows from the packaging (the plugin's `nucleus.executable.type`) which installer this copy came from. */
    private val installer = updater(type = null)

    override val canInstall: Boolean = installer.isUpdateSupported()

    /**
     * Nucleus answers "no update" whenever it cannot install one. The check must still find newer releases so the
     * UI can offer "Download from GitHub", so a copy that cannot install checks as this OS's published installer.
     */
    private val checker = if (canInstall) installer else updater(publishedExecutableType())

    override suspend fun check(): UpdateResult = checker.checkForUpdates()

    override suspend fun latestVersion(): String? {
        // The GitHub provider may itself call the API to find the release, so this runs off the main thread too.
        val url = withContext(Dispatchers.IO) {
            runCatching { provider.resolveMetadataUrl("latest", Platform.Current, http) }.getOrNull()
        } ?: return null
        return fetch(url)?.let(::metadataVersion)
    }

    override suspend fun releaseNotes(version: String): String? =
        fetch("$apiBase/repos/$repo/releases/tags/v$version", "application/vnd.github+json")?.let(::releaseBody)

    override fun download(info: UpdateInfo): Flow<DownloadProgress> = installer.downloadUpdate(info)

    override fun installAndRestart(file: File) = installer.installAndRestart(file)

    override fun installAndQuit(file: File) = installer.installAndQuit(file)

    override fun consumeUpdateEvent(): UpdateEvent? = installer.consumeUpdateEvent()

    /** The body of a 2xx GET, or null on any failure. */
    private suspend fun fetch(url: String, accept: String? = null): String? = withContext(Dispatchers.IO) {
        runCatching {
            val request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "ScrcpyViewer/$APP_VERSION")
                .timeout(Duration.ofSeconds(10))
                .apply { accept?.let { header("Accept", it) } }
                .GET()
                .build()
            http.send(request, HttpResponse.BodyHandlers.ofString())
                .takeIf { it.statusCode() in 200..299 }
                ?.body()
        }.onFailure { log.log(System.Logger.Level.INFO, "Could not fetch $url", it) }.getOrNull()
    }

    private companion object {
        val log: System.Logger = System.getLogger("scrcpyviewer.Update")
    }
}

private val json = Json { ignoreUnknownKeys = true }

/** The non-blank `body` of a GitHub release JSON object, or null. */
internal fun releaseBody(releaseJson: String): String? = runCatching {
    json.parseToJsonElement(releaseJson).jsonObject["body"]?.jsonPrimitive?.contentOrNull
}.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }

/** The top-level `version:` of electron-builder update metadata (`latest-*.yml`), or null. */
internal fun metadataVersion(yml: String): String? = yml.lineSequence()
    .firstOrNull { it.startsWith("version:") }
    ?.substringAfter("version:")
    ?.trim()
    ?.trim('\'', '"')
    ?.takeIf { it.isNotEmpty() }

/** The installer CI publishes for this OS, as a Nucleus executable type; null on other systems. */
internal fun publishedExecutableType(osName: String = System.getProperty("os.name")): String? {
    val os = osName.lowercase()
    return when {
        "mac" in os -> "zip"
        "win" in os -> "nsis"
        "linux" in os -> "deb"
        else -> null
    }
}
