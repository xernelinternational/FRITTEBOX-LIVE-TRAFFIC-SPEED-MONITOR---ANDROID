package com.fritte.speedmonitor

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom

class AuthException : IOException("Login refused")
class HttpException(val code: Int) : IOException("HTTP $code")

data class Svc(val type: String, val control: String, val scpd: String?)
data class Item(val title: String, val value: String?)

private fun md5(s: String): String =
    MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

private fun unescapeXml(s: String): String =
    s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&#39;", "'").replace("&amp;", "&")

/** One SOAP/TR-064 conversation with the router. With a user it answers the HTTP digest login. */
class TrSession(private val host: String, private val user: String?, private val pass: String?) {
    private var realm = ""
    private var nonce = ""
    private var opaque: String? = null
    private var qopAuth = false
    private var nc = 0
    private val rnd = SecureRandom()

    fun call(
        control: String, service: String, action: String,
        args: Map<String, String> = emptyMap(), timeoutMs: Int = 5000
    ): Map<String, String> {
        val argsXml = args.entries.joinToString("") { "<${it.key}>${it.value}</${it.key}>" }
        val body = "<?xml version=\"1.0\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>" +
            "<u:$action xmlns:u=\"$service\">$argsXml</u:$action></s:Body></s:Envelope>"
        var attempt = 0
        while (true) {
            val conn = URL("http://$host:${Router.PORT}$control").openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
                conn.setRequestProperty("SOAPAction", "\"$service#$action\"")
                if (user != null && nonce.isNotEmpty()) {
                    conn.setRequestProperty("Authorization", digestHeader(control))
                }
                conn.outputStream.use { it.write(body.toByteArray()) }
                val code = conn.responseCode
                if (code == 401) {
                    if (user == null || attempt >= 1) throw AuthException()
                    val challenge = conn.getHeaderField("WWW-Authenticate") ?: throw AuthException()
                    parseChallenge(challenge)
                    attempt++
                    continue
                }
                if (code !in 200..299) throw HttpException(code)
                val xml = conn.inputStream.bufferedReader().use { it.readText() }
                return Router.parse(xml)
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun parseChallenge(header: String) {
        val m = Regex("(\\w+)=(?:\"([^\"]*)\"|([^,\\s]+))").findAll(header).associate {
            it.groupValues[1].lowercase() to it.groupValues[2].ifEmpty { it.groupValues[3] }
        }
        realm = m["realm"] ?: ""
        nonce = m["nonce"] ?: ""
        opaque = m["opaque"]
        qopAuth = (m["qop"] ?: "").contains("auth")
        nc = 0
    }

    private fun digestHeader(uri: String): String {
        nc++
        val ncStr = "%08x".format(nc)
        val cnonce = ByteArray(8).also { rnd.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val ha1 = md5("$user:$realm:$pass")
        val ha2 = md5("POST:$uri")
        val response =
            if (qopAuth) md5("$ha1:$nonce:$ncStr:$cnonce:auth:$ha2") else md5("$ha1:$nonce:$ha2")
        val sb = StringBuilder(
            "Digest username=\"$user\", realm=\"$realm\", nonce=\"$nonce\", " +
                "uri=\"$uri\", algorithm=MD5, response=\"$response\""
        )
        if (qopAuth) sb.append(", qop=auth, nc=$ncStr, cnonce=\"$cnonce\"")
        opaque?.let { sb.append(", opaque=\"$it\"") }
        return sb.toString()
    }
}

object Router {
    const val PORT = 49000
    const val WAN_CTRL = "/igdupnp/control/WANCommonIFC1"
    const val WAN_SVC = "urn:schemas-upnp-org:service:WANCommonInterfaceConfig:1"
    const val NA = "Information not available - Admin Username and Password needed"
    const val PUBLIC_TITLE = "Current public IPv4 address"

    private val IGD = listOf(
        "/igdupnp/control/WANIPConn1" to "urn:schemas-upnp-org:service:WANIPConnection:1",
        "/igdupnp/control/WANPPPConn1" to "urn:schemas-upnp-org:service:WANPPPConnection:1"
    )
    private val BEACON_NAMES = mapOf(
        "None" to "open (no encryption)", "Basic" to "WEP", "WPA" to "WPA",
        "11i" to "WPA2", "WPAand11i" to "WPA + WPA2"
    )

    fun parse(xml: String): Map<String, String> =
        Regex("<(New[\\w\\-]+)>([^<]*)</").findAll(xml).associate {
            it.groupValues[1] to unescapeXml(it.groupValues[2])
        }

    fun num(d: Map<String, String>, key: String): Double = d[key]?.toDoubleOrNull() ?: 0.0

    /** Live traffic counters (no login needed, UPnP status information must be on). */
    fun wanCall(host: String, action: String): Map<String, String> =
        TrSession(host, null, null).call(WAN_CTRL, WAN_SVC, action, timeoutMs = 3000)

    fun httpGet(url: String, timeoutMs: Int = 4000): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            if (conn.responseCode !in 200..299) throw HttpException(conn.responseCode)
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** "FRITZ!Box 7590 AX" -> "7590 AX" */
    fun fetchModel(host: String): String? {
        for (path in listOf("/igddesc.xml", "/tr64desc.xml")) {
            try {
                val xml = httpGet("http://$host:$PORT$path")
                val m = Regex("<modelName>([^<]+)</modelName>").find(xml) ?: continue
                val name = unescapeXml(m.groupValues[1]).trim()
                    .replace(Regex("^FRITZ!Box\\s+", RegexOption.IGNORE_CASE), "")
                if (name.isNotEmpty()) return name
            } catch (e: Exception) {
                // try the next address
            }
        }
        return null
    }

    fun connLabel(accessType: String?): String {
        val t = (accessType ?: "").lowercase()
        return when {
            t == "dsl" -> "DSL Connection"
            t.contains("fiber") || t.contains("fibre") -> "Fiber Connection"
            t == "ethernet" -> "Ethernet Connection"
            else -> "Unknown Type"
        }
    }

    private fun trServices(host: String): Map<String, Pair<String, String?>> {
        val xml = httpGet("http://$host:$PORT/tr64desc.xml")
        val out = LinkedHashMap<String, Pair<String, String?>>()
        for (blk in Regex("<service>(.*?)</service>", RegexOption.DOT_MATCHES_ALL).findAll(xml)) {
            val b = blk.groupValues[1]
            val t = Regex("<serviceType>([^<]+)<").find(b)?.groupValues?.get(1)?.trim()
            val c = Regex("<controlURL>([^<]+)<").find(b)?.groupValues?.get(1)?.trim()
            val d = Regex("<SCPDURL>([^<]+)<").find(b)?.groupValues?.get(1)?.trim()
            if (t != null && c != null) out[t] = c to d
        }
        return out
    }

    private fun bandOf(ext: Map<String, String>, info: Map<String, String>, ch: Map<String, String>): String {
        val s = (ext["NewX_AVM-DE_FrequencyBand"] ?: "").replace(" ", "").replace(",", ".").lowercase()
        if (s.startsWith("2.4")) return "2.4 GHz"
        if (s.startsWith("5")) return "5 GHz"
        if (s.startsWith("6")) return "6 GHz"
        val channel = (info["NewChannel"] ?: ch["NewChannel"] ?: "0").toIntOrNull() ?: 0
        return when {
            channel in 1..14 -> "2.4 GHz"
            channel in 36..177 -> "5 GHz"
            else -> "Unknown band"
        }
    }

    private fun fritzOsText(raw: String?): String? {
        if (raw == null) return null
        val m = Regex("(\\d+)\\.(\\d+)\\.(\\d+)").find(raw) ?: return raw
        return "FRITZ!OS ${m.groupValues[2].toInt()}.${m.groupValues[3]}  (firmware $raw)"
    }

    private class Iface(
        val entry: Svc, val guest: Boolean, val band: String,
        val info: Map<String, String>, val ext: Map<String, String>, val ch: Map<String, String>,
        val bt: Map<String, String>, val adv: Map<String, String>, val assoc: Map<String, String>
    ) {
        val label: String get() = band + if (guest) " Guest" else ""
        var devs: List<Pair<String, String>> = emptyList()
        var count: Int? = null
    }

    /** Returns the list of details and the login state: none / ok / refused / unreachable. */
    fun gatherInfo(host: String, user: String, password: String): Pair<List<Item>, String> {
        val hasLogin = password.isNotEmpty()
        var session = if (hasLogin) TrSession(host, user, password) else TrSession(host, null, null)
        val services: Map<String, Pair<String, String?>> =
            try { trServices(host) } catch (e: Exception) { emptyMap() }

        fun find(name: String): Svc? {
            val e = services.entries.sortedBy { it.key }.firstOrNull { it.key.endsWith(":$name:1") }
                ?: return null
            return Svc(e.key, e.value.first, e.value.second)
        }

        fun call(e: Svc?, action: String, args: Map<String, String> = emptyMap()): Map<String, String> {
            if (e == null) return emptyMap()
            return try { session.call(e.control, e.type, action, args) } catch (ex: Exception) { emptyMap() }
        }

        fun igd(action: String): Map<String, String> {
            for ((ctl, typ) in IGD) {
                try { return TrSession(host, null, null).call(ctl, typ, action) } catch (ex: Exception) { }
            }
            return emptyMap()
        }

        fun v(d: Map<String, String>, key: String): String? = d[key]?.takeIf { it.isNotEmpty() }
        fun linesOrNull(l: List<String>): String? = if (l.isEmpty()) null else l.joinToString("\n")

        // check the login once: wrong user/password -> "refused"
        var state = "none"
        if (hasLogin) {
            val probe = find("DeviceInfo") ?: find("LANEthernetInterfaceConfig") ?: find("Hosts")
            if (probe == null) {
                state = "unreachable"
            } else {
                val action = if (probe.type.endsWith(":Hosts:1")) "GetHostNumberOfEntries" else "GetInfo"
                state = try {
                    session.call(probe.control, probe.type, action)
                    "ok"
                } catch (ex: AuthException) {
                    "refused"
                } catch (ex: HttpException) {
                    "ok" // the router answered beyond the login (e.g. unsupported action)
                } catch (ex: Exception) {
                    "unreachable"
                }
            }
            if (state != "ok") session = TrSession(host, null, null) // don't keep sending a wrong password
        }

        // Wi-Fi radios (need login)
        val wl = services.entries.mapNotNull { e ->
            Regex(":WLANConfiguration:(\\d+)$").find(e.key)?.let {
                it.groupValues[1].toInt() to Svc(e.key, e.value.first, e.value.second)
            }
        }.sortedBy { it.first }
        val ifs = mutableListOf<Iface>()
        for ((_, e) in wl) {
            val info = call(e, "GetInfo")
            if (info.isEmpty()) continue
            val ext = call(e, "X_AVM-DE_GetWLANExtInfo")
            val ch = call(e, "GetChannelInfo")
            val guest = (ext["NewX_AVM-DE_APType"] ?: "").lowercase() == "guest"
            ifs.add(
                Iface(e, guest, bandOf(ext, info, ch), info, ext, ch,
                    call(e, "GetBeaconType"), call(e, "GetBeaconAdvertisement"),
                    call(e, "GetTotalAssociations"))
            )
        }

        // 1) SSID
        val ssid = linesOrNull(ifs.map { "${it.label}: ${it.info["NewSSID"] ?: NA}" })

        // 2) public IPv4
        var ip = v(igd("GetExternalIPAddress"), "NewExternalIPAddress")
        if (ip == null) {
            for (name in listOf("WANIPConnection", "WANPPPConnection")) {
                if (ip == null) ip = v(call(find(name), "GetExternalIPAddress"), "NewExternalIPAddress")
            }
        }
        var publicIp: String? = null
        if (ip != null && ip != "0.0.0.0") {
            publicIp = ip
            val o = ip.split(".").mapNotNull { it.toIntOrNull() }
            if (o.size == 4) {
                if (o[0] == 100 && o[1] in 64..127) {
                    publicIp += "   (carrier-grade NAT - not directly reachable from the internet)"
                } else if (o[0] == 10 || (o[0] == 172 && o[1] in 16..31) || (o[0] == 192 && o[1] == 168)) {
                    publicIp += "   (private address - the router sits behind another router/modem)"
                }
            }
        }

        // 3) connected devices per band (normal / guest network) + LAN, with name and local IP
        val hosts = find("Hosts")
        val hostmap = HashMap<String, Pair<String, String>>()
        val lanDevs = mutableListOf<Pair<String, String>>()
        var lan: Int? = null
        val total = call(hosts, "GetHostNumberOfEntries")["NewHostNumberOfEntries"]?.toIntOrNull()
        if (total != null) {
            for (i in 0 until minOf(total, 300)) {
                val h = call(hosts, "GetGenericHostEntry", mapOf("NewIndex" to i.toString()))
                if (h.isEmpty()) continue
                val mac = (h["NewMACAddress"] ?: "").uppercase()
                val name = h["NewHostName"] ?: ""
                val ipa = h["NewIPAddress"] ?: ""
                hostmap[mac] = name to ipa
                if (h["NewActive"] == "1" && h["NewInterfaceType"] == "Ethernet") {
                    lanDevs.add((name.ifEmpty { mac.ifEmpty { "Unknown device" } }) to ipa)
                }
            }
            lan = lanDevs.size
        }

        fun devLines(devs: List<Pair<String, String>>): List<String> =
            devs.sortedBy { it.first.lowercase() }.map { "    ${it.first} - ${it.second.ifEmpty { "no IP address" }}" }

        fun countText(n: Int): String = "$n Device${if (n == 1) "" else "s"} connected."

        for (i in ifs) {
            val n = i.assoc["NewTotalAssociations"]?.toIntOrNull() ?: 0
            val devs = mutableListOf<Pair<String, String>>()
            for (idx in 0 until minOf(n, 100)) {
                val d = call(i.entry, "GetGenericAssociatedDeviceInfo", mapOf("NewAssociatedDeviceIndex" to idx.toString()))
                val mac = (d["NewAssociatedDeviceMACAddress"] ?: "").uppercase()
                if (mac.isEmpty()) continue
                val known = hostmap[mac] ?: ("" to "")
                devs.add(known.first.ifEmpty { mac } to known.second.ifEmpty { d["NewAssociatedDeviceIPAddress"] ?: "" })
            }
            i.devs = devs
            i.count = if (devs.isNotEmpty()) devs.size else if (i.assoc.isNotEmpty()) n else null
        }

        val dev = mutableListOf<String>()
        for (band in listOf("2.4 GHz", "5 GHz", "6 GHz")) {
            val sel = ifs.filter { it.band == band }
            if (ifs.isEmpty()) dev.add("$band: $NA")
            else if (sel.isEmpty()) dev.add("$band: no $band radio found")
            for (guest in listOf(false, true)) {
                for (i in sel.filter { it.guest == guest }) {
                    val head = "$band (${if (guest) "Guest" else "Normal"} network)"
                    val c = i.count
                    dev.add(if (c != null) "$head = ${countText(c)}" else "$head: $NA")
                    dev.addAll(devLines(i.devs))
                    dev.add("")
                }
            }
        }
        dev.add(if (lan != null) "LAN = ${countText(lan)}" else "LAN: $NA")
        dev.addAll(devLines(lanDevs))
        val devices = if (ifs.isEmpty() && lan == null) null else dev.joinToString("\n")

        // 4) MAC addresses
        val macs = mutableListOf<String>()
        v(call(find("LANEthernetInterfaceConfig"), "GetInfo"), "NewMACAddress")?.let { macs.add("LAN: $it") }
        for (i in ifs) v(i.info, "NewBSSID")?.let { macs.add("${i.label} radio: $it") }

        // 5) wireless configuration + radio status
        val cfg = mutableListOf<String>()
        for (i in ifs) {
            val parts = mutableListOf<String>()
            v(i.info, "NewEnable")?.let { parts.add(if (it == "1") "enabled" else "disabled") }
            v(i.info, "NewStatus")?.let { parts.add("status $it") }
            (v(i.ch, "NewChannel") ?: v(i.info, "NewChannel"))?.let { parts.add("channel $it") }
            v(i.info, "NewStandard")?.let { parts.add("standard 802.11$it") }
            v(i.adv, "NewBeaconAdvertisementEnabled")?.let { parts.add(if (it == "1") "SSID visible" else "SSID hidden") }
            cfg.add("${i.label}: " + (if (parts.isEmpty()) NA else parts.joinToString(", ")))
        }

        // 6) security (modes only - passwords are never read)
        val sec = mutableListOf<String>()
        for (i in ifs) {
            val parts = mutableListOf<String>()
            val kind = v(i.bt, "NewBeaconType") ?: v(i.info, "NewBeaconType")
            if (kind != null) parts.add("${BEACON_NAMES[kind] ?: kind} ($kind)")
            v(i.bt, "NewIEEE11iAuthenticationMode")?.let { parts.add("authentication $it") }
            v(i.bt, "NewIEEE11iEncryptionModes")?.let { parts.add("encryption $it") }
            v(i.ext, "NewX_AVM-DE_EncryptionMode")?.let { parts.add("mode $it") }
            v(i.info, "NewMACAddressControlEnabled")?.let { parts.add("MAC filter " + if (it == "1") "on" else "off") }
            if (parts.isNotEmpty()) sec.add("${i.label}: " + parts.joinToString(", "))
        }

        // 7) supported radio options
        val opts = mutableListOf<String>()
        for (i in ifs) {
            val parts = mutableListOf<String>()
            v(i.ch, "NewPossibleChannels")?.let { parts.add("channels $it") }
            v(i.bt, "NewX_AVM-DE_PossibleBeaconTypes")?.let { parts.add("security modes $it") }
            v(i.info, "NewStandard")?.let { parts.add("current standard 802.11$it") }
            if (parts.isNotEmpty()) opts.add("${i.label}: " + parts.joinToString("; "))
        }

        // 8) WAN DNS servers
        val dns = mutableListOf<String>()
        for (name in listOf("WANIPConnection", "WANPPPConnection")) {
            val e = find(name)
            var v4 = v(call(e, "GetInfo"), "NewDNSServers")
            if (v4 == null) {
                val r = call(e, "X_AVM_DE_GetDNSServer")
                v4 = listOfNotNull(r["NewIPv4DNSServer1"], r["NewIPv4DNSServer2"]).joinToString(", ").ifEmpty { null }
            }
            val r6 = call(e, "X_AVM_DE_GetIPv6DNSServer")
            val v6 = listOfNotNull(r6["NewIPv6DNSServer1"], r6["NewIPv6DNSServer2"]).joinToString(", ")
            if (v4 != null) dns.add("IPv4: $v4")
            if (v6.isNotEmpty()) dns.add("IPv6: $v6")
            if (dns.isNotEmpty()) break
        }

        // 9) Wake-on-LAN (reported from the router's service description - no login needed)
        var wol: String? = null
        if (services.isNotEmpty()) {
            try {
                val scpd = hosts?.scpd
                val acts = if (scpd != null) {
                    Regex("<name>([^<]*WakeOnLAN[^<]*)</name>").findAll(httpGet("http://$host:$PORT$scpd"))
                        .map { it.groupValues[1] }.toList()
                } else emptyList()
                wol = if (acts.isNotEmpty()) {
                    "Supported by this router:\n" + acts.joinToString("\n") +
                        "\n(Waking a device needs a FritteBox login; this app only reports availability.)"
                } else "Not offered by this router's firmware"
            } catch (e: Exception) {
                wol = null
            }
        }

        // 10) FRITZ!OS version (boxinfo page needs no login)
        var osver: String? = null
        for (path in listOf("/juis_boxinfo.xml", "/jason_boxinfo.xml")) {
            try {
                val m = Regex("<(?:\\w+:)?Version>([^<]+)<").find(httpGet("http://$host$path"))
                if (m != null) {
                    osver = fritzOsText(m.groupValues[1].trim())
                    break
                }
            } catch (e: Exception) {
                // try next
            }
        }
        if (osver == null) {
            osver = fritzOsText(v(call(find("DeviceInfo"), "GetInfo"), "NewSoftwareVersion"))
        }

        val items = listOf(
            Item("Configured SSID / network name", ssid),
            Item(PUBLIC_TITLE, publicIp),
            Item("Connected devices (2.4 GHz, 5 GHz, 6 GHz, LAN)", devices),
            Item("FritteBox MAC addresses", linesOrNull(macs)),
            Item("Wireless configuration and radio status", linesOrNull(cfg)),
            Item("Wi-Fi security configuration", linesOrNull(sec)),
            Item("Supported radio configuration options", linesOrNull(opts)),
            Item("WAN DNS servers", linesOrNull(dns)),
            Item("Wake-on-LAN functions", wol),
            Item("FRITZ!OS version", osver)
        )
        return items to state
    }
}
