package koharia.storage

import com.hierynomus.mssmb2.SMB2Dialect
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.rapid7.client.dcerpc.RPCException
import com.rapid7.client.dcerpc.mssrvs.ServerService
import com.rapid7.client.dcerpc.transport.SMBTransportFactories
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.util.concurrent.TimeUnit

internal data class SmbAddress(val server: String, val root: String) {
    fun at(path: String): String {
        val endpoint = URI(server)
        return URI(
            "smb",
            null,
            endpoint.host,
            endpoint.port,
            "/${StoragePath.normalize(path)}",
            null,
            null,
        ).toASCIIString()
    }

    companion object {
        fun parse(value: String): SmbAddress {
            val trimmed = value.trim()
            val uri = URI(if ("://" in trimmed) trimmed else "smb://$trimmed")
            require(
                uri.scheme == "smb" && !uri.host.isNullOrBlank() && uri.userInfo == null &&
                    uri.query == null && uri.fragment == null && (uri.port == -1 || uri.port in 1..65535),
            )
            val root = StoragePath.normalize(uri.path.orEmpty())
            return SmbAddress(URI("smb", null, uri.host, uri.port, null, null, null).toASCIIString(), root)
        }
    }
}

internal fun newSmbClient() = SMBClient(
    SmbConfig.builder().withDialects(
        SMB2Dialect.SMB_3_1_1,
        SMB2Dialect.SMB_3_0_2,
        SMB2Dialect.SMB_3_0,
        SMB2Dialect.SMB_2_1,
        SMB2Dialect.SMB_2_0_2,
    ).withDfsEnabled(false).withMultiProtocolNegotiate(false)
        .withTimeout(20, TimeUnit.SECONDS).withSoTimeout(0, TimeUnit.SECONDS).build(),
)

/** Server-level enumeration uses SRVSVC over the authenticated SMB2/3 IPC pipe. */
internal suspend fun authenticateSmbServer(address: String, username: String, password: String, domain: String) =
    withContext(Dispatchers.IO) {
        val endpoint = URI(SmbAddress.parse(address).server)
        newSmbClient().use { client ->
            client.connect(endpoint.host, endpoint.port.takeIf { it > 0 } ?: 445).use { connection ->
                connection.authenticate(
                    if (username.isEmpty()) {
                        AuthenticationContext.anonymous()
                    } else {
                        AuthenticationContext(username, password.toCharArray(), domain)
                    },
                ).use { /* Authentication only: no share connection or directory enumeration. */ }
            }
        }
    }

internal suspend fun browseSmbServer(
    address: String,
    username: String,
    password: String,
    domain: String,
    path: String,
): List<StorageEntry> = withContext(Dispatchers.IO) {
    val server = SmbAddress.parse(address)
    val normalized = StoragePath.normalize(path)
    try {
        if (normalized.isNotEmpty()) {
            val share = normalized.substringBefore('/')
            val relative = normalized.substringAfter('/', "")
            SmbStorageBackend(server.at(share), username, password, domain).use { backend ->
                browseStorageDirectories(backend, relative).map {
                    it.copy(path = StoragePath.normalize("$share/${it.path}"))
                }
            }
        } else {
            val endpoint = URI(server.server)
            newSmbClient().use { client ->
                client.connect(endpoint.host, endpoint.port.takeIf { it > 0 } ?: 445).use { connection ->
                    connection.authenticate(
                        if (username.isEmpty()) {
                            AuthenticationContext.anonymous()
                        } else {
                            AuthenticationContext(username, password.toCharArray(), domain)
                        },
                    ).use { session ->
                        ServerService(SMBTransportFactories.SRVSVC.getTransport(session)).shares1
                            .filter { it.type and 0xffff == 0 }
                            .map { it.netName.trimEnd('\u0000') }
                            .filterNot { it.equals("IPC$", ignoreCase = true) }
                            .map { StorageEntry(StoragePath.child("", it), true) }
                    }
                }
            }
        }.distinctBy { it.path }.sortedWith(compareBy<StorageEntry> { it.name.lowercase() }.thenBy { it.name })
    } catch (error: CancellationException) {
        throw error
    } catch (error: StorageFailure) {
        throw error
    } catch (error: SMBApiException) {
        val reason = when (error.status.name) {
            "STATUS_LOGON_FAILURE", "STATUS_WRONG_PASSWORD" -> StorageFailure.Reason.AUTH
            "STATUS_ACCESS_DENIED" -> StorageFailure.Reason.PERMISSION
            else -> StorageFailure.Reason.NETWORK
        }
        throw StorageFailure(reason, error)
    } catch (error: RPCException) {
        throw StorageFailure(
            if (error.returnValue == 5) StorageFailure.Reason.PERMISSION else StorageFailure.Reason.PROTOCOL,
            error,
        )
    } catch (error: Exception) {
        throw StorageFailure(StorageFailure.Reason.NETWORK, error)
    }
}
