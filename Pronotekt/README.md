# Pronotekt

Pronotekt est l’adaptation Kotlin/Android de la bibliothèque Pronotepy pour dialoguer avec Pronote (sessions chiffrées, emploi du temps, notes, cahier de texte, messagerie, etc.). Le module expose une API synchrone proche de la version Python tout en restant idiomatique côté JVM.

## Fonctionnalités
- Connexion Pronote classique ou via ENT (cookies fournis par une fonction `EntFunction`), prise en charge du login par QR/token (`qrcodeLogin`, `tokenLogin`).
- Gestion de session : rafraîchissement automatique sur erreur, `KeepAlive` pour conserver la session active, export des identifiants (`exportCredentials`).
- Emploi du temps, export iCal sécurisé et génération PDF de l’EDT (`exportIcal`, `generateTimetablePdf`).
- Cahier de texte / devoirs (`homework`), menus cantine (`menus`).
- Notes, moyennes et bulletins par période (`Period.grades`, `Period.averages`, `Period.report`, `Period.overall_average`, `Period.class_overall_average`).
- Messagerie : destinataires (`getRecipients`), nouvelles discussions (`newDiscussion`), discussions et lecture (`discussions`).
- Infos/sondages (`informationAndSurveys`), équipe pédagogique (`getTeachingStaff`), ressources Vie Scolaire (`VieScolaireClient`).

## Installation (Gradle)
```kotlin
repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    implementation("com.github.polip3000:Pronotekt:1.0.0")
}
```
Min SDK 24, cible 36, JVM 11. Le module requiert la désugaring des libs JDK (déjà configurée dans `build.gradle`).

## Démarrage rapide
```kotlin
val client = Client(
    pronoteUrl = "https://demo.index-education.net/pronote/eleve.html",
    username = "demonstration",
    password = "pronotevs",
)

// Optionnel : garder la session ouverte (thread dédié)
val keepAlive = client.keepAlive().also { it.start() }

val todayLessons = client.lessons(LocalDate.now())
val upcomingHomework = client.homework(LocalDate.now())

val period = client.currentPeriod
val grades = period.grades
val averages = period.averages

// Arrêter le keep-alive quand il n’est plus nécessaire
keepAlive.interrupt()
```

### Connexion par QR / token
```kotlin
val client = BaseClientImpl.qrcodeLogin(
    qrCode = qrPayloadFromPronote, // map contenant `url`, `login`, `jeton`
    pin = "0000",
    uuid = "device-uuid",
) { url, user, pass, ent, mode, uuid, accountPin, clientId, deviceName ->
    Client(url, user, pass, ent, mode, uuid, accountPin, clientId, deviceName)
}
// Si le compte exige, bascule automatique vers token via `tokenLogin`.
```

### Compte parent / Vie Scolaire
```kotlin
val parent = ParentClient("https://exemple.fr/pronote/parent.html", "parent", "secret")
parent.setChild("Prénom Nom")
val childHomework = parent.homework(LocalDate.now())
```

### Messagerie
```kotlin
val recipients = client.getRecipients()
client.newDiscussion(
    subject = "Question",
    message = "Bonjour, ...",
    recipients = recipients.take(1),
)
val discussions = client.discussions(onlyUnread = true)
```

## Notes d’usage
- Appels réseau synchrones : exécuter dans un thread/dispatcher dédié (pas sur le main Android).
- `KeepAlive` envoie périodiquement une navigation pour éviter l’expiration après ~110s d’inactivité.
- Les exceptions spécifiques (ex. `PronoteAPIError`, `ExpiredObject`, `MFAError`) permettent de distinguer les erreurs serveur, d’authentification ou 2FA.
- Pour ENT : fournir une `EntFunction` qui retourne un snapshot de cookies après authentification ENT avant d’appeler Pronote.

