@file:Suppress("unused")
package fr.algorythmice.pronotekt.ent

import fr.algorythmice.pronotekt.ENTLoginError
import fr.algorythmice.pronotekt.EntFunction
import java.net.URL
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.Headers.Companion.toHeaders
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

private val HEADERS = mapOf(
    "User-Agent" to "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:73.0) Gecko/20100101 Firefox/73.0",
)

/**
 * Kotlin translation of pronotepy/ent/ent.py mapping ENT helpers to GenericENT implementations.
 */
object Ent {
    // CAS
    val cas_arsene76 = { u: String, p: String -> GenericENT.cas(u, p, "https://cas.arsene76.fr/login?selection=ATS_parent_eleve") }
    val cas_ent27 = { u: String, p: String -> GenericENT.cas(u, p, "https://cas.ent27.fr/login?selection=ATS_parent_eleve") }
    val cas_kosmos = { u: String, p: String -> GenericENT.cas(u, p, "https://cas.kosmoseducation.com/login") }
    val ent_creuse = { u: String, p: String -> GenericENT.cas(u, p, "https://cas.entcreuse.fr/login?selection=ATS_parent_eleve") }
    val occitanie_montpellier = { u: String, p: String -> GenericENT.cas(u, p, "https://cas.mon-ent-occitanie.fr/login?selection=CSES-ENT_parent_eleve") }
    val val_doise = { u: String, p: String -> GenericENT.cas(u, p, "https://cas.moncollege.valdoise.fr/login?selection=eleveparent") }

    // CAS with EduConnect
    val val_de_marne = { u: String, p: String -> GenericENT.casEdu(u, p, "https://cas.moncollege.valdemarne.fr/login?selection=EDU_parent_eleve") }
    val cas_cybercolleges42_edu = { u: String, p: String -> GenericENT.casEdu(u, p, "https://cas.cybercolleges42.fr/login?selection=EDU_parent_eleve&service=") }
    val ecollege_haute_garonne_edu = { u: String, p: String -> GenericENT.casEdu(u, p, "https://cas.ecollege.haute-garonne.fr/login?selection=EDU_parent_eleve&service=") }
    val ac_orleans_tours = { u: String, p: String -> GenericENT.casEdu(u, p, "https://ent.netocentre.fr/cas/login?token=ce8ae867a0accc0b7577fcc340bb99f4&idpId=parentEleveEN-IdP", redirectForm = false) }
    val ac_poitiers = { u: String, p: String -> GenericENT.casEdu(u, p, "https://sp-ts.ac-poitiers.fr/dispatcher/index2.php", redirectForm = false) }
    val ac_reunion = { u: String, p: String -> GenericENT.casEdu(u, p, "https://sso.ac-reunion.fr/saml/discovery/?idp_ident=https://educonnect.education.gouv.fr/idp") }
    val cas_agora06 = { u: String, p: String -> GenericENT.casEdu(u, p, "https://cas.agora06.fr/login?selection=EDU&service=") }
    val cas_seinesaintdenis_edu = { u: String, p: String -> GenericENT.casEdu(u, p, "https://cas.webcollege.seinesaintdenis.fr/login?selection=EDU_parent_eleve&service=") }
    val cas_arsene76_edu = { u: String, p: String -> GenericENT.casEdu(u, p, "https://cas.arsene76.fr/login?selection=EDU_parent_eleve&service=") }
    val eclat_bfc = { u: String, p: String -> GenericENT.casEdu(u, p, "https://cas.eclat-bfc.fr/login?selection=EDU&service=") }
    val ent_auvergnerhonealpe = { u: String, p: String -> GenericENT.casEdu(u, p, "https://cas.ent.auvergnerhonealpes.fr/login?selection=EDU&service=") }
    val laclasse_educonnect = { u: String, p: String -> GenericENT.casEdu(u, p, "https://www.laclasse.com/sso/educonnect", redirectForm = false) }
    val monbureaunumerique = { u: String, p: String -> GenericENT.casEdu(u, p, "https://cas.monbureaunumerique.fr/login?selection=EDU&service=") }
    val ac_reims = monbureaunumerique
    val occitanie_montpellier_educonnect = { u: String, p: String -> GenericENT.casEdu(u, p, "https://cas.mon-ent-occitanie.fr/login?selection=MONT-EDU_parent_eleve&service=") }
    val occitanie_toulouse_edu = { u: String, p: String -> GenericENT.casEdu(u, p, "https://cas.mon-ent-occitanie.fr/login?selection=TOULO-EDU_parent_eleve&service=") }
    val ent_creuse_educonnect = { u: String, p: String -> GenericENT.casEdu(u, p, "https://cas.entcreuse.fr/login?selection=EDU") }

    // Open ENT NG
    val ent77 = { u: String, p: String -> GenericENT.openEntNg(u, p, "https://ent77.seine-et-marne.fr/auth/login") }
    val ent_ecollege78 = { u: String, p: String -> GenericENT.openEntNg(u, p, "https://ent.ecollege78.fr/auth/login") }
    val ent_essonne = { u: String, p: String -> GenericENT.openEntNg(u, p, "https://www.moncollege-ent.essonne.fr/auth/login") }
    val ent_mayotte = { u: String, p: String -> GenericENT.openEntNg(u, p, "https://mayotte.opendigitaleducation.com/auth/login") }
    val neoconnect_guadeloupe = { u: String, p: String -> GenericENT.openEntNg(u, p, "https://neoconnect.opendigitaleducation.com/auth/login") }
    val paris_classe_numerique = { u: String, p: String -> GenericENT.openEntNg(u, p, "https://ent.parisclassenumerique.fr/auth/login") }
    val lyceeconnecte_aquitaine = { u: String, p: String -> GenericENT.openEntNg(u, p, "https://mon.lyceeconnecte.fr/auth/login") }

    // Open ENT NG with EduConnect
    val ent_94 = { u: String, p: String -> GenericENT.openEntNgEdu(u, p, "https://ent94.opendigitaleducation.com", "urn:fi:ent:prod-cd94-edu:1.0") }
    val ent_hdf = { u: String, p: String -> GenericENT.openEntNgEdu(u, p, "https://enthdf.fr") }
    val ent_somme = ent_hdf
    val ent_var = { u: String, p: String -> GenericENT.openEntNgEdu(u, p, "https://moncollege-ent.var.fr", "urn:fi:ent:prod-cd83-edu:1.0") }
    val l_normandie = { u: String, p: String -> GenericENT.openEntNgEdu(u, p, "https://ent.l-educdenormandie.fr") }
    val lyceeconnecte_edu = { u: String, p: String -> GenericENT.openEntNgEdu(u, p, "https://mon.lyceeconnecte.fr") }

    // WAYF
    val ent_elyco = { u: String, p: String -> GenericENT.wayf(u, p, "https://cas3.e-lyco.fr", redirectForm = false) }

    // HubEduConnect
    val bordeaux = { u: String, p: String, pronoteUrl: String -> GenericENT.hubEduconnect(u, p, pronoteUrl) }

    // Simple Auth
    val atrium_sud = { u: String, p: String -> GenericENT.simpleAuth(u, p, "https://www.atrium-sud.fr/connexion/login", mapOf("id" to "fm1")) }
    val laclasse_lyon = { u: String, p: String -> GenericENT.simpleAuth(u, p, "https://www.laclasse.com/sso/login") }
    val extranet_colleges_somme = { u: String, p: String -> GenericENT.simpleAuth(u, p, "http://www.colleges.cg80.fr/identification/identification.php") }
}

/**
 * Kotlin translation of pronotepy/ent/complex_ent.py specialized flows.
 */
object ComplexEnt {
    @Suppress("UNUSED_VARIABLE")
    fun ac_rennes(username: String, password: String): Map<String, String> {
        val toutaticeUrl = "https://www.toutatice.fr/portail/auth/MonEspace"
        val toutaticeLogin = "https://www.toutatice.fr/wayf/Ctrl"
        val toutaticeAuth = "https://www.toutatice.fr/idp/Authn/RemoteUser"

        var cookies: Map<String, String> = emptyMap()
        val first = httpGet(toutaticeUrl)
        cookies = mergeCookies(cookies, first.cookies)
        val entityId = Regex("name=\"entityID\"[^>]*value=\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(first.body)?.groupValues?.getOrNull(1)
        val ret = Regex("name=\"return\"[^>]*value=\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(first.body)?.groupValues?.getOrNull(1)
        val samlIdp = Regex("name=\"_saml_idp\"[^>]*value=\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(first.body)?.groupValues?.getOrNull(1)
        if (entityId == null || ret == null || samlIdp == null) throw ENTLoginError("Missing Toutatice hidden inputs")
        val payload = mapOf("entityID" to entityId, "return" to ret, "_saml_idp" to samlIdp)
        val loginResp = httpPost(toutaticeLogin, cookies, payload)
        cookies = mergeCookies(cookies, loginResp.cookies)
        val (eduResp, eduCookies) = GenericENT.educonnect(loginResp.url, username, password, exceptions = true)
        cookies = mergeCookies(cookies, eduCookies)
        val conv = Regex("conversation=([^&]+)").find(loginResp.url)?.groupValues?.getOrNull(1)
        val sessionId = cookies["IDP_JSESSIONID"] ?: ""
        val params1 = mapOf(
            "conversation" to (conv ?: ""),
            "redirectToLoaderRemoteUser" to "0",
            "sessionid" to sessionId,
        )
        val auth1 = httpGet(toutaticeAuth, cookies, params1)
        cookies = mergeCookies(cookies, auth1.cookies)
        val erreurFonctionnelle = Regex("<erreurFonctionnelle>(.*?)</erreurFonctionnelle>").find(auth1.body)?.groupValues?.getOrNull(1)
        val erreurTechnique = Regex("<erreurTechnique>(.*?)</erreurTechnique>").find(auth1.body)?.groupValues?.getOrNull(1)
        if (erreurFonctionnelle != null) throw ENTLoginError("Toutatice ENT (ac_rennes): $erreurFonctionnelle")
        if (erreurTechnique != null) throw ENTLoginError("Toutatice ENT (ac_rennes): $erreurTechnique")
        val conversation = Regex("<conversation>(.*?)</conversation>").find(auth1.body)?.groupValues?.getOrNull(1) ?: conv ?: ""
        val uidInSession = Regex("<uidInSession>(.*?)</uidInSession>").find(auth1.body)?.groupValues?.getOrNull(1) ?: ""
        val params2 = mapOf(
            "conversation" to conversation,
            "uidInSession" to uidInSession,
            "sessionid" to sessionId,
        )
        val auth2 = httpGet(toutaticeAuth, cookies, params2)
        cookies = mergeCookies(cookies, auth2.cookies)
        return cookies
    }

    fun monlycee(username: String, password: String, pronoteUrl: String): Map<String, String> {
        val cookieStore = mutableListOf<Cookie>()
        val client = OkHttpClient.Builder()
            .cookieJar(object : CookieJar {
                override fun loadForRequest(url: HttpUrl): List<Cookie> =
                    cookieStore.filter { it.matches(url) }

                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                    cookies.forEach { newCookie ->
                        cookieStore.removeIf { it.name == newCookie.name && it.domain == newCookie.domain && it.path == newCookie.path }
                        cookieStore.add(newCookie)
                    }
                }
            })
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        val getRequest = Request.Builder()
            .url(pronoteUrl)
            .headers(HEADERS.toHeaders())
            .get()
            .build()
        val getResponse = client.newCall(getRequest).execute()
        val getBody = getResponse.body?.string() ?: ""
        val currentUrl = getResponse.request.url.toString()

        val formActionMatch = Regex("<form[^>]+action=\"([^\"]+)\"").find(getBody)
            ?: throw Exception("Formulaire de login introuvable → pas sur la page Keycloak ?")
        val rawFormAction = formActionMatch.groupValues[1].replace("&amp;", "&")
        val formAction = URL(URL(currentUrl), rawFormAction).toString()

        val hiddenInputs = Regex("name=\"([^\"]+)\" value=\"([^\"]*)\"")
            .findAll(getBody)
            .associate { it.groupValues[1] to it.groupValues[2] }

        val payload = hiddenInputs.toMutableMap().apply {
            this["username"] = username
            this["password"] = password
            this["credentialId"] = ""
        }

        val formBuilder = FormBody.Builder()
        payload.forEach { (k, v) -> formBuilder.add(k, v) }

        val postRequest = Request.Builder()
            .url(formAction)
            .headers(HEADERS.toHeaders())
            .header("Referer", currentUrl)
            .post(formBuilder.build())
            .build()
        val postResponse = client.newCall(postRequest).execute()
        val postBody = postResponse.body?.string() ?: ""
        val postUrl = postResponse.request.url.toString()

        if (postUrl.contains("index-education.net")) {
            val followReq = Request.Builder()
                .url(postUrl)
                .headers(HEADERS.toHeaders())
                .get()
                .build()
            client.newCall(followReq).execute().use { followResp ->
                followResp.body?.string() ?: ""
            }
        }

        if (Regex("<form[^>]+action=\"[^\"]+\"").containsMatchIn(postBody)) {
            throw Exception("Identifiants incorrects")
        }

        return cookieStore.associate { it.name to it.value }
    }
}
val monlycee: EntFunction = { username, password, pronoteUrl -> ComplexEnt.monlycee(username, password, pronoteUrl) }

val cas_arsene76: EntFunction = { u, p, pronoteUrl -> Ent.cas_arsene76(u, p) }
val cas_ent27: EntFunction = { u, p, pronoteUrl -> Ent.cas_ent27(u, p) }
val cas_kosmos: EntFunction = { u, p, pronoteUrl -> Ent.cas_kosmos(u, p) }
val ent_creuse: EntFunction = { u, p, pronoteUrl -> Ent.ent_creuse(u, p) }
val ent_creuse_educonnect: EntFunction = { u, p, pronoteUrl -> Ent.ent_creuse_educonnect(u, p) }
val occitanie_montpellier: EntFunction = { u, p, pronoteUrl -> Ent.occitanie_montpellier(u, p) }
val val_doise: EntFunction = { u, p, pronoteUrl -> Ent.val_doise(u, p) }
val val_de_marne: EntFunction = { u, p, pronoteUrl -> Ent.val_de_marne(u, p) }
val cas_cybercolleges42_edu: EntFunction = { u, p, pronoteUrl -> Ent.cas_cybercolleges42_edu(u, p) }
val ecollege_haute_garonne_edu: EntFunction = { u, p, pronoteUrl -> Ent.ecollege_haute_garonne_edu(u, p) }
val ac_orleans_tours: EntFunction = { u, p, pronoteUrl -> Ent.ac_orleans_tours(u, p) }
val ac_poitiers: EntFunction = { u, p, pronoteUrl -> Ent.ac_poitiers(u, p) }
val ac_reunion: EntFunction = { u, p, pronoteUrl -> Ent.ac_reunion(u, p) }
val cas_agora06: EntFunction = { u, p, pronoteUrl -> Ent.cas_agora06(u, p) }
val cas_seinesaintdenis_edu: EntFunction = { u, p, pronoteUrl -> Ent.cas_seinesaintdenis_edu(u, p) }
val cas_arsene76_edu: EntFunction = { u, p, pronoteUrl -> Ent.cas_arsene76_edu(u, p) }
val eclat_bfc: EntFunction = { u, p, pronoteUrl -> Ent.eclat_bfc(u, p) }
val ent_auvergnerhonealpe: EntFunction = { u, p, pronoteUrl -> Ent.ent_auvergnerhonealpe(u, p) }
val laclasse_educonnect: EntFunction = { u, p, pronoteUrl -> Ent.laclasse_educonnect(u, p) }
val monbureaunumerique: EntFunction = { u, p, pronoteUrl -> Ent.monbureaunumerique(u, p) }
val ac_reims: EntFunction = monbureaunumerique
val occitanie_montpellier_educonnect: EntFunction = { u, p, pronoteUrl -> Ent.occitanie_montpellier_educonnect(u, p) }
val occitanie_toulouse_edu: EntFunction = { u, p, pronoteUrl -> Ent.occitanie_toulouse_edu(u, p) }
val ent77: EntFunction = { u, p, pronoteUrl -> Ent.ent77(u, p) }
val ent_ecollege78: EntFunction = { u, p, pronoteUrl -> Ent.ent_ecollege78(u, p) }
val ent_essonne: EntFunction = { u, p, pronoteUrl -> Ent.ent_essonne(u, p) }
val ent_mayotte: EntFunction = { u, p, pronoteUrl -> Ent.ent_mayotte(u, p) }
val neoconnect_guadeloupe: EntFunction = { u, p, pronoteUrl -> Ent.neoconnect_guadeloupe(u, p) }
val paris_classe_numerique: EntFunction = { u, p, pronoteUrl -> Ent.paris_classe_numerique(u, p) }
val lyceeconnecte_aquitaine: EntFunction = { u, p, pronoteUrl -> Ent.lyceeconnecte_aquitaine(u, p) }
val ent_94: EntFunction = { u, p, pronoteUrl -> Ent.ent_94(u, p) }
val ent_hdf: EntFunction = { u, p, pronoteUrl -> Ent.ent_hdf(u, p) }
val ent_somme: EntFunction = ent_hdf
val ent_var: EntFunction = { u, p, pronoteUrl -> Ent.ent_var(u, p) }
val l_normandie: EntFunction = { u, p, pronoteUrl -> Ent.l_normandie(u, p) }
val lyceeconnecte_edu: EntFunction = { u, p, pronoteUrl -> Ent.lyceeconnecte_edu(u, p) }
val ent_elyco: EntFunction = { u, p, pronoteUrl -> Ent.ent_elyco(u, p) }
val bordeaux: EntFunction = { u, p, pronoteUrl -> Ent.bordeaux(u, p, pronoteUrl) }
val atrium_sud: EntFunction = { u, p, pronoteUrl -> Ent.atrium_sud(u, p) }
val laclasse_lyon: EntFunction = { u, p, pronoteUrl -> Ent.laclasse_lyon(u, p) }
val extranet_colleges_somme: EntFunction = { u, p, pronoteUrl -> Ent.extranet_colleges_somme(u, p) }
val ac_rennes: EntFunction = { u, p, pronoteUrl -> ComplexEnt.ac_rennes(u, p) }
