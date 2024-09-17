import com.fasterxml.jackson.annotation.JsonProperty

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
