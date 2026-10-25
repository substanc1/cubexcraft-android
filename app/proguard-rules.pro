# © 2026 CubeXCraft. Source-available, view only — see LICENSE.
# The native bridge finds these classes and methods by name (JNI): keep them as they are.
-keep class net.kdt.pojavlaunch.** { *; }
-keep class org.lwjgl.glfw.CallbackBridge { *; }
-keep class com.oracle.dalvik.VMLauncher { *; }
-keep class site.cubexcraft.core.** { *; }
-keepclasseswithmembernames class * { native <methods>; }
-dontwarn org.slf4j.**
