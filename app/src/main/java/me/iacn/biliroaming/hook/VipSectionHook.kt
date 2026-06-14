package me.iacn.biliroaming.hook

import android.os.Bundle
import android.view.View
import me.iacn.biliroaming.BiliBiliPackage.Companion.instance
import me.iacn.biliroaming.utils.*


class VipSectionHook(classLoader: ClassLoader) : BaseHook(classLoader) {
    override fun startHook() {
        if (!sPrefs.getBoolean("hidden", false)
            || !sPrefs.getBoolean("remove_vip_section", false)
        ) return

        instance.homeUserCenterClass!!.hookAfterMethod(
            "onViewCreated",
            View::class.java,
            Bundle::class.java
        ) {
            val root = it.args[0] as? View ?: return@hookAfterMethod
            hideByModuleManager(it.thisObject)
            hideCurrentVipViews(root)
        }
    }

    private fun hideCurrentVipViews(root: View) {
        root.hideChildById("mine_vip_layout")
    }

    private fun View.findChildById(idName: String): View? {
        val id = getId(idName)
        return if (id == 0) null else findViewById(id)
    }

    private fun View.hideChildById(idName: String) {
        findChildById(idName)?.visibility = View.GONE
    }

    private fun hideByModuleManager(fragment: Any) = runCatching {
        val homeUserCenterClass = instance.homeUserCenterClass ?: return@runCatching false
        val vipModuleManager = homeUserCenterClass.declaredFields.single {
            // $mineVipModuleManager
            it.type.toString().contains("MineVipModuleManager")
        }.run {
            isAccessible = true
            get(fragment)
        }

        vipModuleManager::class.java.declaredMethods.single {
            // $method(isTeenager: Boolean)
            it.parameterCount == 1 &&
                    it.parameterTypes[0] == Boolean::class.java
        }.run {
            isAccessible = true
            invoke(vipModuleManager, true)
        }
        true
    }.getOrDefault(false)
}
