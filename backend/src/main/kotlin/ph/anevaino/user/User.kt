package ph.anevaino.user

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

enum class Theme { SYSTEM, LIGHT, DARK }

@Entity
@Table(name = "app_user")
class User(
    @Id
    @Column(name = "id", nullable = false)
    val id: UUID = UUID.randomUUID(),
    @Column(name = "email", nullable = false, unique = true)
    val email: String,
    @Column(name = "display_name", nullable = false)
    var displayName: String,
    @Column(name = "unit_cap")
    var unitCap: Int? = null,
    @Enumerated(EnumType.STRING)
    @Column(name = "theme", nullable = false)
    var theme: Theme = Theme.SYSTEM,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
)