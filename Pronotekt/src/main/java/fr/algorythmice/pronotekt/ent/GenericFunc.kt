@file:Suppress("unused")
package fr.algorythmice.pronotekt.ent

import fr.algorythmice.pronotekt.ENTLoginError
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.logging.Logger

private val entLog: Logger = Logger.getLogger("PronotepyENTGeneric")

private val HEADERS = mapOf(
    "User-Agent" to "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:73.0) Gecko/20100101 Firefox/73.0",
)

data class HttpResponse(
    val status: Int,
    val body: String,
    val url: String,
    val cookies: Map<String, String>,
    val location: String? = null,
)

private fun encodeForm(data: Map<String, String>): String =
    data.entries.joinToString("&") {
        "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
    }

private fun readResponse(conn: HttpURLConnection): HttpResponse {
    val body = try {
        BufferedReader(InputStreamReader(if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream)).use { it.readText() }
    } catch (_: Exception) { "" }
    val cookies = mutableMapOf<String, String>()
    conn.headerFields["Set-Cookie"]?.forEach { raw ->
        val part = raw.split(';')[0]
        val segs = part.split('=')
        if (segs.size >= 2) cookies[segs[0]] = segs.subList(1, segs.size).joinToString("=")
    }
    val location = conn.getHeaderField("Location")
    return HttpResponse(conn.responseCode, body, conn.url.toString(), cookies, location)
}

internal fun httpGet(url: String, cookies: Map<String, String> = emptyMap(), params: Map<String, String> = emptyMap()): HttpResponse {
    val fullUrl = if (params.isEmpty()) url else {
        val qs = encodeForm(params)
        if (url.contains("?")) "$url&$qs" else "$url?$qs"
    }
    val conn = URL(fullUrl).openConnection() as HttpURLConnection
    conn.instanceFollowRedirects = true
    HEADERS.forEach { (k, v) -> conn.setRequestProperty(k, v) }
    if (cookies.isNotEmpty()) conn.setRequestProperty("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
    conn.requestMethod = "GET"
    return readResponse(conn)
}

internal fun httpPost(url: String, cookies: Map<String, String> = emptyMap(), form: Map<String, String> = emptyMap(), referer: String? = null): HttpResponse {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.instanceFollowRedirects = true
    conn.doOutput = true
    conn.requestMethod = "POST"
    HEADERS.forEach { (k, v) -> conn.setRequestProperty(k, v) }
    referer?.let { conn.setRequestProperty("Referer", it) }
    conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
    if (cookies.isNotEmpty()) conn.setRequestProperty("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
    val payload = encodeForm(form)
    conn.outputStream.use { it.write(payload.toByteArray()) }
    return readResponse(conn)
}

internal fun mergeCookies(base: Map<String, String>, addition: Map<String, String>): Map<String, String> = base.toMutableMap().also { it.putAll(addition) }

private fun extractInputs(html: String, formAttrKey: String? = null, formAttrVal: String? = null): Pair<Map<String, String>, String?> {
    val formRegex = if (formAttrKey != null && formAttrVal != null) Regex("<form[^>]*$formAttrKey=\"$formAttrVal\"[^>]*>", RegexOption.IGNORE_CASE) else Regex("<form[^>]*>", RegexOption.IGNORE_CASE)
    val formMatch = formRegex.find(html)
    val start = formMatch?.range?.last ?: -1
    val endForm = if (start >= 0) html.indexOf("</form>", start, ignoreCase = true) else -1
    val formContent = if (start >= 0 && endForm > start) html.substring(start, endForm) else html
    val inputRegex = Regex("<input[^>]*name=\"([^\"]+)\"[^>]*value=\"([^\"]*)\"[^>]*>", RegexOption.IGNORE_CASE)
    val map = mutableMapOf<String, String>()
    inputRegex.findAll(formContent).forEach { m -> map[m.groupValues[1]] = m.groupValues[2] }
    val actionRegex = Regex("<form[^>]*action=\"([^\"]+)\"[^>]*>", RegexOption.IGNORE_CASE)
    val action = actionRegex.find(formMatch?.value ?: "")?.groupValues?.getOrNull(1)
    return map to action
}

private fun ssoRedirect(sessionCookies: Map<String, String>, response: HttpResponse, samlType: String, requestUrl: String = "", requestPayload: Map<String, String> = emptyMap()): Pair<HttpResponse?, Map<String, String>> {
    var cookies = sessionCookies
    var resp = response
    var html = resp.body
    var samlValue = Regex("name=\"${samlType}\"[^>]*value=\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(html)?.groupValues?.getOrNull(1)
    if (samlValue == null && resp.status == 200 && requestUrl.isNotBlank() && requestUrl != resp.url) {
        resp = httpPost(resp.url, cookies, requestPayload)
        cookies = mergeCookies(cookies, resp.cookies)
        html = resp.body
        samlValue = Regex("name=\"${samlType}\"[^>]*value=\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(html)?.groupValues?.getOrNull(1)
    }
    if (samlValue == null) return null to cookies
    val relay = Regex("name=\"RelayState\"[^>]*value=\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(html)?.groupValues?.getOrNull(1)
    val action = Regex("<form[^>]*action=\"([^\"]+)\"[^>]*>", RegexOption.IGNORE_CASE).find(html)?.groupValues?.getOrNull(1)
    val payload = mutableMapOf(samlType to samlValue)
    if (relay != null) payload["RelayState"] = relay
    val postUrl = action ?: resp.url
    val next = httpPost(postUrl, cookies, payload)
    val newCookies = mergeCookies(cookies, next.cookies)
    return next to newCookies
}

object GenericENT {
    fun educonnect(sessionUrl: String, username: String, password: String, exceptions: Boolean = true, extra: Map<String, String> = emptyMap()): Pair<HttpResponse?, Map<String, String>> {
        if (sessionUrl.isBlank()) throw ENTLoginError("Missing url attribute")
        entLog.fine("[EduConnect $sessionUrl] Logging in with $username")
        val payload = mutableMapOf("j_username" to username, "j_password" to password, "_eventId_proceed" to "")
        payload.putAll(extra)
        val resp = httpPost(sessionUrl, form = payload)
        val (redir, cookies) = ssoRedirect(resp.cookies, resp, "SAMLResponse", sessionUrl, payload)
        if (redir == null) {
            if (exceptions) throw ENTLoginError("Fail to connect with EduConnect : probably wrong login information") else return null to cookies
        }
        return redir to cookies
    }

    fun casEdu(username: String, password: String, url: String, redirectForm: Boolean = true): Map<String, String> {
        if (url.isBlank()) throw ENTLoginError("Missing url attribute")
        entLog.fine("[ENT $url] Logging in with $username")
        var cookies: Map<String, String> = emptyMap()
        val first = httpGet(url)
        cookies = mergeCookies(cookies, first.cookies)
        val resp = if (redirectForm) ssoRedirect(cookies, first, "SAMLRequest", url).first else first
        val r = resp ?: throw ENTLoginError("Connection failure")
        val (finalResp, finalCookies) = educonnect(r.url, username, password, exceptions = false)
        cookies = mergeCookies(cookies, finalCookies)
        if (finalResp == null) throw ENTLoginError("Connection failure")
        return cookies
    }

    fun cas(username: String, password: String, url: String): Map<String, String> {
        if (url.isBlank()) throw ENTLoginError("Missing url attribute")
        entLog.fine("[ENT $url] Logging in with $username")
        var cookies: Map<String, String> = emptyMap()
        val first = httpGet(url)
        cookies = mergeCookies(cookies, first.cookies)
        val html = first.body
        val (inputs, _) = extractInputs(html, "class", "cas__login-form")
        val payload = inputs.toMutableMap()
        payload["username"] = username
        payload["password"] = password
        val r = httpPost(first.url, cookies, payload)
        cookies = mergeCookies(cookies, r.cookies)
        if (r.body.contains("cas__login-form")) throw ENTLoginError("Fail to connect with CAS $url : probably wrong login information")
        return cookies
    }

    fun openEntNg(username: String, password: String, url: String): Map<String, String> {
        if (url.isBlank()) throw ENTLoginError("Missing url attribute")
        entLog.fine("[ENT $url] Logging in with $username")
        var cookies: Map<String, String> = emptyMap()
        val r = httpPost(url, form = mapOf("email" to username, "password" to password))
        cookies = mergeCookies(cookies, r.cookies)
        if (r.url.contains("login")) throw ENTLoginError("Fail to connect with Open NG $url : probably wrong login information")
        return cookies
    }

    fun openEntNgEdu(username: String, password: String, domain: String, providerId: String? = null): Map<String, String> {
        if (domain.isBlank()) throw ENTLoginError("Missing domain attribute")
        val provider = providerId ?: "$domain/auth/saml/metadata/idp.xml"
        entLog.fine("[ENT $domain] Logging in with $username")
        val entLoginPage = "https://educonnect.education.gouv.fr/idp/profile/SAML2/Unsolicited/SSO"
        var cookies: Map<String, String> = emptyMap()
        val first = httpGet(entLoginPage, params = mapOf("providerId" to provider))
        cookies = mergeCookies(cookies, first.cookies)
        val (resp, cookies2) = educonnect(first.url, username, password, exceptions = false)
        cookies = mergeCookies(cookies, cookies2)
        if (resp == null || resp.url.contains("login")) {
            return openEntNg(username, password, resp?.url ?: "$domain/auth/login")
        }
        return cookies
    }

    fun wayf(username: String, password: String, domain: String, entityID: String? = null, returnX: String? = null, redirectForm: Boolean = true): Map<String, String> {
        if (domain.isBlank()) throw ENTLoginError("Missing domain attribute")
        val entity = entityID ?: "$domain/shibboleth"
        val ret = returnX ?: "$domain/Shibboleth.sso/Login"
        entLog.fine("[ENT $domain] Logging in with $username")
        val entLoginPage = "$domain/discovery/WAYF"
        var cookies: Map<String, String> = emptyMap()
        val params = mapOf(
            "entityID" to entity,
            "returnX" to ret,
            "returnIDParam" to "entityID",
            "action" to "selection",
            "origin" to "https://educonnect.education.gouv.fr/idp",
        )
        val first = httpGet(entLoginPage, params = params)
        cookies = mergeCookies(cookies, first.cookies)
        val resp = if (redirectForm) ssoRedirect(cookies, first, "SAMLRequest", entLoginPage).first else first
        val r = resp ?: throw ENTLoginError("Connection failure")
        val (finalResp, finalCookies) = educonnect(r.url, username, password, exceptions = false)
        cookies = mergeCookies(cookies, finalCookies)
        if (finalResp == null) throw ENTLoginError("Connection failure")
        return cookies
    }

    fun ozeEnt(username: String, password: String, url: String): Map<String, String> {
        if (url.isBlank()) throw ENTLoginError("Missing url attribute")
        entLog.fine("[ENT $url] Logging in with $username")
        var cookies: Map<String, String> = emptyMap()
        val first = httpGet(url)
        cookies = mergeCookies(cookies, first.cookies)
        val domain = URL(url).host.lowercase(Locale.ROOT)
        val effectiveUsername = if (domain in username) username else "$username@$domain"
        val (inputs, _) = extractInputs(first.body, "id", "kc-form-login")
        val payload = inputs.toMutableMap()
        payload["username"] = effectiveUsername
        payload["password"] = password
        val r = httpPost(first.url, cookies, payload)
        cookies = mergeCookies(cookies, r.cookies)
        if (r.body.contains("auth_form")) throw ENTLoginError("Fail to connect with Oze ENT $url : probably wrong login information")
        // Proxy SSO retrieval not fully implemented; requires API-specific calls
        // TODO: Implement Oze API proxySSO flow to fetch Pronote session cookies.
        return cookies
    }

    fun simpleAuth(username: String, password: String, url: String, formAttr: Map<String, String> = emptyMap()): Map<String, String> {
        if (url.isBlank()) throw ENTLoginError("Missing url attribute")
        entLog.fine("[ENT $url] Logging in with $username")
        var cookies: Map<String, String> = emptyMap()
        val first = httpGet(url)
        cookies = mergeCookies(cookies, first.cookies)
        val attrKey = formAttr.keys.firstOrNull()
        val attrVal = formAttr.values.firstOrNull()
        val (inputs, _) = extractInputs(first.body, attrKey, attrVal)
        val payload = inputs.toMutableMap()
        payload["username"] = username
        payload["password"] = password
        val r = httpPost(first.url, cookies, payload)
        cookies = mergeCookies(cookies, r.cookies)
        if (r.body.contains(attrVal ?: "")) throw ENTLoginError("Fail to connect with $url : probably wrong login information")
        return cookies
    }

    fun hubEduconnect(username: String, password: String, pronoteUrl: String): Map<String, String> {
        val hubUrl = "https://hubeduconnect.index-education.net/EduConnect/cas/login"
        val url = "$hubUrl?service=$pronoteUrl"
        var cookies: Map<String, String> = emptyMap()
        val first = httpGet(url)
        cookies = mergeCookies(cookies, first.cookies)
        val resp = ssoRedirect(cookies, first, "SAMLRequest", url).first ?: throw ENTLoginError("Connection failure")
        if (resp.body.contains("L&#x27;url de service est vide")) throw ENTLoginError("Fail to connect with HubEduConnect : Service URL not provided.")
        if (resp.body.contains("n&#x27;est pas une url de confiance.")) throw ENTLoginError("Fail to connect with HubEduConnect : Service URL not trusted. Is Pronote instance supported?")
        val (finalResp, finalCookies) = educonnect(resp.url, username, password, exceptions = true)
        cookies = mergeCookies(cookies, finalCookies)
        if (finalResp == null) throw ENTLoginError("Connection failure")
        return cookies
    }
}
