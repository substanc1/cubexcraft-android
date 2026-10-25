// core — Java on Android, GNU LGPL v3 (see core/COPYING.LESSER). Added by CubeXCraft, 2026.
package site.cubexcraft.core;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Everything one game launch needs; filled by the app, applied by {@link net.kdt.pojavlaunch.utils.JREUtils}. */
public final class LaunchConfig {
    /** Unpacked Java runtime (has bin/, lib/, lib/server/libjvm.so). */
    public File javaHome;
    /** The app's nativeLibraryDir (libpojavexec.so, libmobileglues.so, libopenal.so…). */
    public String nativeLibDir;
    /** LWJGL natives for this device (liblwjgl.so, libfreetype.so…). */
    public File lwjglNativesDir;
    /** Working directory of the game = .minecraft. */
    public File gameDir;
    /** HOME and user.home of the game. */
    public File homeDir;
    /** Temp files and shader caches. */
    public File cacheDir;
    /** Native stdout/stderr of the JVM go here (truncated on each launch). */
    public File logFile;
    /** GL library LWJGL loads, by file name inside nativeLibDir; MobileGlues by default. */
    public String glLibrary = "libmobileglues.so";
    /** Directory for MobileGlues' own config and caches (MG_DIR_PATH). */
    public File mobileGluesDir;
    /** Window size the game starts with (framebuffer pixels, after render scale). */
    public int windowWidth, windowHeight;
    /** Java heap, MB (-Xms = -Xmx). */
    public int heapMb;
    /** JVM options of the app (GC, -D…), without -Xms/-Xmx: those come from heapMb. */
    public final List<String> jvmArgs = new ArrayList<>();
    /** -cp, main class and game arguments, in order, after the JVM options. */
    public final List<String> programArgs = new ArrayList<>();
    /** Extra environment for the game process (applied last, so it can override). */
    public final Map<String, String> env = new LinkedHashMap<>();
}
