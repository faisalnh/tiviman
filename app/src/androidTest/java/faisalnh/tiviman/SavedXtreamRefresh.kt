package faisalnh.tiviman

import android.app.Instrumentation
import android.os.Bundle

/** Explicit user-authorized update only. No Activity/player or credential-bearing output. */
class SavedXtreamRefresh(private val runner: Instrumentation) {
    fun run() {
        val report = Bundle()
        var stage = "read saved Xtream account"
        try {
            val store = PlaylistStore(runner.targetContext)
            val before = store.snapshot()
            val config = before.provider ?: error("No account")
            check(config.type == SourceType.XTREAM && before.channels != null)
            stage = "download updated playlist"
            val channels = Providers(config).import()
            stage = "download and validate XMLTV"
            val guide = Epg.parse(ProviderHttp().get(Providers(config).xmltvUrl()))
            val matched = Epg.project(channels, guide, Epg.minute()).count { c -> c.programmes.any { it.title != "No information" } }
            check(matched > 0)
            stage = "save playlist and EPG atomically"
            store.refreshXtream(before.revision, config, channels, guide)
            val after = store.snapshot()
            check(after.revision == before.revision && after.tracks == before.tracks && after.display == before.display && after.history == before.history && after.favorites == before.favorites)
            check(store.loadEpg(after.revision)?.events?.size == guide.events.size)
            report.putInt("channels", channels.size)
            report.putInt("epg_matched_channels", matched)
            report.putInt("programmes", guide.events.size)
            report.putString("summary", "PASS: saved Xtream playlist and XMLTV updated atomically; stable stream-matched IDs and scoped stores preserved. No playback started.")
            runner.finish(-1, report)
        } catch (_: Exception) {
            report.putString("failure", "Could not $stage; private exception details suppressed. Inspect stored state before retrying.")
            runner.finish(0, report)
        }
    }
}
