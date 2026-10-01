package com.volla.hub

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.CertificateException
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/** Bewertung einer einzelnen Diagnosezeile. */
enum class DiagStatus { OK, WARN, ERROR, INFO }

data class DiagRow(
    val label: String,
    val value: String,
    val status: DiagStatus = DiagStatus.INFO
)

data class DiagSection(
    val title: String,
    val rows: List<DiagRow>
)

/**
 * Netzwerk-Diagnose für Support-Fälle (Empfang, DNS, TLS, Erreichbarkeit).
 *
 * Datenschutz / FOSS:
 *  - Es werden ausschließlich die Hosts in [TEST_HOSTS] kontaktiert.
 *  - Keine Google-Dienste, keine Drittanbieter-Bibliotheken außer dem bereits
 *    eingebundenen OkHttp.
 *  - SSID, BSSID, eigene IP-Adressen, IMEI und Rufnummer werden weder gelesen
 *    noch ausgegeben. Von den IP-Adressen wird nur die Protokollfamilie
 *    (IPv4/IPv6) ausgewertet.
 *
 * Grenzen:
 *  - ICMP-Ping ist ohne Root nicht zuverlässig möglich; die Latenz wird
 *    deshalb als Dauer des TCP-Verbindungsaufbaus auf Port 443 gemessen.
 *  - VoLTE/VoWiFi-Status ist für normale Apps nicht auslesbar (privilegierte
 *    Berechtigung), daher nicht Teil dieser Diagnose.
 *
 * Aufrufreihenfolge: [collectStatic] (synchron, schnell) → [runTests]
 * (suspend, parallel je Host). Die Texte stammen aus dem übergebenen Context,
 * damit die per-App-Sprache gilt. Nach Gebrauch [close] aufrufen.
 */
class NetworkDiagnostics(private val ctx: Context) {

    companion object {
        val TEST_HOSTS = listOf("volla.online", "wiki.volla.online", "forum.volla.online", "f-droid.org")

        private const val DNS_TIMEOUT_MS = 6_000L
        private const val TCP_TIMEOUT_MS = 3_000
        private const val TCP_PORT = 443
        private const val TCP_SAMPLES = 3
        private const val SLOW_DNS_MS = 500L
        private const val SLOW_TCP_MS = 300L
        private const val INVALID_RSSI = -127
    }

    private val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /** Eigener Scope, damit blockierende DNS-/Socket-Aufrufe den Aufrufer nicht festhalten. */
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .followRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
    }

    private class StaticState {
        var airplane = false
        var hasNetwork = false
        var validated = false
        var wifiConnected = false
        var mobileDataOff = false
    }

    private var state = StaticState()

    private sealed class DnsOutcome {
        data class Ok(val addrs: List<InetAddress>, val ms: Long) : DnsOutcome()
        data class Fail(val message: String) : DnsOutcome()
        object Timeout : DnsOutcome()
    }

    private sealed class HttpOutcome {
        data class Ok(val code: Int, val protocol: String, val tls: String?, val ms: Long) : HttpOutcome()
        data class Fail(val message: String, val tlsProblem: Boolean) : HttpOutcome()
    }

    private class HostProbe(
        val host: String,
        val dns: DnsOutcome,
        /** null = übersprungen (DNS fehlgeschlagen); Eintrag null = Versuch fehlgeschlagen. */
        val tcp: List<Long?>?,
        val http: HttpOutcome?
    )

    fun close() {
        ioScope.cancel()
    }

    // ------------------------------------------------------------------
    // Statische Abschnitte
    // ------------------------------------------------------------------

    /** True, wenn das Gerät Telefonie hat, die Berechtigung READ_PHONE_STATE aber fehlt. */
    fun needsPhonePermission(): Boolean = hasTelephony() && !hasPhonePermission()

    fun collectStatic(): List<DiagSection> {
        val st = StaticState()
        state = st

        val active = cm.activeNetwork
        val caps = active?.let { cm.getNetworkCapabilities(it) }
        val lp = active?.let { cm.getLinkProperties(it) }
        st.hasNetwork = caps != null

        val sections = mutableListOf<DiagSection>()
        sections += buildConnectionSection(st, caps)
        if (lp != null) sections += buildConfigSection(lp)

        val physical = findPhysicalNetworkCaps()
        if (physical != null && physical.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            st.wifiConnected = true
            buildWifiSection(physical)?.let { sections += it }
        }
        if (hasTelephony()) sections += buildMobileSection(st)
        return sections
    }

    private fun buildConnectionSection(st: StaticState, caps: NetworkCapabilities?): DiagSection {
        val rows = mutableListOf<DiagRow>()

        st.airplane = Settings.Global.getInt(ctx.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
        rows += DiagRow(
            ctx.getString(R.string.netdiag_airplane),
            ctx.getString(if (st.airplane) R.string.netdiag_state_on else R.string.netdiag_state_off),
            if (st.airplane) DiagStatus.WARN else DiagStatus.INFO
        )

        if (caps == null) {
            rows += DiagRow(
                ctx.getString(R.string.netdiag_transport),
                ctx.getString(R.string.netdiag_no_network),
                DiagStatus.ERROR
            )
            return DiagSection(ctx.getString(R.string.netdiag_section_connection), rows)
        }

        rows += DiagRow(ctx.getString(R.string.netdiag_transport), transportLabels(caps))

        st.validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        rows += if (st.validated) {
            DiagRow(ctx.getString(R.string.netdiag_validated), ctx.getString(R.string.netdiag_validated_yes), DiagStatus.OK)
        } else {
            DiagRow(ctx.getString(R.string.netdiag_validated), ctx.getString(R.string.netdiag_validated_no), DiagStatus.WARN)
        }

        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)) {
            rows += DiagRow(
                ctx.getString(R.string.netdiag_captive),
                ctx.getString(R.string.netdiag_captive_yes),
                DiagStatus.WARN
            )
        }

        val metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        rows += DiagRow(
            ctx.getString(R.string.netdiag_metered),
            ctx.getString(if (metered) R.string.yes else R.string.no)
        )

        rows += when (cm.restrictBackgroundStatus) {
            ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED ->
                DiagRow(ctx.getString(R.string.netdiag_datasaver), ctx.getString(R.string.netdiag_datasaver_on), DiagStatus.WARN)
            ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED ->
                DiagRow(ctx.getString(R.string.netdiag_datasaver), ctx.getString(R.string.netdiag_datasaver_whitelisted), DiagStatus.INFO)
            else ->
                DiagRow(ctx.getString(R.string.netdiag_datasaver), ctx.getString(R.string.netdiag_state_off), DiagStatus.INFO)
        }

        val down = caps.linkDownstreamBandwidthKbps / 1000
        val up = caps.linkUpstreamBandwidthKbps / 1000
        if (down > 0 || up > 0) {
            rows += DiagRow(
                ctx.getString(R.string.netdiag_bandwidth),
                ctx.getString(R.string.netdiag_bandwidth_value, down, up)
            )
        }
        return DiagSection(ctx.getString(R.string.netdiag_section_connection), rows)
    }

    private fun transportLabels(caps: NetworkCapabilities): String {
        val labels = mutableListOf<String>()
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) labels.add(ctx.getString(R.string.netdiag_transport_wifi))
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) labels.add(ctx.getString(R.string.netdiag_transport_cellular))
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) labels.add(ctx.getString(R.string.netdiag_transport_ethernet))
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) labels.add(ctx.getString(R.string.netdiag_transport_bluetooth))
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) labels.add(ctx.getString(R.string.netdiag_transport_vpn))
        return if (labels.isEmpty()) ctx.getString(R.string.netdiag_transport_other) else labels.joinToString(" + ")
    }

    private fun buildConfigSection(lp: LinkProperties): DiagSection {
        val rows = mutableListOf<DiagRow>()

        lp.interfaceName?.let { rows += DiagRow(ctx.getString(R.string.netdiag_interface), it) }

        var hasV4 = false
        var hasV6 = false
        for (linkAddress in lp.linkAddresses) {
            val a = linkAddress.address
            if (a.isLoopbackAddress || a.isLinkLocalAddress) continue
            if (a is Inet4Address) hasV4 = true
            if (a is Inet6Address && !isUniqueLocal(a)) hasV6 = true
        }
        val family = when {
            hasV4 && hasV6 -> R.string.netdiag_ip_dual
            hasV4 -> R.string.netdiag_ip_v4only
            hasV6 -> R.string.netdiag_ip_v6only
            else -> R.string.netdiag_ip_none
        }
        rows += DiagRow(
            ctx.getString(R.string.netdiag_ip_family),
            ctx.getString(family),
            if (hasV4 || hasV6) DiagStatus.INFO else DiagStatus.ERROR
        )

        val dns = lp.dnsServers.mapNotNull { it.hostAddress }
        rows += if (dns.isEmpty()) {
            DiagRow(ctx.getString(R.string.netdiag_dns_servers), ctx.getString(R.string.netdiag_none), DiagStatus.ERROR)
        } else {
            DiagRow(ctx.getString(R.string.netdiag_dns_servers), dns.joinToString("\n"))
        }

        rows += if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val host = lp.privateDnsServerName
            when {
                host != null -> DiagRow(
                    ctx.getString(R.string.netdiag_private_dns),
                    ctx.getString(R.string.netdiag_private_dns_host, host),
                    DiagStatus.OK
                )
                lp.isPrivateDnsActive -> DiagRow(
                    ctx.getString(R.string.netdiag_private_dns),
                    ctx.getString(R.string.netdiag_private_dns_active),
                    DiagStatus.OK
                )
                else -> DiagRow(ctx.getString(R.string.netdiag_private_dns), ctx.getString(R.string.netdiag_private_dns_off))
            }
        } else {
            DiagRow(ctx.getString(R.string.netdiag_private_dns), ctx.getString(R.string.netdiag_unsupported))
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && lp.mtu > 0) {
            rows += DiagRow(ctx.getString(R.string.netdiag_mtu), lp.mtu.toString())
        }

        val proxy = lp.httpProxy
        rows += DiagRow(
            ctx.getString(R.string.netdiag_proxy),
            if (proxy != null) "${proxy.host}:${proxy.port}" else ctx.getString(R.string.netdiag_none)
        )

        return DiagSection(ctx.getString(R.string.netdiag_section_config), rows)
    }

    /** Unique-Local-Adressen (fc00::/7) sind keine global routbaren IPv6-Adressen. */
    private fun isUniqueLocal(a: Inet6Address): Boolean = (a.address[0].toInt() and 0xFE) == 0xFC

    /**
     * Bevorzugt das validierte, nicht-VPN-Netzwerk mit Internet-Fähigkeit. Bei aktivem
     * VPN ist das aktive Netzwerk der Tunnel; WLAN-/Mobilfunkdetails stehen am
     * darunterliegenden physischen Netzwerk.
     */
    @Suppress("DEPRECATION")
    private fun findPhysicalNetworkCaps(): NetworkCapabilities? {
        val candidates = cm.allNetworks.mapNotNull { n: Network -> cm.getNetworkCapabilities(n) }
            .filter {
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            }
        return candidates.firstOrNull { it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) }
            ?: candidates.firstOrNull()
    }

    @Suppress("DEPRECATION")
    private fun readWifiInfo(caps: NetworkCapabilities): WifiInfo? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            (caps.transportInfo as? WifiInfo)?.let { return it }
        }
        return try {
            (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.connectionInfo
        } catch (e: SecurityException) {
            null
        }
    }

    private fun buildWifiSection(caps: NetworkCapabilities): DiagSection? {
        val info = readWifiInfo(caps) ?: return null
        val rows = mutableListOf<DiagRow>()

        val rssi = info.rssi
        if (rssi > INVALID_RSSI && rssi < 0) {
            val (quality, status) = qualityFromRssi(rssi)
            rows += DiagRow(
                ctx.getString(R.string.netdiag_signal),
                ctx.getString(R.string.netdiag_signal_value, rssi, quality),
                status
            )
        }

        val freq = info.frequency
        if (freq > 0) {
            val band = when {
                freq < 3000 -> R.string.netdiag_band_24
                freq < 5925 -> R.string.netdiag_band_5
                else -> R.string.netdiag_band_6
            }
            rows += DiagRow(
                ctx.getString(R.string.netdiag_wifi_band),
                ctx.getString(R.string.netdiag_wifi_band_value, ctx.getString(band), freq)
            )
        }

        val speed = info.linkSpeed
        if (speed > 0) {
            rows += DiagRow(ctx.getString(R.string.netdiag_wifi_speed), ctx.getString(R.string.netdiag_mbit_value, speed))
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Werte entsprechen ScanResult.WIFI_STANDARD_*
            val standard = when (info.wifiStandard) {
                1 -> "802.11a/b/g"
                4 -> "Wi-Fi 4 (802.11n)"
                5 -> "Wi-Fi 5 (802.11ac)"
                6 -> "Wi-Fi 6 (802.11ax)"
                7 -> "802.11ad"
                8 -> "Wi-Fi 7 (802.11be)"
                else -> null
            }
            if (standard != null) rows += DiagRow(ctx.getString(R.string.netdiag_wifi_standard), standard)
        }

        return if (rows.isEmpty()) null else DiagSection(ctx.getString(R.string.netdiag_section_wifi), rows)
    }

    private fun qualityFromRssi(rssi: Int): Pair<String, DiagStatus> = when {
        rssi >= -55 -> ctx.getString(R.string.netdiag_quality_excellent) to DiagStatus.OK
        rssi >= -67 -> ctx.getString(R.string.netdiag_quality_good) to DiagStatus.OK
        rssi >= -75 -> ctx.getString(R.string.netdiag_quality_fair) to DiagStatus.WARN
        else -> ctx.getString(R.string.netdiag_quality_poor) to DiagStatus.WARN
    }

    private fun qualityFromLevel(level: Int): Pair<String, DiagStatus> = when {
        level >= 4 -> ctx.getString(R.string.netdiag_quality_excellent) to DiagStatus.OK
        level == 3 -> ctx.getString(R.string.netdiag_quality_good) to DiagStatus.OK
        level == 2 -> ctx.getString(R.string.netdiag_quality_fair) to DiagStatus.WARN
        else -> ctx.getString(R.string.netdiag_quality_poor) to DiagStatus.WARN
    }

    private fun hasTelephony(): Boolean =
        ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)

    private fun hasPhonePermission(): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    private fun buildMobileSection(st: StaticState): DiagSection {
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val rows = mutableListOf<DiagRow>()

        val (simText, simStatus) = when (tm.simState) {
            TelephonyManager.SIM_STATE_READY -> ctx.getString(R.string.netdiag_sim_ready) to DiagStatus.OK
            TelephonyManager.SIM_STATE_ABSENT -> ctx.getString(R.string.netdiag_sim_absent) to DiagStatus.ERROR
            TelephonyManager.SIM_STATE_PIN_REQUIRED,
            TelephonyManager.SIM_STATE_PUK_REQUIRED,
            TelephonyManager.SIM_STATE_NETWORK_LOCKED -> ctx.getString(R.string.netdiag_sim_locked) to DiagStatus.WARN
            else -> ctx.getString(R.string.netdiag_sim_unknown) to DiagStatus.INFO
        }
        rows += DiagRow(ctx.getString(R.string.netdiag_sim_state), simText, simStatus)

        val operator = tm.networkOperatorName
        rows += if (operator.isNullOrBlank()) {
            DiagRow(ctx.getString(R.string.netdiag_operator), ctx.getString(R.string.netdiag_operator_none), DiagStatus.WARN)
        } else {
            DiagRow(ctx.getString(R.string.netdiag_operator), operator)
        }

        val roaming = tm.isNetworkRoaming
        rows += DiagRow(
            ctx.getString(R.string.netdiag_roaming),
            ctx.getString(if (roaming) R.string.yes else R.string.no),
            if (roaming) DiagStatus.WARN else DiagStatus.INFO
        )

        if (hasPhonePermission()) {
            try {
                networkTypeName(tm.dataNetworkType)?.let {
                    rows += DiagRow(ctx.getString(R.string.netdiag_net_type), it)
                }
                readMobileSignal(tm)?.let { rows += it }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val enabled = tm.isDataEnabled
                    st.mobileDataOff = !enabled
                    rows += DiagRow(
                        ctx.getString(R.string.netdiag_mobile_data),
                        ctx.getString(if (enabled) R.string.netdiag_state_on else R.string.netdiag_state_off),
                        if (enabled) DiagStatus.OK else DiagStatus.WARN
                    )
                }
            } catch (e: SecurityException) {
                rows += DiagRow(ctx.getString(R.string.netdiag_net_type), ctx.getString(R.string.netdiag_phone_perm_missing))
            }
        } else {
            rows += DiagRow(ctx.getString(R.string.netdiag_net_type), ctx.getString(R.string.netdiag_phone_perm_missing))
        }

        return DiagSection(ctx.getString(R.string.netdiag_section_mobile), rows)
    }

    /** Werte entsprechen TelephonyManager.NETWORK_TYPE_*; 0 (unbekannt) liefert null. */
    private fun networkTypeName(type: Int): String? = when (type) {
        1 -> "2G (GPRS)"
        2 -> "2G (EDGE)"
        3 -> "3G (UMTS)"
        4, 7 -> "2G (CDMA)"
        5, 6, 12, 14 -> "3G (EV-DO)"
        8 -> "3G (HSDPA)"
        9 -> "3G (HSUPA)"
        10 -> "3G (HSPA)"
        11 -> "2G (iDEN)"
        13 -> "4G (LTE)"
        15 -> "3G (HSPA+)"
        16 -> "2G (GSM)"
        17 -> "3G (TD-SCDMA)"
        18 -> "IWLAN"
        19 -> "4G (LTE-CA)"
        20 -> "5G (NR)"
        else -> null
    }

    private fun readMobileSignal(tm: TelephonyManager): DiagRow? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        val ss = (try {
            tm.signalStrength
        } catch (e: SecurityException) {
            null
        }) ?: return null

        val level = ss.level
        val (quality, status) = qualityFromLevel(level)
        var dbm: Int? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            dbm = ss.cellSignalStrengths.firstOrNull { it.dbm != Int.MAX_VALUE }?.dbm
        }
        val value = if (dbm != null) {
            ctx.getString(R.string.netdiag_signal_value, dbm, quality)
        } else {
            ctx.getString(R.string.netdiag_signal_level, quality, level)
        }
        return DiagRow(ctx.getString(R.string.netdiag_signal), value, status)
    }

    // ------------------------------------------------------------------
    // Erreichbarkeitstests
    // ------------------------------------------------------------------

    /** Führt DNS-, TCP- und HTTPS-Test je Host parallel aus. Erfordert vorheriges [collectStatic]. */
    suspend fun runTests(): List<DiagSection> {
        val jobs = TEST_HOSTS.map { host -> ioScope.async { probeHost(host) } }
        val probes = jobs.map { it.await() }

        val sections = probes.map { buildHostSection(it) }.toMutableList()
        buildHints(probes)?.let { sections += it }
        return sections
    }

    private suspend fun probeHost(host: String): HostProbe {
        val dnsJob = ioScope.async { resolve(host) }
        val dns = withTimeoutOrNull(DNS_TIMEOUT_MS) { dnsJob.await() } ?: DnsOutcome.Timeout
        if (dns !is DnsOutcome.Ok) return HostProbe(host, dns, null, null)

        val target = dns.addrs.first()
        val tcp = (1..TCP_SAMPLES).map { tcpConnectMillis(target) }
        val http = httpProbe(host)
        return HostProbe(host, dns, tcp, http)
    }

    private fun resolve(host: String): DnsOutcome = try {
        val t0 = System.nanoTime()
        val addrs = InetAddress.getAllByName(host).toList()
        DnsOutcome.Ok(addrs, (System.nanoTime() - t0) / 1_000_000)
    } catch (e: Exception) {
        DnsOutcome.Fail(describe(e))
    }

    private fun tcpConnectMillis(target: InetAddress): Long? = try {
        Socket().use { socket ->
            val t0 = System.nanoTime()
            socket.connect(InetSocketAddress(target, TCP_PORT), TCP_TIMEOUT_MS)
            (System.nanoTime() - t0) / 1_000_000
        }
    } catch (e: Exception) {
        null
    }

    private fun httpProbe(host: String): HttpOutcome = try {
        val request = Request.Builder()
            .url("https://$host/")
            .head()
            .header("User-Agent", "VollaHub-NetworkDiagnostic")
            .build()
        val t0 = System.nanoTime()
        httpClient.newCall(request).execute().use { response ->
            val ms = (System.nanoTime() - t0) / 1_000_000
            HttpOutcome.Ok(
                code = response.code,
                protocol = response.protocol.toString(),
                tls = response.handshake?.tlsVersion?.javaName,
                ms = ms
            )
        }
    } catch (e: Exception) {
        HttpOutcome.Fail(describe(e), isTlsProblem(e))
    }

    private fun isTlsProblem(e: Throwable): Boolean {
        var t: Throwable? = e
        var depth = 0
        while (t != null && depth < 8) {
            if (t is SSLException || t is CertificateException) return true
            t = t.cause
            depth++
        }
        return false
    }

    private fun describe(e: Throwable): String {
        val name = e.javaClass.simpleName
        val msg = e.message
        return if (msg.isNullOrBlank()) name else "$name: $msg"
    }

    private fun buildHostSection(probe: HostProbe): DiagSection {
        val rows = mutableListOf<DiagRow>()
        val skipped = ctx.getString(R.string.netdiag_skipped)

        // DNS
        val dnsLabel = ctx.getString(R.string.netdiag_dns)
        rows += when (val dns = probe.dns) {
            is DnsOutcome.Ok -> {
                val families = dns.addrs.map { if (it is Inet6Address) "IPv6" else "IPv4" }.distinct().joinToString("+")
                DiagRow(
                    dnsLabel,
                    ctx.getString(R.string.netdiag_dns_ok, dns.ms, dns.addrs.first().hostAddress ?: "?", families),
                    if (dns.ms > SLOW_DNS_MS) DiagStatus.WARN else DiagStatus.OK
                )
            }
            is DnsOutcome.Fail -> DiagRow(dnsLabel, ctx.getString(R.string.netdiag_failed, dns.message), DiagStatus.ERROR)
            DnsOutcome.Timeout -> DiagRow(
                dnsLabel,
                ctx.getString(R.string.netdiag_dns_timeout, (DNS_TIMEOUT_MS / 1000).toInt()),
                DiagStatus.ERROR
            )
        }

        // TCP
        val tcpLabel = ctx.getString(R.string.netdiag_tcp)
        val tcp = probe.tcp
        rows += if (tcp == null) {
            DiagRow(tcpLabel, skipped, DiagStatus.INFO)
        } else {
            val ok = tcp.filterNotNull()
            if (ok.isEmpty()) {
                DiagRow(tcpLabel, ctx.getString(R.string.netdiag_tcp_fail, tcp.size), DiagStatus.ERROR)
            } else {
                val avg = ok.average().toLong()
                DiagRow(
                    tcpLabel,
                    ctx.getString(R.string.netdiag_tcp_ok, ok.minOrNull() ?: 0L, avg, ok.maxOrNull() ?: 0L, ok.size, tcp.size),
                    if (ok.size < tcp.size || avg > SLOW_TCP_MS) DiagStatus.WARN else DiagStatus.OK
                )
            }
        }

        // HTTPS
        val httpLabel = ctx.getString(R.string.netdiag_https)
        rows += when (val http = probe.http) {
            null -> DiagRow(httpLabel, skipped, DiagStatus.INFO)
            is HttpOutcome.Ok -> DiagRow(
                httpLabel,
                ctx.getString(R.string.netdiag_https_ok, http.code, http.protocol, http.tls ?: "–", http.ms),
                if (http.code in 200..399 || http.code == 405) DiagStatus.OK else DiagStatus.WARN
            )
            is HttpOutcome.Fail -> DiagRow(httpLabel, ctx.getString(R.string.netdiag_failed, http.message), DiagStatus.ERROR)
        }

        return DiagSection(ctx.getString(R.string.netdiag_section_host, probe.host), rows)
    }

    private fun buildHints(probes: List<HostProbe>): DiagSection? {
        val st = state
        val hints = mutableListOf<Int>()

        if (st.airplane) hints += R.string.netdiag_hint_airplane
        if (!st.hasNetwork) {
            hints += R.string.netdiag_hint_no_network
        } else {
            if (!st.validated) hints += R.string.netdiag_hint_not_validated
            if (st.mobileDataOff && !st.wifiConnected) hints += R.string.netdiag_hint_mobile_data_off
            if (probes.all { it.dns !is DnsOutcome.Ok }) hints += R.string.netdiag_hint_dns_all
        }
        if (probes.any { (it.http as? HttpOutcome.Fail)?.tlsProblem == true }) hints += R.string.netdiag_hint_tls
        if (probes.any { p -> p.tcp?.let { t -> t.any { it == null } && t.any { it != null } } == true }) {
            hints += R.string.netdiag_hint_loss
        }

        if (hints.isEmpty()) return null
        val label = ctx.getString(R.string.netdiag_hint_label)
        return DiagSection(
            ctx.getString(R.string.netdiag_section_hints),
            hints.map { DiagRow(label, ctx.getString(it), DiagStatus.INFO) }
        )
    }

    // ------------------------------------------------------------------
    // Auswertung / Export
    // ------------------------------------------------------------------

    /** Anzahl (Fehler, Warnungen) über alle Abschnitte. */
    fun countIssues(sections: List<DiagSection>): Pair<Int, Int> {
        val rows = sections.flatMap { it.rows }
        return rows.count { it.status == DiagStatus.ERROR } to rows.count { it.status == DiagStatus.WARN }
    }

    /** Klartext für Zwischenablage / Teilen (z. B. Forum, Telegram). */
    fun toPlainText(sections: List<DiagSection>): String {
        val version = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        } catch (e: Exception) {
            "?"
        }
        val timestamp = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date())
        val sb = StringBuilder()
        sb.append(ctx.getString(R.string.netdiag_report_header)).append('\n')
        sb.append(ctx.getString(R.string.netdiag_report_meta, timestamp, version, Build.MODEL, Build.VERSION.RELEASE)).append("\n\n")
        for (section in sections) {
            sb.append("== ").append(section.title).append(" ==\n")
            for (row in section.rows) {
                val tag = when (row.status) {
                    DiagStatus.OK -> "[OK]"
                    DiagStatus.WARN -> "[WARN]"
                    DiagStatus.ERROR -> "[ERR]"
                    DiagStatus.INFO -> "[i]"
                }
                sb.append(tag).append(' ').append(row.label).append(": ")
                    .append(row.value.replace("\n", ", ")).append('\n')
            }
            sb.append('\n')
        }
        return sb.toString().trimEnd()
    }
}
