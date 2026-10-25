// core — Java on Android, GNU LGPL v3 (see core/COPYING.LESSER).
// CubeXCraft, 2026: Amethyst's Tools is the launcher's utility class; here only the two natives utils.c implements
// under this class name are kept.
package net.kdt.pojavlaunch;

import androidx.annotation.Keep;

@Keep
public final class Tools {
    private Tools() {}

    /** Global JNI reference to the object as a pointer string (for DALVIK_APPLICATION). */
    public static native String jObjectToString(Object object);

    /** The app's JavaVM pointer (for DALVIK_JAVAVM). */
    public static native long getJavaVMPointer();
}
