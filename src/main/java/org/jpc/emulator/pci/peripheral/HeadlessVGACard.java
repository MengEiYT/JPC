package org.jpc.emulator.pci.peripheral;

/**
 * VGA card that draws into a plain int[] (0x00RRGGBB per pixel) instead of an AWT image.
 * Written for the JPC mod so the emulator needs no java.awt at all.
 *
 * Call {@link #updateDisplay()} from the emulator thread, then read {@link #getDisplayBuffer()}.
 */
public final class HeadlessVGACard extends VGACard {
    private int[] rawImageData = new int[0];
    private int width;
    private int height;
    private volatile int resizeCount;
    // Rows redrawn since the last clearDirty(); lets the game copy only what changed.
    private int dirtyFirst = Integer.MAX_VALUE;
    private int dirtyLast = -1;

    public HeadlessVGACard() {
    }

    @Override
    protected int rgbToPixel(int red, int green, int blue) {
        return ((0xFF & red) << 16) | ((0xFF & green) << 8) | (0xFF & blue);
    }

    @Override
    public void resizeDisplay(int width, int height) {
        if (width == 0 || height == 0) {
            return;
        }
        this.width = width;
        this.height = height;
        this.rawImageData = new int[width * height];
        this.resizeCount++;
        this.dirtyFirst = 0;
        this.dirtyLast = height - 1;
    }

    @Override
    public int getDisplayWidth() {
        return width;
    }

    @Override
    public int getDisplayHeight() {
        return height;
    }

    /** Pixels of the current screen, row by row, size getDisplayWidth() * getDisplayHeight(). */
    @Override
    public int[] getDisplayBuffer() {
        return rawImageData;
    }

    /** Increases every time the screen size changed. */
    public int resizeCount() {
        return resizeCount;
    }

    @Override
    protected void dirtyDisplayRegion(int x, int y, int w, int h) {
        if (h <= 0) {
            return;
        }
        if (y < dirtyFirst) {
            dirtyFirst = y;
        }
        if (y + h - 1 > dirtyLast) {
            dirtyLast = y + h - 1;
        }
    }

    /** First row redrawn since {@link #clearDirty()}, or a value above {@link #dirtyLastRow()} if none. */
    public int dirtyFirstRow() {
        return Math.max(0, dirtyFirst);
    }

    /** Last row redrawn since {@link #clearDirty()}, or -1 if none. */
    public int dirtyLastRow() {
        return Math.min(height - 1, dirtyLast);
    }

    public void clearDirty() {
        dirtyFirst = Integer.MAX_VALUE;
        dirtyLast = -1;
    }

    @Override
    public void saveScreenshot() {
        // not supported
    }
}
