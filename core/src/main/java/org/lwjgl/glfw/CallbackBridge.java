// core — Java on Android, GNU LGPL v3 (see core/COPYING.LESSER).
// CubeXCraft, 2026: slimmed from Amethyst's CallbackBridge — no SDL, no launcher preferences or Activity references;
// clipboard, links and DPI go through the app's CoreHost. The natives and the methods input_bridge_v3.c calls back
// (accessAndroidClipboard, notifyLauncher, onDirectInputEnable, onGrabStateChanged, getAndroidDPI) keep their signatures.
package org.lwjgl.glfw;

import android.util.DisplayMetrics;
import android.view.Choreographer;

import androidx.annotation.Keep;
import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.GrabListener;
import net.kdt.pojavlaunch.LwjglGlfwKeycode;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;

import dalvik.annotation.optimization.CriticalNative;
import site.cubexcraft.core.Core;

public class CallbackBridge {
    public static final Choreographer sChoreographer = Choreographer.getInstance();
    private static volatile boolean isGrabbing = false;
    private static final ArrayList<GrabListener> grabListeners = new ArrayList<>();

    public static final int CLIPBOARD_COPY = 2000;
    public static final int CLIPBOARD_PASTE = 2001;
    public static final int CLIPBOARD_OPEN = 2002;

    public static volatile int windowWidth, windowHeight;
    public static volatile int physicalWidth, physicalHeight;
    public static float mouseX, mouseY;
    public volatile static boolean holdingAlt, holdingCapslock, holdingCtrl, holdingNumlock, holdingShift;

    public static final ByteBuffer sGamepadButtonBuffer;
    public static final FloatBuffer sGamepadAxisBuffer;
    public static volatile boolean sGamepadDirectInput = false;

    public static void sendCursorPos(float x, float y) {
        mouseX = x;
        mouseY = y;
        nativeSendCursorPos(x, y);
    }

    /**
     * Key event into GLFW. keycode — GLFW key (0 = none); keychar — the typed character (0 = none). Control characters
     * are not sent as chars, otherwise Minecraft would try to type them into the chat.
     */
    public static void sendKeycode(int keycode, char keychar, int scancode, int modifiers, boolean isDown) {
        if (keycode != 0) nativeSendKey(keycode, scancode, isDown ? 1 : 0, modifiers);
        if (isDown && keychar != 0 && !Character.isISOControl(keychar)) {
            nativeSendCharMods(keychar, modifiers);
            nativeSendChar(keychar);
        }
    }

    /** Typed text from the keyboard (emoji included, hence no control-character filter). */
    public static void sendChar(char keychar, int modifiers) {
        nativeSendCharMods(keychar, modifiers);
        nativeSendChar(keychar);
    }

    public static void sendKeyPress(int keyCode, int modifiers, boolean status) {
        sendKeycode(keyCode, '\u0000', 0, modifiers, status);
    }

    public static void sendKeyPress(int keyCode) {
        sendKeyPress(keyCode, getCurrentMods(), true);
        sendKeyPress(keyCode, getCurrentMods(), false);
    }

    public static void sendMouseButton(int button, boolean status) {
        nativeSendMouseButton(button, status ? 1 : 0, getCurrentMods());
    }

    public static void sendScroll(float xoffset, float yoffset) {
        nativeSendScroll(xoffset, yoffset);
    }

    public static void sendUpdateWindowSize(int w, int h) {
        nativeSendScreenSize(w, h);
    }

    public static boolean isGrabbing() {
        return isGrabbing;
    }

    // Called from the game side
    @SuppressWarnings("unused")
    @Keep
    public static @Nullable String accessAndroidClipboard(int type, String copy) {
        switch (type) {
            case CLIPBOARD_COPY:
                Core.host().setClipboard(copy);
                return null;
            case CLIPBOARD_PASTE:
                String text = Core.host().getClipboard();
                return text == null ? "" : text;
            case CLIPBOARD_OPEN:
                Core.host().openLink(copy);
                return null;
            default:
                return null;
        }
    }

    /** Notifications from the game side. Amethyst uses them for SDL, which CubeX Mobile does not ship. */
    @SuppressWarnings("unused")
    @Keep
    public static boolean notifyLauncher(int type, int... action) {
        return false;
    }

    public static int getCurrentMods() {
        int mods = 0;
        if (holdingAlt) mods |= LwjglGlfwKeycode.GLFW_MOD_ALT;
        if (holdingCapslock) mods |= LwjglGlfwKeycode.GLFW_MOD_CAPS_LOCK;
        if (holdingCtrl) mods |= LwjglGlfwKeycode.GLFW_MOD_CONTROL;
        if (holdingNumlock) mods |= LwjglGlfwKeycode.GLFW_MOD_NUM_LOCK;
        if (holdingShift) mods |= LwjglGlfwKeycode.GLFW_MOD_SHIFT;
        return mods;
    }

    public static void setModifiers(int keyCode, boolean isDown) {
        switch (keyCode) {
            case LwjglGlfwKeycode.GLFW_KEY_LEFT_SHIFT: holdingShift = isDown; return;
            case LwjglGlfwKeycode.GLFW_KEY_LEFT_CONTROL: holdingCtrl = isDown; return;
            case LwjglGlfwKeycode.GLFW_KEY_LEFT_ALT: holdingAlt = isDown; return;
            case LwjglGlfwKeycode.GLFW_KEY_CAPS_LOCK: holdingCapslock = isDown; return;
            case LwjglGlfwKeycode.GLFW_KEY_NUM_LOCK: holdingNumlock = isDown;
        }
    }

    // Called from the game side when it reads a gamepad directly
    @SuppressWarnings("unused")
    @Keep
    private static void onDirectInputEnable() {
        sGamepadDirectInput = true;
    }

    // Called from the game side: the mouse got captured (in the world) or released (menus, chat)
    @SuppressWarnings("unused")
    @Keep
    private static void onGrabStateChanged(final boolean grabbing) {
        isGrabbing = grabbing;
        sChoreographer.postFrameCallbackDelayed((time) -> {
            if (isGrabbing != grabbing) return;   // changed again meanwhile
            synchronized (grabListeners) {
                for (GrabListener g : grabListeners) g.onGrabState(grabbing);
            }
        }, 16);
    }

    public static void addGrabListener(GrabListener listener) {
        synchronized (grabListeners) {
            listener.onGrabState(isGrabbing);
            grabListeners.add(listener);
        }
    }

    public static void removeGrabListener(GrabListener listener) {
        synchronized (grabListeners) {
            grabListeners.remove(listener);
        }
    }

    public static FloatBuffer createGamepadAxisBuffer() {
        ByteBuffer axisByteBuffer = nativeCreateGamepadAxisBuffer();
        // NOTE: hardcoded order (also in jre_lwjgl3glfw CallbackBridge)
        return axisByteBuffer.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
    }

    @Keep // glfwGetWindowContentScale
    private static float getAndroidDPI() {
        DisplayMetrics metrics = new DisplayMetrics();
        metrics.setToDefaults();
        return metrics.density * Core.host().renderScale();
    }

    @Keep @CriticalNative public static native void nativeSetUseInputStackQueue(boolean useInputStackQueue);
    @Keep @CriticalNative private static native boolean nativeSendChar(char codepoint);
    @Keep @CriticalNative private static native boolean nativeSendCharMods(char codepoint, int mods);
    @Keep @CriticalNative private static native void nativeSendKey(int key, int scancode, int action, int mods);
    @Keep @CriticalNative private static native void nativeSendCursorPos(float x, float y);
    @Keep @CriticalNative private static native void nativeSendMouseButton(int button, int action, int mods);
    @Keep @CriticalNative private static native void nativeSendScroll(double xoffset, double yoffset);
    @Keep @CriticalNative private static native void nativeSendScreenSize(int width, int height);
    public static native void nativeSetWindowAttrib(int attrib, int value);
    private static native ByteBuffer nativeCreateGamepadButtonBuffer();
    private static native ByteBuffer nativeCreateGamepadAxisBuffer();

    static {
        System.loadLibrary("pojavexec");
        sGamepadButtonBuffer = nativeCreateGamepadButtonBuffer();
        sGamepadAxisBuffer = createGamepadAxisBuffer();
    }
}
