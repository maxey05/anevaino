package ph.anevaino.user

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import ph.anevaino.auth.CurrentUser
import java.util.UUID

@RestController
class MeController(private val userService: UserService) {
    @GetMapping("/api/me")
    fun me(
        @CurrentUser userId: UUID,
    ): MeDto = userService.toMeDto(userService.requireById(userId))
}