package org.example.memosm.model

data class SignInRequestV0260(
    val passwordCredentials: PasswordCredentials? = null,
    val ssoCredentials: SSOCredentialsV0260? = null
) {
    companion object {
        fun from(request: SignInRequest): SignInRequestV0260 = SignInRequestV0260(
            passwordCredentials = request.passwordCredentials,
            ssoCredentials = request.ssoCredentials?.let {
                require(it.idpName.startsWith("identity-providers/")) { "Invalid provider name" }
                val id = it.idpName.removePrefix("identity-providers/").toIntOrNull()
                require(id != null && id > 0) { "This server requires a numeric provider ID" }
                SSOCredentialsV0260(id, it.code, it.redirectUri, it.codeVerifier)
            }
        )
    }
}

data class SSOCredentialsV0260(
    val idpId: Int, val code: String, val redirectUri: String, val codeVerifier: String
)
