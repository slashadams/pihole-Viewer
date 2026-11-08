package com.devadam.piholeviewer

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Thrown when an HTTPS Pi-hole instance presents a self-signed or untrusted certificate
 * whose SHA-256 fingerprint has not yet been pinned by the user for this instance,
 * or differs from the certificate fingerprint previously pinned for this instance.
 */
class UntrustedCertException(
    val instanceId: String,
    val instanceName: String,
    val fingerprintSha256: String,
    val subjectDn: String,
    val issuerDn: String,
    val previousPinnedSha256: String? = null
) : Exception("Untrusted certificate ($fingerprintSha256) for $instanceName") {
    val isPinMismatch: Boolean
        get() = !previousPinnedSha256.isNullOrBlank() &&
            !previousPinnedSha256.trim().equals(fingerprintSha256.trim(), ignoreCase = true)
}

data class PiHoleListEntry(
    val id: Int,
    val address: String,
    val type: String, // "block" or "allow"
    val comment: String,
    val enabled: Boolean,
    val groups: List<Int> = listOf(0),
    val numberDomains: Int = 0
)

/**
 * Handles Pi-hole v6 REST API calls, per-instance certificate pinning (no global SSL bypass),
 * and automatic session token management (auto re-login on 401 expiry + explicit DELETE /api/auth logout).
 */
object PiHoleApiClient {

    // In-memory cache of active session tokens keyed by instance ID
    private val sessionTokens = ConcurrentHashMap<String, String>()
    private val authMutexes = ConcurrentHashMap<String, Mutex>()

    private fun mutexFor(instanceId: String): Mutex =
        authMutexes.computeIfAbsent(instanceId) { Mutex() }

    fun getCachedSession(instanceId: String): String? = sessionTokens[instanceId]

    fun setCachedSession(instanceId: String, sid: String?) {
        if (sid == null) {
            sessionTokens.remove(instanceId)
        } else {
            sessionTokens[instanceId] = sid
        }
    }

    /**
     * Computes a human-readable colon-separated SHA-256 fingerprint of an X.509 certificate.
     */
    fun sha256Fingerprint(cert: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        return digest.joinToString(":") { "%02X".format(it) }
    }

    /**
     * Builds an SSLSocketFactory and HostnameVerifier scoped strictly to a single PiHoleInstance.
     * - Checks standard Android system CA trust first.
     * - If standard validation fails (e.g., Pi-hole v6 self-signed cert), checks whether the leaf
     *   certificate's SHA-256 fingerprint matches the user-pinned fingerprint for THIS instance only.
     * - If not pinned, captures the certificate details so the UI can ask the user whether to pin it.
     */
    private class InstanceCertCheck(val instance: PiHoleInstance) {
        var untrustedCert: X509Certificate? = null

        fun configureHttps(conn: HttpsURLConnection, expectedHost: String) {
            val defaultTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
                init(null as KeyStore?)
            }
            val systemTm = defaultTmf.trustManagers.filterIsInstance<X509TrustManager>().first()

            val pinnedFp = instance.pinnedCertSha256?.trim()?.uppercase(Locale.US)

            val customTm = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                    systemTm.checkClientTrusted(chain, authType)
                }

                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                    if (chain.isNullOrEmpty()) throw CertificateException("Empty certificate chain")
                    val leaf = chain[0]
                    val actualFp = sha256Fingerprint(leaf).uppercase(Locale.US)
                    if (!pinnedFp.isNullOrEmpty()) {
                        if (actualFp == pinnedFp) {
                            // Explicitly pinned by the user for this Pi-hole instance
                            return
                        }
                        untrustedCert = leaf
                        throw CertificateException("Pinned certificate mismatch for ${instance.name}: expected $pinnedFp, got $actualFp")
                    }
                    try {
                        systemTm.checkServerTrusted(chain, authType)
                        return
                    } catch (sysEx: CertificateException) {
                        untrustedCert = leaf
                        throw sysEx
                    }
                }

                override fun getAcceptedIssuers(): Array<X509Certificate> = systemTm.acceptedIssuers
            }

            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, arrayOf(customTm), SecureRandom())
            conn.sslSocketFactory = sslContext.socketFactory

            conn.hostnameVerifier = HostnameVerifier { hostname, session ->
                // If the user pinned the exact certificate for this instance's host, allow the host
                // (since Pi-hole self-signed certs are often issued to "pi.hole" while accessed via LAN IP)
                if (!pinnedFp.isNullOrEmpty() && hostname.equals(expectedHost, ignoreCase = true)) {
                    try {
                        val peerCerts = session.peerCertificates
                        if (peerCerts.isNotEmpty() && peerCerts[0] is X509Certificate) {
                            val actualFp = sha256Fingerprint(peerCerts[0] as X509Certificate).uppercase(Locale.US)
                            if (actualFp == pinnedFp) return@HostnameVerifier true
                        }
                    } catch (_: Exception) {
                    }
                }
                val verified = HttpsURLConnection.getDefaultHostnameVerifier().verify(hostname, session)
                if (!verified) {
                    try {
                        val peerCerts = session.peerCertificates
                        if (peerCerts.isNotEmpty() && peerCerts[0] is X509Certificate) {
                            untrustedCert = peerCerts[0] as X509Certificate
                        }
                    } catch (_: Exception) {
                    }
                }
                verified
            }
        }
    }

    private data class HttpResponse(
        val code: Int,
        val body: String
    )

    private fun executeHttp(
        instance: PiHoleInstance,
        method: String,
        path: String,
        sid: String? = null,
        jsonBody: String? = null,
        timeoutMs: Int = 8000,
        redirectCount: Int = 0
    ): HttpResponse {
        val cleanBase = sanitizeUrl(instance.url)
        val fullUrl = if (path.startsWith("http://") || path.startsWith("https://")) {
            URL(path)
        } else {
            URL("$cleanBase$path")
        }
        val conn = fullUrl.openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = false
        val certCheck = if (conn is HttpsURLConnection) {
            InstanceCertCheck(instance).also { it.configureHttps(conn, fullUrl.host) }
        } else null

        try {
            conn.requestMethod = method
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("Accept", "application/json")
            if (sid != null) {
                conn.setRequestProperty("X-FTL-SID", sid)
            }
            if (jsonBody != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                val bytes = jsonBody.toByteArray(Charsets.UTF_8)
                conn.setRequestProperty("Content-Length", bytes.size.toString())
                conn.outputStream.use { it.write(bytes) }
            }

            val code = conn.responseCode
            if (code in listOf(301, 302, 303, 307, 308) && redirectCount < 3) {
                val location = conn.getHeaderField("Location")
                if (!location.isNullOrBlank()) {
                    val redirectedUrl = URL(fullUrl, location).toString()
                    return executeHttp(
                        instance = instance,
                        method = method,
                        path = redirectedUrl,
                        sid = sid,
                        jsonBody = jsonBody,
                        timeoutMs = timeoutMs,
                        redirectCount = redirectCount + 1
                    )
                }
            }

            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return HttpResponse(code, body)
        } catch (sslEx: javax.net.ssl.SSLException) {
            val captured = certCheck?.untrustedCert
            if (captured != null) {
                throw UntrustedCertException(
                    instanceId = instance.id,
                    instanceName = instance.name,
                    fingerprintSha256 = sha256Fingerprint(captured),
                    subjectDn = captured.subjectX500Principal.name,
                    issuerDn = captured.issuerX500Principal.name,
                    previousPinnedSha256 = instance.pinnedCertSha256?.takeIf { it.isNotBlank() }
                )
            }
            throw sslEx
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Authenticates with POST /api/auth and stores the session ID in memory.
     */
    suspend fun login(instance: PiHoleInstance): String? = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("password", instance.password)
        }.toString()

        val resp = executeHttp(
            instance = instance,
            method = "POST",
            path = "/api/auth",
            jsonBody = payload
        )
        if (resp.code != 200) {
            Log.e("PiHoleApi", "Login failed (${resp.code}) for ${instance.name}: ${resp.body.take(200)}")
            return@withContext null
        }
        if (!resp.body.trimStart().startsWith("{")) {
            throw Exception("Received HTML instead of JSON from ${instance.url} (check URL)")
        }
        val json = JSONObject(resp.body)
        val sid = json.optJSONObject("session")?.optString("sid", "")?.takeIf { it.isNotEmpty() }
        if (sid != null) {
            sessionTokens[instance.id] = sid
        }
        sid
    }

    /**
     * Logs out cleanly via DELETE /api/auth so Pi-hole v6 doesn't run out of session slots.
     */
    suspend fun logout(instance: PiHoleInstance): Boolean = withContext(Dispatchers.IO) {
        val sid = sessionTokens.remove(instance.id) ?: instance.sessionToken ?: return@withContext true
        try {
            val resp = executeHttp(
                instance = instance,
                method = "DELETE",
                path = "/api/auth",
                sid = sid
            )
            resp.code in 200..299 || resp.code == 401
        } catch (e: Exception) {
            Log.w("PiHoleApi", "Logout failed for ${instance.name}: ${e.message}")
            false
        }
    }

    /**
     * Runs an authenticated request; if the session is missing or expired (HTTP 401),
     * automatically logs in again once and retries the request.
     */
    private suspend fun requestWithAutoAuth(
        instance: PiHoleInstance,
        method: String,
        path: String,
        jsonBody: String? = null,
        timeoutMs: Int = 8000
    ): HttpResponse = withContext(Dispatchers.IO) {
        val mutex = mutexFor(instance.id)
        var sid = mutex.withLock {
            sessionTokens[instance.id] ?: instance.sessionToken?.also { sessionTokens[instance.id] = it }
                ?: login(instance)
        } ?: return@withContext HttpResponse(401, "Authentication failed (check Web Admin password)")

        var resp = executeHttp(instance, method, path, sid, jsonBody, timeoutMs)
        if (resp.code == 401) {
            Log.i("PiHoleApi", "Session expired (401) on ${instance.name}, re-authenticating...")
            val newSid = mutex.withLock {
                // Only login if another coroutine hasn't already refreshed the token
                val current = sessionTokens[instance.id]
                if (current != null && current != sid) {
                    current
                } else {
                    sessionTokens.remove(instance.id)
                    login(instance)
                }
            }
            if (newSid != null) {
                sid = newSid
                resp = executeHttp(instance, method, path, sid, jsonBody, timeoutMs)
            }
        }
        resp
    }

    /**
     * Fetches summary stats (/api/stats/summary) AND blocking state (/api/dns/blocking).
     */
    suspend fun fetchStatsAndStatus(instance: PiHoleInstance): PiHoleStats = withContext(Dispatchers.IO) {
        val summaryResp = requestWithAutoAuth(instance, "GET", "/api/stats/summary")
        if (summaryResp.code == 401 || summaryResp.code == 403) {
            throw Exception("Invalid password or unauthorized (HTTP ${summaryResp.code})")
        }
        if (summaryResp.code !in 200..299) {
            throw Exception("Stats HTTP ${summaryResp.code}")
        }
        val blockingResp = requestWithAutoAuth(instance, "GET", "/api/dns/blocking")

        val root = JSONObject(summaryResp.body)
        val queriesObj = root.optJSONObject("queries") ?: root
        val gravityObj = root.optJSONObject("gravity")

        val blockingStatus = if (blockingResp.code in 200..299 && blockingResp.body.isNotEmpty()) {
            val bJson = JSONObject(blockingResp.body)
            val bVal = bJson.optString("blocking", "")
            when {
                bVal.equals("enabled", ignoreCase = true) || bVal.equals("true", ignoreCase = true) -> "enabled"
                bVal.equals("disabled", ignoreCase = true) || bVal.equals("false", ignoreCase = true) -> "disabled"
                else -> "unknown"
            }
        } else {
            "unknown"
        }

        PiHoleStats(
            domainsBlocked = gravityObj?.optInt("domains_being_blocked", 0)
                ?: root.optInt("domains_being_blocked", 0),
            dnsQueries = queriesObj.optInt("total", 0),
            queriesBlocked = queriesObj.optInt("blocked", 0),
            percentBlocked = queriesObj.optDouble("percent_blocked", 0.0).toFloat(),
            status = blockingStatus
        )
    }

    /**
     * Toggles DNS blocking via POST /api/dns/blocking.
     */
    suspend fun setBlockingStatus(
        instance: PiHoleInstance,
        enable: Boolean,
        durationSeconds: Int? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("blocking", enable)
            if (!enable && durationSeconds != null) {
                put("timer", durationSeconds)
            }
        }.toString()

        val resp = requestWithAutoAuth(instance, "POST", "/api/dns/blocking", payload)
        resp.code in 200..299
    }

    /**
     * Fetches recent queries from GET /api/queries.
     * Properly identifies Pi-hole v6 blocked status values (GRAVITY, REGEX, BLACKLIST, EXTERNAL_*, CNAME, etc.)
     * and preserves raw timestamp for accurate cross-instance sorting.
     */
    suspend fun fetchQueryLog(instance: PiHoleInstance, limit: Int = 25): List<QueryLogEntry> =
        withContext(Dispatchers.IO) {
            val resp = requestWithAutoAuth(instance, "GET", "/api/queries?length=$limit")
            if (resp.code !in 200..299) return@withContext emptyList()

            val json = JSONObject(resp.body)
            val queries = json.optJSONArray("queries") ?: return@withContext emptyList()
            val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

            (0 until queries.length()).map { i ->
                val q = queries.getJSONObject(i)
                val tsSeconds = q.optDouble("time", q.optDouble("timestamp", 0.0)).toLong()
                val rawStatus = q.optString("status", "UNKNOWN").uppercase(Locale.US)
                val isBlocked = rawStatus.contains("GRAVITY") ||
                    rawStatus.contains("BLACKLIST") ||
                    rawStatus.contains("REGEX") ||
                    rawStatus.contains("DENY") ||
                    rawStatus.contains("BLOCKED") ||
                    rawStatus.contains("EXTERNAL") ||
                    rawStatus.contains("CNAME") ||
                    rawStatus.contains("SPECIAL_DOMAIN")

                val clientObj = q.optJSONObject("client")
                val clientStr = clientObj?.optString("name")?.takeIf { it.isNotEmpty() && it != "null" }
                    ?: clientObj?.optString("ip")
                    ?: q.optString("client", "unknown")

                QueryLogEntry(
                    timestampSeconds = tsSeconds,
                    time = sdf.format(Date(tsSeconds * 1000)),
                    domain = q.optString("domain", "unknown"),
                    client = clientStr,
                    status = if (isBlocked) "Blocked ($rawStatus)" else "Allowed ($rawStatus)",
                    instanceName = instance.name
                )
            }
        }

    // =========================================================================
    // Feature 1: Lists API (/api/lists) and Gravity Update (/api/action/gravity)
    // =========================================================================

    /**
     * Fetches subscribed adlists/allowlists from GET /api/lists.
     */
    suspend fun fetchLists(instance: PiHoleInstance): List<PiHoleListEntry> = withContext(Dispatchers.IO) {
        val resp = requestWithAutoAuth(instance, "GET", "/api/lists")
        if (resp.code !in 200..299) {
            throw Exception("Failed to load lists (${resp.code})")
        }
        val json = JSONObject(resp.body)
        val arr = json.optJSONArray("lists") ?: return@withContext emptyList()

        (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            val groupsJson = obj.optJSONArray("groups")
            val groupsList = if (groupsJson != null) {
                (0 until groupsJson.length()).map { g -> groupsJson.getInt(g) }
            } else {
                listOf(0)
            }
            PiHoleListEntry(
                id = obj.optInt("id", i),
                address = obj.optString("address", ""),
                type = obj.optString("type", "block"),
                comment = if (obj.isNull("comment")) "" else obj.optString("comment", ""),
                enabled = obj.optBoolean("enabled", true),
                groups = groupsList,
                numberDomains = obj.optInt("number", 0)
            )
        }
    }

    /**
     * Adds a new list via POST /api/lists.
     * Pi-hole v6 accepts {"address": "...", "type": "block"|"allow", "comment": "...", "enabled": true, "groups": [0]}.
     */
    suspend fun addList(
        instance: PiHoleInstance,
        address: String,
        type: String = "block",
        comment: String = "",
        enabled: Boolean = true
    ): Boolean = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("address", address.trim())
            put("type", type)
            put("comment", comment.trim())
            put("enabled", enabled)
            put("groups", JSONArray().put(0))
        }.toString()

        val resp = requestWithAutoAuth(instance, "POST", "/api/lists", payload)
        resp.code in 200..299
    }

    /**
     * Updates an existing list (enable/disable or comment) via PUT /api/lists/{address}?type={type}.
     */
    suspend fun updateList(
        instance: PiHoleInstance,
        entry: PiHoleListEntry,
        newEnabled: Boolean,
        newComment: String = entry.comment
    ): Boolean = withContext(Dispatchers.IO) {
        val encodedAddr = URLEncoder.encode(entry.address, "UTF-8").replace("+", "%20")
        val encodedType = URLEncoder.encode(entry.type, "UTF-8")
        val groupsArr = JSONArray().apply {
            (entry.groups.ifEmpty { listOf(0) }).forEach { put(it) }
        }
        val payload = JSONObject().apply {
            put("comment", newComment)
            put("type", entry.type)
            put("enabled", newEnabled)
            put("groups", groupsArr)
        }.toString()

        val resp = requestWithAutoAuth(
            instance = instance,
            method = "PUT",
            path = "/api/lists/$encodedAddr?type=$encodedType",
            jsonBody = payload
        )
        resp.code in 200..299
    }

    /**
     * Deletes a list via DELETE /api/lists/{address}?type={type}.
     */
    suspend fun deleteList(
        instance: PiHoleInstance,
        address: String,
        type: String
    ): Boolean = withContext(Dispatchers.IO) {
        val encodedAddr = URLEncoder.encode(address, "UTF-8").replace("+", "%20")
        val encodedType = URLEncoder.encode(type, "UTF-8")
        val resp = requestWithAutoAuth(
            instance = instance,
            method = "DELETE",
            path = "/api/lists/$encodedAddr?type=$encodedType"
        )
        resp.code in 200..299
    }

    /**
     * Triggers a Gravity update via POST /api/action/gravity.
     * Note: Gravity updates download lists and rebuild the database, so we use a 60s read timeout.
     */
    suspend fun runGravityUpdate(instance: PiHoleInstance): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            val resp = requestWithAutoAuth(
                instance = instance,
                method = "POST",
                path = "/api/action/gravity",
                timeoutMs = 60000
            )
            val ok = resp.code in 200..299
            val summary = if (ok) {
                val tail = resp.body.lines().filter { it.isNotBlank() }.takeLast(3).joinToString("\n")
                tail.ifEmpty { "Gravity updated successfully." }
            } else {
                "Gravity failed (HTTP ${resp.code}): ${resp.body.take(200)}"
            }
            ok to summary
        }
}
