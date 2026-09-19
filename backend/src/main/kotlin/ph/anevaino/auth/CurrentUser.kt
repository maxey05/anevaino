package ph.anevaino.auth

import java.io.Serializable
import java.security.Principal
import java.util.UUID

@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class CurrentUser

data class AnevainoPrincipal(val userId: UUID) : Principal, Serializable {
    override fun getName(): String = userId.toString()
}