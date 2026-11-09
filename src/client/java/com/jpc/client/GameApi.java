package com.jpc.client;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;

import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.Minecraft;

/**
 * The two game internals the VM screen needs that are not part of a stable public API: the raw memory address of
 * a {@link NativeImage}, and whether the game window has focus. They are looked up by name once, when this class
 * loads, so a game update that moves them makes the mod fall back to a slower path instead of failing to start.
 */
final class GameApi {
	private static final System.Logger LOG = System.getLogger("jpc.client");

	private static final MethodHandle PIXEL_POINTER = findPixelPointer();
	private static final MethodHandle WINDOW_ACTIVE = findWindowActive();
	private static volatile boolean pointerBroken;

	private GameApi() {
	}

	/** True if {@link #pixelPointer} can work at all. */
	static boolean hasPixelPointer() {
		return PIXEL_POINTER != null && !pointerBroken;
	}

	/**
	 * Address of the image's pixel memory (width * height * 4 bytes, rows back to back, bytes R G B A), or 0
	 * if this game version does not offer it.
	 */
	static long pixelPointer(NativeImage image) {
		if (!hasPixelPointer()) {
			return 0L;
		}
		try {
			return (long) PIXEL_POINTER.invokeExact(image);
		} catch (Throwable t) {
			pointerBroken = true;
			LOG.log(System.Logger.Level.WARNING, "Could not read the screen texture's memory address, using the slow copy: " + t);
			return 0L;
		}
	}

	/** Whether the game window has focus. True when that cannot be told. */
	static boolean windowActive(Minecraft minecraft) {
		if (WINDOW_ACTIVE == null || minecraft == null) {
			return true;
		}
		try {
			return (boolean) WINDOW_ACTIVE.invokeExact(minecraft);
		} catch (Throwable t) {
			return true;
		}
	}

	private static MethodHandle findPixelPointer() {
		MethodHandles.Lookup lookup = MethodHandles.lookup();
		try {
			return lookup.findVirtual(NativeImage.class, "getPointer", MethodType.methodType(long.class));
		} catch (ReflectiveOperationException | RuntimeException e) {
			// not public or not there: try the field behind it
		}
		try {
			Field f = NativeImage.class.getDeclaredField("pixels");
			if (f.getType() == long.class) {
				f.setAccessible(true);
				return lookup.unreflectGetter(f);
			}
		} catch (ReflectiveOperationException | RuntimeException e) {
			// nothing else to try
		}
		LOG.log(System.Logger.Level.WARNING, "NativeImage.getPointer() not found: the VM screen will use the slow per-pixel copy");
		return null;
	}

	private static MethodHandle findWindowActive() {
		try {
			return MethodHandles.lookup().findVirtual(Minecraft.class, "isWindowActive", MethodType.methodType(boolean.class));
		} catch (ReflectiveOperationException | RuntimeException e) {
			LOG.log(System.Logger.Level.INFO, "Minecraft.isWindowActive() not found: the computer will not pause when the game window loses focus");
			return null;
		}
	}
}
