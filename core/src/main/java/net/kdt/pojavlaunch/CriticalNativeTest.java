/*
 * Copyright (c) 2026-2027 CubeXCraft
 *
 * This file is part of CubeXCraft Android (core module),
 * licensed under the GNU Lesser General Public License v3.0.
 * See core/COPYING and core/COPYING.LESSER for details.
 */

package net.kdt.pojavlaunch;

import androidx.annotation.Keep;

import dalvik.annotation.optimization.CriticalNative;

@Keep
public class CriticalNativeTest {
    @CriticalNative
    public static native void testCriticalNative(int arg0, int arg1);
    public static void invokeTest() {
        testCriticalNative(0, 0);
    }
}
