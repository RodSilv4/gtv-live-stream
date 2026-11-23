package to.silva.gtvlivestream

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest

/**
 * NanoHTTPD routes for `GET /health`, `POST /show` and `POST /hide`. Every route except `/health`
 * requires the `X-Auth-Token` header (or a `?token=` query parameter) to match [tokenProvider].
 *
 * Header values and request bodies are never logged: stream URLs carry camera credentials.
 */
class HttpApiServer(
    private val port: Int,
    private val tokenProvider: () -> String,
    private val statusProvider: () -> Map<String, Any?>,
    private val onShow: (ShowRequest, () -> Unit) -> Unit,
    private val onHide: () -> Unit,
    private val defaultsProvider: () -> ShowDefaults,
    private val appVersion: String,
    private val overlayPermission: () -> Boolean,
) : NanoHTTPD(port) {

    private val tag: String = "HttpApiServer"

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri.orEmpty().trimEnd('/').ifEmpty { "/" }
        return try {
            when {
                session.method == Method.GET && uri == "/health" -> handleHealth()
                session.method == Method.POST && uri == "/show" -> handleShow(session)
                session.method == Method.POST && uri == "/hide" -> handleHide(session)
                else -> json(Response.Status.NOT_FOUND, """{"ok":false,"error":"not found"}""")
            }
        } catch (e: Exception) {
            Log.e(tag, "request failed for $uri", e)
            json(Response.Status.INTERNAL_ERROR, """{"ok":false,"error":"internal error"}""")
        }
    }

    private fun handleHealth(): Response {
        val status = try {
            statusProvider()
        } catch (e: RuntimeException) {
            emptyMap<String, Any?>()
        }
        val canOverlay = try {
            overlayPermission()
        } catch (e: RuntimeException) {
            false
        }
        val body = JSONObject()
        body.put("ok", true)
        body.put("app", "gtv-live-stream")
        body.put("version", appVersion)
        body.put("port", port)
        body.put("overlayPermission", canOverlay)
        for ((key, value) in status) {
            body.put(key, value ?: JSONObject.NULL)
        }
        return json(Response.Status.OK, body.toString())
    }

    private fun handleShow(session: IHTTPSession): Response {
        if (!isAuthorized(session)) {
            return unauthorized()
        }
        val body = try {
            val files = HashMap<String, String>()
            session.parseBody(files)
            files["postData"].orEmpty()
        } catch (e: IOException) {
            Log.w(tag, "could not read body")
            return badRequest("could not read body")
        } catch (e: ResponseException) {
            Log.w(tag, "malformed request")
            return badRequest("malformed request")
        }

        return try {
            val request = ShowRequestParser.parse(body, defaultsProvider())
            onShow(request) { }
            json(Response.Status.OK, """{"ok":true,"playing":true}""")
        } catch (e: IllegalArgumentException) {
            badRequest(e.message ?: "invalid request")
        }
    }

    private fun handleHide(session: IHTTPSession): Response {
        if (!isAuthorized(session)) {
            return unauthorized()
        }
        onHide()
        return json(Response.Status.OK, """{"ok":true}""")
    }

    private fun isAuthorized(session: IHTTPSession): Boolean {
        val expected = tokenProvider()
        if (expected.isEmpty()) {
            return false
        }
        val provided = headerToken(session) ?: queryToken(session)
        if (provided.isNullOrEmpty()) {
            return false
        }
        return MessageDigest.isEqual(
            provided.toByteArray(Charsets.UTF_8),
            expected.toByteArray(Charsets.UTF_8),
        )
    }

    private fun headerToken(session: IHTTPSession): String? {
        val headers = session.headers ?: return null
        for ((name, value) in headers) {
            if (name.equals(HEADER_AUTH, ignoreCase = true)) {
                return value
            }
        }
        return null
    }

    private fun queryToken(session: IHTTPSession): String? =
        session.parameters[PARAM_TOKEN]?.firstOrNull()

    private fun unauthorized(): Response =
        json(Response.Status.UNAUTHORIZED, """{"ok":false,"error":"bad token"}""")

    private fun badRequest(message: String): Response {
        val safe = message.replace("\\", " ").replace("\"", "'").replace("\n", " ")
        return json(Response.Status.BAD_REQUEST, """{"ok":false,"error":"$safe"}""")
    }

    private fun json(status: Response.Status, body: String): Response {
        val response = newFixedLengthResponse(status, MIME_JSON, body)
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Cache-Control", "no-store")
        return response
    }

    private companion object {
        const val MIME_JSON: String = "application/json; charset=utf-8"
        const val HEADER_AUTH: String = "X-Auth-Token"
        const val PARAM_TOKEN: String = "token"
    }
}
