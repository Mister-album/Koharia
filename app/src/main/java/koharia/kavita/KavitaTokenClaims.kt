package koharia.kavita

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okio.ByteString.Companion.decodeBase64

/** Read only a token returned by the authenticated server; the server still enforces every request. */
internal fun withKavitaTokenClaims(account: KavitaAccount, json: Json): KavitaAccount {
    val payload = account.token.split('.').takeIf { it.size == 3 }?.get(1)?.decodeBase64()?.utf8()
        ?: return account
    val claims = try {
        json.parseToJsonElement(payload).jsonObject
    } catch (_: Exception) {
        throw KavitaException(KavitaException.Reason.PROTOCOL)
    }
    val id = (
        (claims["nameid"] ?: claims["http://schemas.xmlsoap.org/ws/2005/05/identity/claims/nameidentifier"])
            as? JsonPrimitive
        )?.longOrNull ?: 0
    val roleClaim = claims["role"] ?: claims["http://schemas.microsoft.com/ws/2008/06/identity/claims/role"]
    val roles = when (roleClaim) {
        is JsonArray -> roleClaim.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        is JsonPrimitive -> listOfNotNull(roleClaim.contentOrNull)
        else -> emptyList()
    }
    return account.copy(
        id = account.id.takeIf { it > 0 } ?: id,
        roles = account.roles.ifEmpty { roles },
    )
}
