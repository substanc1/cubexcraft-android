// © 2026 CubeXCraft (https://www.cubexcraft.site). Source-available, view only — see LICENSE.
package site.cubexcraft.mobile

import android.app.Application
import site.cubexcraft.core.Core
import site.cubexcraft.mobile.net.Api
import site.cubexcraft.mobile.net.Relay

/** Kept light on purpose: nothing here may delay the first frame (the game process starts through here too). */
class CubexApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Api.appVersion = packageManager.getPackageInfo(packageName, 0).versionName ?: "3.0.0"
        Relay.init(this)
        Core.setHost(Host(this))
    }
}
