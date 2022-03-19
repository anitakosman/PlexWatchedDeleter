import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.dataformat.xml.JacksonXmlModule
import com.fasterxml.jackson.dataformat.xml.XmlMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.io.File
import java.net.URL
import java.util.*

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
    logFile.writeText("Started task\n")
}

val logFile = File("logs/${System.currentTimeMillis()}.txt")

const val base = "http://127.0.0.1:32400"
const val tokenQuery = "?X-Plex-Token="
const val tokenAnita = "xxyBuhnssASHQcCwh-hx"
const val tokenPhedny = "opVisCZwVzuZaK3_nXt5"
val libraryURL = URL("$base/library/sections/3/all$tokenQuery$tokenAnita")

val subscriptions = mapOf("3918" to listOf(tokenAnita, tokenPhedny)).withDefault { listOf(tokenAnita) }

private fun checkAndDelete() {
    val xmlMapper = XmlMapper(JacksonXmlModule().apply {
        setDefaultUseWrapper(false)
    }).registerKotlinModule()
        .configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES, true)
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    val watchedSeries = xmlMapper.readValue(libraryURL, MediaContainerLibrary::class.java).directory
        .filter { it.viewedLeafCount > 0 || subscriptions.getValue(it.ratingKey).contains(tokenPhedny) }

    val videos = watchedSeries
        .map { directory ->
            val countedVideos = subscriptions.getValue(directory.ratingKey)
                .map { token ->
                    xmlMapper.readValue(
                        URL(base + directory.key.replace("children", "allLeaves") + tokenQuery + token),
                        MediaContainerSeries::class.java
                    )
                }
                .flatMap { it.video }
                .groupBy { it.key }
                .map { (key, videos) -> CountedVideo(key, videos.all { it.viewCount != null }) }
            CountedMediaContainerSeries(countedVideos, countedVideos.all { it.allWatched })
        }
        .flatMap { series ->
            series.videos
                .filter { it.allWatched }
                .map { RemovableVideo(it.key, series.seriesDone) }
        }

    val files = videos
        .map {
            xmlMapper.readValue(
                URL(base + it.key + tokenQuery + tokenAnita),
                MediaContainerSeries::class.java
            ) to it.seriesDone
        }
        .flatMap { (mc, seriesDone) ->
            mc.video.flatMap { video ->
                video.media.flatMap { media ->
                    media.part.flatMap { part -> part.stream!!.map { it.file }.plus(part.file) }
                }
            }.filterNotNull().map { it to seriesDone }
        }
        .map { findUpperSeriesFile(File(it.first), it.second) }

    files.forEach { logFile.writeText("Deleting: $it\n") }
    files.forEach { if (!it.deleteRecursively()) logFile.writeText("Error deleting $it\n") }
}

fun findUpperSeriesFile(file: File, seriesDone: Boolean): File {
    var a: File? = null
    var f = file
    var p = f.parentFile
    while (p.name != "Series") {
        a = f
        f = p
        p = p.parentFile
    }
    return if (seriesDone || f.name.matches(Regex(".*s\\d\\de\\d\\d.*", RegexOption.IGNORE_CASE))) f else a!!
}

data class MediaContainerLibrary(val directory: List<Directory>)

data class Directory(val key: String, val viewedLeafCount: Int, val ratingKey: String)

data class MediaContainerSeries(val video: List<Video>)

data class CountedMediaContainerSeries(val videos: List<CountedVideo>, val seriesDone: Boolean)

data class Video(val viewCount: Int?, val key: String, val media: List<Media>)

data class CountedVideo(val key: String, val allWatched: Boolean)

data class RemovableVideo(val key: String, val seriesDone: Boolean)

data class Media(val part: List<Part>)

data class Part(val stream: List<Stream>?, val file: String)

data class Stream(val file: String?)