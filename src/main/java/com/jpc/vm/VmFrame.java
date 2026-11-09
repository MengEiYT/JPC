package com.jpc.vm;

import java.util.Arrays;

/**
 * A snapshot of a virtual screen. {@link #pixels} are ARGB (alpha always 0xFF), row by row.
 *
 * <p>{@link #texels} is the same picture in the byte order of an RGBA8888 texture (a little-endian int is then
 * 0xAABBGGRR). The built-in emulator fills it on its own thread, so the game's render thread can hand it to the GPU
 * with a single memory copy. It is null for frames that only have {@link #pixels} (the VNC link).
 */
public final class VmFrame {
	public final int width;
	public final int height;
	public final int[] pixels;
	/** Texture-order copy of {@link #pixels}, or null. */
	public int[] texels;

	public VmFrame(int width, int height) {
		this.width = width;
		this.height = height;
		this.pixels = new int[width * height];
		Arrays.fill(pixels, 0xFF000000);
	}

	/** 0xAARRGGBB to 0xAABBGGRR (and back: the swap is its own inverse). */
	public static int swapRedBlue(int argb) {
		return (argb & 0xFF00FF00) | ((argb >> 16) & 0xFF) | ((argb & 0xFF) << 16);
	}
}
