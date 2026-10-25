// core — Java on Android, GNU LGPL v3 (see core/COPYING.LESSER). Added by CubeXCraft, 2026.
package site.cubexcraft.core;

import android.content.Context;

/**
 * What the core needs from the app that embeds it. The bridge classes keep the names the native code looks up
 * (net.kdt.pojavlaunch.MainActivity, ExitActivity, org.lwjgl.glfw.CallbackBridge); they forward here, so the app
 * decides how to show links, the clipboard and the exit screen. Set once with {@link Core#setHost} before launching.
 */
public interface CoreHost {
    /** Open a link or a file path the game asked for (Minecraft «Open link», screenshots folder). */
    void openLink(String link);

    /** Text the game copied. */
    void setClipboard(String text);

    /** Text for the game to paste, or "" when there is none. Called from the game threads. */
    String getClipboard();

    /** Scale of the game framebuffer to the screen (render scale), used for the window content scale. */
    float renderScale();

    /** The JVM exited (code) or died on a signal; the app shows its own screen. Called from the exiting thread. */
    void onGameExit(Context context, int code, boolean isSignal);
}
