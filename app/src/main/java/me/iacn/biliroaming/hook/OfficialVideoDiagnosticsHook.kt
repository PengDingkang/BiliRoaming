package me.iacn.biliroaming.hook

import me.iacn.biliroaming.BiliBiliPackage.Companion.instance
import me.iacn.biliroaming.BuildConfig
import me.iacn.biliroaming.utils.Log
import me.iacn.biliroaming.utils.OfficialVideoDiagnosticsHelper.LOG_PREFIX
import me.iacn.biliroaming.utils.OfficialVideoDiagnosticsHelper.activeSessionUposHost
import me.iacn.biliroaming.utils.OfficialVideoDiagnosticsHelper.describeUrlDecision
import me.iacn.biliroaming.utils.OfficialVideoDiagnosticsHelper.processName
import me.iacn.biliroaming.utils.OfficialVideoDiagnosticsHelper.replaceMediaHostInUrl
import me.iacn.biliroaming.utils.OfficialVideoDiagnosticsHelper.sessionDebugSummary
import me.iacn.biliroaming.utils.OfficialVideoDiagnosticsHelper.urlForLog
import me.iacn.biliroaming.utils.UposReplaceHelper.hostForLog
import me.iacn.biliroaming.utils.callStaticMethod
import me.iacn.biliroaming.utils.getObjectField
import me.iacn.biliroaming.utils.hookBeforeAllMethods
import me.iacn.biliroaming.utils.setObjectField

class OfficialVideoDiagnosticsHook(classLoader: ClassLoader) : BaseHook(classLoader) {
    private val loggedKeys = LinkedHashSet<String>()

    override fun startHook() {
        val interceptorClass = instance.defaultRequestInterceptClass
        val interceptMethod = instance.interceptMethod()
        if (interceptorClass == null || interceptMethod == null) {
            Log.d(
                "$LOG_PREFIX hook skipped process=${processName()} " +
                        "interceptor=${interceptorClass?.name} method=$interceptMethod " +
                        "session=${sessionDebugSummary()}"
            )
            return
        }
        Log.d(
            "$LOG_PREFIX hook installed process=${processName()} " +
                    "interceptor=${interceptorClass.name} method=$interceptMethod " +
                    "session=${sessionDebugSummary()}"
        )
        interceptorClass.hookBeforeAllMethods(interceptMethod) { param ->
            val target = activeSessionUposHost() ?: run {
                logLimited("no_session") {
                    "$LOG_PREFIX request skip reason=no_active_session ${sessionDebugSummary()}"
                }
                return@hookBeforeAllMethods
            }
            val request = param.args[0] ?: run {
                logLimited("no_request") {
                    "$LOG_PREFIX request skip reason=no_request target=$target"
                }
                return@hookBeforeAllMethods
            }
            val urlField = instance.urlField() ?: run {
                logLimited("no_url_field") {
                    "$LOG_PREFIX request skip reason=no_url_field target=$target"
                }
                return@hookBeforeAllMethods
            }
            val httpUrl = request.getObjectField(urlField) ?: run {
                logLimited("no_http_url") {
                    "$LOG_PREFIX request skip reason=no_http_url target=$target"
                }
                return@hookBeforeAllMethods
            }
            val url = httpUrl.toString()
            val decision = describeUrlDecision(url, target)
            val replaced = replaceMediaHostInUrl(url, target)
            if (replaced == url) {
                if (url.looksRelevantForDiagnosticsLog()) {
                    logLimited("skip:${urlForLog(url)}") {
                        "$LOG_PREFIX request $decision"
                    }
                }
                return@hookBeforeAllMethods
            }
            val newHttpUrl = instance.httpUrlClass?.callStaticMethod(
                instance.httpUrlParseMethod(),
                replaced
            ) ?: run {
                logLimited("parse_new_url_failed") {
                    "$LOG_PREFIX request skip reason=parse_new_url_failed $decision"
                }
                return@hookBeforeAllMethods
            }
            request.setObjectField(urlField, newHttpUrl)
            Log.d("$LOG_PREFIX request replaced from=${url.hostForLog()} to=$target original=${urlForLog(url)}")
        }
    }

    private fun String.looksRelevantForDiagnosticsLog(): Boolean =
        contains("bilivideo", true) ||
                contains("acgvideo", true) ||
                contains("akamaized", true) ||
                contains("mountaintoys", true) ||
                contains("playurl", true) ||
                contains("video-diagnostics", true)

    private inline fun logLimited(key: String, message: () -> String) {
        if (!BuildConfig.DEBUG) return
        synchronized(loggedKeys) {
            if (loggedKeys.size >= 80 || !loggedKeys.add(key)) return
        }
        Log.d(message())
    }
}
