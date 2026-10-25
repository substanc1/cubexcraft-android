// core — Java on Android, GNU LGPL v3 (see core/COPYING.LESSER).
// CubeXCraft, 2026: in Amethyst this is an Activity with the exit dialog; here only showExitMessage, which the exit trap
// in stdio_is.c calls by this class name, is kept, and the app's CoreHost shows its own screen.
package net.kdt.pojavlaunch;

import android.content.Context;

import androidx.annotation.Keep;

import site.cubexcraft.core.Core;

@Keep
public final class ExitActivity {
    private ExitActivity() {}

    @Keep
    public static void showExitMessage(Context ctx, int code, boolean isSignal) {
        Core.host().onGameExit(ctx, code, isSignal);
    }
}
