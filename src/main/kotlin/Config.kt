data class Config(val plexBaseDirectory: String?, val unwantedFileExtensions: List<String>, val mainUserToken: String, val users: List<User>)

data class User(val name: String, val token: String, val subscriptions: List<String>)
