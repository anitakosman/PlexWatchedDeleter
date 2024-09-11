import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.xml.JacksonXmlModule
import com.fasterxml.jackson.dataformat.xml.XmlMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.sksamuel.hoplite.ConfigLoaderBuilder
import com.sksamuel.hoplite.addResourceSource
import com.sksamuel.hoplite.watch.ReloadableConfig
import java.io.File
import java.net.URL
import java.util.*
import kotlin.io.path.toPath
import kotlin.time.Duration.Companion.minutes

fun main() {
    logFile.parentFile.mkdirs()
    logFile.createNewFile()
    val timer = Timer()
    val task: TimerTask = object : TimerTask() {
        override fun run() {
            checkAndDelete()
        }
    }
    timer.schedule(task, Date(), 15.minutes.inWholeMilliseconds)
    logFile.appendText("Started task\n")
}

val logFile = MediaContainer::class.java.protectionDomain.codeSource.location.toURI().toPath().parent.toFile()
    .resolve("logs/${System.currentTimeMillis()}.txt")

val reloadableConfig =
    ReloadableConfig(ConfigLoaderBuilder.default().addResourceSource("/config.yaml").build(), Config::class)
        .addInterval(15.minutes)

const val base = "http://127.0.0.1:32400"
const val tokenQuery = "?X-Plex-Token="
const val libraryPath = "/library/sections"

val xmlMapper: ObjectMapper = XmlMapper(JacksonXmlModule().apply { setDefaultUseWrapper(false) }).registerKotlinModule()
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

private fun checkAndDelete() {
    logFile.appendText("Starting run\n")

    val config = reloadableConfig.getLatest()
    val libraries = xmlMapper.readValue(
        URL("$base$libraryPath$tokenQuery${config.mainUserToken}"),
        MediaContainer::class.java
    ).directories!!

    val subscriptions: Map<String, List<String>> = getSubscriptions(config)

    getAvailableVideos(libraries, config)
        .filter { allSubscribersWatched(it, subscriptions) }
        .flatMap { getFiles(it, config) }
        .forEach {
            logFile.appendText("Deleting: $it\n")
            if (!it.exists() || !it.deleteRecursively()) {
                logFile.appendText("Error deleting $it. Check if it still exists.\n")
            }
        }

    cleanUpUnwantedFilesAndEmptyDirectories(libraries, config)

    logFile.appendText("Finished run\n")
}

private fun getSubscriptions(config: Config) = config.users
    .flatMap { user -> user.subscriptions.map { it to user.token } }
    .groupBy({ it.first }, { it.second })
    .withDefault { emptyList() }

private fun getAvailableVideos(libraries: List<Directory>, config: Config) = libraries.flatMap { library ->
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

private fun allSubscribersWatched(video: Video, subscriptions: Map<String, List<String>>): Boolean {
    val title = video.seriesTitle ?: video.title
    val subscribers = subscriptions.getValue(title)
        .plus(subscriptions.getValue("All"))
        .minus(subscriptions.getValue("-$title"))

    return subscribers.all { token ->
        val viewCountForToken = xmlMapper.readValue(
            URL("$base${video.key}$tokenQuery$token"),
            MediaContainer::class.java
        ).videos!![0].viewCount ?: 0

        viewCountForToken > 0
    }
}

private fun getFiles(it: Video, config: Config) = xmlMapper.readValue(
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

private fun cleanUpUnwantedFilesAndEmptyDirectories(libraries: List<Directory>, config: Config) {
    libraries.forEach { library ->
        library.locations?.forEach { location ->
            File("${config.plexBaseDirectory ?: ""}${location.path}").walkBottomUp().forEach { file ->
                if (file.extension == "exe" || file.extension == "txt" || file.list()?.isEmpty() == true) {
                    logFile.appendText("Deleting: $file\n")
                    file.deleteRecursively()
                }
            }
        }
    }
}

// Plex XML Mapper data classes
data class MediaContainer(
    @JsonProperty("Directory")
    val directories: List<Directory>?,
    @JsonProperty("Video")
    val videos: List<Video>?
)

data class Directory(
    val key: String,
    val type: String,
    val title: String,
    @JsonProperty("Location")
    val locations: List<Location>?
)

data class Location(
    val path: String
)

data class Video(
    val key: String,
    val title: String,
    var seriesTitle: String?,
    val viewCount: Int?,
    @JsonProperty("Media")
    val medias: List<Media>
)

data class Media(
    @JsonProperty("Part")
    val parts: List<Part>
)

data class Part(
    @JsonProperty("Stream")
    val streams: List<Stream>?,
    val file: String
)

data class Stream(
    val file: String?
)

// Configuration data classes
data class Config(val plexBaseDirectory: String?, val mainUserToken: String, val users: List<User>)
data class User(val name: String, val token: String, val subscriptions: List<String>)