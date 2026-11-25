/*
 * Copyright (c) 2026-2027 CubeXCraft
 *
 * This file is part of CubeXCraft Android (core module),
 * licensed under the GNU Lesser General Public License v3.0.
 * See core/COPYING and core/COPYING.LESSER for details.
 */

package com.oracle.dalvik;

public final class VMLauncher {
	private VMLauncher() {
	}
	public static native int launchJVM(String[] args);
}
