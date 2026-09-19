package ph.anevaino.user

import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import ph.anevaino.common.error.NotFound
import java.util.UUID

@Service
class UserService(private val userRepository: UserRepository) {
    @Transactional
    fun findOrCreate(email: String, name: String?): User {
        val normalizedEmail = email.lowercase()
        userRepository.findByEmail(normalizedEmail)?.let { return it }
        val displayName = name?.takeIf { it.isNotBlank() } ?: normalizedEmail.substringBefore('@')
        return try {
            userRepository.saveAndFlush(User(email = normalizedEmail, displayName = displayName))
        } catch (ex: DataIntegrityViolationException) {
            userRepository.findByEmail(normalizedEmail) ?: throw ex
        }
    }

    @Transactional(readOnly = true)
    fun requireById(id: UUID): User =
        userRepository.findById(id).orElseThrow { NotFound("The signed-in account no longer exists.") }

    fun toMeDto(user: User): MeDto =
        MeDto(
            id = user.id.toString(),
            email = user.email,
            displayName = user.displayName,
            unitCap = user.unitCap,
            theme = user.theme,
            hasCurriculum = false,
        )
}