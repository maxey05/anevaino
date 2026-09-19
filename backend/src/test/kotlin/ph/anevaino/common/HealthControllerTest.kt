package ph.anevaino.common

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import ph.anevaino.common.health.HealthController
import ph.anevaino.common.web.RequestIdFilter
import ph.anevaino.common.web.SecurityHeadersConfig

@WebMvcTest(controllers = [HealthController::class])
@Import(RequestIdFilter::class, SecurityHeadersConfig::class)
class HealthControllerTest(@Autowired val mvc: MockMvc) {

    @Test
    fun `health returns UP`() {
        mvc.get("/api/health").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("UP") }
        }
    }

    @Test
    fun `response has X-Request-Id and security headers`() {
        mvc.get("/api/health").andExpect {
            status { isOk() }
            header { exists("X-Request-Id") }
            header { string("X-Content-Type-Options", "nosniff") }
            header { string("Referrer-Policy", "no-referrer") }
            header { exists("Content-Security-Policy") }
            header { exists("Strict-Transport-Security") }
        }
    }
}