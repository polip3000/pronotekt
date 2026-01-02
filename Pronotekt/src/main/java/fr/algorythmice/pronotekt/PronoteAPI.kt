@file:Suppress("unused", "UNUSED_PARAMETER", "UNCHECKED_CAST")
package fr.algorythmice.pronotekt

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.RSAPublicKeySpec
import java.time.Instant
import java.util.Base64
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Logger
import java.util.zip.Deflater
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONObject


interface HttpResponse {
    val statusCode: Int
    val content: ByteArray
    val cookies: Map<String, String>
    fun iterContent(chunkSize: Int = 1024): Sequence<ByteArray>
}

interface HttpSession {
    fun get(url: String): HttpResponse
    fun post(url: String, body: String, cookieSnapshot: Map<String, String> = emptyMap()): HttpResponse
    fun close() {}
}

interface Encryption {
    fun aes_encrypt(input: ByteArray): ByteArray
    fun aes_decrypt(data: ByteArray): ByteArray
}

interface Communication {
    val encryption: Encryption
    val root_site: String
    val session: HttpSession
    var last_ping: Long
    fun post(functionName: String, data: Map<String, Any?>, decryptionChange: Map<String, ByteArray>? = null): Map<String, Any?>
}

interface ClientBase {
    val communication: Communication
    val func_options: Map<String, Any?>
    val attributes: Map<String, Any?>
    val info: ClientInfo
    val periods: List<Period>
    fun post(path: String, function: Int, data: Map<String, Any?>): Map<String, Any?>
    fun get_week(date: java.time.LocalDate): Int
}

private val apiLog: Logger = Logger.getLogger("PronoteAPI")

private val HEADERS = mapOf(
    "User-Agent" to "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:73.0) Gecko/20100101 Firefox/73.0  PRONOTE Mobile APP",
)

private val errorMessages = mapOf(
    22 to "[ERROR 22] The object was from a previous session. Please read the \"Long Term Usage\" section in README on github.",
    10 to "[ERROR 10] Session has expired and pronotepy was not able to reinitialise the connection.",
    25 to "[ERROR 25] Exceeded max authorization requests. Please wait before retrying...",
)

class CommunicationImpl(site: String, cookies: Map<String, String>? = null) : Communication {
    override val session: HttpSession = SimpleHttpSession(HEADERS, cookies?.toMutableMap() ?: mutableMapOf())
    override val encryption: EncryptionImpl = EncryptionImpl()
    override val root_site: String
    override var last_ping: Long = 0
    var last_response_status: Int = 0

    val htmlPage: String
    var attributes: MutableMap<String, Any?> = mutableMapOf()
    private val requestNumber = AtomicInteger(1)
    private var cookiesStore: MutableMap<String, String> = (cookies?.toMutableMap() ?: mutableMapOf())
    var authorizedOnglets: MutableList<Int> = mutableListOf()
    var compressRequests: Boolean = false
    var encryptRequests: Boolean = false
    private var lastResponse: HttpResponse? = null

    init {
        val (root, page) = getRootAddress(site)
        root_site = root
        htmlPage = page
    }

    fun initialise(clientIdentifier: String?): Pair<Map<String, Any?>, Map<String, Any?>> {
        repeat(3) {
            try {
                apiLog.fine("Requesing html: $root_site/$htmlPage")
                val response = session.get("$root_site/$htmlPage")
                attributes = parseHtml(response.content)
                cookiesStore.putAll((response as? SimpleHttpResponse)?.cookies ?: emptyMap())
                return@repeat
            } catch (e: IllegalStateException) {
                apiLog.warning("[_Communication.initialise] Failed to parse html, retrying...")
            }
        }
        if (!attributes.containsKey("h")) {
            throw PronoteAPIError("Unable to connect to pronote, please try again later")
        }

        val useRsa = attributes["http"] != null
        val ivPayload = if (useRsa) {
            encryption.rsa_encrypt(encryption.aes_iv_temp)
        } else {
            encryption.aes_iv_temp
        }
        val uuid = Base64.getEncoder().encodeToString(ivPayload)

        val jsonPost = mapOf("Uuid" to uuid, "identifiantNav" to clientIdentifier)
        encryptRequests = attributes["CrA"] != null
        compressRequests = attributes["CoA"] != null

        val initialResponse = post(
            "FonctionParametres",
            mapOf("data" to jsonPost),
            decryptionChange = mapOf("iv" to md5(encryption.aes_iv_temp)),
        )

        return attributes to initialResponse
    }

    override fun post(functionName: String, data: Map<String, Any?>, decryptionChange: Map<String, ByteArray>?): Map<String, Any?> {
        if (data.containsKey("Signature")) {
            val signature = data["Signature"] as? Map<*, *>
            val onglet = (signature?.get("onglet") as? Number)?.toInt()
            if (onglet != null && !authorizedOnglets.contains(onglet)) {
                throw PronoteAPIError("Action not permitted. (onglet is not normally accessible)")
            }
        }

        var postData: Any = data
        if (compressRequests) {
            apiLog.fine("[_Communication.post] compressing data")
            val jsonString = JSONObject(postData as Map<*, *>).toString()
            val hex = jsonString.toByteArray().toHexString()
            val compressed = deflateRaw(hex.toByteArray())
            postData = compressed.toHexString().uppercase(Locale.ROOT)
        }
        if (encryptRequests) {
            apiLog.fine("[_Communication.post] encrypt data")
            postData = when (postData) {
                is Map<*, *> -> {
                    val jsonString = JSONObject(postData).toString()
                    encryption.aes_encrypt(jsonString.toByteArray()).toHexString().uppercase(Locale.ROOT)
                }
                is String -> {
                    val raw = postData.fromHex()
                    encryption.aes_encrypt(raw).toHexString().uppercase(Locale.ROOT)
                }
                else -> postData
            }
        }

        val rNumber = encryption.aes_encrypt(requestNumber.get().toString().toByteArray()).toHexString()
        val json = mutableMapOf<String, Any?>(
            "session" to attributes["h"].toString().toInt(),
            "no" to rNumber,
            "id" to functionName,
            "dataSec" to postData,
        )

        apiLog.fine("[_Communication.post] sending post request: $json")
        val pSite = "$root_site/appelfonction/${attributes["a"]}/${attributes["h"]}/$rNumber"
        val response = session.post(pSite, JSONObject(json).toString(), cookiesStore)
        last_response_status = response.statusCode
        last_ping = Instant.now().epochSecond
        cookiesStore.putAll(response.cookies)
        lastResponse = response
        requestNumber.addAndGet(2)

        if (response.statusCode !in 200..299) {
            throw PronoteAPIError("Bad request (http status: ${response.statusCode})")
        }
        val responseData = parseJsonObject(String(response.content))
        if (responseData.containsKey("Erreur")) {
            val err = responseData["Erreur"] as Map<*, *>
            val code = (err["G"] as? Number)?.toInt()
            if (code == 22) throw ExpiredObject(errorMessages[22])
            throw PronoteAPIError(
                errorMessages[code] ?: "Unknown error from pronote: ${err["G"]} | ${err["Titre"]}",
                pronoteErrorCode = code,
                pronoteErrorMsg = err["Titre"]?.toString(),
            )
        }

        decryptionChange?.let {
            apiLog.fine("[_Communication.post] decryption change")
            it["iv"]?.let { iv -> encryption.aes_iv = iv }
            it["key"]?.let { key -> encryption.aes_key = key }
        }

        if (encryptRequests) {
            apiLog.fine("[_Communication.post] decrypting")
            val encryptedData = (responseData["dataSec"] as? String)?.fromHex()
                ?: throw PronoteAPIError("Missing encrypted payload")
            val decrypted = encryption.aes_decrypt(encryptedData)
            responseData["dataSec"] = if (!compressRequests) {
                parseJsonObject(String(decrypted))
            } else {
                decrypted
            }
        }
        if (compressRequests) {
            apiLog.fine("[_Communication.post] decompressing")
            val raw = responseData["dataSec"]
            val bytes = when (raw) {
                is String -> raw.fromHex()
                is ByteArray -> raw
                else -> ByteArray(0)
            }
            val inflated = inflateRaw(bytes)
            responseData["dataSec"] = parseJsonObject(String(inflated))
        }

        return responseData
    }

    fun after_auth(data: Map<String, Any?>, authKey: ByteArray) {
        encryption.aes_key = authKey
        val dataSec = (data["dataSec"] as? Map<*, *>) ?: return
        val cleHex = ((dataSec["data"] as? Map<*, *>)?.get("cle") as? String) ?: return
        val work = encryption.aes_decrypt(cleHex.fromHex())
        encryption.aes_key = md5(_enBytes(String(work)))
        if (cookiesStore.isEmpty()) {
            cookiesStore.putAll((lastResponse as? SimpleHttpResponse)?.cookies ?: emptyMap())
        }
    }

    private fun parseHtml(html: ByteArray): MutableMap<String, Any?> {
        val parsed = org.jsoup.Jsoup.parse(String(html))
        val onloadAttr = parsed.getElementById("id_body")?.attr("onload")
        val onloadContent = if (!onloadAttr.isNullOrEmpty()) {
            val match = Regex("Start\\s*\\(\\{\\s*(?<param>[^}]*)\\s*\\}\\)").find(onloadAttr)
            match?.groups?.get(1)?.value ?: match?.groupValues?.getOrNull(1)
        } else if (html.toString(Charsets.UTF_8).contains("IP")) {
            throw PronoteAPIError("Your IP address is suspended.")
        } else {
            null
        }
        val attributes = mutableMapOf<String, Any?>()
        val content = onloadContent ?: throw PronoteAPIError("Page html is different than expected. Be sure that pronote_url is the direct url to your pronote page.")
        content.split(',').forEach { part ->
            val split = part.split(":")
            if (split.size >= 2) {
                val key = split[0]
                val value = split[1].replace("'", "")
                attributes[key] = value
            }
        }
        if (!attributes.containsKey("h")) throw IllegalStateException("internal exception to retry -> cannot prase html")
        return attributes
    }
}

class EncryptionImpl : Encryption {
    companion object {
        private const val RSA_1024_MODULO = "130337874517286041778445012253514395801341480334668979416920989365464528904618150245388048105865059387076357492684573172203245221386376405947824377827224846860699130638566643129067735803555082190977267155957271492183684665050351182476506458843580431717209261903043895605014125081521285387341454154194253026277"
        private const val RSA_1024_EXPONENT = 65537L
    }

    var aes_iv: ByteArray = ByteArray(16)
    val aes_iv_temp: ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }
    var aes_key: ByteArray = md5(ByteArray(0))
    var rsa_keys: MutableMap<String, String> = mutableMapOf()

    override fun aes_encrypt(input: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        val padded = pkcs7Pad(input, 16)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aes_key, "AES"), IvParameterSpec(aes_iv))
        return cipher.doFinal(padded)
    }

    override fun aes_decrypt(data: ByteArray): ByteArray {
        return try {
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aes_key, "AES"), IvParameterSpec(aes_iv))
            val decrypted = cipher.doFinal(data)
            pkcs7Unpad(decrypted)
        } catch (e: Exception) {
            throw CryptoError("Decryption failed while trying to un pad. (probably bad decryption key/iv)")
        }
    }

    fun aes_set_iv(iv: ByteArray? = null) {
        aes_iv = iv ?: md5(aes_iv_temp)
    }

    fun aes_set_key(key: ByteArray? = null) {
        key?.let { aes_key = md5(it) }
    }

    fun rsa_encrypt(data: ByteArray): ByteArray {
        val modulus = RSA_1024_MODULO.toBigInteger()
        val exponent = RSA_1024_EXPONENT.toBigInteger()
        val spec = RSAPublicKeySpec(modulus, exponent)
        val factory = KeyFactory.getInstance("RSA")
        val publicKey = factory.generatePublic(spec)
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        return cipher.doFinal(data)
    }
}

class KeepAlive(private val client: ClientBase) : Thread() {
    @Volatile var keepAlive: Boolean = true

    override fun run() {
        while (keepAlive) {
            if (Instant.now().epochSecond - client.communication.last_ping >= 110) {
                client.post("Navigation", 7, mapOf("onglet" to 7, "ongletPrec" to 7))
            }
            sleep(1000)
        }
    }

    override fun interrupt() {
        keepAlive = false
        super.interrupt()
    }
}

class SimpleHttpResponse(
    override val statusCode: Int,
    override val content: ByteArray,
    private val streamFactory: () -> InputStream?,
    override val cookies: Map<String, String>,
) : HttpResponse {
    override fun iterContent(chunkSize: Int): Sequence<ByteArray> = sequence {
        streamFactory()?.use { input ->
            val buffer = ByteArray(chunkSize)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                yield(buffer.copyOf(read))
            }
        }
    }
}

class SimpleHttpSession(
    private val defaultHeaders: Map<String, String>,
    private val cookies: MutableMap<String, String> = mutableMapOf(),
) : HttpSession {
    override fun get(url: String): HttpResponse = request("GET", url, null, null)

    override fun post(url: String, body: String, cookieSnapshot: Map<String, String>): HttpResponse =
        request("POST", url, body, cookieSnapshot)

    private fun request(method: String, url: String, body: String?, cookieSnapshot: Map<String, String>?): HttpResponse {
        var currentUrl = url
        var currentMethod = method
        var currentBody = body
        var redirects = 0
        val currentCookies: MutableMap<String, String> = (cookieSnapshot?.toMutableMap() ?: cookies)
        while (true) {
            val connection = URL(currentUrl).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.requestMethod = currentMethod
            if (currentMethod == "POST") connection.doOutput = true
            applyHeaders(connection, currentCookies)
            currentBody?.let { bytes -> connection.outputStream.use { it.write(bytes.toByteArray()) } }

            val responseCode = connection.responseCode
            val errorStream: InputStream? = runCatching { connection.errorStream }.getOrNull()
            val responseStream: InputStream = when {
                responseCode in 200..299 -> connection.inputStream
                errorStream != null -> errorStream
                else -> ByteArrayInputStream(ByteArray(0))
            }
            val responseBytes = responseStream.use { it.readBytes() }
            val setCookies = readCookies(connection)
            currentCookies.putAll(setCookies)
            cookies.putAll(setCookies)

            val location = connection.getHeaderField("Location")
            if (responseCode in 300..399 && location != null && redirects < 10) {
                redirects += 1
                currentUrl = URL(URL(currentUrl), location).toString()
                if (responseCode in listOf(301, 302, 303)) {
                    currentMethod = "GET"
                    currentBody = null
                }
                continue
            }

            return SimpleHttpResponse(responseCode, responseBytes, { responseBytes.inputStream() }, currentCookies)
        }
    }

    override fun close() {
        // HttpURLConnection closes automatically; nothing to do here.
    }

    private fun applyHeaders(connection: HttpURLConnection, cookieSnapshot: Map<String, String>? = null) {
        defaultHeaders.forEach { (k, v) -> connection.setRequestProperty(k, v) }
        val cookieHeader = (cookieSnapshot ?: cookies).entries.joinToString("; ") { "${it.key}=${it.value}" }
        if (cookieHeader.isNotEmpty()) connection.setRequestProperty("Cookie", cookieHeader)
        connection.setRequestProperty("Content-Type", "application/json")
    }

    private fun readCookies(connection: HttpURLConnection): Map<String, String> {
        val cookiesMap = mutableMapOf<String, String>()
        connection.headerFields["Set-Cookie"]?.forEach { header ->
            val parts = header.split(';')[0].split('=')
            if (parts.size >= 2) cookiesMap[parts[0]] = parts.subList(1, parts.size).joinToString("=")
        }
        return cookiesMap
    }
}

private fun getRootAddress(addr: String): Pair<String, String> {
    val parts = addr.split('/')
    return parts.dropLast(1).joinToString("/") to parts.last()
}

fun _enleverAlea(text: String): String = text.filterIndexed { index, _ -> index % 2 == 0 }

fun _enBytes(string: String): ByteArray = string.split(',').map { it.toInt() }.map { it.toByte() }.toByteArray()

fun _prepare_onglets(listOfOnglets: Any?): List<Any?> {
    val output = mutableListOf<Any?>()
    when (listOfOnglets) {
        is List<*> -> listOfOnglets.forEach { item ->
            when (item) {
                is Map<*, *> -> output.addAll(_prepare_onglets(item.values.toList()))
                else -> output.addAll(_prepare_onglets(item))
            }
        }
        else -> if (listOfOnglets != null) output.add(listOfOnglets)
    }
    return output
}

@Suppress("SameParameterValue")
private fun pkcs7Pad(data: ByteArray, blockSize: Int): ByteArray {
    val padding = blockSize - (data.size % blockSize)
    val padBytes = ByteArray(padding) { padding.toByte() }
    return data + padBytes
}

private fun pkcs7Unpad(data: ByteArray): ByteArray {
    if (data.isEmpty()) throw IllegalArgumentException("Data is empty")
    val padding = data.last().toInt()
    if (padding <= 0 || padding > data.size) throw IllegalArgumentException("Invalid padding")
    return data.copyOfRange(0, data.size - padding)
}

private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

private fun String.fromHex(): ByteArray {
    require(length % 2 == 0) { "Hex string must have even length" }
    return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

private fun md5(bytes: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(bytes)

private fun deflateRaw(input: ByteArray): ByteArray {
    val deflater = Deflater(6, true)
    deflater.setInput(input)
    deflater.finish()
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(1024)
    while (!deflater.finished()) {
        val count = deflater.deflate(buffer)
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

private fun inflateRaw(input: ByteArray): ByteArray {
    val inflater = Inflater(true)
    inflater.setInput(input)
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(1024)
    while (!inflater.finished()) {
        val count = inflater.inflate(buffer)
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

private fun parseJsonObject(json: String): MutableMap<String, Any?> {
    val obj = JSONObject(json)
    return obj.toMapMutable()
}

private fun JSONObject.toMapMutable(): MutableMap<String, Any?> {
    val map = mutableMapOf<String, Any?>()
    keys().forEach { key ->
        map[key] = when (val value = this.get(key)) {
            is JSONObject -> value.toMapMutable()
            is JSONArray -> value.toList()
            JSONObject.NULL -> null
            else -> value
        }
    }
    return map
}

private fun JSONArray.toList(): List<Any?> {
    val list = mutableListOf<Any?>()
    for (i in 0 until length()) {
        val value = get(i)
        list.add(
            when (value) {
                is JSONObject -> value.toMapMutable()
                is JSONArray -> value.toList()
                JSONObject.NULL -> null
                else -> value
            },
        )
    }
    return list
}
