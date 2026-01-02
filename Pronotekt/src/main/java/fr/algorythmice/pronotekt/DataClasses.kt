@file:Suppress("unused", "UNCHECKED_CAST", "MemberVisibilityCanBePrivate", "UNUSED_PARAMETER")
package fr.algorythmice.pronotekt

import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.logging.Logger
import kotlin.math.roundToInt

private val log: Logger = Logger.getLogger("DataClasses")

private object Missing

object Util {
    val gradeTranslate = listOf(
        "Absent",
        "Dispense",
        "NonNote",
        "Inapte",
        "NonRendu",
        "AbsentZero",
        "NonRenduZero",
        "Felicitations",
    )

    fun <T> get(iterable: Iterable<T>, vararg filters: Pair<String, Any?>): List<T> {
        val output = mutableListOf<T>()
        iterable.forEach { item ->
            var matches = true
            for ((attr, value) in filters) {
                val prop = item!!::class.members.find { it.name == attr }
                if (prop == null || prop.call(item) != value) {
                    matches = false
                    break
                }
            }
            if (matches) output += item
        }
        return output
    }

    fun gradeParse(string: String): String {
        return if ("|" in string) {
            val idx = string.getOrNull(1)?.digitToIntOrNull()?.minus(1) ?: -1
            if (idx in gradeTranslate.indices) gradeTranslate[idx] else string
        } else string
    }

    fun dateParse(formattedDate: String): LocalDate {
        val patterns = listOf(
            Pair(Regex("\\d{2}/\\d{2}/\\d{4}$"), "dd/MM/yyyy"),
            Pair(Regex("\\d{2}/\\d{2}/\\d{2}$"), "dd/MM/yy"),
            Pair(Regex("\\d{2}/\\d{2}/\\d{4} \\d{2}:\\d{2}:\\d{2}$"), "dd/MM/yyyy HH:mm:ss"),
            Pair(Regex("\\d{2}/\\d{2}/\\d{2} \\d{2}h\\d{2}$"), "dd/MM/yy HH'h'MM"),
        )
        for ((regex, pattern) in patterns) {
            if (regex.matches(formattedDate)) {
                return LocalDate.parse(formattedDate, DateTimeFormatter.ofPattern(pattern))
            }
        }
        if (Regex("\\d{2}/\\d{2}").matches(formattedDate)) {
            val withYear = "$formattedDate/${LocalDate.now().year}"
            return LocalDate.parse(withYear, DateTimeFormatter.ofPattern("dd/MM/yyyy"))
        }
        if (Regex("\\d{2}\\d{2}$").matches(formattedDate)) {
            val today = LocalDate.now()
            val hours = formattedDate.take(2).toInt()
            val minutes = formattedDate.substring(2, 4).toInt()
            val dt = LocalDateTime.of(today, LocalTime.of(hours, minutes))
            return dt.toLocalDate()
        }
        throw DateParsingError("Could not parse date")
    }

    fun datetimeParse(formattedDate: String): LocalDateTime {
        val patterns = listOf(
            Pair(Regex("\\d{2}/\\d{2}/\\d{4}$"), "dd/MM/yyyy"),
            Pair(Regex("\\d{2}/\\d{2}/\\d{4} \\d{2}:\\d{2}:\\d{2}$"), "dd/MM/yyyy HH:mm:ss"),
            Pair(Regex("\\d{2}/\\d{2}/\\d{2} \\d{2}h\\d{2}$"), "dd/MM/yy HH'h'MM"),
        )
        for ((regex, pattern) in patterns) {
            if (regex.matches(formattedDate)) {
                return if (pattern == "dd/MM/yyyy") {
                    LocalDate.parse(formattedDate, DateTimeFormatter.ofPattern(pattern)).atStartOfDay()
                } else {
                    LocalDateTime.parse(formattedDate, DateTimeFormatter.ofPattern(pattern))
                }
            }
        }
        throw DateParsingError("Could not parse date")
    }

    fun htmlParse(htmlText: String): String {
        val stripped = htmlText.replace(Regex("<.*?>"), "")
        return stripped
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
    }

    fun place2time(listeHeures: List<Map<String, Any?>>, place: Int): LocalTime {
        val adjustedPlace = if (place > listeHeures.size) {
            place % (listeHeures.size - 1)
        } else place
        val start = listeHeures.firstOrNull { (it["G"] as? Number)?.toInt() == adjustedPlace }
            ?: throw IllegalArgumentException("Could not find starting time for place $place")
        val label = start["L"] as? String ?: throw IllegalArgumentException("Missing time label")
        val parsed = LocalTime.parse(label, DateTimeFormatter.ofPattern("HH'h'mm", Locale.getDefault()))
        return parsed
    }
}

open class Object(jsonDict: Map<String, Any?>) {
    protected val _resolver = Resolver(jsonDict)

    protected class Resolver(private val jsonDict: Map<String, Any?>) {
        fun <R> resolve(
            converter: (Any?) -> R,
            vararg path: String,
            default: Any? = Missing,
            strict: Boolean = true
        ): R {
            var jsonValue: Any? = jsonDict
            try {
                for (p in path) {
                    @Suppress("UNCHECKED_CAST")
                    jsonValue = (jsonValue as Map<String, Any?>).getValue(p)
                }
            } catch (e: Exception) {
                if (default !== Missing) {
                    log.fine("Could not get value for path: ${path.joinToString(",")}, using default")
                    jsonValue = default
                } else if (strict) {
                    log.fine("Could not follow path $path in $jsonDict")
                    throw ParsingError("Could not follow path", path.toList())
                } else {
                    jsonValue = null
                }
            }
            try {
                return converter(jsonValue)
            } catch (e: Exception) {
                log.fine("Could not convert value ${jsonValue?.let { it::class }}($jsonValue) with $converter")
                throw ParsingError("Error while converting value: ${e.message}", path.toList())
            }
        }
    }

    open fun toDict(exclude: Set<String> = emptySet(), includeProperties: Boolean = false): Map<String, Any?> {
        val serialized = mutableMapOf<String, Any?>()
        val fields = mutableListOf<java.lang.reflect.Field>()
        var cls: Class<*>? = this::class.java
        while (cls != null && cls != Any::class.java) {
            fields += cls.declaredFields
            cls = cls.superclass
        }
        fields.filter { !it.name.startsWith("_") && !exclude.contains(it.name) }.forEach { field ->
            field.isAccessible = true
            val value = field.get(this)
            serialized[field.name] = when (value) {
                is Object -> value.toDict()
                is List<*> -> value.map { v -> if (v is Object) v.toDict() else v }
                else -> value
            }
        }
        if (includeProperties) {
            this::class.members.filter { it.name !in exclude && it.parameters.size == 1 && it.name !in serialized.keys }
                .forEach { member ->
                    try {
                        val v = member.call(this)
                        serialized[member.name] = when (v) {
                            is Object -> v.toDict()
                            is List<*> -> v.map { if (it is Object) it.toDict() else it }
                            else -> v
                        }
                    } catch (_: Exception) {
                    }
                }
        }
        return serialized
    }
}

class Subject(parsedJson: Map<String, Any?>) : Object(parsedJson) {
    val id: String = _resolver.resolve({ it.toString() }, "N")
    val name: String = _resolver.resolve({ it.toString() }, "L")
    val groups: Boolean = _resolver.resolve({ it as Boolean }, "estServiceGroupe", default = false)
}

class Report(parsedJson: Map<String, Any?>) : Object(parsedJson) {
    class ReportSubject(parsedJson: Map<String, Any?>) : Object(parsedJson) {
        val id: String = _resolver.resolve({ it.toString() }, "N")
        val name: String = _resolver.resolve({ it.toString() }, "L")
        val color: String = _resolver.resolve({ it.toString() }, "couleur")
        val comments: List<String> = _resolver.resolve({ l -> (l as List<*>).mapNotNull { (it as? Map<*, *>)?.get("L") as? String } }, "ListeAppreciations", "V")

        private fun gradeOrNone(grade: Any?): String? = (grade as? String)?.let { if (it.isNotEmpty()) Util.gradeParse(it) else null }

        val class_average: String? = _resolver.resolve(::gradeOrNone, "MoyenneClasse", "V", strict = false)
        val student_average: String? = _resolver.resolve(::gradeOrNone, "MoyenneEleve", "V", strict = false)
        val min_average: String? = _resolver.resolve(::gradeOrNone, "MoyenneInf", "V", strict = false)
        val max_average: String? = _resolver.resolve(::gradeOrNone, "MoyenneSup", "V", strict = false)
        val coefficient: String? = _resolver.resolve({ it?.toString() }, "Coefficient", "V", strict = false)
        val teachers: List<String> = _resolver.resolve({ l -> (l as List<*>).mapNotNull { (it as? Map<*, *>)?.get("L") as? String } }, "ListeProfesseurs", "V", default = emptyList<String>())
    }

    val subjects: List<ReportSubject> = _resolver.resolve({ value: Any? -> (value as? List<*>)?.map { ReportSubject(it as Map<String, Any?>) } ?: emptyList<ReportSubject>() }, "ListeServices", "V", default = emptyList<ReportSubject>())
    val comments: List<String> = _resolver.resolve({ value: Any? -> (value as? List<*>)?.mapNotNull { (it as? Map<*, *>)?.get("L") as? String } ?: emptyList<String>() }, "ObjetListeAppreciations", "V", "ListeAppreciations", "V", default = emptyList<String>())
}

class Absence(jsonDict: Map<String, Any?>) : Object(jsonDict) {
    val id: String = _resolver.resolve({ it.toString() }, "N")
    val from_date: LocalDateTime = _resolver.resolve({ Util.datetimeParse(it as String) }, "dateDebut", "V")
    val to_date: LocalDateTime = _resolver.resolve({ Util.datetimeParse(it as String) }, "dateFin", "V")
    val justified: Boolean = _resolver.resolve({ it as Boolean }, "justifie", default = false)
    val hours: String? = _resolver.resolve({ it?.toString() }, "NbrHeures", strict = false)
    val days: Int = _resolver.resolve({ (it as? Number)?.toInt() ?: 0 }, "NbrJours", default = 0)
    val reasons: List<String> = _resolver.resolve({ l: Any? -> (l as? List<*>)?.mapNotNull { (it as? Map<*, *>)?.get("L") as? String } ?: emptyList<String>() }, "listeMotifs", "V", default = emptyList<String>())
}

class Delay(jsonDict: Map<String, Any?>) : Object(jsonDict) {
    val id: String = _resolver.resolve({ it.toString() }, "N")
    val date: LocalDateTime = _resolver.resolve({ Util.datetimeParse(it as String) }, "date", "V")
    val minutes: Int = _resolver.resolve({ (it as? Number)?.toInt() ?: 0 }, "duree", default = 0)
    val justified: Boolean = _resolver.resolve({ it as Boolean }, "justifie", default = false)
    val justification: String? = _resolver.resolve({ it?.toString() }, "justification", strict = false)
    val reasons: List<String> = _resolver.resolve({ l: Any? -> (l as? List<*>)?.mapNotNull { (it as? Map<*, *>)?.get("L") as? String } ?: emptyList<String>() }, "listeMotifs", "V", default = emptyList<String>())
}

class Period(private val client: ClientBase, jsonDict: Map<String, Any?>) : Object(jsonDict) {
    companion object { val instances: MutableSet<Period> = mutableSetOf() }

    val id: String
    val name: String
    val start: LocalDateTime
    val end: LocalDateTime

    init {
        instances.add(this)
        id = _resolver.resolve({ it.toString() }, "N")
        name = _resolver.resolve({ it.toString() }, "L")
        start = _resolver.resolve({ Util.datetimeParse(it as String) }, "dateDebut", "V")
        end = _resolver.resolve({ Util.datetimeParse(it as String) }, "dateFin", "V")
    }

    val report: Report?
        get() {
            val jsonData = mapOf("periode" to mapOf("G" to 2, "N" to id, "L" to name))
            val data = client.post("PageBulletins", 13, jsonData)["dataSec"]
            val inner = (data as? Map<*, *>)?.get("data") as? Map<String, Any?> ?: return null
            return if (!inner.containsKey("Message")) Report(inner) else null
        }

    val grades: List<Grade>
        get() {
            val jsonData = mapOf("Periode" to mapOf("N" to id, "L" to name))
            val response = client.post("DernieresNotes", 198, jsonData)
            val gradesJson = (((response["dataSec"] as Map<*, *>) ["data"] as Map<*, *>)["listeDevoirs"] as Map<*, *>)["V"] as List<*>
            return gradesJson.map { Grade(it as Map<String, Any?>) }
        }

    val averages: List<Average>
        get() {
            val jsonData = mapOf("Periode" to mapOf("N" to id, "L" to name))
            val response = client.post("DernieresNotes", 198, jsonData)
            val crs = (((response["dataSec"] as Map<*, *>)["data"] as Map<*, *>)["listeServices"] as Map<*, *>)["V"] as List<*>
            return try {
                crs.map { Average(it as Map<String, Any?>) }
            } catch (e: ParsingError) {
                if (e.path == listOf("moyEleve", "V")) throw UnsupportedOperation("Could not get averages") else throw e
            }
        }

    val overall_average: String
        get() {
            val jsonData = mapOf("Periode" to mapOf("N" to id, "L" to name))
            val response = client.post("DernieresNotes", 198, jsonData)
            val data = (response["dataSec"] as Map<*, *>)["data"] as Map<*, *>
            val average = data["moyGenerale"] as? Map<*, *>
            if (average != null) return average["V"].toString()
            val services = (data["listeServices"] as Map<*, *>)["V"] as List<*>
            if (services.isNotEmpty()) {
                var sum = 0.0
                var total = 0
                services.forEach { s ->
                    try {
                        val avrg = ((s as Map<*, *>)["moyEleve"] as Map<*, *>)["V"].toString().replace(",", ".")
                        val flt = avrg.toDoubleOrNull()
                        if (flt != null) {
                            sum += flt
                            total += 1
                        }
                    } catch (e: Exception) {
                        throw UnsupportedOperation("Could not get averages")
                    }
                }
                val res = if (total > 0) (sum / total).let { (it * 100).roundToInt() / 100.0 } else -1.0
                return res.toString()
            }
            return "-1"
        }

    val class_overall_average: String?
        get() {
            val jsonData = mapOf("Periode" to mapOf("N" to id, "L" to name))
            val response = client.post("DernieresNotes", 198, jsonData)
            val data = (response["dataSec"] as Map<*, *>)["data"] as Map<*, *>
            val average = data["moyGeneraleClasse"] as? Map<*, *>
            return average?.get("V") as? String
        }

    val evaluations: List<Evaluation>
        get() {
            val jsonData = mapOf("periode" to mapOf("N" to id, "L" to name, "G" to 2))
            val response = client.post("DernieresEvaluations", 201, jsonData)
            val evaluationsJson = ((((response["dataSec"] as Map<*, *>) ["data"] as? Map<*, *>)?.get("listeEvaluations") as? Map<*, *>)?.get("V") as? List<*>)
                ?: emptyList<Any>()
            return evaluationsJson.map { Evaluation(it as Map<String, Any?>) }
        }

    val absences: List<Absence>
        get() {
            val jsonData = mapOf(
                "periode" to mapOf("N" to id, "L" to name, "G" to 2),
                "DateDebut" to mapOf("_T" to 7, "V" to start.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"))),
                "DateFin" to mapOf("_T" to 7, "V" to end.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"))),
            )
            val response = client.post("PagePresence", 19, jsonData)
            val absencesJson = (((response["dataSec"] as Map<*, *>)["data"] as Map<*, *>)["listeAbsences"] as Map<*, *>)["V"] as List<*>
            return absencesJson.filter { (it as Map<*, *>) ["G"] == 13 }.map { Absence(it as Map<String, Any?>) }
        }

    val delays: List<Delay>
        get() {
            val jsonData = mapOf(
                "periode" to mapOf("N" to id, "L" to name, "G" to 2),
                "DateDebut" to mapOf("_T" to 7, "V" to start.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"))),
                "DateFin" to mapOf("_T" to 7, "V" to end.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"))),
            )
            val response = client.post("PagePresence", 19, jsonData)
            val delaysJson = (((response["dataSec"] as Map<*, *>)["data"] as Map<*, *>)["listeAbsences"] as Map<*, *>)["V"] as List<*>
            return delaysJson.filter { (it as Map<*, *>)["G"] == 14 }.map { Delay(it as Map<String, Any?>) }
        }

    val punishments: List<Punishment>
        get() {
            val jsonData = mapOf(
                "periode" to mapOf("N" to id, "L" to name, "G" to 2),
                "DateDebut" to mapOf("_T" to 7, "V" to start.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"))),
                "DateFin" to mapOf("_T" to 7, "V" to end.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"))),
            )
            val response = client.post("PagePresence", 19, jsonData)
            val absencesJson = (((response["dataSec"] as Map<*, *>)["data"] as Map<*, *>)["listeAbsences"] as Map<*, *>)["V"] as List<*>
            return absencesJson.filter { (it as Map<*, *>)["G"] == 41 }.map { Punishment(client, it as Map<String, Any?>) }
        }
}

class Average(jsonDict: Map<String, Any?>) : Object(jsonDict) {
    val student: String = _resolver.resolve({ Util.gradeParse(it as String) }, "moyEleve", "V")
    val out_of: String = _resolver.resolve({ Util.gradeParse(it as String) }, "baremeMoyEleve", "V")
    val default_out_of: String = _resolver.resolve({ Util.gradeParse(it as String) }, "baremeMoyEleveParDefault", "V", default = "")
    val class_average: String = _resolver.resolve({ Util.gradeParse(it as String) }, "moyClasse", "V")
    val min: String = _resolver.resolve({ Util.gradeParse(it as String) }, "moyMin", "V")
    val max: String = _resolver.resolve({ Util.gradeParse(it as String) }, "moyMax", "V")
    val subject: Subject = Subject(jsonDict)
    val background_color: String? = _resolver.resolve({ it?.toString() }, "couleur", strict = false)
}

class Grade(jsonDict: Map<String, Any?>) : Object(jsonDict) {
    val id: String = _resolver.resolve({ it.toString() }, "N")
    val grade: String = _resolver.resolve({ Util.gradeParse(it as String) }, "note", "V")
    val out_of: String = _resolver.resolve({ Util.gradeParse(it as String) }, "bareme", "V")
    val default_out_of: String = _resolver.resolve({ Util.gradeParse(it as String) }, "baremeParDefaut", "V", strict = false)
    val date: LocalDate = _resolver.resolve({ Util.dateParse(it as String) }, "date", "V")
    val subject: Subject = _resolver.resolve({ Subject(it as Map<String, Any?>) }, "service", "V")
    val period: Period = _resolver.resolve({ p ->
        val periodId = p?.toString()
            ?: throw ParsingError("Missing period id", listOf("periode", "V", "N"))
        Util.get(Period.instances, "id" to periodId).firstOrNull()
            ?: throw ParsingError("Missing period id", listOf("periode", "V", "N"))
    }, "periode", "V", "N")
    val average: String = _resolver.resolve({ Util.gradeParse(it as String) }, "moyenne", "V", strict = false)
    val max: String = _resolver.resolve({ Util.gradeParse(it as String) }, "noteMax", "V")
    val min: String = _resolver.resolve({ Util.gradeParse(it as String) }, "noteMin", "V")
    val coefficient: String = _resolver.resolve({ it.toString() }, "coefficient")
    val comment: String = _resolver.resolve({ it.toString() }, "commentaire")
    val is_bonus: Boolean = _resolver.resolve({ it as Boolean }, "estBonus")
    val is_optionnal: Boolean = _resolver.resolve({ (it as Boolean) && !is_bonus }, "estFacultatif")
    val is_out_of_20: Boolean = _resolver.resolve({ it as Boolean }, "estRamenerSur20")

    override fun toDict(exclude: Set<String>, includeProperties: Boolean): Map<String, Any?> {
        return super.toDict(exclude = exclude + setOf("period"), includeProperties = includeProperties)
    }
}

class Attachment(private val client: ClientBase, jsonDict: Map<String, Any?>) : Object(jsonDict) {
    val name: String = _resolver.resolve({ it?.toString() ?: "" }, "L", default = "")
    val id: String = _resolver.resolve({ it.toString() }, "N")
    val type: Int = _resolver.resolve({ (it as? Number)?.toInt() ?: 0 }, "G")
    val url: String
    private var _data: ByteArray? = null

    init {
        url = if (type == 0) {
            val urlVal = _resolver.resolve({ it?.toString() }, "url", default = null, strict = false)
            urlVal ?: name
        } else {
            val jsonPayload = byteArrayOf('{'.code.toByte()) + "\"N\":\"$id\",\"Actif\":true".toByteArray() + byteArrayOf('}'.code.toByte())
            val padded = pad(jsonPayload, 16)
            val magicStuff = client.communication.encryption.aes_encrypt(padded).joinToString("") { b -> "%02x".format(b) }
            "${client.communication.root_site}/FichiersExternes/$magicStuff/" +
                java.net.URLEncoder.encode(name, "UTF-8") + "?Session=" + client.attributes["h"]
        }
    }

    @Suppress("SameParameterValue")
    private fun pad(data: ByteArray, blockSize: Int): ByteArray {
        val padding = blockSize - (data.size % blockSize)
        return data + ByteArray(padding) { padding.toByte() }
    }

    fun save(fileName: String? = null) {
        if (type == 1) {
            val response = client.communication.session.get(url)
            val target = fileName ?: name
            if (response.statusCode != 200) {
                throw kotlinx.io.files.FileNotFoundException("The file was not found on pronote. The url may be badly formed.")
            }
            java.io.File(target).outputStream().use { output ->
                response.iterContent(1024).forEach { chunk -> output.write(chunk) }
            }
        }
    }

    val data: ByteArray
        get() {
            _data?.let { return it }
            val response = client.communication.session.get(url)
            return response.content
        }
}

class LessonContent(private val client: ClientBase, jsonDict: Map<String, Any?>) : Object(jsonDict) {
    val title: String? = _resolver.resolve({ it?.toString() }, "L", strict = false)
    val description: String = _resolver.resolve({ Util.htmlParse(it as String) }, "descriptif", "V")
    val category: String? = _resolver.resolve({ it?.toString() }, "categorie", "V", "L", strict = false)
    private val _files: List<Map<String, Any?>> = _resolver.resolve({ it as? List<Map<String, Any?>> ?: emptyList() }, "ListePieceJointe", "V")

    val files: List<Attachment>
        get() = _files.map { Attachment(client, it) }
}

class Lesson(private val client: ClientBase, private val jsonDict: Map<String, Any?>) : Object(jsonDict) {
    val id: String
    val canceled: Boolean
    val status: String?
    val memo: String?
    val background_color: String?
    val outing: Boolean
    val start: LocalDateTime
    val exempted: Boolean
    val virtual_classrooms: List<String>
    val num: Int
    val detention: Boolean
    val test: Boolean
    var end: LocalDateTime
    val teacher_names: MutableList<String> = mutableListOf()
    val classrooms: MutableList<String> = mutableListOf()
    val group_names: MutableList<String> = mutableListOf()
    var subject: Subject? = null
    val teacher_name: String?
    val classroom: String?
    val group_name: String?
    private var _content: LessonContent? = null

    init {
        id = _resolver.resolve({ it.toString() }, "N")
        canceled = _resolver.resolve({ it as Boolean }, "estAnnule", default = false)
        status = _resolver.resolve({ it?.toString() }, "Statut", strict = false)
        memo = _resolver.resolve({ it?.toString() }, "memo", strict = false)
        background_color = _resolver.resolve({ it?.toString() }, "CouleurFond", strict = false)
        outing = _resolver.resolve({ it as Boolean }, "estSortiePedagogique", default = false)
        start = _resolver.resolve({ Util.datetimeParse(it as String) }, "DateDuCours", "V")
        exempted = _resolver.resolve({ it as Boolean }, "dispenseEleve", default = false)
        virtual_classrooms = _resolver.resolve({ l: Any? -> (l as? List<*>)?.mapNotNull { (it as? Map<*, *>)?.get("url") as? String } ?: emptyList<String>() }, "listeVisios", "V", default = emptyList<String>())
        num = _resolver.resolve({ (it as? Number)?.toInt() ?: 0 }, "P", default = 0)
        detention = _resolver.resolve({ it as Boolean }, "estRetenue", default = false)
        test = _resolver.resolve({ it as Boolean }, "cahierDeTextes", "V", "estDevoir", default = false)
        val parsedEnd: LocalDateTime? = _resolver.resolve({ v: Any? -> v?.let { Util.datetimeParse(it as String) } }, "DateDuCoursFin", "V", strict = false)
        end = parsedEnd ?: run {
            val endTimesAny = (((client.func_options["dataSec"] as Map<*, *>) ["data"] as Map<*, *>) ["General"] as Map<*, *>)
                .get("ListeHeuresFin")
            val endMap = endTimesAny as? Map<*, *>
            val endList = (endMap?.get("V") as? List<Map<String, Any?>>) ?: emptyList()
            val endPlace = ((jsonDict["place"] as Number).toInt() % (endList.size - 1)) + ((jsonDict["duree"] as Number).toInt()) - 1
            val endTime = Util.place2time(endList, endPlace)
            start.withHour(endTime.hour).withMinute(endTime.minute)
        }
        val listeContenus = jsonDict["ListeContenus"] as? Map<*, *> ?: throw ParsingError("Error while parsing for lesson details",
            listOf("ListeContenus", "V"))
        val values = listeContenus["V"] as? List<Map<String, Any?>> ?: throw ParsingError("Error while parsing for lesson details",
            listOf("ListeContenus", "V"))
        values.forEach { d ->
            val g = d["G"] as? Int ?: return@forEach
            when (g) {
                16 -> subject = Subject(d)
                3 -> teacher_names += d["L"] as String
                17 -> classrooms += d["L"] as String
                2 -> group_names += d["L"] as String
            }
        }
        teacher_name = if (teacher_names.isNotEmpty()) teacher_names.joinToString(", ") else null
        classroom = if (classrooms.isNotEmpty()) classrooms.joinToString(", ") else null
        group_name = if (group_names.isNotEmpty()) group_names.joinToString(", ") else null
    }

    val normal: Boolean
        get() = detention == false && outing == false

    val content: LessonContent?
        get() {
            _content?.let { return it }
            val week = client.get_week(start.toLocalDate())
            val data = mapOf("domaine" to mapOf("_T" to 8, "V" to "[$week..$week]"))
            val response = client.post("PageCahierDeTexte", 89, data)
            val lessons = (((response["dataSec"] as Map<*, *>)["data"] as Map<*, *>)["ListeCahierDeTextes"] as Map<*, *>)["V"] as List<*>
            var contents: Map<String, Any?>? = null
            lessons.forEach { lesson ->
                val lessonMap = lesson as Map<String, Any?>
                val course = (lessonMap["cours"] as Map<*, *>)["V"] as Map<*, *>
                if (course["N"].toString() == id && (lessonMap["listeContenus"] as Map<*, *>)["V"] != null) {
                    val listC = (lessonMap["listeContenus"] as Map<*, *>)["V"] as List<*>
                    if (listC.isNotEmpty()) contents = listC[0] as Map<String, Any?>
                }
            }
            contents ?: return null
            _content = LessonContent(client, contents)
            return _content
        }
}

class Homework(private val client: ClientBase, jsonDict: Map<String, Any?>) : Object(jsonDict) {
    val id: String = _resolver.resolve({ it.toString() }, "N")
    val description: String = _resolver.resolve({ Util.htmlParse(it as String) }, "descriptif", "V")
    var done: Boolean = _resolver.resolve({ it as Boolean }, "TAFFait")
    val subject: Subject = _resolver.resolve({ Subject(it as Map<String, Any?>) }, "Matiere", "V")
    val date: LocalDate = _resolver.resolve({ Util.dateParse(it as String) }, "PourLe", "V")
    val background_color: String = _resolver.resolve({ it.toString() }, "CouleurFond")
    private val _files: List<Map<String, Any?>> = _resolver.resolve({ it as? List<Map<String, Any?>> ?: emptyList() }, "ListePieceJointe", "V")

    fun set_done(status: Boolean) {
        val data = mapOf("listeTAF" to listOf(mapOf("N" to id, "TAFFait" to status)))
        client.post("SaisieTAFFaitEleve", 88, data)
        done = status
    }

    val files: List<Attachment>
        get() = _files.map { Attachment(client, it) }
}

class Information(private val client: ClientBase, jsonDict: Map<String, Any?>) : Object(jsonDict) {
    val id: String = _resolver.resolve({ it.toString() }, "N")
    val title: String? = _resolver.resolve({ it?.toString() }, "L", strict = false)
    val author: String = _resolver.resolve({ it.toString() }, "auteur")
    private val _raw_content: List<Any?> = _resolver.resolve({ it as List<Any?> }, "listeQuestions", "V")
    var read: Boolean = _resolver.resolve({ it as Boolean }, "lue")
    val creation_date: LocalDateTime = _resolver.resolve({ Util.datetimeParse(it as String) }, "dateCreation", "V")
    val start_date: LocalDateTime? = _resolver.resolve({ v: Any? -> v?.let { Util.datetimeParse(it as String) } }, "dateDebut", "V", strict = false)
    val end_date: LocalDateTime? = _resolver.resolve({ v: Any? -> v?.let { Util.datetimeParse(it as String) } }, "dateFin", "V", strict = false)
    val category: String = _resolver.resolve({ it.toString() }, "categorie", "V", "L")
    val survey: Boolean = _resolver.resolve({ it as Boolean }, "estSondage")
    val template: Boolean = _resolver.resolve({ it as Boolean }, "estModele", default = false)
    val shared_template: Boolean = _resolver.resolve({ it as Boolean }, "estModelePartage", default = false)
    val anonymous_response: Boolean = _resolver.resolve({ it as Boolean }, "reponseAnonyme")
    val attachments: List<Attachment>

    init {
        fun makeAttachments(questions: Any?): List<Attachment> {
            val list = questions as? List<*> ?: return emptyList()
            val attachments = mutableListOf<Attachment>()
            list.forEach { question ->
                val qMap = question as? Map<*, *> ?: return@forEach
                val pieces = ((qMap["listePiecesJointes"] as Map<*, *>)["V"] as? List<*>) ?: emptyList<Any?>()
                pieces.forEach { pj -> attachments += Attachment(client, pj as Map<String, Any?>) }
            }
            return attachments
        }
        attachments = _resolver.resolve<List<Attachment>>(::makeAttachments, "listeQuestions", "V")
    }

    val content: String
        get() = Util.htmlParse((((_raw_content[0] as Map<*, *>)["texte"] as Map<*, *>)["V"] as String))

    fun mark_as_read(status: Boolean) {
        val data = mapOf(
            "listeActualites" to listOf(
                mapOf(
                    "N" to id,
                    "validationDirecte" to true,
                    "genrePublic" to 4,
                    "public" to mapOf("N" to client.info.id, "G" to 4),
                    "lue" to status,
                ),
            ),
            "saisieActualite" to false,
        )
        client.post("SaisieActualites", 8, data)
        read = status
    }
}

class Recipient(private val client: ClientBase, jsonDict: Map<String, Any?>) : Object(jsonDict) {
    private val _type: Int = _resolver.resolve({ (it as? Number)?.toInt() ?: 0 }, "G")
    val id: String = _resolver.resolve({ it.toString() }, "N")
    val name: String = _resolver.resolve({ it.toString() }, "L")
    val type: String = if (_type == 3) "teacher" else "staff"
    val email: String? = _resolver.resolve({ it?.toString() }, "email", strict = false)
    val functions: List<String>
    val with_discussion: Boolean = _resolver.resolve({ it as Boolean }, "avecDiscussion", default = false)

    init {
        functions = if (type == "teacher") {
            _resolver.resolve({ x: Any? -> (x as? List<*>)?.mapNotNull { (it as? Map<*, *>)?.get("L") as? String } ?: emptyList<String>() }, "listeRessources", "V")
        } else {
            _resolver.resolve({ f: Any? -> listOfNotNull(((f as? Map<*, *>)?.get("L") ?: f) as? String) }, "fonction", "V", "L", default = emptyList<String>())
        }
    }
}

class Student(private val client: ClientBase, jsonDict: Map<String, Any?>) : Object(jsonDict) {
    val full_name: String = _resolver.resolve({ it.toString() }, "L")
    val id: String = _resolver.resolve({ it.toString() }, "N")
    val enrollment_date: LocalDate = _resolver.resolve({ Util.dateParse(it as String) }, "entree", "V")
    val date_of_birth: LocalDate = _resolver.resolve({ Util.dateParse(it as String) }, "neLe", "V")
    val projects: List<String> = _resolver.resolve({ p: Any? ->
        (p as? List<*>)?.map { x ->
            val m = x as? Map<*, *> ?: return@map ""
            "${m.getOrDefault("typeAmenagement", "")} (${m.getOrDefault("handicap", "")})"
        } ?: emptyList()
    }, "listeProjets", "V", default = emptyList<String>())
    val last_name: String = _resolver.resolve({ it.toString() }, "nom")
    val first_names: String = _resolver.resolve({ it.toString() }, "prenoms")
    val sex: String = _resolver.resolve({ it.toString() }, "sexe")

    private var cache: Map<String, Any?>? = null

    private fun loadIdentity(): Identity {
        if (cache == null) {
            cache = client.post("FicheEleve", 105, mapOf("Eleve" to mapOf("N" to id), "AvecEleve" to true, "AvecResponsables" to true))
        }
        val ident = (((cache!!["dataSec"] as Map<*, *>) ["data"] as Map<*, *>) ["Identite"] as Map<*, *>) ["V"]
        return Identity(ident as Map<String, Any?>)
    }

    val identity: Identity
        get() = loadIdentity()

    val guardians: List<Guardian>
        get() {
            if (cache == null) loadIdentity()
            val resp = cache!!
            val guardiansJson = (((resp["dataSec"] as Map<*, *>) ["data"] as Map<*, *>) ["Responsables"] as Map<*, *>) ["V"] as List<*>
            return guardiansJson.map { Guardian(it as Map<String, Any?>) }
        }
}

class ClientInfo(private val client: ClientBase, json: Map<String, Any?>) {
    val id: String = json["N"].toString()
    val raw_resource: Map<String, Any?> = json
    private var __cache: Map<String, Any?>? = null

    val name: String
        get() = raw_resource["L"].toString()

    val profile_picture: Attachment?
        get() = if (raw_resource["avecPhoto"] == true) Attachment(client, mapOf("L" to "photo.jpg", "N" to raw_resource["N"]!!, "G" to 1)) else null

    val delegue: List<String>
        get() = if (raw_resource["estDelegue"] == true) ((raw_resource["listeClassesDelegue"] as Map<*, *>) ["V"] as List<*>).mapNotNull { (it as? Map<*, *>)?.get("L") as? String } else emptyList()

    val class_name: String
        get() = (raw_resource["classeDEleve"] as? Map<*, *>)?.get("L") as? String ?: ""

    val establishment: String
        get() = (((raw_resource["Etablissement"] as? Map<*, *>)?.get("V") as? Map<*, *>)?.get("L") as? String) ?: ""

    private fun cache(): Map<String, Any?> {
        if (__cache == null) {
            val resp = client.communication.post(
                "PageInfosPerso",
                mapOf("Signature" to mapOf("onglet" to 49, "ressource" to mapOf("N" to id, "G" to 4))),
            )
            __cache = (((resp["dataSec"] as? Map<*, *>)?.get("data") as? Map<*, *>)?.get("Informations") as? Map<String, Any?>)
                ?: emptyMap()
        }
        return __cache!!
    }

    private fun Map<String, Any?>.stringOrEmpty(key: String): String = (this[key] as? String) ?: ""

    val address: List<String>
        get() {
            val infos = cache()
            return listOf(
                infos.stringOrEmpty("adresse1"),
                infos.stringOrEmpty("adresse2"),
                infos.stringOrEmpty("adresse3"),
                infos.stringOrEmpty("adresse4"),
                infos.stringOrEmpty("codePostal"),
                infos.stringOrEmpty("ville"),
                infos.stringOrEmpty("province"),
                infos.stringOrEmpty("pays"),
            )
        }

    val email: String
        get() = cache().stringOrEmpty("eMail")

    val phone: String
        get() {
            val infos = cache()
            return "+" + infos.stringOrEmpty("indicatifTel") + infos.stringOrEmpty("telephonePortable")
        }

    val ine_number: String
        get() = cache().stringOrEmpty("numeroINE")
}

class Discussion(private val client: ClientBase, jsonDict: Map<String, Any?>, labels: Map<Int, Int>) : Object(jsonDict) {
    private val _possessions: List<Any?> = _resolver.resolve({ it as List<Any?> }, "listePossessionsMessages", "V")
    private var _date_cache: LocalDateTime? = null
    val replyable: Boolean = true
    val subject: String = _resolver.resolve({ it.toString() }, "objet")
    val creator: String? = _resolver.resolve({ it?.toString() }, "initiateur", strict = false)
    private val _participants_message_id: String = _resolver.resolve({ it.toString() }, "messagePourParticipants", "V", "N")
    val unread: Int = _resolver.resolve({ (it as? Number)?.toInt() ?: 0 }, "nbNonLus", default = 0)
    val closed: Boolean = _resolver.resolve({ it as Boolean }, "ferme", default = false)
    val close: Boolean = closed
    val labelsList: List<String>

    init {
        val labelsStr = mapOf(4 to "Drafts", 5 to "Trash")
        labelsList = _resolver.resolve({ l: Any? -> (l as? List<*>)?.map { labelsStr[labels[(it as Map<*, *>) ["N"]] ?: ""] ?: "" } ?: emptyList<String>() }, "listeEtiquettes", "V", default = emptyList<String>())
    }

    fun participants(): List<String> {
        val resp = client.post(
            "SaisiePublicMessage",
            131,
            mapOf(
                "estDestinatairesReponse" to false,
                "estPublicParticipant" to true,
                "message" to mapOf("N" to _participants_message_id),
            ),
        )
        val list = (((resp["dataSec"] as Map<*, *>) ["data"] as Map<*, *>) ["listeDest"] as Map<*, *>) ["V"] as List<*>
        return list.mapNotNull { (it as? Map<*, *>)?.get("L") as? String }
    }

    val messages: List<Message>
        get() {
            val resp = client.post("ListeMessages", 131, mapOf("listePossessionsMessages" to _possessions))
            val msgs = mutableMapOf<String, Message>()
            val list = (((resp["dataSec"] as Map<*, *>) ["data"] as Map<*, *>) ["listeMessages"] as Map<*, *>) ["V"] as List<*>
            list.forEach { js ->
                val m = Message(client, js as Map<String, Any?>)
                msgs[m.id] = m
            }
            list.forEach { js ->
                val jsMap = js as Map<*, *>
                val source = (jsMap["messageSource"] as Map<*, *>) ["V"] as Map<*, *>
                val target = msgs[jsMap["N"].toString()]
                target?.replying_to?.apply { msgs[source["N"].toString()] }
            }
            return msgs.values.sortedBy { it.created }
        }

    val date: LocalDateTime
        get() {
            if (_date_cache == null) {
                val msgs = messages
                _date_cache = msgs.first().created
            }
            return _date_cache!!
        }

    fun mark_as(read: Boolean) {
        client.post(
            "SaisieMessage",
            131,
            mapOf(
                "commande" to "pourLu",
                "lu" to read,
                "listePossessionsMessages" to _possessions,
            ),
        )
    }

    fun reply(message: String) {
        if (closed) throw DiscussionClosed("Cannot reply to discussion")
        val resp = client.post("ListeMessages", 131, mapOf("listePossessionsMessages" to _possessions))
        val msg = (((resp["dataSec"] as Map<*, *>) ["data"] as Map<*, *>?)?.get("messagePourReponse") as? Map<*, *>)
        val button = (((resp["dataSec"] as Map<*, *>) ["data"] as Map<*, *>) ["listeBoutons"] as Map<*, *>) ["V"] as List<*>? ?: emptyList<Any>()
        client.post(
            "SaisieMessage",
            131,
            mapOf(
                "messagePourReponse" to msg,
                "contenu" to message,
                "listeFichiers" to emptyList<Any>(),
                "bouton" to button.firstOrNull(),
            ),
        )
    }

    fun delete() {
        client.post(
            "SaisieMessage",
            131,
            mapOf(
                "commande" to "corbeille",
                "listePossessionsMessages" to _possessions,
            ),
        )
    }
}

class StudentClass(private val client: BaseClientImpl, jsonDict: Map<String, Any?>) : Object(jsonDict) {
    val id: String = _resolver.resolve({ it.toString() }, "N")
    val name: String = _resolver.resolve({ it.toString() }, "L")
    val responsible: Boolean = _resolver.resolve({ it as Boolean }, "estResponsable", strict = false, default = false)
    val grade: String = _resolver.resolve({
        (it as? Map<*, *>)?.get("V")?.toString() ?: it?.toString() ?: ""
    }, "niveau", "V", "L", strict = false, default = "")

    fun students(period: Period? = null): List<Student> {
        val selectedPeriod = period ?: client.periods.firstOrNull()
            ?: throw PronoteAPIError("Missing period for StudentClass")
        val response = client.post(
            "ListeRessources",
            105,
            mapOf(
                "classe" to mapOf("N" to id, "G" to 1),
                "periode" to mapOf("N" to selectedPeriod.id, "G" to 1),
            ),
        )
        val ressources = (((response["dataSec"] as? Map<*, *>)?.get("data") as? Map<*, *>)
            ?.get("listeRessources") as? Map<*, *>)?.get("V") as? List<*> ?: emptyList<Any?>()
        return ressources.map { Student(client, it as Map<String, Any?>) }
    }
}

class Acquisition(jsonDict: Map<String, Any?>) : Object(jsonDict) {
    val id: String = _resolver.resolve({ it.toString() }, "N")
    val level: String = _resolver.resolve({ it.toString() }, "L")
    val abbreviation: String = _resolver.resolve({ it.toString() }, "abbreviation")
    val coefficient: Int = _resolver.resolve({ (it as Number).toInt() }, "coefficient")
    val domain: String = _resolver.resolve({ it.toString() }, "domaine", "V", "L")
    val domain_id: String = _resolver.resolve({ it.toString() }, "domaine", "V", "N")
    val name: String? = _resolver.resolve({ it?.toString() }, "item", "V", "L", strict = false)
    val name_id: String? = _resolver.resolve({ it?.toString() }, "item", "V", "N", strict = false)
    val order: Int = _resolver.resolve({ (it as Number).toInt() }, "ordre")
    val pillar: String = _resolver.resolve({ it.toString() }, "pilier", "V", "L")
    val pillar_id: String = _resolver.resolve({ it.toString() }, "pilier", "V", "N")
    val pillar_prefix: String = _resolver.resolve({ it.toString() }, "pilier", "V", "strPrefixes")
}

class Evaluation(jsonDict: Map<String, Any?>) : Object(jsonDict) {
    val name: String = _resolver.resolve({ it.toString() }, "L")
    val id: String = _resolver.resolve({ it.toString() }, "N")
    val domain: String? = _resolver.resolve({ it?.toString() }, "domaine", "V", "L", strict = false)
    val teacher: String = _resolver.resolve({ it.toString() }, "individu", "V", "L")
    val coefficient: Int = _resolver.resolve({ (it as Number).toInt() }, "coefficient")
    val description: String = _resolver.resolve({ it.toString() }, "descriptif")
    val subject: Subject = _resolver.resolve({ Subject(it as Map<String, Any?>) }, "matiere", "V")
    val paliers: List<String> = _resolver.resolve({ list -> (list as List<*>).mapNotNull { (it as? Map<*, *>)?.get("L") as? String } }, "listePaliers", "V")
    val acquisitions: List<Acquisition> = _resolver.resolve({ list -> (list as List<*>).map { Acquisition(it as Map<String, Any?>) }.sortedBy { it.order } }, "listeNiveauxDAcquisitions", "V")
    val date: LocalDate = _resolver.resolve({ Util.dateParse(it as String) }, "date", "V")
}

class Punishment(private val client: ClientBase, jsonDict: Map<String, Any?>) : Object(jsonDict) {
    class ScheduledPunishment(client: ClientBase, jsonDict: Map<String, Any?>) : Object(jsonDict) {
        private val clientRef = client
        val id: String = _resolver.resolve({ it.toString() }, "N")
        val start: Any
        val duration: Duration?
        init {
            val date = _resolver.resolve({ Util.dateParse(it as String) }, "date", "V")
            val place = _resolver.resolve({ (it as? Number)?.toInt() }, "placeExecution", strict = false)
            start = if (place != null) {
                val heuresData = ((clientRef.func_options["dataSec"] as Map<*, *>) ["data"] as Map<*, *>)
                val heuresGeneral = heuresData["General"] as Map<*, *>
                val heuresListe = heuresGeneral["ListeHeures"] as Map<*, *>
                val heures = heuresListe["V"] as List<*>
                val time = Util.place2time(heures as List<Map<String, Any?>>, place)
                LocalDateTime.of(date, time)
            } else date
            duration = _resolver.resolve({ v -> (v as? Number)?.let { Duration.ofMinutes(it.toLong()) } }, "duree", strict = false)
        }
    }

    val id: String = _resolver.resolve({ it.toString() }, "N")
    val during_lesson: Boolean = _resolver.resolve({ !(it as Boolean) }, "horsCours")
    val given: Any
    val exclusion: Boolean = _resolver.resolve({ it as Boolean }, "estUneExclusion")
    val homework: String = _resolver.resolve({ it?.toString() ?: "" }, "travailAFaire", strict = false)
    val homework_documents: List<Attachment> = _resolver.resolve({ list -> (list as? List<*>)?.map { Attachment(client, it as Map<String, Any?>) } ?: emptyList() }, "documentsTAF", "V", default = emptyList<Attachment>())
    val circumstances: String = _resolver.resolve({ it.toString() }, "circonstances")
    val circumstance_documents: List<Attachment> = _resolver.resolve({ list -> (list as? List<*>)?.map { Attachment(client, it as Map<String, Any?>) } ?: emptyList() }, "documentsCirconstances", "V", default = emptyList<Attachment>())
    val nature: String = _resolver.resolve({ it.toString() }, "nature", "V", "L")
    val requires_parent: String? = _resolver.resolve({ it?.toString() }, "nature", "V", "estAvecARParent", strict = false)
    val reasons: List<String> = _resolver.resolve({ list -> (list as List<*>).mapNotNull { (it as? Map<*, *>)?.get("L") as? String } }, "listeMotifs", "V", default = emptyList<String>())
    val giver: String = _resolver.resolve({ it.toString() }, "demandeur", "V", "L")
    val schedulable: Boolean = _resolver.resolve({ it as Boolean }, "estProgrammable")
    val schedule: List<ScheduledPunishment>
    val duration: Duration?

    init {
        val date = _resolver.resolve({ Util.dateParse(it as String) }, "dateDemande", "V")
        given = if (during_lesson) {
            val place = _resolver.resolve({ (it as Number).toInt() }, "placeDemande")
            val heuresData = ((client.func_options["dataSec"] as Map<*, *>)["data"] as Map<*, *>)
            val heuresGeneral = heuresData["General"] as Map<*, *>
            val heuresListe = heuresGeneral["ListeHeures"] as Map<*, *>
            val heures = heuresListe["V"] as List<*>
            val time = Util.place2time(heures as List<Map<String, Any?>>, place)
            LocalDateTime.of(date, time)
        } else date
        schedule = if (schedulable) {
            _resolver.resolve({ list -> (list as List<*>).map { ScheduledPunishment(client, it as Map<String, Any?>) } }, "programmation", "V", default = emptyList<ScheduledPunishment>())
        } else emptyList()
        duration = _resolver.resolve({ v -> (v as? Number)?.let { Duration.ofMinutes(it.toLong()) } }, "duree", strict = false)
    }
}

class Menu(private val client: ClientBase, jsonDict: Map<String, Any?>) : Object(jsonDict) {
    class FoodLabel(jsonDict: Map<String, Any?>) : Object(jsonDict) {
        val id: String = _resolver.resolve({ it.toString() }, "N")
        val name: String = _resolver.resolve({ it.toString() }, "L")
        val color: String? = _resolver.resolve({ it?.toString() }, "couleur", strict = false)
    }
    class Food(jsonDict: Map<String, Any?>) : Object(jsonDict) {
        val id: String = _resolver.resolve({ it.toString() }, "N")
        val name: String = _resolver.resolve({ it.toString() }, "L")
        val labels: List<FoodLabel> = _resolver.resolve({ labels -> (labels as List<*>).map { FoodLabel(it as Map<String, Any?>) } }, "listeLabelsAlimentaires", "V", default = emptyList<FoodLabel>())
    }

    val id: String = _resolver.resolve({ it.toString() }, "N")
    val name: String? = _resolver.resolve({ it?.toString() }, "L", strict = false)
    val date: LocalDate = _resolver.resolve({ Util.dateParse(it as String) }, "Date", "V")
    val is_lunch: Boolean = _resolver.resolve({ (it as Number).toInt() == 0 }, "G")
    val is_dinner: Boolean = _resolver.resolve({ (it as Number).toInt() == 1 }, "G")
    val first_meal: List<Food>?
    val main_meal: List<Food>?
    val side_meal: List<Food>?
    val other_meal: List<Food>?
    val cheese: List<Food>?
    val dessert: List<Food>?

    init {
        // Build map keyed by meal "G" value (as string) like Python does
        val platsList = when (val raw = _resolver.resolve({ it }, "ListePlats", "V", strict = false)) {
            is Map<*, *> -> (raw["V"] as? List<*>) ?: emptyList<Any>()
            is List<*> -> raw
            else -> emptyList<Any>()
        }
        val dDict = platsList.mapNotNull { it as? Map<String, Any?> }.associateBy {
            ((it["G"] as? Number)?.toInt() ?: it["G"].toString().toInt()).toString()
        }
        val tempResolver = Resolver(dDict)
        fun initFood(d: Any?): List<Food>? {
            val dict = d as? Map<*, *> ?: return null
            val aliments = (dict["ListeAliments"] as? Map<*, *>)?.get("V") as? List<*> ?: return null
            return aliments.map { Food(it as Map<String, Any?>) }
        }
        first_meal = tempResolver.resolve(::initFood, "0", strict = false)
        main_meal = tempResolver.resolve(::initFood, "1", strict = false)
        side_meal = tempResolver.resolve(::initFood, "2", strict = false)
        other_meal = tempResolver.resolve(::initFood, "3", strict = false)
        cheese = tempResolver.resolve(::initFood, "5", strict = false)
        dessert = tempResolver.resolve(::initFood, "4", strict = false)
    }
}

class Identity(jsonDict: Map<String, Any?>) : Object(jsonDict) {
    val postal_code: String = _resolver.resolve({ it.toString() }, "CP")
    val date_of_birth: LocalDate? = _resolver.resolve({ v -> (v as? String)?.let { Util.dateParse(it) } }, "dateNaiss", strict = false)
    val email: String? = _resolver.resolve({ it?.toString() }, "email", strict = false)
    val last_name: String = _resolver.resolve({ it.toString() }, "nom")
    val country: String = _resolver.resolve({ it.toString() }, "pays")
    val mobile_number: String? = _resolver.resolve({ it?.toString() }, "telPort", strict = false)
    val landline_number: String? = _resolver.resolve({ it?.toString() }, "telFixe", strict = false)
    val other_phone_number: String? = _resolver.resolve({ it?.toString() }, "telAutre", strict = false)
    val city: String = _resolver.resolve({ it.toString() }, "ville")
    val place_of_birth: String? = _resolver.resolve({ it?.toString() }, "villeNaiss", strict = false)
    val address: List<String>
    val formatted_address: String
    val first_names: List<String>

    init {
        val addrs = mutableListOf<String>()
        var i = 1
        while (true) {
            val opt = jsonDict["adresse$i"] as? String ?: break
            addrs += opt
            i += 1
        }
        address = addrs
        formatted_address = (address + listOf(postal_code, city, country)).joinToString(",")
        first_names = listOf(
            jsonDict["prenom"] as? String ?: "",
            jsonDict["prenom2"] as? String ?: "",
            jsonDict["prenom3"] as? String ?: "",
        )
    }
}

class Guardian(jsonDict: Map<String, Any?>) : Object(jsonDict) {
    val authorized_email: Boolean = _resolver.resolve({ it as Boolean }, "autoriseEmail", default = false)
    val authorized_pick_up_kid: Boolean = _resolver.resolve({ it as Boolean }, "autoriseRecupererEnfant", default = false)
    val urgency_contact: Boolean = _resolver.resolve({ it as Boolean }, "contactUrgence", default = false)
    val relatives_link: String = _resolver.resolve({ it.toString() }, "lienParente")
    val responsibility_level: String = _resolver.resolve({ it.toString() }, "niveauResponsabilite")
    val full_name: String = _resolver.resolve({ it.toString() }, "nom")
    val identity: Identity = Identity(jsonDict)
    val is_legal: Boolean = responsibility_level == "LEGAL"
}

class TeachingStaff(jsonDict: Map<String, Any?>) : Object(jsonDict) {
    class TeachingSubject(jsonDict: Map<String, Any?>) : Object(jsonDict) {
        val id: String = _resolver.resolve({ it.toString() }, "N")
        val name: String = _resolver.resolve({ it.toString() }, "L")
        private val _duration: String = _resolver.resolve({ it.toString() }, "volumeHoraire", default = "")
        val parent_subject_name: String? = _resolver.resolve({ it?.toString() }, "servicePere", "V", "L", strict = false)
        val parent_subject_id: String? = _resolver.resolve({ it?.toString() }, "servicePere", "V", "N", strict = false)
        val duration: Duration? = if (_duration.contains("h")) {
            val parts = _duration.split("h")
            Duration.ofHours(parts[0].toLong()).plusMinutes(parts.getOrNull(1)?.toLongOrNull() ?: 0)
        } else null
    }

    val id: String = _resolver.resolve({ it.toString() }, "N")
    val name: String = _resolver.resolve({ it.toString() }, "L")
    val num: Int = _resolver.resolve({ (it as Number).toInt() }, "P")
    private val _type: Int = _resolver.resolve({ (it as Number).toInt() }, "G")
    val type: String = if (_type == 3) "teacher" else "staff"
    val subjects: List<TeachingSubject> = _resolver.resolve({ list -> (list as List<*>).map { TeachingSubject(it as Map<String, Any?>) } }, "matieres", "V", default = emptyList<TeachingSubject>())
}

class Message(private val client: ClientBase, jsonDict: Map<String, Any?>) : Object(jsonDict) {
    val id: String = _resolver.resolve({ it.toString() }, "N")
    val created: LocalDateTime = _resolver.resolve({ Util.datetimeParse(it as String) }, "date", "V")
    val read: Boolean = _resolver.resolve({ it as Boolean }, "lu", default = false)
    val author: String = _resolver.resolve({ it.toString() }, "emetteur", "V", "L")
    val body: String = _resolver.resolve({ Util.htmlParse(it as String) }, "contenu")
    val replying_to: Message? = _resolver.resolve({ v ->
        val m = v as? Map<*, *> ?: return@resolve null
        Message(client, m as Map<String, Any?>)
    }, "messageOriginal", strict = false)
    val documents: List<Attachment> = _resolver.resolve({ list -> (list as? List<*>)?.map { Attachment(client, it as Map<String, Any?>) } ?: emptyList() }, "listePiecesJointes", "V", default = emptyList<Attachment>())
}
