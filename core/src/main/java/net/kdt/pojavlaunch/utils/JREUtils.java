// core — Java on Android, GNU LGPL v3 (see core/COPYING.LESSER).
// CubeXCraft, 2026: slimmed from Amethyst's JREUtils. The app describes the launch in a LaunchConfig (paths, heap,
// arguments, environment) instead of Amethyst's preferences and profiles; only MobileGlues is supported as the renderer.
// The natives keep their names and signatures (utils.c, egl_bridge.c, stdio_is.c).
package net.kdt.pojavlaunch.utils;

import android.content.Context;
import android.os.Build;
import android.system.Os;
import android.util.Log;

import com.oracle.dalvik.VMLauncher;

import net.kdt.pojavlaunch.Logger;
import net.kdt.pojavlaunch.Tools;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import site.cubexcraft.core.LaunchConfig;

public final class JREUtils {
    private static final String TAG = "CubexCore";

    public static String LD_LIBRARY_PATH;
    public static String jvmLibraryPath;

    private JREUtils() {}

    /** Library directory of the runtime: JDK 9+ keeps the .so files right in lib/. */
    private static File libDir(File javaHome) {
        File arch = new File(javaHome, "lib/aarch64");
        return arch.isDirectory() ? arch : new File(javaHome, "lib");
    }

    private static String findInLdLibPath(String libName) {
        if (LD_LIBRARY_PATH == null) return libName;
        for (String dir : LD_LIBRARY_PATH.split(":")) {
            File f = new File(dir, libName);
            if (f.exists() && f.isFile()) return f.getAbsolutePath();
        }
        return libName;
    }

    private static void collectLibs(File dir, List<File> out) {
        File[] list = dir.listFiles();
        if (list == null) return;
        for (File f : list) {
            if (f.isDirectory()) collectLibs(f, out);
            else if (f.getName().endsWith(".so")) out.add(f);
        }
    }

    private static void setEnvironment(Context ctx, LaunchConfig c) throws Throwable {
        String javaHome = c.javaHome.getAbsolutePath();
        File lib = libDir(c.javaHome);
        LD_LIBRARY_PATH = lib.getAbsolutePath() + "/jli:" + lib.getAbsolutePath()
                + ":/system/lib64:/vendor/lib64:/vendor/lib64/hw:" + c.nativeLibDir
                + (c.lwjglNativesDir != null ? ":" + c.lwjglNativesDir.getAbsolutePath() : "");

        Map<String, String> env = new LinkedHashMap<>();
        env.put("POJAV_NATIVEDIR", c.nativeLibDir);
        env.put("JAVA_HOME", javaHome);
        env.put("HOME", c.homeDir.getAbsolutePath());
        env.put("TMPDIR", c.cacheDir.getAbsolutePath());
        env.put("LD_LIBRARY_PATH", LD_LIBRARY_PATH);
        env.put("PATH", javaHome + "/bin:" + Os.getenv("PATH"));
        // MobileGlues: GL 4.x over the device's GLES 3.x; it also provides EGL to the bridge
        env.put("AMETHYST_RENDERER", "opengles_mobileglues");
        env.put("LIBGL_ES", "3");
        env.put("POJAVEXEC_EGL", c.glLibrary);
        // read by pojavInitOpenGL (egl_bridge.c); unset it was strcmp(NULL) → SIGSEGV at window creation (3.0.0-dev.5, 25.10)
        env.put("FORCE_VSYNC", "false");
        if (c.mobileGluesDir != null) env.put("MG_DIR_PATH", c.mobileGluesDir.getAbsolutePath());
        env.put("AWTSTUB_WIDTH", Integer.toString(c.windowWidth));
        env.put("AWTSTUB_HEIGHT", Integer.toString(c.windowHeight));
        env.put("DALVIK_APPLICATION", Tools.jObjectToString(ctx.getApplicationContext()));
        env.put("DALVIK_JAVAVM", String.valueOf(Tools.getJavaVMPointer()));
        env.putAll(c.env);
        for (Map.Entry<String, String> e : env.entrySet()) {
            Os.setenv(e.getKey(), e.getValue(), true);
        }

        File server = new File(lib, "server/libjvm.so");
        jvmLibraryPath = new File(lib, server.exists() ? "server" : "client").getAbsolutePath();
        setLdLibraryPath(jvmLibraryPath + ":" + LD_LIBRARY_PATH);
    }

    private static void loadRuntime(LaunchConfig c) {
        File lib = libDir(c.javaHome);
        dlopen(findInLdLibPath("libjli.so"));
        if (!dlopen("libjvm.so")) dlopen(jvmLibraryPath + "/libjvm.so");
        for (String name : new String[]{"libverify.so", "libjava.so", "libnet.so", "libnio.so", "libawt.so",
                "libawt_headless.so", "libfreetype.so", "libfontmanager.so"}) {
            dlopen(findInLdLibPath(name));
        }
        List<File> all = new ArrayList<>();
        collectLibs(lib, all);
        for (File f : all) dlopen(f.getAbsolutePath());
        dlopen(c.nativeLibDir + "/libopenal.so");
    }

    /** As in Amethyst: right after the environment, before the JVM libraries (MobileGlues reads MG_DIR_PATH on load). */
    private static void loadRenderer(LaunchConfig c) {
        if (!dlopen(c.glLibrary)) dlopen(c.nativeLibDir + "/" + c.glLibrary);
    }

    /** JVM options the bridge itself needs; the app's own options come before them and must not repeat these. */
    private static List<String> bridgeArgs(LaunchConfig c) {
        List<String> a = new ArrayList<>();
        String javaHome = c.javaHome.getAbsolutePath();
        a.add("-Xms" + c.heapMb + "M");
        a.add("-Xmx" + c.heapMb + "M");
        a.add("-Djava.home=" + javaHome);
        a.add("-Djava.io.tmpdir=" + c.cacheDir.getAbsolutePath());
        a.add("-Djna.boot.library.path=" + c.nativeLibDir);
        a.add("-Duser.home=" + c.homeDir.getAbsolutePath());
        a.add("-Duser.timezone=" + TimeZone.getDefault().getID());
        a.add("-Dos.name=Linux");
        a.add("-Dos.version=Android-" + Build.VERSION.RELEASE);
        a.add("-Dorg.lwjgl.opengl.libname=" + c.glLibrary);
        a.add("-Dorg.lwjgl.vulkan.libname=libvulkan.so");
        if (c.lwjglNativesDir != null) a.add("-Dorg.lwjgl.freetype.libname=" + c.lwjglNativesDir.getAbsolutePath() + "/libfreetype.so");
        a.add("-Dorg.lwjgl.spvc.libname=spirv-cross-c-shared");
        a.add("-Dorg.lwjgl.system.allocator=system");
        a.add("-Dglfwstub.windowWidth=" + c.windowWidth);
        a.add("-Dglfwstub.windowHeight=" + c.windowHeight);
        a.add("-Dglfwstub.initEgl=false");
        a.add("-Dlog4j2.formatMsgNoLookups=true");
        a.add("-Dfml.earlyprogresswindow=false");          // Forge/NeoForge early window needs a desktop GL context
        a.add("-Dloader.disable_forked_guis=true");
        a.add("-Djdk.lang.Process.launchMechanism=FORK");  // POSIX_SPAWN needs jspawnhelper, which Android cannot run
        a.add("-XX:ActiveProcessorCount=" + java.lang.Runtime.getRuntime().availableProcessors());
        return a;
    }

    /**
     * Runs the game JVM in this process and returns its exit code (blocks until the game exits). Call from a
     * dedicated thread after {@link #setupBridgeWindow} got the game Surface.
     */
    public static int launchJavaVM(Context ctx, LaunchConfig c) throws Throwable {
        if (c.logFile != null) {
            // stdio_is.c opens the log with O_WRONLY|O_TRUNC, no O_CREAT: the file must exist (Amethyst creates it beforehand)
            File parent = c.logFile.getParentFile();
            if (parent != null) parent.mkdirs();
            c.logFile.createNewFile();
            Logger.begin(c.logFile.getAbsolutePath());
        }
        setEnvironment(ctx, c);
        loadRenderer(c);
        loadRuntime(c);

        List<String> args = new ArrayList<>();
        args.add("java");   // argv[0]
        args.addAll(c.jvmArgs);
        args.addAll(bridgeArgs(c));
        args.addAll(c.programArgs);

        setupExitMethod(ctx.getApplicationContext());
        initializeHooks();
        chdir(c.gameDir.getAbsolutePath());
        Log.i(TAG, "launching JVM, " + args.size() + " args");
        int code = VMLauncher.launchJVM(args.toArray(new String[0]));
        Logger.appendToLog("Java exit code: " + code);
        return code;
    }

    public static native int chdir(String path);
    public static native boolean dlopen(String libPath);
    public static native void setLdLibraryPath(String ldLibraryPath);
    public static native void setupBridgeWindow(Object surface);
    public static native void releaseBridgeWindow();
    public static native void initializeHooks();
    public static native void setupExitMethod(Context context);
    /** AWT screen pixels for mods that open AWT windows. */
    public static native int[] renderAWTScreenFrame();

    static {
        System.loadLibrary("exithook");
        System.loadLibrary("pojavexec");
        System.loadLibrary("pojavexec_awt");
    }
}
