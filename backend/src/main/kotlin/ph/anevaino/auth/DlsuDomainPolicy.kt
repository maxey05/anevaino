package ph.anevaino.auth

private const val DLSU_DOMAIN = "dlsu.edu.ph"
private const val DLSU_EMAIL_SUFFIX = "@$DLSU_DOMAIN"

object DlsuDomainPolicy {
    fun isAllowed(claims: GoogleIdTokenClaims): Boolean =
        claims.emailVerified &&
            claims.email.lowercase().endsWith(DLSU_EMAIL_SUFFIX) &&
            claims.hostedDomain?.lowercase() == DLSU_DOMAIN
}