package ph.anevaino.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import ph.anevaino.common.error.ProblemType
import tools.jackson.core.type.TypeReference 
import tools.jackson.databind.json.JsonMapper
import java.io.File

class ProblemTypesParityTest {
    private data class ProblemTypeEntry(val slug: String, val status: Int, val title: String)

    @Test
    fun `ProblemType slugs equal shared problem-types json`() {
        // Gradle runs tests with working dir = backend/
        val mapper = JsonMapper.builder().build()
        val entries =
            mapper.readValue(
                File("../shared/problem-types.json"),
                object : TypeReference<List<ProblemTypeEntry>>() {},
            )
        assertEquals(entries.map { it.slug }.toSet(), ProblemType.allSlugs.toSet())
    }
}