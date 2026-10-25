// core — Java on Android, GNU LGPL v3 (see core/COPYING.LESSER).
// CubeXCraft, 2026: in Amethyst this is the game Activity; here only the static callbacks that awt_bridge.c looks up
// by this class name are kept, and they forward to the app's CoreHost.
package net.kdt.pojavlaunch;

import androidx.annotation.Keep;

import site.cubexcraft.core.Core;

@Keep
public final class MainActivity {
    private MainActivity() {}

    @Keep
    public static void openLink(String link) {
        Core.host().openLink(link);
    }

    @Keep
    public static void querySystemClipboard() {
        String text = Core.host().getClipboard();
        if (text == null || text.isEmpty()) AWTInputBridge.nativeClipboardReceived(null, null);
        else AWTInputBridge.nativeClipboardReceived(text, "plain");
    }

    @Keep
    public static void putClipboardData(String data, String mimeType) {
        if ("text/plain".equals(mimeType) || mimeType == null) Core.host().setClipboard(data);
    }
}
