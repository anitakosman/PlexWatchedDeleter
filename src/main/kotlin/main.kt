import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.xml.JacksonXmlModule
import com.fasterxml.jackson.dataformat.xml.XmlMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.io.File
import java.net.URL
import java.util.*
import kotlin.io.path.toPath

fun main() {
    logFile.parentFile.mkdirs()
    logFile.createNewFile()
    val timer = Timer()
    val task: TimerTask = object : TimerTask() {
        override fun run() {
            checkAndDelete()
        }
    }
    timer.schedule(task, Date(), 1000L * 60 * 15) //Every 15 minutes
    logFile.appendText("Started task\n")
}

val logFile = MediaContainer::class.java.protectionDomain.codeSource.location.toURI().toPath().parent.toFile()
    .resolve("logs/${System.currentTimeMillis()}.txt")

const val base = "http://127.0.0.1:32400"
const val tokenQuery = "?X-Plex-Token="
const val tokenAnita = "QrnYz46rC1iz_y7KxBrB"
const val tokenRoflin = "txqY-yZ5xxV3K7Rs2Bb9"
const val libraryPath = "/library/sections"
val subscriptions = mapOf("Succession" to listOf(tokenRoflin)).withDefault { listOf(tokenAnita) }

val xmlMapper: ObjectMapper = XmlMapper(JacksonXmlModule().apply { setDefaultUseWrapper(false) }).registerKotlinModule()
    .configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES, true)
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
val libraries =
    xmlMapper.readValue(URL("$base$libraryPath$tokenQuery$tokenAnita"), MediaContainer::class.java).directories!!

private fun checkAndDelete() {
    logFile.appendText("Starting run\n")

    val availableVideos = libraries.flatMap { library ->
        when (library.type) {
            "show" -> {
                xmlMapper.readValue(
                    URL("$base$libraryPath/${library.key}/all$tokenQuery$tokenAnita"),
                    MediaContainer::class.java
                ).directories!!.flatMap { directory ->
                    xmlMapper.readValue(
                        URL(
                            "$base${
                                directory.key.replace(
                                    "children",
                                    "allLeaves"
                                )
                            }$tokenQuery$tokenAnita"
                        ), MediaContainer::class.java
                    ).videos!!.onEach { it.seriesTitle = directory.title }
                }
            }

            "movie" -> {
                xmlMapper.readValue(
                    URL("$base$libraryPath/${library.key}/all$tokenQuery$tokenAnita"),
                    MediaContainer::class.java
                ).videos!!
            }

            else -> emptyList()
        }
    }

    val removableVideos = availableVideos.filter { video ->
        subscriptions.getValue(video.seriesTitle ?: video.title).all { token ->
            val viewCountForToken = xmlMapper.readValue(
                URL("$base${video.key}$tokenQuery$token"),
                MediaContainer::class.java
            ).videos!![0].viewCount ?: 0

            viewCountForToken > 0
        }
    }

    val files: List<File> = removableVideos.flatMap {
        xmlMapper.readValue(
            URL("$base${it.key}$tokenQuery$tokenAnita"), MediaContainer::class.java
        ).videos?.flatMap { video ->
            video.medias.flatMap { media ->
                media.parts.flatMap { part ->
                    (part.streams?.mapNotNull { stream -> stream.file } ?: emptyList())
                        .plus(part.file)
                        .map { file -> File("/home/dennis/plex$file") }
                }
            }
        } ?: emptyList()
    }

    files.forEach {
        logFile.appendText("Deleting: $it\n")
        if (!it.exists() || !it.deleteRecursively()) {
            logFile.appendText("Error deleting $it. Check if it still exists.\n")
        }
    }

    libraries.forEach { library ->
        library.locations?.forEach { location ->
            File("/home/dennis/plex${location.path}").walkBottomUp().forEach { file ->
                if (file.extension == "exe" || file.extension == "txt" || file.list()?.isEmpty() == true) {
                    logFile.appendText("Deleting: $file\n")
                    file.deleteRecursively()
                }
            }
        }
    }

    logFile.appendText("Finished run\n")
}

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