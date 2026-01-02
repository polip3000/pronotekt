@file:Suppress("UNCHECKED_CAST", "unused", "UNNECESSARY_NOT_NULL_ASSERTION")
package fr.algorythmice.pronotekt

import java.net.URI
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.logging.Logger
import kotlin.math.max
import org.json.JSONArray

private val clientLog: Logger = Logger.getLogger("PronotepyClients")

typealias EntFunction = (username: String, password: String, pronoteUrl: String) -> Map<String, String>?

data class CredentialsExport(
    val pronoteUrl: String,
    val username: String,
    val password: String,
    val clientIdentifier: String?,
    val uuid: String,
)

private fun sha256Hex(input: String): String = MessageDigest.getInstance("SHA-256")
    .digest(input.toByteArray())
    .joinToString("") { "%02x".format(it) }

@PublishedApi
internal fun String.fromHexStrict(): ByteArray {
    require(length % 2 == 0) { "Hex string must have even length" }
    return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

/**
 * Base implementation of the Pronote client logic from pronotepy/clients.py (ClientBase).
 * Note: class name differs from Python's ClientBase to avoid clashes with existing interfaces.
 */
open class BaseClientImpl(
    private val pronoteUrl: String,
    private var username: String = "",
    private var password: String = "",
    private val ent: EntFunction? = null,
    private var loginMode: String = "normal",
    private val uuid: String = "",
    private val accountPin: String? = null,
    private var clientIdentifier: String? = null,
    private val deviceName: String? = null,
) : ClientBase {

    override var communication: Communication = CommunicationImpl(pronoteUrl, ent?.invoke(username, password, pronoteUrl))
    override var func_options: Map<String, Any?> = emptyMap()
    override var attributes: Map<String, Any?> = emptyMap()
    override lateinit var info: ClientInfo
    private var periodsCache: List<Period>? = null
    override val periods: List<Period>
        get() = periodsCache ?: loadPeriods().also { periodsCache = it }

    private var refreshing = false
    private var expired = false
    private val encryption = EncryptionImpl()
    var parametresUtilisateur: Map<String, Any?> = emptyMap()
        private set
    var loggedIn: Boolean = false
        private set
    var lastConnection: LocalDateTime? = null
        private set
    protected val startDay: LocalDate
    private var week: Int

    init {
        if (username.isEmpty() && password.isEmpty()) {
            throw PronoteAPIError("Please provide login credentials. Cookies are None, and username and password are empty.")
        }

        // If an ENT handler is provided, perform ENT login to retrieve cookies before Pronote init
        val cookies = ent?.invoke(username, password, pronoteUrl)
        communication = CommunicationImpl(pronoteUrl, cookies)
        val init = (communication as CommunicationImpl).initialise(clientIdentifier)
        attributes = init.first
        func_options = init.second

        if (clientIdentifier == null) {
            clientIdentifier = (((func_options["dataSec"] as? Map<*, *>)?.get("data") as? Map<*, *>)?.get("identifiantNav"))?.toString()
        }

        encryption.aes_iv = (communication.encryption as EncryptionImpl).aes_iv

        val premierLundi = (((((func_options["dataSec"] as Map<*, *>) ["data"] as Map<*, *>) ["General"] as Map<*, *>) ["PremierLundi"] as Map<*, *>) ["V"]).toString()
        startDay = LocalDate.parse(premierLundi, DateTimeFormatter.ofPattern("dd/MM/yyyy"))
        week = get_week(LocalDate.now())

        // Preload periods to mirror Python's ClientBase.__init__ behaviour
        periodsCache = loadPeriods()

        loggedIn = login()
    }

    // region login
    private fun login(): Boolean {
        var usedUsername = username
        var usedPassword = password
        if (ent != null) {
            usedUsername = attributes["e"].toString()
            usedPassword = attributes["f"].toString()
        }

        val identJson = mutableMapOf<String, Any?>(
            "genreConnexion" to 0,
            "genreEspace" to attributes["a"].toString().toIntOrNull(),
            "identifiant" to usedUsername,
            "pourENT" to (ent != null),
            "enConnexionAuto" to false,
            "demandeConnexionAuto" to false,
            "demandeConnexionAppliMobile" to (loginMode == "qr_code"),
            "demandeConnexionAppliMobileJeton" to (loginMode == "qr_code"),
            "enConnexionAppliMobile" to (loginMode == "token"),
            "uuidAppliMobile" to if (loginMode in listOf("qr_code", "token")) uuid else "",
            "loginTokenSAV" to "",
        )

        val idr = postRaw("Identification", data = identJson)
        val dataSecData = (idr["dataSec"] as Map<*, *>) ["data"] as Map<*, *>
        val challenge = dataSecData["challenge"].toString()
        val e = EncryptionImpl()
        e.aes_iv = (communication.encryption as EncryptionImpl).aes_iv

        val modeCompLog = dataSecData["modeCompLog"] == true
        val modeCompMdp = dataSecData["modeCompMdp"] == true
        val alea = dataSecData["alea"] as? String ?: ""

        if (modeCompLog) usedUsername = usedUsername.lowercase(Locale.ROOT)
        if (modeCompMdp) usedPassword = usedPassword.lowercase(Locale.ROOT)

        val motdepasse = if (ent != null) {
            sha256Hex(usedPassword).uppercase(Locale.ROOT)
        } else {
            sha256Hex(alea + usedPassword).uppercase(Locale.ROOT)
        }
        if (ent != null) {
            e.aes_set_key(motdepasse.encodeToByteArray())
        } else {
            e.aes_set_key((usedUsername + motdepasse).encodeToByteArray())
        }

        val ch = try {
            val dec = e.aes_decrypt(challenge.fromHexStrict())
            val decNoAlea = _enleverAlea(dec.decodeToString())
            e.aes_encrypt(decNoAlea.encodeToByteArray()).toHexString()
        } catch (ex: Exception) {
            throw CryptoError("exception happened during login -> probably bad username/password or expired qr code", cause = ex)
        }

        val authJson = mapOf(
            "connexion" to 0,
            "challenge" to ch,
            "espace" to attributes["a"].toString().toIntOrNull(),
        )
        val authResponse = postRaw("Authentification", data = authJson)
        val dataSec = (authResponse["dataSec"] as Map<*, *>) ["data"] as Map<*, *>
        if (!dataSec.containsKey("cle")) {
            clientLog.info("login failed")
            return false
        }

        (communication as CommunicationImpl).after_auth(authResponse, e.aes_key)
        encryption.aes_key = e.aes_key

        dataSec["actionsDoubleAuth"]?.let { actionsDoubleAuth ->
            val actions = (actionsDoubleAuth as? Map<*, *>)?.get("V")?.toString()?.let { json ->
                runCatching { JSONArray(json) }.getOrNull()?.let { arr ->
                    (0 until arr.length()).mapNotNull { arr.optInt(it) }
                }
            } ?: emptyList()
            val doRegisterDevice = actions.contains(5) || actions.contains(3)
            val doVerifyPin = actions.contains(3)
            do2fa(doVerifyPin, doRegisterDevice, accountPin, deviceName)
        }

        clientLog.info("successfully logged in as $username")
        val lastConn = dataSec["derniereConnexion"] as? Map<*, *>
        lastConnection = lastConn?.get("V")?.toString()?.let { Util.datetimeParse(it) }

        if (loginMode in listOf("qr_code", "token") && dataSec["jetonConnexionAppliMobile"] != null) {
            password = dataSec["jetonConnexionAppliMobile"].toString()
        }

        parametresUtilisateur = postRaw("ParametresUtilisateur")
        info = ClientInfo(this, ((parametresUtilisateur["dataSec"] as Map<*, *>) ["data"] as Map<*, *>) ["ressource"] as Map<String, Any?>)
        val onglets = ((parametresUtilisateur["dataSec"] as Map<*, *>) ["data"] as Map<*, *>) ["listeOnglets"]
        (communication as CommunicationImpl).authorizedOnglets = _prepare_onglets(onglets).mapNotNull { (it as? Number)?.toInt() }.toMutableList()
        return true
    }

    private fun do2fa(
        doVerifyPin: Boolean,
        doRegisterDevice: Boolean,
        pin: String?,
        identifier: String?,
    ) {
        var encryptedPin: String? = null
        if (doVerifyPin) {
            if (pin == null) throw MFAError("PIN is required for this account")
            encryptedPin = communication.encryption.aes_encrypt(pin.encodeToByteArray()).toHexString()
            val resp = postRaw("SecurisationCompteDoubleAuth", data = mapOf("action" to 0, "codePin" to encryptedPin))
            val ok = (((resp["dataSec"] as Map<*, *>) ["data"] as Map<*, *>) ["result"]) as? Boolean ?: false
            if (!ok) throw MFAError("Invalid PIN")
        }
        if (doRegisterDevice) {
            if (identifier == null) throw MFAError("A device identifier is required for this account")
            val data = mutableMapOf<String, Any?>(
                "action" to 3,
                "avecIdentification" to true,
                "strIdentification" to identifier,
            )
            encryptedPin?.let { data["codePin"] = it }
            postRaw("SecurisationCompteDoubleAuth", data = data)
        }
    }
    // endregion login

    fun exportCredentials(): CredentialsExport = CredentialsExport(pronoteUrl, username, password, clientIdentifier, uuid)

    override fun get_week(date: LocalDate): Int = 1 + ((date.toEpochDay() - startDay.toEpochDay()) / 7).toInt()

    override fun post(path: String, function: Int, data: Map<String, Any?>): Map<String, Any?> = postRaw(path, onglet = function, data = data)

    fun postRaw(functionName: String, onglet: Int? = null, data: Map<String, Any?>? = null): Map<String, Any?> {
        val postData = mutableMapOf<String, Any?>()
        if (onglet != null) postData["Signature"] = mapOf("onglet" to onglet)
        if (data != null) postData["data"] = data
        return try {
            communication.post(functionName, postData)
        } catch (e: PronoteAPIError) {
            if (e is ExpiredObject) throw e
            clientLog.info("Have you tried turning it off and on again? ERROR: ${e.pronoteErrorCode} | ${e.pronoteErrorMsg}")
            if (refreshing) throw e
            refreshing = true
            refresh()
            refreshing = false
            communication.post(functionName, postData)
        }
    }

    private fun loadPeriods(): List<Period> {
        val json = ((((func_options["dataSec"] as Map<*, *>) ["data"] as Map<*, *>) ["General"] as Map<*, *>) ["ListePeriodes"] as List<*>)
        return json.map { Period(this, it as Map<String, Any?>) }
    }

    fun keepAlive(): KeepAlive = KeepAlive(this)

    fun refresh() {
        communication.session.close()
        val cookies = ent?.invoke(username, password, pronoteUrl)
        communication = CommunicationImpl(pronoteUrl, cookies)
        val init = (communication as CommunicationImpl).initialise(clientIdentifier)
        attributes = init.first
        func_options = init.second
        encryption.aes_iv = (communication.encryption as EncryptionImpl).aes_iv
        login()
        periodsCache = null
        week = get_week(LocalDate.now())
        expired = true
    }

    fun sessionCheck(): Boolean {
        postRaw("Navigation", onglet = 7, data = mapOf("onglet" to 7, "ongletPrec" to 7))
        return if (expired) {
            expired = false
            true
        } else false
    }

    fun requestQrCodeData(pin: String): Map<String, Any?> {
        val req = postRaw("JetonAppliMobile", 7, mapOf("code" to pin))
        val regex = Regex("/(?:mobile.)?(\\w+).html$")
        val fixedUrl = regex.replace(pronoteUrl) { m -> "/mobile.${m.groupValues[1]}.html" }
        val data = (req["dataSec"] as Map<*, *>) ["data"] as Map<*, *>
        val map = mutableMapOf<String, Any?>("url" to fixedUrl)
        data.forEach { (k, v) -> map[k.toString()] = v }
        return map
    }

    companion object {
        @PublishedApi internal fun String.toHexStrict() = fromHexStrict()
        @PublishedApi internal fun BaseClientImpl.credPronoteUrl() = pronoteUrl
        @PublishedApi internal fun BaseClientImpl.credUsername() = username
        @PublishedApi internal fun BaseClientImpl.credPassword() = password
        inline fun <reified T : BaseClientImpl> qrcodeLogin(
            qrCode: Map<String, Any?>,
            pin: String,
            uuid: String,
            accountPin: String? = null,
            clientIdentifier: String? = null,
            deviceName: String? = null,
            skip2fa: Boolean = false,
            factory: (String, String, String, EntFunction?, String, String, String?, String?, String?) -> T,
        ): T {
            val encryption = EncryptionImpl()
            encryption.aes_set_key(pin.encodeToByteArray())
            val shortToken = (qrCode["login"] as String).fromHexStrict()
            val longToken = (qrCode["jeton"] as String).fromHexStrict()
            val login = encryption.aes_decrypt(shortToken).decodeToString()
            val jeton = encryption.aes_decrypt(longToken).decodeToString()

            val urlObj = URI(qrCode["url"].toString())
            val parts = urlObj.path.split("/").toMutableList()
            if (!parts.last().startsWith("mobile.")) {
                parts[parts.lastIndex] = "mobile.${parts.last()}"
            }
            val fixedPath = parts.joinToString("/")
            val query = "fd=1&bydlg=A6ABB224-12DD-4E31-AD3E-8A39A1C2C335&login=true"
            val fixedUrl = URI(urlObj.scheme, urlObj.authority, fixedPath, query, null).toString()

            val client = factory(fixedUrl, login, jeton, null, "qr_code", uuid, accountPin, clientIdentifier, deviceName)
            if (!skip2fa) {
                val resp = client.postRaw("PageInfosPerso", onglet = 49)
                val mode = ((resp["dataSec"] as Map<*, *>) ["data"] as Map<*, *>) ["securisation"]?.let { (it as Map<*, *>) ["mode"] as? Number }?.toInt() ?: 0
                if (mode != 0) {
                    return tokenLogin(client.credPronoteUrl(), client.credUsername(), client.credPassword(), uuid, accountPin, clientIdentifier, deviceName, factory)
                }
            }
            return client
        }

        inline fun <reified T : BaseClientImpl> tokenLogin(
            pronoteUrl: String,
            username: String,
            password: String,
            uuid: String,
            accountPin: String? = null,
            clientIdentifier: String? = null,
            deviceName: String? = null,
            factory: (String, String, String, EntFunction?, String, String, String?, String?, String?) -> T,
        ): T = factory(pronoteUrl, username, password, null, "token", uuid, accountPin, clientIdentifier, deviceName)
    }
}

/**
 * Student/standard client (Python: Client). Renamed to avoid collision with interface Client.
 */
open class Client(
    pronoteUrl: String,
    username: String = "",
    password: String = "",
    ent: EntFunction? = null,
    mode: String = "normal",
    uuid: String = "",
    accountPin: String? = null,
    clientIdentifier: String? = null,
    deviceName: String? = null,
 ) : BaseClientImpl(pronoteUrl, username, password, ent, mode, uuid, accountPin, clientIdentifier, deviceName) {

    fun lessons(dateFrom: LocalDate, dateTo: LocalDate? = null): List<Lesson> {
        val ressource = mapOf("G" to 4, "N" to (((parametresUtilisateur["dataSec"] as Map<*, *>) ["data"] as Map<*, *>) ["ressource"] as Map<*, *>) ["N"])
        val data = mutableMapOf<String, Any?>(
            "ressource" to ressource,
            "avecAbsencesEleve" to false,
            "avecConseilDeClasse" to true,
            "estEDTPermanence" to false,
            "avecAbsencesRessource" to true,
            "avecDisponibilites" to true,
            "avecInfosPrefsGrille" to true,
            "Ressource" to ressource,
        )
        val output = mutableListOf<Lesson>()
        val startDateTime = dateFrom.atStartOfDay()
        val endDateTime = (dateTo ?: dateFrom).atTime(23, 59, 59)
        val firstWeek = get_week(startDateTime.toLocalDate())
        val lastWeek = max(firstWeek, get_week(endDateTime.toLocalDate()))
        for (week in firstWeek..lastWeek) {
            data["NumeroSemaine"] = week
            data["numeroSemaine"] = week
            val response = post("PageEmploiDuTemps", 16, data)
            val lList = ((response["dataSec"] as Map<*, *>) ["data"] as Map<*, *>) ["ListeCours"] as List<*>
            lList.forEach { output += Lesson(this, it as Map<String, Any?>) }
        }
        return output.filter { !it.start.isBefore(startDateTime) && !it.start.isAfter(endDateTime) }
    }

    fun exportIcal(timezoneShift: Int = 0): String {
        val user = (parametresUtilisateur["dataSec"] as Map<*, *>) ["data"] as Map<*, *>
        val ressource = user["ressource"] as Map<*, *>
        val data = mutableMapOf<String, Any?>(
            "ressource" to ressource,
            "avecAbsencesEleve" to false,
            "avecConseilDeClasse" to true,
            "estEDTPermanence" to false,
            "avecAbsencesRessource" to true,
            "avecDisponibilites" to true,
            "avecInfosPrefsGrille" to true,
            "Ressource" to ressource,
            "NumeroSemaine" to 1,
            "numeroSemaine" to 1,
        )
        val response = post("PageEmploiDuTemps", 16, data)
        val paramExp = (((response["dataSec"] as Map<*, *>) ["data"] as Map<*, *>) ["ParametreExportiCal"])
        val rawToken = when (paramExp) {
            is Map<*, *> -> paramExp["V"]?.toString() ?: paramExp.toString()
            else -> paramExp?.toString() ?: ""
        }.ifEmpty { throw ICalExportError("Pronote did not return ICal token") }
        if (rawToken.startsWith("http")) return rawToken
        if (rawToken.startsWith("/")) return "${communication.root_site}$rawToken"
        val ver = (((func_options["dataSec"] as Map<*, *>) ["data"] as Map<*, *>) ["General"] as Map<*, *>) ["versionPN"].toString()
        val param = "lh=${timezoneShift}".encodeToByteArray().toHexString()
        return "${communication.root_site}/ical/Edt.ics?icalsecurise=${rawToken}&version=${ver}&param=${param}"
    }

    fun generateTimetablePdf(
        day: LocalDate? = null,
        portrait: Boolean = false,
        overflow: Int = 0,
        fontSize: Pair<Int, Int> = 8 to 3,
    ): String {
        val ressource = (((parametresUtilisateur["dataSec"] as Map<*, *>) ["data"] as Map<*, *>) ["ressource"] as Map<*, *>)
        val user = mapOf("G" to 4, "N" to ressource["N"])
        val data = mapOf(
            "options" to mapOf(
                "portrait" to portrait,
                "taillePolice" to fontSize.first,
                "taillePoliceMin" to fontSize.second,
                "couleur" to 1,
                "renvoi" to overflow,
                "uneGrilleParSemaine" to false,
                "inversionGrille" to false,
                "ignorerLesPlagesSansCours" to false,
                "estEDTAnnuel" to (day == null),
            ),
            "genreGenerationPDF" to 0,
            "estPlanning" to false,
            "estPlanningParRessource" to false,
            "estPlanningOngletParJour" to false,
            "estPlanningParJour" to false,
            "indiceJour" to 0,
            "ressource" to user,
            "ressources" to listOf(user),
            "domaine" to mapOf("_T" to 8, "V" to "[${if (day == null) 0 else get_week(day)}]"),
            "avecCoursAnnules" to true,
            "grilleInverse" to false,
        )
        val response = post("GenerationPDF", 16, data)
        val url = (((response["dataSec"] as Map<*, *>) ["data"] as Map<*, *>) ["url"] as Map<*, *>) ["V"].toString()
        return "${communication.root_site}/$url"
    }

    fun homework(dateFrom: LocalDate, dateTo: LocalDate? = null): List<Homework> {
        val toDate = dateTo ?: Util.dateParse(((((func_options["dataSec"] as Map<*, *>) ["data"] as Map<*, *>) ["General"] as Map<*, *>) ["DerniereDate"] as Map<*, *>) ["V"].toString())
        val jsonData = mapOf(
            "domaine" to mapOf(
                "_T" to 8,
                "V" to "[${get_week(dateFrom)}..${get_week(toDate)}]",
            ),
        )
        val response = post("PageCahierDeTexte", 88, jsonData)
        val hList = ((response["dataSec"] as Map<*, *>) ["data"] as Map<*, *>) ["ListeTravauxAFaire"] as Map<*, *>
        val arr = hList["V"] as List<*>
        return arr.map { Homework(this, it as Map<String, Any?>) }
            .filter { !it.date.isBefore(dateFrom) && !it.date.isAfter(toDate) }
    }

    fun getRecipients(): List<Recipient> {
        val ressource = mapOf("G" to 4, "N" to ((parametresUtilisateur["dataSec"] as Map<*, *>) ["data"] as Map<*, *>) ["ressource"]?.let { (it as Map<*, *>) ["N"] })
        val data = mapOf("onglet" to mapOf("N" to 0, "G" to 10), "ressource" to ressource)
        val teacherResp = post("ListeRessourcesPourCommunication", 131, data)
        var recipients = (((teacherResp["dataSec"] as Map<*, *>) ["data"] as Map<*, *>) ["listeRessourcesPourCommunication"] as Map<*, *>) ["V"] as List<*>
        val dataStaff = mapOf("onglet" to mapOf("N" to 0, "G" to 34))
        val staffResp = post("ListeRessourcesPourCommunication", 131, dataStaff)
        recipients += (((staffResp["dataSec"] as Map<*, *>) ["data"] as Map<*, *>) ["listeRessourcesPourCommunication"] as Map<*, *>) ["V"] as List<*>
        return recipients.map { Recipient(this, it as Map<String, Any?>) }
    }

    fun getTeachingStaff(): List<TeachingStaff> {
        val resp = post("PageEquipePedagogique", 37, emptyMap())
        val teachers = (((resp["dataSec"] as Map<*, *>) ["data"] as Map<*, *>) ["liste"] as Map<*, *>) ["V"] as List<*>
        return teachers.map { TeachingStaff(it as Map<String, Any?>) }
    }

    fun newDiscussion(subject: String, message: String, recipients: List<Recipient>) {
        val recipientsJson = recipients.map { r ->
            val field = r.javaClass.getDeclaredField("_type")
            field.isAccessible = true
            val g = (field.get(r) as Number).toInt()
            mapOf("N" to r.id, "G" to g, "L" to r.name)
        }
        val data = mapOf("objet" to subject, "contenu" to message, "listeDestinataires" to recipientsJson)
        post("SaisieMessage", 131, data)
    }

    fun discussions(onlyUnread: Boolean = false): List<Discussion> {
        val discussions = post("ListeMessagerie", 131, mapOf("avecMessage" to true, "avecLu" to !onlyUnread))
        val labelsRaw = (((discussions["dataSec"] as Map<*, *>) ["data"] as Map<*, *>) ["listeEtiquettes"] as Map<*, *>) ["V"] as List<*>
        val labels: Map<Int, Int> = labelsRaw.associate {
            val item = it as Map<*, *>
            val key = when (val n = item["N"]) {
                is Number -> n.toInt()
                is String -> n.toIntOrNull() ?: 0
                else -> 0
            }
            val value = when (val g = item["G"]) {
                is Number -> g.toInt()
                is String -> g.toIntOrNull() ?: 0
                else -> 0
            }
            key to value
        }
        val list = (((discussions["dataSec"] as Map<*, *>) ["data"] as Map<*, *>) ["listeMessagerie"] as Map<*, *>) ["V"] as List<*>
        return list.filter {
            val map = it as Map<*, *>
            map.getOrDefault("estUneDiscussion", false) == true && ((map.getOrDefault("profondeur", 1) as? Number)?.toInt()
                ?: map.getOrDefault("profondeur", 1).toString().toIntOrNull() ?: 1) == 0
        }.map { Discussion(this, it as Map<String, Any?>, labels) }
    }

    fun informationAndSurveys(
        dateFrom: LocalDateTime? = null,
        dateTo: LocalDateTime? = null,
        onlyUnread: Boolean = false,
    ): List<Information> {
        val response = post("PageActualites", 8, mapOf("modesAffActus" to mapOf("_T" to 26, "V" to "[0..3]")))
        val infoLists = mutableListOf<Information>()
        val modes = (((response["dataSec"] as Map<*, *>) ["data"] as Map<*, *>) ["listeModesAff"] as List<*>)
        modes.forEach { m ->
            val list = (m as Map<*, *>) ["listeActualites"] as Map<*, *>
            val arr = list["V"] as List<*>
            arr.forEach { infoLists += Information(this, it as Map<String, Any?>) }
        }
        var infoFiltered = infoLists.toList()
        if (onlyUnread) infoFiltered = infoFiltered.filter { !it.read }
        dateFrom?.let { from -> infoFiltered = infoFiltered.filter { it.start_date != null && !it.start_date!!.isBefore(from) } }
        dateTo?.let { to -> infoFiltered = infoFiltered.filter { it.start_date != null && it.start_date!!.isBefore(to) } }
        return infoFiltered
    }

    fun menus(dateFrom: LocalDate, dateTo: LocalDate? = null): List<Menu> {
        val out = mutableListOf<Menu>()
        val endDate = dateTo ?: dateFrom
        var firstDay = dateFrom.minusDays(dateFrom.dayOfWeek.value.toLong() - 1)
        while (!firstDay.isAfter(endDate)) {
            val data = mapOf("date" to mapOf("_T" to 7, "V" to firstDay.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")) + " 0:0:0"))
            val response = post("PageMenus", 10, data)
            val lList = (((response["dataSec"] as Map<*, *>) ["data"] as Map<*, *>) ["ListeJours"] as Map<*, *>) ["V"] as List<*>
            lList.forEach { day ->
                val dayMap = day as Map<*, *>
                val menus = (dayMap["ListeRepas"] as Map<*, *>) ["V"] as List<*>
                menus.forEach { menu ->
                    val menuMap = (menu as Map<String, Any?>).toMutableMap()
                    menuMap["Date"] = dayMap["Date"]
                    out += Menu(this, menuMap)
                }
            }
            firstDay = firstDay.plusDays(7)
        }
        return out.filter { !it.date.isBefore(dateFrom) && !it.date.isAfter(endDate) }
    }

    val currentPeriod: Period
        get() {
            val ressource = ((parametresUtilisateur["dataSec"] as Map<*, *>) ["data"] as Map<*, *>) ["ressource"] as Map<*, *>
            val list = (ressource["listeOngletsPourPeriodes"] as Map<*, *>) ["V"] as List<*>
            val onglet = list.firstOrNull { (it as Map<*, *>).getOrDefault("G", 0) == 198 } as? Map<*, *> ?: list.first() as Map<*, *>
            val idPeriod = (((onglet["periodeParDefaut"] as Map<*, *>) ["V"] as Map<*, *>) ["N"]).toString()
            return Util.get(periods, "id" to idPeriod).first()
        }

    val startDayPublic: LocalDate
        get() = startDay
}

/**
 * Parent client (Python: ParentClient).
 */
class ParentClient(
    pronoteUrl: String,
    username: String = "",
    password: String = "",
    ent: EntFunction? = null,
    mode: String = "normal",
    uuid: String = "",
    accountPin: String? = null,
    clientIdentifier: String? = null,
    deviceName: String? = null,
) : Client(pronoteUrl, username, password, ent, mode, uuid, accountPin, clientIdentifier, deviceName) {
    val children: List<ClientInfo>
    private var selectedChild: ClientInfo

    init {
        val list = (((parametresUtilisateur["dataSec"] as Map<*, *>) ["data"] as Map<*, *>) ["ressource"] as Map<*, *>) ["listeRessources"] as List<*>
        children = list.map { ClientInfo(this, it as Map<String, Any?>) }
        if (children.isEmpty()) throw ChildNotFound("No children were found.")
        selectedChild = children.first()
        val dataMap = ((parametresUtilisateur["dataSec"] as Map<*, *>) ["data"] as MutableMap<Any?, Any?>)
        val ressource = (dataMap["ressource"] as MutableMap<Any?, Any?>)
        ressource.clear(); ressource.putAll(selectedChild.raw_resource)
    }

    fun setChild(child: Any) {
        val target = child as? ClientInfo ?: Util.get(children, "name" to child.toString()).firstOrNull()
        if (target == null) throw ChildNotFound("A child with the name $child was not found.")
        selectedChild = target
        val dataMap = ((parametresUtilisateur["dataSec"] as Map<*, *>) ["data"] as MutableMap<Any?, Any?>)
        val ressource = (dataMap["ressource"] as MutableMap<Any?, Any?>)
        ressource.clear(); ressource.putAll(selectedChild.raw_resource)
    }

    override fun post(path: String, function: Int, data: Map<String, Any?>): Map<String, Any?> {
        val postData = mutableMapOf<String, Any?>()
        postData["Signature"] = mapOf(
            "onglet" to function,
            "membre" to mapOf("N" to selectedChild.id, "G" to 4),
        )
        postData["data"] = data
        return try {
            communication.post(path, postData)
        } catch (e: PronoteAPIError) {
            if (e is ExpiredObject) throw e
            clientLog.info("Have you tried turning it off and on again? ERROR: ${e.pronoteErrorCode} | ${e.pronoteErrorMsg}")
            refresh()
            communication.post(path, postData)
        }
    }
}

/**
 * Vie Scolaire client (Python: VieScolaireClient).
 */
class VieScolaireClient(
    pronoteUrl: String,
    username: String = "",
    password: String = "",
    ent: EntFunction? = null,
    mode: String = "normal",
    uuid: String = "",
    accountPin: String? = null,
    clientIdentifier: String? = null,
    deviceName: String? = null,
) : BaseClientImpl(pronoteUrl, username, password, ent, mode, uuid, accountPin, clientIdentifier, deviceName) {
    val classes: List<StudentClass> = run {
        val dataSec = parametresUtilisateur["dataSec"] as? Map<*, *> ?: throw PronoteAPIError("Missing dataSec for VieScolaireClient")
        val data = dataSec["data"] as? Map<*, *> ?: throw PronoteAPIError("Missing data for VieScolaireClient")
        val values = when (val listeClasses = data["listeClasses"]) {
            is Map<*, *> -> listeClasses["V"] as? List<*>
            is List<*> -> listeClasses
            else -> null
        } ?: throw PronoteAPIError("Missing listeClasses for VieScolaireClient")
        values.map { json -> StudentClass(this, json as Map<String, Any?>) }
    }
}
