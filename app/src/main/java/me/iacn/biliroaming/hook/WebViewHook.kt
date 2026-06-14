package me.iacn.biliroaming.hook

import android.graphics.Bitmap
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import me.iacn.biliroaming.BuildConfig
import me.iacn.biliroaming.XposedInit.Companion.moduleRes
import me.iacn.biliroaming.utils.*
import me.iacn.biliroaming.utils.OfficialVideoDiagnosticsHelper.LOG_PREFIX
import me.iacn.biliroaming.utils.OfficialVideoDiagnosticsHelper.describeUrlDecision
import me.iacn.biliroaming.utils.OfficialVideoDiagnosticsHelper.isDiagnosticsPage
import me.iacn.biliroaming.utils.OfficialVideoDiagnosticsHelper.mediaHostsInTextForLog
import me.iacn.biliroaming.utils.OfficialVideoDiagnosticsHelper.replaceMediaHostInText
import me.iacn.biliroaming.utils.OfficialVideoDiagnosticsHelper.replaceMediaHostInUrl
import me.iacn.biliroaming.utils.OfficialVideoDiagnosticsHelper.selectedUposHost
import me.iacn.biliroaming.utils.OfficialVideoDiagnosticsHelper.sessionDebugSummary
import me.iacn.biliroaming.utils.OfficialVideoDiagnosticsHelper.urlForLog
import me.iacn.biliroaming.utils.UposReplaceHelper.hostForLog


class WebViewHook(classLoader: ClassLoader) : BaseHook(classLoader) {
    private val hookedClient = HashSet<Class<*>>()
    private val diagnosticsLogs = LinkedHashSet<String>()

    private val jsHooker = object : Any() {
        @Suppress("UNUSED")
        @JavascriptInterface
        fun hook(url: String, text: String): String {
            return this@WebViewHook.hook(url, text)
        }

        @Suppress("UNUSED")
        @JavascriptInterface
        fun hookPage(pageUrl: String, url: String, text: String): String {
            return this@WebViewHook.hook(pageUrl, url, text)
        }

        @Suppress("UNUSED")
        @JavascriptInterface
        fun hookRequest(pageUrl: String, url: String): String {
            return this@WebViewHook.hookRequest(pageUrl, url)
        }

        @Suppress("UNUSED")
        @JavascriptInterface
        fun saveImage(url: String) {
            MainScope().launch(Dispatchers.IO) {
                CommentImageHook.saveImage(url)
            }
        }
    }

    private val js by lazy {
        runCatchingOrNull {
            moduleRes.assets.open("xhook.js")
                .use { it.bufferedReader().readText() }
        } ?: ""
    }

    override fun startHook() {
        Log.d("startHook: WebView")
        WebView::class.java.hookBeforeMethod(
            "setWebViewClient", WebViewClient::class.java
        ) { param ->
            val clazz = param.args[0].javaClass
            (param.thisObject as WebView).addJavascriptInterface(jsHooker, "hooker")
            if (hookedClient.contains(clazz)) return@hookBeforeMethod
            try {
                clazz.getDeclaredMethod(
                    "onPageStarted",
                    WebView::class.java, String::class.java, Bitmap::class.java
                ).hookBeforeMethod { p ->
                    val webView = p.args[0] as WebView
                    webView.evaluateJavascript("""(function(){$js})()""".trimMargin(), null)
                }
                if (sPrefs.getBoolean("save_comment_image", false)) {
                    clazz.getDeclaredMethod(
                        "onPageFinished",
                        WebView::class.java, String::class.java
                    ).hookBeforeMethod { p ->
                        val webView = p.args[0] as WebView
                        val url = p.args[1] as String
                        if (url.startsWith("https://www.bilibili.com/h5/note-app/view")) {
                            webView.evaluateJavascript(
                                """(function(){for(var i=0;i<document.images.length;++i){if(document.images[i].className==='img-preview'){document.images[i].addEventListener("contextmenu",(e)=>{hooker.saveImage(e.target.currentSrc);})}}})()""",
                                null
                            )
                        }
                    }
                }
                hookedClient.add(clazz)
                Log.d("hook webview $clazz")
            } catch (_: NoSuchMethodException) {
            }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun hook(url: String, text: String): String {
        return text
    }

    fun hook(pageUrl: String, url: String, text: String): String {
        if (!isDiagnosticsPage(pageUrl)) return hook(url, text)
        val target = selectedUposHost() ?: run {
            logDiagnosticsLimited(
                "web_no_target",
                "$LOG_PREFIX web response skip reason=no_target page=${urlForLog(pageUrl)} " +
                        "url=${urlForLog(url)} session=${sessionDebugSummary()}"
            )
            return text
        }
        val replaced = replaceMediaHostInText(text, target)
        val hosts = mediaHostsInTextForLog(text)
        if (replaced != text) {
            Log.d(
                "$LOG_PREFIX web response replaced url=${url.hostForLog()} target=$target hosts=$hosts"
            )
        } else if (hosts != "none" || url.looksRelevantForDiagnosticsLog()) {
            logDiagnosticsLimited(
                "web_response:${urlForLog(url)}:$hosts",
                "$LOG_PREFIX web response skip url=${urlForLog(url)} target=$target hosts=$hosts len=${text.length}"
            )
        }
        return replaced
    }

    fun hookRequest(pageUrl: String, url: String): String {
        if (!isDiagnosticsPage(pageUrl)) return url
        val target = selectedUposHost() ?: run {
            logDiagnosticsLimited(
                "web_request_no_target",
                "$LOG_PREFIX web request skip reason=no_target page=${urlForLog(pageUrl)} " +
                        "url=${urlForLog(url)} session=${sessionDebugSummary()}"
            )
            return url
        }
        return replaceMediaHostInUrl(url, target).also { replaced ->
            if (replaced != url) {
                Log.d("$LOG_PREFIX web request replaced ${url.hostForLog()} -> $target original=${urlForLog(url)}")
            } else if (url.looksRelevantForDiagnosticsLog()) {
                logDiagnosticsLimited(
                    "web_request:${urlForLog(url)}",
                    "$LOG_PREFIX web request ${describeUrlDecision(url, target)}"
                )
            }
        }
    }

    private fun String.looksRelevantForDiagnosticsLog(): Boolean =
        contains("bilivideo", true) ||
                contains("acgvideo", true) ||
                contains("akamaized", true) ||
                contains("mountaintoys", true) ||
                contains("playurl", true) ||
                contains("video-diagnostics", true)

    private fun logDiagnosticsLimited(key: String, message: String) {
        synchronized(diagnosticsLogs) {
            if (diagnosticsLogs.size >= 80 || !diagnosticsLogs.add(key)) return
        }
        Log.d(message)
    }

    override fun lateInitHook() {
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
    }
}
