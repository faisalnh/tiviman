package faisalnh.tiviman

import android.app.Instrumentation
import android.os.Bundle
import java.io.File
import java.security.MessageDigest

/** Read-only explicit private request. Never emit source text, URLs or exception messages. */
class SavedEpgDiagnostic(private val runner: Instrumentation) {
    fun run() {
        val report = Bundle()
        fun digests() = runner.targetContext.noBackupFilesDir.listFiles().orEmpty().filter { it.isFile }
            .associate { it.name to MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList() }
        val before = digests()
        var stage = "read_saved_account"
        try {
            val snapshot = PlaylistStore(runner.targetContext).snapshot()
            val config = snapshot.provider ?: error("Missing account")
            check(config.type == SourceType.XTREAM)
            stage = "download_xmltv"
            val text = ProviderHttp { key, value -> report.putInt(key, value) }
                .get(Providers(config).xmltvUrl())
            report.putBoolean("doctype_present", text.contains("<!DOCTYPE", true))
            report.putBoolean("entity_declaration_present", text.contains("<!ENTITY", true))
            report.putBoolean("xmltv_root_marker_present", Regex("<tv(?:\\s|>)").containsMatchIn(text))
            report.putBoolean("programme_element_present", Regex("<programme(?:\\s|>)").containsMatchIn(text))
            stage = "parse_xmltv"
            val data = Epg.parse(text)
            report.putInt("programme_count", data.events.size)
            report.putInt("current_or_future_events", data.events.count { it.end > Epg.minute() })
            stage = "match_saved_catalog"
            val catalog = snapshot.channels ?: error("Missing catalog")
            report.putInt("catalog_epg_id_count", catalog.count { it.epgId.isNotBlank() })
            report.putInt("matched_current_or_future_channels", Epg.project(catalog, data, Epg.minute())
                .count { channel -> channel.programmes.any { it.title != "No information" } })
            report.putString("result", "XMLTV downloaded and parsed; saved catalog matching checked, no writes")
            runner.finish(-1, report.apply { putBoolean("saved_files_unchanged", digests() == before) })
        } catch (error: Exception) {
            report.putString("failed_stage", stage)
            if (stage == "parse_xmltv") report.putString("xmltv_validation", when (error.message) {
                "No XMLTV programmes" -> "no_programmes"
                "Invalid XMLTV time" -> "invalid_timestamp"
                "Invalid XMLTV" -> "malformed_xml"
                "XML declarations unsupported" -> "doctype_or_entity_rejected"
                else -> "other_validation_failure"
            })
            report.putString("error_category", when (error) {
                is java.net.SocketTimeoutException -> "timeout"
                is java.net.UnknownHostException -> "dns"
                is javax.net.ssl.SSLException -> "tls"
                is java.net.ConnectException -> "connection"
                is org.xml.sax.SAXException -> "xml_syntax"
                is IllegalArgumentException -> "validation_rejected"
                is java.io.IOException -> "io"
                else -> "request_or_state_rejected"
            })
            report.putBoolean("saved_files_unchanged", digests() == before)
            runner.finish(0, report)
        }
    }
}
