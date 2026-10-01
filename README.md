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
- 🛠️ **Hardware-Selbsttest** - Geführte und automatische Tests der Gerätehardware
- 📶 **Netzwerk-Diagnose** - Verbindung, DNS und Erreichbarkeit der Volla-Server prüfen
- 📱 **Social Media** - Direkte Verknüpfung zur Volla Community (Telegram, Mastodon, etc.)
- 💾 **Speicherbelegung** - Analysetool für die Speicherbelegung

## ✨ Features

- ✅ **100% ohne Google-Dienste** - Perfekt für Volla-Geräte (Volla OS & Ubuntu Touch via Waydroid)
- 🤖 **HelpBot Support** - Schnelle Antworten durch intelligente Suche in Volla-Ressourcen per Chat
- 🌑 **True Black Dark Mode** - Optimiert für OLED-Displays (Schwarz/Rot Design)
- 🔔 **Blog Notifications** - Hintergrundprüfung auf neue Blogartikel via WorkManager
- 📄 **PDF Export** - Erstellung von Support-Berichten inkl. Notizen und Foto-Anhängen
- 📋 **Spec Copy** - Schnelles Kopieren von Geräte-Informationen in die Zwischenablage
- 🛠️ **Hardware-Selbsttest** - 12 Tests (Touchscreen, Display, Lautsprecher, Mikrofon, Sensoren, Akku, GPS u. a.) mit Übernahme der Ergebnisse in den Geräte-Report
- 📶 **Netzwerk-Diagnose** - WLAN-/Mobilfunk-Details sowie DNS-, TCP- und HTTPS-Tests mit Hinweisen zur Fehlerursache
- 🔍 **Integrierte Suche** - Durchsuche alle Inhalte effizient
- 🔄 **Pull-to-Refresh** - Aktualisiere Inhalte durch einfaches Herunterziehen
- 🌍 **Mehrsprachig** - Wiki und Forum in bis zu 8 Sprachen verfügbar, App-Oberfläche in Deutsch und Englisch

## 🖼️ Screenshots

<img width="1600" height="2560" alt="Screenshot_20261001-221900_Volla Hub" src="https://github.com/user-attachments/assets/a9863a3d-484d-435e-9ef6-f9b3667b8ce8" />



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
│   ├── HardwareTestActivity.kt  # Hardware-Selbsttest (Oberfläche und Ablauf)
│   ├── HardwareTester.kt        # Testlogik: Sensoren, Akku, Audio, Vibration, GPS
│   ├── HardwareTestModel.kt     # Testliste, Status und lokale Ergebnisspeicherung
│   ├── TouchGridView.kt         # Vollbild-Raster für den Touchscreen-Test
│   ├── NetworkDiagnosticActivity.kt # Netzwerk-Diagnose (Oberfläche)
│   ├── NetworkDiagnostics.kt    # Netzwerk-Diagnose (Verbindung, DNS, TCP, HTTPS)
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
- Übernimmt vorhandene Ergebnisse des Hardware-Selbsttests als eigenen Abschnitt `HARDWARE-SELBSTTEST` in Text- und PDF-Bericht.
- Exportiert einen formatierten PDF-Bericht nach `/Documents/Volla/`.
- Optimierte Share-Funktion für Telegram/E-Mail (Auto-Caption Support).

### Hardware-Selbsttest
Der Selbsttest prüft die Gerätehardware und eignet sich zur Eingrenzung von Fehlern vor einer Support-Anfrage.

**Automatische Tests**
- **Funkmodule und Ausstattung:** WLAN, Bluetooth, Bluetooth LE, NFC, GPS, Mobilfunk, Fingerabdrucksensor, USB-Host, Kameras und Blitz (vorhanden / ein- bzw. ausgeschaltet).
- **Sensoren:** Beschleunigungssensor, Gyroskop, Magnetometer, Annäherungs-, Licht- und Drucksensor. Geprüft wird, ob vorhandene Sensoren tatsächlich Messwerte liefern.
- **Akku:** Ladestand, Zustand, Temperatur, Spannung, Ladezustand, Anschlussart und (ab Android 14) Ladezyklen.

**Tests mit Nutzerinteraktion**
- **Touchscreen:** Vollbild-Raster; erkennt nicht reagierende Bereiche und zählt Multitouch-Punkte.
- **Display-Farben:** Rot, Grün, Blau, Weiß und Schwarz zur Erkennung defekter Pixel und Verfärbungen.
- **Lautsprecher:** Testton über den Medienkanal.
- **Mikrofon:** Pegelmessung über drei Sekunden.
- **Vibration:** Zwei Vibrationsimpulse.
- **Lautstärketasten:** Erkennung beider Tasten, mit Möglichkeit, eine Taste als defekt zu melden.
- **Kamera:** Auflistung aller Kameras (Ausrichtung, Auflösung, Blitz) und Testfoto über die System-Kamera-App.
- **Ladeanschluss:** Erkennung eines Ladekabels oder einer Ladematte innerhalb von 30 Sekunden.
- **GPS-Fix:** Zeit bis zum ersten Fix, Genauigkeit und Satellitenanzahl (Zeitlimit 60 Sekunden, am besten im Freien).

**Ergebnisse und Datenschutz**
- Die Ergebnisse werden ausschließlich lokal gespeichert und können kopiert, geteilt oder zurückgesetzt werden.
- Mikrofonaufnahmen werden nur im Arbeitsspeicher auf den Pegel ausgewertet und nicht gespeichert.
- Vom GPS-Test werden keine Koordinaten übernommen, nur Zeit, Genauigkeit und Satellitenanzahl.
- Das Testfoto der Kamera wird von der System-Kamera-App aufgenommen. Volla Hub deklariert die Berechtigung `CAMERA` nicht und speichert das Foto nicht.
- Berechtigungen für Mikrofon (`RECORD_AUDIO`) und Standort (`ACCESS_FINE_LOCATION`) werden erst beim jeweiligen Test zur Laufzeit angefragt. `VIBRATE` ist eine normale Berechtigung ohne Abfrage.

### Netzwerk-Diagnose
Die Diagnose zeigt Verbindungsstatus und Konfiguration und prüft die Erreichbarkeit der Volla-Server.

- **Verbindung:** Flugmodus, Verbindungsart (WLAN, Mobilfunk, Ethernet, Bluetooth, VPN), von Android bestätigter Internetzugang, Captive Portal, getaktete Verbindung, Datensparmodus und Bandbreite (Systemschätzung).
- **Netzwerkkonfiguration:** Schnittstelle, IP-Protokolle (IPv4/IPv6), DNS-Server, Privater DNS, MTU und HTTP-Proxy.
- **WLAN:** Signalstärke, Frequenzband, Verbindungsgeschwindigkeit und WLAN-Standard.
- **Mobilfunk:** SIM-Status, Betreiber und Roaming. Mit der optionalen Berechtigung `READ_PHONE_STATE` zusätzlich Netztyp (2G/3G/4G/5G), Signalstärke und Status der mobilen Daten.
- **Erreichbarkeit:** Für `volla.online`, `wiki.volla.online`, `forum.volla.online` und `f-droid.org` werden DNS-Auflösung, TCP-Verbindungsaufbau auf Port 443 (drei Messungen mit min/Ø/max und Verlust) sowie eine HTTPS-Abfrage (Statuscode, Protokoll, TLS-Version) durchgeführt.
- **Hinweise:** Regelbasierte Textempfehlungen, z. B. bei Captive Portal, ausgefallenem DNS, TLS-Fehlern, ausgeschalteten mobilen Daten oder Verbindungsabbrüchen.
- Das Ergebnis lässt sich als Klartext kopieren oder teilen (z. B. für Forum oder Telegram).

**Hinweise zu Datenschutz und Grenzen**
- Die Tests kontaktieren ausschließlich die oben genannten Hosts. Es werden keine Google-Dienste und keine zusätzlichen Bibliotheken verwendet.
- SSID, BSSID und eigene IP-Adressen werden weder angezeigt noch geteilt; von den IP-Adressen wird nur die Protokollfamilie (IPv4/IPv6) ausgewertet.
- Ein ICMP-Ping ist ohne Root nicht zuverlässig möglich. Die Latenz wird daher als Dauer des TCP-Verbindungsaufbaus gemessen.
- Der VoLTE-/VoWiFi-Status ist für normale Apps nicht auslesbar und daher nicht Teil der Diagnose.
- `READ_PHONE_STATE` wird nur auf Nutzeraktion zur Laufzeit angefragt; ohne die Berechtigung bleiben die übrigen Prüfungen nutzbar. Rufnummer und Gerätekennungen werden nicht gelesen.

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
