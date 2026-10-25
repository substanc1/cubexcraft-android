// core — Java on Android, GNU LGPL v3 (see core/COPYING.LESSER). Added by CubeXCraft, 2026.
package site.cubexcraft.core;

/** Holds the app's {@link CoreHost} for the bridge classes that the native code calls back. */
public final class Core {
    private static volatile CoreHost host;

    private Core() {}

    public static void setHost(CoreHost h) {
        host = h;
    }

    /** Never null once the app set it; the bridge only calls back after a launch, which requires a host. */
    public static CoreHost host() {
        CoreHost h = host;
        if (h == null) throw new IllegalStateException("Core.setHost() was not called");
        return h;
    }
}
