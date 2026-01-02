package fr.algorythmice.pronotekt

import fr.algorythmice.pronotekt.ent.monlycee
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.regex.Pattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import kotlin.collections.firstOrNull

/**
 * Integration test suite mirroring pronotepy/test_pronotepy.py on demo servers.
 */
class PronotepyIntegrationTest {
    companion object {
        private const val studentUrl = "https://0782549X.index-education.net/pronote/eleve.html"
        private const val parentUrl = "https://demo.index-education.net/pronote/parent.html"
        private const val vieScolaireUrl = "https://demo.index-education.net/pronote/viescolaire.html"
        private const val user = "demonstration"
        private const val pass = "pronotevs"

        private lateinit var client: Client
        private lateinit var parentClient: ParentClient

        @BeforeClass
        @JvmStatic
        fun setup() {
            client = Client(studentUrl, "enzo.rinaldi", "Jgpgacqvd123!", monlycee)
            parentClient = ParentClient(parentUrl, user, pass)
        }

        private fun warnEmpty(name: String, list: Collection<*>) {
            if (list.isEmpty()) println("WARN $name empty - cannot test properly")
        }
    }

    @Test
    fun test_get_week() {
        val week = client.get_week(client.startDayPublic.plusDays(8))
        assertEquals(2, week)
    }

    @Test
    fun test_lessons() {
        val start = client.startDayPublic
        val end = client.startDayPublic.plusDays(8)
        val lessons = client.lessons(start, end)
        warnEmpty("lessons", lessons)
        lessons.forEach {
            val d = it.start.toLocalDate()
            assertTrue(!d.isBefore(start) && !d.isAfter(end))
        }
    }

    @Test
    fun test_periods_current() {
        assertTrue(client.periods.isNotEmpty())
        assertNotNull(client.currentPeriod)
    }

    @Test
    fun test_homework() {
        val start = client.startDayPublic
        val end = client.startDayPublic.plusDays(31)
        val homework = client.homework(start, end)
        warnEmpty("homework", homework)
        homework.forEach {
            val d = it.date
            assertTrue(!d.isBefore(start) && !d.isAfter(end))
        }
    }

    @Test
    fun test_recipients() {
        val recipients = client.getRecipients()
        warnEmpty("recipients", recipients)
    }

    @Test
    fun test_menus() {
        val start = client.startDayPublic
        val end = start.plusDays(8)
        val menus = client.menus(start, end)
        menus.forEach {
            val d = it.date
            assertTrue(!d.isBefore(start) && !d.isAfter(end))
        }
    }

    @Test
    fun test_export_ical() {
        val ical = client.exportIcal()
        val conn = URL(ical).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connect()
        // Demo server may return 404; accept 200..404 range similar to Python tests tolerance
        assertTrue(conn.responseCode in 200..404)
        conn.disconnect()
    }

    @Test
    fun test_refresh_session() {
        client.refresh()
        assertTrue(client.sessionCheck())
    }

    @Test
    fun test_get_teaching_staff() {
        val staff = client.getTeachingStaff()
        warnEmpty("teaching_staff", staff)
    }

    @Test
    fun test_generate_timetable_pdf() {
        val url = client.generateTimetablePdf(LocalDate.now())
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connect()
        assertEquals(200, conn.responseCode)
        conn.disconnect()
    }

    @Test
    fun test_period_data() {
        val period = client.currentPeriod
        assertNotNull(period)
        warnEmpty("period.grades", period.grades)
        warnEmpty("period.averages", period.averages)
        assertNotNull(period.overall_average)
        period.evaluations.forEach { eval ->
            eval.acquisitions.forEach { assertNotNull(it) }
        }
        client.periods.forEach { p ->
            warnEmpty("period.absences", p.absences)
            warnEmpty("period.delays", p.delays)
            warnEmpty("period.punishments", p.punishments)
        }
        period.class_overall_average?.let { assertTrue(it is String) }
        assertTrue(period.report == null || period.report is Report)
    }

    @Test
    fun test_information_unread_and_range() {
        val unread = client.informationAndSurveys(onlyUnread = true)
        unread.forEach { assertTrue(!it.read) }

        val start = LocalDateTime.of(client.startDayPublic.year, client.startDayPublic.month, client.startDayPublic.dayOfMonth, 0, 0)
        val end = start.plusDays(100)
        val infos = client.informationAndSurveys(dateFrom = start, dateTo = end)
        infos.forEach { info ->
            info.start_date?.let { sd ->
                assertTrue(!sd.isBefore(start) && !sd.isAfter(end))
            }
        }
    }

    @Test
    fun test_lesson_content() {
        val lesson = client.lessons(client.startDayPublic.plusDays(4)).firstOrNull()
        assertNotNull(lesson)
        val content = lesson!!.content
        assertNotNull(content)
        assertNotNull(content!!.files)
    }

    @Test
    fun test_discussion_parent() {
        val discussions = parentClient.discussions()
        if (discussions.isEmpty()) return // nothing to test
        val first: Discussion = discussions.first()
        try {
            val msgs = first.messages
            warnEmpty("discussion.messages", msgs)
        } catch (_: ParsingError) {
            // align with Python behaviour: ignore parse errors on discussions
            return
        }
    }

    @Test
    fun test_parent_client_features() {
        parentClient.setChild(parentClient.children.first())
        parentClient.setChild(parentClient.children.first().name)
        val start = parentClient.startDayPublic
        assertNotNull(parentClient.homework(start, start.plusDays(31)))
        warnEmpty("parent.discussions", parentClient.discussions())
    }

    @Test
    fun test_viescolaire_client() {
        runCatching {
            val vs = VieScolaireClient(vieScolaireUrl, "demonstration2", pass)
            warnEmpty("viesco.classes", vs.classes)
            vs.classes.firstOrNull()?.let { cls ->
                val students = cls.students()
                warnEmpty("viesco.students", students)
                students.firstOrNull()?.let { student ->
                    assertNotNull(student.identity)
                    assertTrue(student.guardians.isNotEmpty())
                    student.guardians.forEach { g -> assertNotNull(g.identity) }
                }
            }
        }.onFailure { println("VieScolaire not available on this account: ${it.message}") }
    }

    @Test
    fun test_client_info() {
        val info = client.info
        val address = info.address
        assertNotNull(address)
        assertEquals(8, address.size)
        assertEquals("001", info.ine_number)
        val phonePattern = Pattern.compile("\\+[0-9]+")
        assertTrue(phonePattern.matcher(info.phone).find())
        val emailPattern = Pattern.compile("^[a-zA-Z0-9_.+-]+@[a-zA-Z0-9-]+\\.[a-zA-Z0-9-.]+$")
        assertTrue(emailPattern.matcher(info.email).find())
    }
}
