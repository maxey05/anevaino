package ph.anevaino.common

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import ph.anevaino.common.error.GlobalExceptionHandler
import ph.anevaino.common.error.NotFound
import ph.anevaino.common.error.RuleViolation
import ph.anevaino.common.error.ValidationFailure
import ph.anevaino.common.web.RequestIdFilter

@WebMvcTest(controllers = [GlobalExceptionHandlerTest.ThrowingController::class])
@Import(GlobalExceptionHandler::class, RequestIdFilter::class)
class GlobalExceptionHandlerTest(@Autowired val mvc: MockMvc) {

    @RestController
    class ThrowingController {
        @GetMapping("/test/not-found")
        fun notFound(): Nothing = throw NotFound("Curriculum 42 was not found.")

        @GetMapping("/test/validation")
        fun validation(): Nothing = throw ValidationFailure("units must be positive")

        @GetMapping("/test/rule-violation")
        fun ruleViolation(): Nothing =
            throw RuleViolation("Hard prerequisite is unmet.", mapOf("unmetPrerequisites" to listOf("CCPROG2")))

        @GetMapping("/test/unexpected")
        fun unexpected(): Nothing = throw IllegalStateException("secret")
    }

    @Test
    fun `NotFound maps to 404 problem json with requestId`() {
        mvc.get("/test/not-found").andExpect {
            status { isNotFound() }
            content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.type") { value("common/not-found") }
            jsonPath("$.requestId") { exists() }
        }
    }

    @Test
    fun `ValidationFailure maps to 400`() {
        mvc.get("/test/validation").andExpect {
            status { isBadRequest() }
            jsonPath("$.type") { value("common/validation") }
        }
    }

    @Test
    fun `RuleViolation carries extensions`() {
        mvc.get("/test/rule-violation").andExpect {
            status { isBadRequest() }
            jsonPath("$.unmetPrerequisites[0]") { value("CCPROG2") }
        }
    }

    @Test
    fun `unexpected exception yields 500 internal-error without message or stack trace`() {
        val result =
            mvc.get("/test/unexpected").andExpect {
                status { isInternalServerError() }
                jsonPath("$.type") { value("common/internal-error") }
            }.andReturn()
        val body = result.response.contentAsString
        assertFalse(body.contains("secret"))
        assertFalse(body.contains("at ph.anevaino"))
    }
}