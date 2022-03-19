import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.dataformat.xml.JacksonXmlModule
import com.fasterxml.jackson.dataformat.xml.XmlMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.io.File
import java.net.URL
import java.time.ZonedDateTime
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
    timer.schedule(task, getFirstTime(), 1000 * 60 * 60 * 24)
    logFile.writeText("Started task\n")
}

private fun getFirstTime() = Date.from(ZonedDateTime.now().plusDays(1).withHour(3).withMinute(0).toInstant())

private fun checkAndDelete() {
    val base = "http://127.0.0.1:32400"
    val token = "?X-Plex-Token=xxyBuhnssASHQcCwh-hx"
    val libraryURL = URL("$base/library/sections/3/all$token")
    val xmlMapper = XmlMapper(JacksonXmlModule().apply {
        setDefaultUseWrapper(false)
    }).registerKotlinModule()
        .configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES, true)
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    val watchedSeries = xmlMapper.readValue(libraryURL, MediaContainerLibrary::class.java).directory
        .filter { it.viewedLeafCount > 0 }
    val videos = watchedSeries.map {
        xmlMapper.readValue(
            URL(base + it.key.replace("children", "allLeaves") + token),
            MediaContainerSeries::class.java
        )
    }
        .flatMap { series -> series.video.filter { it.viewCount != null }.map { video -> video to series.video.none { it.viewCount == null } }  }
    val files = videos.map { xmlMapper.readValue(URL(base + it.first.key + token), MediaContainerSeries::class.java) to it.second }
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
    return if(seriesDone || f.name.matches(Regex(".*s\\d\\de\\d\\d.*", RegexOption.IGNORE_CASE))) f else a!!
}

data class MediaContainerLibrary(val directory: List<Directory>)

data class Directory(val key: String, val viewedLeafCount: Int)

data class MediaContainerSeries(val video: List<Video>)

data class Video(val viewCount: Int?, val key: String, val media: List<Media>)

data class Media(val part: List<Part>)

data class Part(val stream: List<Stream>?, val file: String)

data class Stream(val file: String?)