# Volla Hub 📱

Eine native Android-App für die Volla-Community, die alle wichtigen Volla-Ressourcen an einem Ort vereint.

![Android](https://img.shields.io/badge/Android-24%2B-green.svg)
![Kotlin](https://img.shields.io/badge/Kotlin-100%25-purple.svg)
![License](https://img.shields.io/badge/License-MIT-blue.svg)

## 📋 Über die App

Volla Hub ist eine umfassende Android-App, die Zugriff auf alle wichtigen Volla-Plattformen bietet:

- 🏠 **Hub Startseite** - Schneller Zugriff auf alle Bereiche und Highlights
- 🤖 **Volla HelpBot** - Integrierter Support-Assistent für Wiki & Forum Suche
- 🌐 **Volla Online** - Alle Seiten von volla.online hierarchisch organisiert
- 📝 **Volla Blog** - Die neuesten Blogbeiträge mit Benachrichtigungsfunktion
- 📚 **Volla Wiki** - Mehrsprachiges Wiki (DE, EN, ES, IT, CS, DA, NO, SV)
- 💬 **Volla Forum** - Direktzugriff auf Unterforen in verschiedenen Sprachen
- 🔍 **Geräte-Report** - Hardware/Software Spezifikationen auslesen und als PDF exportieren
- 📱 **Social Media** - Direkte Verknüpfung zur Volla Community (Telegram, Mastodon, etc.)

## ✨ Features

- ✅ **100% ohne Google-Dienste** - Perfekt für Volla-Geräte (Volla OS & Ubuntu Touch via Waydroid)
- 🤖 **HelpBot Support** - Schnelle Antworten durch intelligente Suche in Volla-Ressourcen per Chat
- 🌑 **True Black Dark Mode** - Optimiert für OLED-Displays (Schwarz/Rot Design)
- 🔔 **Blog Notifications** - Hintergrundprüfung auf neue Blogartikel via WorkManager
- 📄 **PDF Export** - Erstellung von Support-Berichten inkl. Notizen und Foto-Anhängen
- 📋 **Spec Copy** - Schnelles Kopieren von Geräte-Informationen in die Zwischenablage
- 🔍 **Integrierte Suche** - Durchsuche alle Inhalte effizient
- 🔄 **Pull-to-Refresh** - Aktualisiere Inhalte durch einfaches Herunterziehen
- 🌍 **Mehrsprachig** - Wiki und Forum in bis zu 8 Sprachen verfügbar

## 🖼️ Screenshots

<img width="1080" height="2400" alt="Screenshot_20260612-231208_Volla Hub" src="https://github.com/user-attachments/assets/94aeefae-3cf8-42be-85e4-4392cdc0a12f" />


## 🛠️ Technologie-Stack

- **Sprache:** Kotlin
- **Min SDK:** 24 (Android 7.0)
- **Target SDK:** 36 (Android 16 DP)
- **Build-System:** Gradle (KTS)
- **UI:** Material 3 mit ViewBinding
- **Architektur:** MVVM mit Kotlin Coroutines & WorkManager
- **HTML-Parsing:** Jsoup 1.22.1

## 📦 Installation

### Aus den Releases

1. Lade die neueste APK aus den [Releases](https://github.com/tux4us/VollaHubAndroidApp/releases) herunter
2. Aktiviere "Installation aus unbekannten Quellen" in den Android-Einstellungen
3. Installiere die APK

### Selbst kompilieren
```bash
# Repository klonen
git clone https://github.com/tux4us/VollaHubAndroidApp.git
cd VollaHubAndroidApp

# In Android Studio öffnen und Build ausführen
```

## 🏗️ Projekt-Struktur
```
app/src/main/
├── java/com/volla/hub/
│   ├── StartActivity.kt         # Hub-Einstieg mit Blog-Highlights
│   ├── MainActivity.kt          # Listenansichten für Online/Blog/Wiki/Forum
│   ├── ChatBotActivity.kt       # Support-Assistent Chat Interface
│   ├── DeviceReportActivity.kt  # System-Specs & PDF Export
│   ├── ContentActivity.kt       # Optimierter Web-Viewer
│   ├── VollaParser.kt           # Jsoup Parser Logik für alle Quellen
│   ├── BlogNotificationWorker.kt # Hintergrund-Check für News
│   ├── ChatAdapter.kt           # RecyclerView Adapter für HelpBot
│   └── ContentAdapter.kt        # RecyclerView Adapter für Listen
├── res/
│   ├── layout/                  # Material 3 XML-Layouts
│   ├── menu/                    # Toolbar & BottomNav Definitionen
│   ├── values/                  # Strings & Light Theme
│   ├── values-night/            # True Black Theme (#000000)
│   └── xml/                     # Backup & Network Security Config
└── AndroidManifest.xml
```

## 🎨 Features im Detail

### Hub & Blog
- Automatische Anzeige des neuesten Blog-Artikels direkt beim Start.
- Hintergrundprüfung auf neue Posts (alle 6 Stunden) mit System-Benachrichtigung.

### Volla HelpBot
- Durchsucht das Wiki, Forum und Volla Online gleichzeitig.
- Chat-basiertes Interface für intuitive Fragenstellung.
- Verlinkt direkt auf die gefundenen Artikel für tiefergehende Informationen.

### Geräte-Report
- Liest Hersteller, Modell, Hardware, Android-Version und Build-Fingerprint aus.
- Erlaubt das Hinzufügen von Notizen und Galerie-Fotos für Support-Anfragen.
- Exportiert einen formatierten PDF-Bericht nach `/Documents/Volla/`.
- Optimierte Share-Funktion für Telegram/E-Mail (Auto-Caption Support).

### Volla Wiki & Forum
- Vollständiger Zugriff auf das Wiki in 8 Sprachen.
- Direkte Verknüpfung zu den länderspezifischen Foren.

### Social Media & Community
- Schnellzugriff auf Telegram, X (Twitter), Facebook, Instagram und Mastodon.
- Integration der offiziellen YouTube und GitHub Kanäle.

## 🤝 Beitragen

Beiträge sind willkommen! Bitte beachte:

1. Forke das Repository
2. Erstelle einen Feature-Branch (`git checkout -b feature/AmazingFeature`)
3. Committe deine Änderungen (`git commit -m 'Add some AmazingFeature'`)
4. Pushe zum Branch (`git push origin feature/AmazingFeature`)
5. Öffne einen Pull Request

## 📝 Lizenz

Dieses Projekt steht unter der MIT-Lizenz - siehe [LICENSE](LICENSE) Datei für Details.

## 🙏 Danksagungen

- [Volla](https://volla.online) für die großartigen Produkte und die offene Community.
- [tux4us](https://github.com/tux4us) für die Entwicklung.

## 📧 Kontakt

Bei Fragen oder Problemen:
- Öffne ein [Issue](https://github.com/tux4us/VollaHubAndroidApp/issues)
- Kontaktiere mich über [tux4us@online.de]
