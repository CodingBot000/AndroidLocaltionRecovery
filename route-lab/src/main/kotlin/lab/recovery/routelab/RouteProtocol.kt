package lab.recovery.routelab

import com.google.gson.Gson
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken

internal const val maxResponseBytes = 32 * 1024L
internal const val maxRouteEdges = 256
private val identifier = Regex("[A-Za-z0-9_-]{1,64}")
private val checksum = Regex("[a-f0-9]{64}")
private val version = Regex("[1-9][0-9]{0,9}")

internal data class RouteRequest(
    val sessionId: String,
    val requestId: String,
    val mapId: String,
    val mapChecksum: String,
    val baseRouteVersion: Int,
    val startEdgeId: String,
    val destinationEdgeId: String,
    val schemaVersion: Int = 1,
) {
    fun encode(): String {
        require(listOf(sessionId, requestId, mapId, startEdgeId, destinationEdgeId).all(identifier::matches))
        require(checksum.matches(mapChecksum) && baseRouteVersion > 0 && schemaVersion == 1)
        return Gson().toJson(this)
    }
}

internal data class RouteResponse(
    val requestId: String,
    val mapId: String,
    val mapChecksum: String,
    val baseRouteVersion: Int,
    val newRouteVersion: Int,
    val routeEdgeIds: List<String>,
    val schemaVersion: Int = 1,
) {
    companion object {
        fun decode(text: String): RouteResponse {
            JsonReader(text.reader()).use { reader ->
                reader.strictness = Strictness.STRICT
                val fields = mutableMapOf<String, Any>()
                reader.beginObject()
                while (reader.hasNext()) {
                    val key = reader.nextName()
                    require(key !in fields)
                    fields[key] = when (key) {
                        "requestId", "mapId" -> reader.string(identifier)
                        "mapChecksum" -> reader.string(checksum)
                        "schemaVersion", "baseRouteVersion", "newRouteVersion" -> reader.integer()
                        "routeEdgeIds" -> buildList {
                            reader.beginArray()
                            while (reader.hasNext()) {
                                require(size < maxRouteEdges)
                                add(reader.string(identifier))
                            }
                            reader.endArray()
                        }
                        else -> throw IllegalArgumentException("Unexpected field")
                    }
                }
                reader.endObject()
                require(reader.peek() == JsonToken.END_DOCUMENT && fields.size == 7)
                @Suppress("UNCHECKED_CAST")
                return RouteResponse(fields.getValue("requestId") as String, fields.getValue("mapId") as String,
                    fields.getValue("mapChecksum") as String, fields.getValue("baseRouteVersion") as Int,
                    fields.getValue("newRouteVersion") as Int, fields.getValue("routeEdgeIds") as List<String>,
                    fields.getValue("schemaVersion") as Int)
            }
        }

        private fun JsonReader.string(pattern: Regex): String {
            require(peek() == JsonToken.STRING)
            return nextString().also { require(pattern.matches(it)) }
        }

        private fun JsonReader.integer(): Int {
            require(peek() == JsonToken.NUMBER)
            return nextString().let { require(version.matches(it)); it.toInt() }
        }
    }
}

internal enum class RouteError { TIMEOUT, IO, HTTP_STATUS, SCHEMA, SEMANTIC, BUDGET }
internal sealed interface TransportResult {
    data class Response(val value: RouteResponse) : TransportResult
    data class Failure(
        val reason: RouteError,
        val status: Int? = null,
        val retryAfterMs: Long? = null,
        val retryable: Boolean = false,
    ) : TransportResult
}

internal fun interface RouteTransport {
    suspend fun request(query: RouteRequest): TransportResult
}
