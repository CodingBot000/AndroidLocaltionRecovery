package lab.recovery.simulation

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

class MapFixtureReaderTest {
    private val fixture = File(checkNotNull(System.getProperty("fixtureRoot")), "extended/maps/M01.json")

    @Test fun fieldOrderDoesNotChangeTheMapAndChecksumUsesOriginalBytes() {
        val original = fixture.readBytes()
        val fields = JsonParser.parseString(original.decodeToString()).asJsonObject.entrySet().toList()
        val reordered = JsonObject().apply {
            fields.asReversed().forEach { (key, value) -> add(key, value) }
        }.toString().encodeToByteArray() + "\n ".encodeToByteArray()

        val (map, length) = readMapFixture(original.inputStream(), "M01", FixtureProfile.STANDARD)
        val (newMap, newLength) = readMapFixture(reordered.inputStream(), "M01", FixtureProfile.STANDARD)

        assertEquals(map.network.nodes, newMap.network.nodes)
        assertEquals(map.network.edges, newMap.network.edges)
        assertEquals(map.network.edges.values.map { map.network.outgoing(it.id) },
            newMap.network.edges.values.map { newMap.network.outgoing(it.id) })
        assertEquals(map.visual, newMap.visual)
        assertEquals(original.size, length)
        assertEquals(reordered.size, newLength)
        assertEquals(sha256(original), map.checksum)
        assertEquals(sha256(reordered), newMap.checksum)
        assertNotEquals(map.checksum, newMap.checksum)
    }

    @Test fun byteLimitAppliesAfterTheClosingBraceAndTrailingJsonIsRejected() {
        val original = fixture.readBytes()
        val oversize = original + ByteArray(FixtureProfile.STANDARD.byteLimit - original.size + 1) { ' '.code.toByte() }
        val error = assertThrows(InvalidFixture::class.java) {
            readMapFixture(oversize.inputStream(), "M01", FixtureProfile.STANDARD)
        }
        assertEquals(FixtureError.SIZE_LIMIT, error.error)
        val trailingObject = original + "{}".encodeToByteArray()
        val malformed = assertThrows(InvalidFixture::class.java) {
            fixtureSchema { readMapFixture(trailingObject.inputStream(), "M01", FixtureProfile.STANDARD) }
        }
        assertEquals(FixtureError.SCHEMA, malformed.error)
    }
}
