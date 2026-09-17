package ph.anevaino

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.env.Environment
import ph.anevaino.support.PostgresContainerSupport

class FlywayStartupTest : PostgresContainerSupport() {

    @Autowired
    lateinit var environment: Environment

    @Autowired
    lateinit var flyway: Flyway

    @Test
    fun `context starts with flyway and ddl-auto validate`() {
        val locations = flyway.configuration.locations.map { it.toString() }
        assertTrue(locations.any { it.contains("db/migration") })
        assertEquals("validate", environment.getProperty("spring.jpa.hibernate.ddl-auto"))
        assertEquals("5", environment.getProperty("spring.datasource.hikari.maximum-pool-size"))
        assertFalse(environment.getProperty("spring.jpa.open-in-view", Boolean::class.java, false))
    }
}