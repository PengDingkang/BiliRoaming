package me.iacn.biliroaming.hook

import me.iacn.biliroaming.BiliBiliPackage.Companion.instance
import me.iacn.biliroaming.utils.Log
import me.iacn.biliroaming.utils.OfficialVideoDiagnosticsHelper.activeSessionUposHost
import me.iacn.biliroaming.utils.OfficialVideoDiagnosticsHelper.replaceMediaHostInUrl
import me.iacn.biliroaming.utils.UposReplaceHelper.hostForLog
import me.iacn.biliroaming.utils.callStaticMethod
import me.iacn.biliroaming.utils.getObjectField
import me.iacn.biliroaming.utils.hookBeforeAllMethods
import me.iacn.biliroaming.utils.setObjectField

class OfficialVideoDiagnosticsHook(classLoader: ClassLoader) : BaseHook(classLoader) {
    override fun startHook() {
        val interceptorClass = instance.defaultRequestInterceptClass ?: return
        val interceptMethod = instance.interceptMethod() ?: return
        interceptorClass.hookBeforeAllMethods(interceptMethod) { param ->
            val target = activeSessionUposHost() ?: return@hookBeforeAllMethods
            val request = param.args[0] ?: return@hookBeforeAllMethods
            val urlField = instance.urlField() ?: return@hookBeforeAllMethods
            val httpUrl = request.getObjectField(urlField) ?: return@hookBeforeAllMethods
            val url = httpUrl.toString()
            val replaced = replaceMediaHostInUrl(url, target)
            if (replaced == url) return@hookBeforeAllMethods
            val newHttpUrl = instance.httpUrlClass?.callStaticMethod(
                instance.httpUrlParseMethod(),
                replaced
            ) ?: return@hookBeforeAllMethods
            request.setObjectField(urlField, newHttpUrl)
            Log.d("official diagnostics CDN download request ${url.hostForLog()} -> $target")
        }
    }
}
