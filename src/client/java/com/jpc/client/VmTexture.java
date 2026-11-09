package com.jpc.client;

import java.util.Arrays;

import com.mojang.blaze3d.platform.NativeImage;

import org.lwjgl.system.MemoryUtil;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import com.jpc.JpcMod;
import com.jpc.vm.VmFrame;

/** Keeps the VM's pixels in a GPU texture that the screen can draw. */
final class VmTexture {
	private static final System.Logger LOG = System.getLogger("jpc.client");

	/** Upload at most ~30 times per second. */
	private static final long MIN_UPLOAD_INTERVAL_NS = 33_000_000L;

	private final Identifier id = JpcMod.id("vm_screen");

	private DynamicTexture texture;
	private NativeImage image;
	private int width;
	private int height;
	private long lastVersion = -1;
	private long lastUploadNs;

	/** Texture-order copy made on the render thread, only for pictures that did not come with one (VNC). */
	private int[] converted;
	/** Slow path only: what the texture holds, so unchanged rows can be skipped. */
	private int[] shadow;

	Identifier id() {
		return id;
	}

	/**
	 * Makes sure the texture exists with the frame's size and copies new pixels in.
	 *
	 * @return true when the texture can be drawn
	 */
	boolean update(VmFrame frame, long version) {
		if (frame == null) {
			return false;
		}

		if (texture == null || frame.width != width || frame.height != height) {
			create(frame.width, frame.height);
		}

		long now = System.nanoTime();
		if (version != lastVersion && now - lastUploadNs >= MIN_UPLOAD_INTERVAL_NS) {
			if (copyIntoTexture(frame)) {
				texture.upload();
			}
			lastVersion = version;
			lastUploadNs = now;
		}
		return true;
	}

	private void create(int w, int h) {
		image = new NativeImage(w, h, false);
		texture = new DynamicTexture(() -> "jpc_vm_screen", image);
		// Registering under the same id replaces the old (differently sized) texture.
		Minecraft.getInstance().getTextureManager().register(id, texture);
		width = w;
		height = h;
		shadow = null;
		lastVersion = -1;
		LOG.log(System.Logger.Level.INFO, "VM screen " + w + "x" + h + ": "
				+ (GameApi.hasPixelPointer() ? "texture filled by one native memory copy" : "texture filled pixel by pixel (slow)"));
	}

	/**
	 * Puts the picture into the texture's memory. The emulator thread already prepared it in the texture's own
	 * byte order, so on the render thread this is a single bulk copy from the Java array into native memory.
	 *
	 * @return true if the texture changed and must be uploaded
	 */
	private boolean copyIntoTexture(VmFrame frame) {
		int count = width * height;
		long pointer = GameApi.pixelPointer(image);
		if (pointer == 0L) {
			return copyChangedRows(frame);
		}
		int[] texels = frame.texels;
		if (texels == null || texels.length < count) {
			texels = toTextureOrder(frame.pixels, count);
		}
		// LWJGL's memory view of the texture; put(int[]) is one memcpy into it (MemoryUtil.memCopy needs a native source).
		MemoryUtil.memIntBuffer(pointer, count).put(texels, 0, count);
		return true;
	}

	/** ARGB to the texture's byte order, for pictures that only exist as ARGB. */
	private int[] toTextureOrder(int[] argb, int count) {
		if (converted == null || converted.length < count) {
			converted = new int[count];
		}
		for (int i = 0; i < count; i++) {
			converted[i] = VmFrame.swapRedBlue(argb[i]);
		}
		return converted;
	}

	/** Fallback when the texture's address is unavailable: pixel by pixel, skipping rows that did not change. */
	private boolean copyChangedRows(VmFrame frame) {
		int[] px = frame.pixels;
		boolean everything = shadow == null || shadow.length != px.length;
		if (everything) {
			shadow = new int[px.length];
		}
		boolean changed = false;
		for (int y = 0; y < height; y++) {
			int row = y * width;
			if (!everything && Arrays.equals(px, row, row + width, shadow, row, row + width)) {
				continue;
			}
			for (int x = 0; x < width; x++) {
				// ARGB in; if red and blue look swapped in game, this is the line to change.
				image.setPixel(x, y, px[row + x]);
			}
			System.arraycopy(px, row, shadow, row, width);
			changed = true;
		}
		return changed;
	}
}
