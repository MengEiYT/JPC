package com.jpc.vm;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Small RFB (VNC) client. It only does what the VM screen needs:
 * no password, Raw + CopyRect + DesktopSize, keyboard and pointer input.
 * That is enough for QEMU's built-in VNC server on localhost.
 */
public final class VncClient implements VmDisplay {
	private static final System.Logger LOG = System.getLogger("jpc.vm");

	/** RFB pointer-event bits for the scroll wheel (buttons 4 and 5). */
	private static final int WHEEL_UP = 8;
	private static final int WHEEL_DOWN = 16;

	private final String host;
	private final int port;
	private final Object writeLock = new Object();
	private final AtomicLong frameVersion = new AtomicLong();

	private Socket socket;
	private DataInputStream in;
	private DataOutputStream out;
	private Thread reader;

	private volatile VmFrame frame;
	private volatile boolean running;
	private volatile String serverName = "";
	private volatile String error;

	public VncClient(String host, int port) {
		this.host = host;
		this.port = port;
	}

	/** Connects, does the handshake and starts the reader thread. */
	public void connect(int timeoutMs) throws IOException {
		socket = new Socket();
		socket.connect(new InetSocketAddress(host, port), timeoutMs);
		socket.setTcpNoDelay(true);
		socket.setSoTimeout(timeoutMs); // only for the handshake
		in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 1 << 16));
		out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), 1 << 12));

		handshake();

		socket.setSoTimeout(0);
		running = true;
		reader = new Thread(this::readLoop, "jpc-vnc-reader");
		reader.setDaemon(true);
		reader.start();
	}

	// -------------------------------------------------------------- accessors

	/** Current screen, or null before the first connect. */
	@Override
	public VmFrame frame() {
		return frame;
	}

	@Override
	public long frameVersion() {
		return frameVersion.get();
	}

	@Override
	public boolean isConnected() {
		return running;
	}

	/** Name the VNC server reports, e.g. "QEMU (vm)". */
	@Override
	public String serverName() {
		return serverName;
	}

	@Override
	public String error() {
		return error;
	}

	// ------------------------------------------------------------------ input

	@Override
	public void sendKey(int glfwKey, boolean down) {
		int keysym = KeyMap.toKeysym(glfwKey);
		if (keysym != 0) {
			sendKeysym(keysym, down);
		}
	}

	/** @param keysym X11 keysym (see {@link KeyMap}) */
	public void sendKeysym(int keysym, boolean down) {
		synchronized (writeLock) {
			if (!running) {
				return;
			}
			try {
				out.writeByte(4);
				out.writeByte(down ? 1 : 0);
				out.writeShort(0);
				out.writeInt(keysym);
				out.flush();
			} catch (IOException e) {
				fail(e);
			}
		}
	}

	@Override
	public void sendPointer(int buttons, int x, int y) {
		synchronized (writeLock) {
			if (!running) {
				return;
			}
			try {
				out.writeByte(5);
				out.writeByte(buttons);
				out.writeShort(Math.max(0, x));
				out.writeShort(Math.max(0, y));
				out.flush();
			} catch (IOException e) {
				fail(e);
			}
		}
	}

	@Override
	public void sendWheel(boolean up, int buttons, int x, int y) {
		int wheel = up ? WHEEL_UP : WHEEL_DOWN;
		sendPointer(buttons | wheel, x, y);
		sendPointer(buttons, x, y);
	}

	// -------------------------------------------------------------- handshake

	private void handshake() throws IOException {
		byte[] ver = new byte[12];
		in.readFully(ver);
		String v = new String(ver, StandardCharsets.US_ASCII);
		if (!v.startsWith("RFB 003.")) {
			throw new IOException("Not a VNC server: " + v.trim());
		}
		int minor = Integer.parseInt(v.substring(8, 11).trim());
		if (minor < 7) {
			throw new IOException("VNC server is too old (" + v.trim() + ")");
		}
		out.write("RFB 003.008\n".getBytes(StandardCharsets.US_ASCII));
		out.flush();

		int count = in.readUnsignedByte();
		if (count == 0) {
			throw new IOException("VNC server refused: " + readReason());
		}
		boolean hasNone = false;
		StringBuilder offered = new StringBuilder();
		for (int i = 0; i < count; i++) {
			int type = in.readUnsignedByte();
			offered.append(type).append(' ');
			if (type == 1) {
				hasNone = true;
			}
		}
		if (!hasNone) {
			throw new IOException("VNC server needs a password (security types: " + offered.toString().trim() + ")");
		}
		out.writeByte(1); // None
		out.flush();

		if (minor >= 8) {
			int result = in.readInt();
			if (result != 0) {
				throw new IOException("VNC security failed: " + readReason());
			}
		}

		out.writeByte(1); // ClientInit: shared
		out.flush();

		int w = in.readUnsignedShort();
		int h = in.readUnsignedShort();
		in.skipBytes(16); // server pixel format, we set our own below
		int nameLen = in.readInt();
		if (nameLen < 0 || nameLen > 4096) {
			throw new IOException("Bad server name length " + nameLen);
		}
		byte[] name = new byte[nameLen];
		in.readFully(name);
		serverName = new String(name, StandardCharsets.UTF_8);
		checkSize(w, h);
		frame = new VmFrame(w, h);

		// SetPixelFormat: 32 bpp, little endian, R<<16 | G<<8 | B
		out.writeByte(0);
		out.write(new byte[3]);
		out.writeByte(32);   // bits per pixel
		out.writeByte(24);   // depth
		out.writeByte(0);    // big endian
		out.writeByte(1);    // true colour
		out.writeShort(255); // red max
		out.writeShort(255); // green max
		out.writeShort(255); // blue max
		out.writeByte(16);   // red shift
		out.writeByte(8);    // green shift
		out.writeByte(0);    // blue shift
		out.write(new byte[3]);

		// SetEncodings: Raw, CopyRect, DesktopSize
		out.writeByte(2);
		out.writeByte(0);
		out.writeShort(3);
		out.writeInt(0);
		out.writeInt(1);
		out.writeInt(-223);

		sendUpdateRequest(false, 0, 0, w, h);
	}

	private String readReason() throws IOException {
		int len = in.readInt();
		if (len < 0 || len > 4096) {
			return "(no reason)";
		}
		byte[] b = new byte[len];
		in.readFully(b);
		return new String(b, StandardCharsets.UTF_8);
	}

	// ------------------------------------------------------------------ loop

	private void readLoop() {
		try {
			while (running) {
				int type = in.readUnsignedByte();
				switch (type) {
					case 0 -> readFramebufferUpdate();
					case 1 -> skipColourMap();
					case 2 -> { /* bell */ }
					case 3 -> {
						in.skipNBytes(3);
						int len = in.readInt();
						if (len < 0) {
							throw new IOException("Bad cut text length");
						}
						in.skipNBytes(len);
					}
					default -> throw new IOException("Unknown server message " + type);
				}
			}
		} catch (IOException e) {
			if (running) {
				fail(e);
			}
		}
	}

	private void readFramebufferUpdate() throws IOException {
		in.skipNBytes(1);
		int rects = in.readUnsignedShort();
		boolean resized = false;
		byte[] row = new byte[0];

		for (int i = 0; i < rects; i++) {
			int x = in.readUnsignedShort();
			int y = in.readUnsignedShort();
			int w = in.readUnsignedShort();
			int h = in.readUnsignedShort();
			int enc = in.readInt();
			VmFrame f = frame;

			switch (enc) {
				case 0 -> { // Raw
					if (x + w > f.width || y + h > f.height) {
						throw new IOException("Raw rect outside screen");
					}
					if (row.length < w * 4) {
						row = new byte[w * 4];
					}
					for (int yy = 0; yy < h; yy++) {
						in.readFully(row, 0, w * 4);
						int dst = (y + yy) * f.width + x;
						for (int xx = 0; xx < w; xx++) {
							int b = row[xx * 4] & 0xFF;
							int g = row[xx * 4 + 1] & 0xFF;
							int r = row[xx * 4 + 2] & 0xFF;
							f.pixels[dst + xx] = 0xFF000000 | (r << 16) | (g << 8) | b;
						}
					}
				}
				case 1 -> { // CopyRect
					int sx = in.readUnsignedShort();
					int sy = in.readUnsignedShort();
					if (x + w > f.width || y + h > f.height || sx + w > f.width || sy + h > f.height) {
						throw new IOException("CopyRect outside screen");
					}
					int[] tmp = new int[w * h];
					for (int yy = 0; yy < h; yy++) {
						System.arraycopy(f.pixels, (sy + yy) * f.width + sx, tmp, yy * w, w);
					}
					for (int yy = 0; yy < h; yy++) {
						System.arraycopy(tmp, yy * w, f.pixels, (y + yy) * f.width + x, w);
					}
				}
				case -223 -> { // DesktopSize: w/h is the new screen size
					checkSize(w, h);
					frame = new VmFrame(w, h);
					resized = true;
				}
				default -> throw new IOException("Unsupported VNC encoding " + enc);
			}
		}

		frameVersion.incrementAndGet();

		VmFrame f = frame;
		sendUpdateRequest(!resized, 0, 0, f.width, f.height);
	}

	private void skipColourMap() throws IOException {
		in.skipNBytes(1);
		in.readUnsignedShort(); // first colour
		int n = in.readUnsignedShort();
		in.skipNBytes((long) n * 6);
	}

	private void sendUpdateRequest(boolean incremental, int x, int y, int w, int h) throws IOException {
		synchronized (writeLock) {
			out.writeByte(3);
			out.writeByte(incremental ? 1 : 0);
			out.writeShort(x);
			out.writeShort(y);
			out.writeShort(w);
			out.writeShort(h);
			out.flush();
		}
	}

	private static void checkSize(int w, int h) throws IOException {
		if (w < 1 || h < 1 || w > 8192 || h > 8192 || (long) w * h > 16_000_000L) {
			throw new IOException("Unreasonable screen size " + w + "x" + h);
		}
	}

	private void fail(IOException e) {
		if (running) {
			error = e.getClass().getSimpleName() + ": " + e.getMessage();
			LOG.log(System.Logger.Level.WARNING, "VNC connection lost: " + error);
		}
		running = false;
		closeQuietly();
	}

	@Override
	public void close() {
		running = false;
		closeQuietly();
	}

	private void closeQuietly() {
		try {
			if (socket != null) {
				socket.close();
			}
		} catch (IOException ignored) {
			// nothing to do
		}
	}
}
