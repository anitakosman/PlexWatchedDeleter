import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.xml.JacksonXmlModule
import com.fasterxml.jackson.dataformat.xml.XmlMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.sksamuel.hoplite.ConfigLoaderBuilder
import com.sksamuel.hoplite.addResourceSource
import com.sksamuel.hoplite.watch.ReloadableConfig
import kotlinx.datetime.*
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format.char
import java.io.File
import java.net.URL
import java.util.*
import kotlin.io.path.absolutePathString
import kotlin.io.path.toPath
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.minutes

/**
 * Base URL for local plex server
 */
const val base = "http://127.0.0.1:32400"

/**
 * Plex token query parameter
 */
const val tokenQuery = "?X-Plex-Token="

/**
 * Relative path to Plex library sections
 */
const val libraryPath = "/library/sections"

/**
 * Jackson XML Mapper which doesn't fail on unknown properties
 */
val xmlMapper: ObjectMapper = XmlMapper(JacksonXmlModule().apply { setDefaultUseWrapper(false) }).registerKotlinModule()
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

/**
 * Config loader which reads environment variables (with config.override. prefix), system properties (with config.override. prefix (so -Dconfig.override.property for JVM)),
 * user settings (in ~/.userconfig.yaml) and default configuration values (config.yaml resource) with precedence in that order
 */
val reloadableConfig =
    ReloadableConfig(ConfigLoaderBuilder.default().addResourceSource("/config.yaml").build(), Config::class)
        .addInterval(15.minutes)

/**
 * Config read from config loader
 */
var config: Config = reloadableConfig.getLatest()

/**
 * The datetime format to user for log file name
 */
val dateTimeFormat = 
    LocalDateTime.Format { year(); char('-'); monthNumber(); char('-'); dayOfMonth(); char('-'); hour(); char(':'); minute(); char(':'); second() }

/**
 * Log file with current timestamp as name
 */
val logFile = File(getLogFileLocation()).resolve("${Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).format(dateTimeFormat)}.txt")

/**
 * Creates log file and starts a task to run the checkAndDelete function every 15 minutes.
 */
fun main() {
    logFile.parentFile.mkdirs()
    logFile.createNewFile()
    val timer = Timer()
    val task: TimerTask = object : TimerTask() {
        override fun run() {
            try {
                checkAndDelete()
            } catch (_: Throwable) {
                exitProcess(1)
            }
        }
    }
    timer.schedule(task, Date(), 15.minutes.inWholeMilliseconds)
    logFile.appendText("Started task\n")
}

/**
 * Returns log file location from config with $EXECUTABLE_DIR$ replaced by the actual directory containing the executable
 */
private fun getLogFileLocation() = config.logFileDirectory
    .replace("\$EXECUTABLE_DIR\$", MediaContainer::class.java.protectionDomain.codeSource.location.toURI().toPath().parent.absolutePathString())

/**
 * Checks with files available on the plex server have been watched and thus can be deleted
 */
private fun checkAndDelete() {
    logFile.appendText("Starting run\n")
    config = reloadableConfig.getLatest()

    val libraries = xmlMapper.readValue(
        URL("$base$libraryPath$tokenQuery${config.mainUserToken}"),
        MediaContainer::class.java
    ).directories!!

    val subscriptions: Map<String, List<String>> = getSubscriptions()

    getAvailableVideos(libraries)
        .filter { allSubscribersWatched(it, subscriptions) }
        .flatMap { getFiles(it) }
        .forEach {
            logFile.appendText("Deleting: $it\n")
            if (!it.exists() || !it.deleteRecursively()) {
                logFile.appendText("Error deleting $it. Check if it still exists.\n")
            }
        }

    cleanUpUnwantedFilesAndEmptyDirectories(libraries)

    logFile.appendText("Finished run\n")
}

/**
 * Returns a map of series and/or movie titles to lists of tokens of users who have subscribed to that series or movie in the config
 */
private fun getSubscriptions() = config.users
    .flatMap { user -> user.subscriptions.map { it to user.token } }
    .groupBy({ it.first }, { it.second })
    .withDefault { emptyList() }

/**
 * Returns all available videos on the Plex server
 */
private fun getAvailableVideos(libraries: List<Directory>) = libraries.flatMap { library ->
    when (library.type) {
        "show" -> {
            xmlMapper.readValue(
                URL("$base$libraryPath/${library.key}/all$tokenQuery${config.mainUserToken}"),
                MediaContainer::class.java
            ).directories!!.flatMap { directory ->
                xmlMapper.readValue(
                    URL(
                        "$base${
                            directory.key.replace(
                                "children",
                                "allLeaves"
                            )
                        }$tokenQuery${config.mainUserToken}"
                    ), MediaContainer::class.java
                ).videos!!.onEach { it.seriesTitle = directory.title }
            }
        }

        "movie" -> {
            xmlMapper.readValue(
                URL("$base$libraryPath/${library.key}/all$tokenQuery${config.mainUserToken}"),
                MediaContainer::class.java
            ).videos!!
        }

        else -> emptyList()
    }
}

/**
 * Returns true iff all users who have subscribed to the movie or series of the video have watched the video
 */
private fun allSubscribersWatched(video: Video, subscriptions: Map<String, List<String>>): Boolean {
    val title = video.seriesTitle ?: video.title
    val subscribers = subscriptions.getValue(title)
        .plus(subscriptions.getValue("All"))
        .minus(subscriptions.getValue("-$title"))

    return subscribers.all { token ->
        val viewCountForToken = if (token == config.mainUserToken) {
            video.viewCount ?: 0
        } else {
            xmlMapper.readValue(
                URL("$base${video.key}$tokenQuery$token"),
                MediaContainer::class.java
            ).videos!![0].viewCount ?: 0
        }

        viewCountForToken > 0
    }
}

/**
 * Get all files associated with a video (including associated subtitle files)
 */
private fun getFiles(it: Video) = xmlMapper.readValue(
    URL("$base${it.key}$tokenQuery${config.mainUserToken}"), MediaContainer::class.java
).videos?.flatMap { video ->
    video.medias.flatMap { media ->
        media.parts.flatMap { part ->
            (part.streams?.mapNotNull { stream -> stream.file } ?: emptyList())
                .plus(part.file)
                .map { file -> File("${config.plexBaseDirectory ?: ""}$file") }
        }
    }
} ?: emptyList()

/**
 * Clean up files with unwanted extensions and empty directories
 */
private fun cleanUpUnwantedFilesAndEmptyDirectories(libraries: List<Directory>) {
    libraries.forEach { library ->
        library.locations?.forEach { location ->
            File("${config.plexBaseDirectory ?: ""}${location.path}").walkBottomUp().forEach { file ->
                if (file.extension in config.unwantedFileExtensions || file.list()?.isEmpty() == true) {
                    logFile.appendText("Deleting: $file\n")
                    file.deleteRecursively()
                }
            }
        }
    }
}
