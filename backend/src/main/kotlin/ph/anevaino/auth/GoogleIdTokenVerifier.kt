package ph.anevaino.auth

import org.springframework.beans.factory.annotation.Value
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.security.oauth2.jwt.JwtTimestampValidator
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.stereotype.Component
import ph.anevaino.common.error.InvalidToken
import java.time.Duration

private val GOOGLE_ISSUERS = setOf("https://accounts.google.com", "accounts.google.com")

data class GoogleIdTokenClaims(
    val subject: String,
    val email: String,
    val emailVerified: Boolean,
    val hostedDomain: String?,
    val name: String?,
)

@Component
class GoogleIdTokenVerifier(
    @Value("\${anevaino.auth.google-client-id:}") googleClientId: String,
    @Value("\${anevaino.auth.jwk-set-uri:https://www.googleapis.com/oauth2/v3/certs}") jwkSetUri: String,
    @Value("\${anevaino.auth.clock-skew-seconds:60}") clockSkewSeconds: Long,
) {
    private val decoder: NimbusJwtDecoder =
        NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build().apply {
            setJwtValidator(
                DelegatingOAuth2TokenValidator(
                    JwtTimestampValidator(Duration.ofSeconds(clockSkewSeconds)),
                    IssuerValidator,
                    AudienceValidator(googleClientId),
                ),
            )
        }

    fun verify(credential: String): GoogleIdTokenClaims {
        val jwt =
            try {
                decoder.decode(credential)
            } catch (ex: JwtException) {
                throw InvalidToken("The Google sign-in token could not be verified.")
            }
        val email =
            jwt.getClaimAsString("email")
                ?: throw InvalidToken("The Google sign-in token could not be verified.")
        return GoogleIdTokenClaims(
            subject = jwt.subject ?: "",
            email = email,
            emailVerified = jwt.getClaimAsBoolean("email_verified") ?: false,
            hostedDomain = jwt.getClaimAsString("hd"),
            name = jwt.getClaimAsString("name"),
        )
    }

    private object IssuerValidator : OAuth2TokenValidator<Jwt> {
        override fun validate(token: Jwt): OAuth2TokenValidatorResult =
            if (token.getClaimAsString("iss") in GOOGLE_ISSUERS) {
                OAuth2TokenValidatorResult.success()
            } else {
                OAuth2TokenValidatorResult.failure(OAuth2Error("invalid_token", "Unexpected issuer", null))
            }
    }

    private class AudienceValidator(private val clientId: String) : OAuth2TokenValidator<Jwt> {
        override fun validate(token: Jwt): OAuth2TokenValidatorResult =
            if (clientId.isNotBlank() && token.audience?.contains(clientId) == true) {
                OAuth2TokenValidatorResult.success()
            } else {
                OAuth2TokenValidatorResult.failure(OAuth2Error("invalid_token", "Unexpected audience", null))
            }
    }
}