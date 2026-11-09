package com.jpc.vm;

import java.io.IOException;
import java.nio.file.Path;

/**
 * One virtual machine as the game sees it. Three kinds:
 * <ul>
 *   <li><b>built-in</b> (default): the 32-bit x86 emulator that lives inside the mod, nothing else to install;</li>
 *   <li><b>QEMU</b> (optional, desktop): starts QEMU itself and reads its screen over VNC, which also allows 64-bit guests;</li>
 *   <li><b>remote</b> (optional): connects to a VNC server that is already running.</li>
 * </ul>
 * Starting is asynchronous so the game thread never blocks.
 */
public final class VmSession implements AutoCloseable {
	private static final System.Logger LOG = System.getLogger("jpc.vm");

	public enum State { STOPPED, STARTING, RUNNING, FAILED }

	private enum Kind { BUILTIN, QEMU, REMOTE }

	private final Kind kind;
	private final JpcMachine.Config builtinConfig; // BUILTIN only
	private final VmConfig qemuConfig;             // QEMU only
	private final Path logFile;                    // QEMU only
	private final String remoteHost;               // REMOTE only
	private final int remotePort;

	private volatile int connectTimeoutMs;
	private volatile State state = State.STOPPED;
	private volatile String error;
	private volatile QemuProcess qemu;
	private volatile VmDisplay display;
	private volatile boolean paused;
	private volatile boolean closed;

	/** Built-in emulator session. */
	public static VmSession builtin(JpcMachine.Config config) {
		return new VmSession(Kind.BUILTIN, config, null, null, null, 0, 0);
	}

	/** Local QEMU session: this class starts and stops QEMU. */
	public VmSession(VmConfig config, Path logFile) {
		this(Kind.QEMU, null, config, logFile, null, 0, 20_000);
	}

	/** Remote session: connect to a VNC server that is already running. */
	public static VmSession remote(String host, int port) {
		return new VmSession(Kind.REMOTE, null, null, null, host, port, 8_000);
	}

	private VmSession(Kind kind, JpcMachine.Config builtinConfig, VmConfig qemuConfig, Path logFile,
					  String remoteHost, int remotePort, int connectTimeoutMs) {
		this.kind = kind;
		this.builtinConfig = builtinConfig;
		this.qemuConfig = qemuConfig;
		this.logFile = logFile;
		this.remoteHost = remoteHost;
		this.remotePort = remotePort;
		this.connectTimeoutMs = connectTimeoutMs;
	}

	public boolean isRemote() {
		return kind == Kind.REMOTE;
	}

	public boolean isBuiltin() {
		return kind == Kind.BUILTIN;
	}

	/** QEMU config, or null for the other kinds. */
	public VmConfig config() {
		return qemuConfig;
	}

	/** Short description for the screen's status bar. */
	public String describe() {
		return switch (kind) {
			case BUILTIN -> "built-in x86, " + builtinConfig.ramMb() + " MB" + liveStatus();
			case REMOTE -> "remote VM " + remoteHost + ":" + remotePort;
			case QEMU -> qemuConfig.arch().label + " (QEMU), " + qemuConfig.ramMb() + " MB";
		};
	}

	/** ", 4.2 MIPS, 720x400" once the machine reports it, so a slow guest can be told from a stuck one. */
	private String liveStatus() {
		VmDisplay d = display;
		String line = d == null ? "" : d.statusLine();
		return line == null || line.isEmpty() ? "" : ", " + line;
	}

	/** How long to keep retrying the screen connection before giving up (QEMU and remote). */
	public void setConnectTimeoutMs(int ms) {
		this.connectTimeoutMs = ms;
	}

	public State state() {
		return state;
	}

	/** Human readable reason when {@link #state()} is FAILED. */
	public String error() {
		return error;
	}

	/** The screen and input link, or null until the VM is RUNNING. */
	public VmDisplay display() {
		return display;
	}

	/** Pauses the machine while the screen is closed (built-in emulator only). */
	public void setPaused(boolean paused) {
		this.paused = paused;
		VmDisplay d = display;
		if (d != null) {
			d.setPaused(paused);
		}
	}

	/** Starts (and connects) in the background. */
	public synchronized void startAsync() {
		if (state == State.STARTING || state == State.RUNNING) {
			return;
		}
		state = State.STARTING;
		error = null;
		closed = false;

		Thread t = new Thread(this::startBlocking, "jpc-vm-start");
		t.setDaemon(true);
		t.start();
	}

	private void startBlocking() {
		QemuProcess q = null;
		try {
			VmDisplay d;
			if (kind == Kind.BUILTIN) {
				d = JpcMachine.start(builtinConfig);
				LOG.log(System.Logger.Level.INFO, "Built-in x86 machine started");
			} else if (kind == Kind.REMOTE) {
				d = connectWithRetry(remoteHost, remotePort, null);
			} else {
				q = new QemuProcess(qemuConfig, logFile);
				q.start();
				qemu = q;
				d = connectWithRetry("127.0.0.1", q.vncPort(), q);
			}
			if (d == null) {
				return; // closed while connecting
			}
			synchronized (this) {
				if (closed) {
					d.close();
					return;
				}
				display = d;
				d.setPaused(paused);
				state = State.RUNNING;
			}
		} catch (VmException e) {
			fail(e.getMessage());
			if (q != null) {
				q.close();
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			if (q != null) {
				q.close();
			}
		}
	}

	private VncClient connectWithRetry(String host, int port, QemuProcess q) throws VmException, InterruptedException {
		long deadline = System.currentTimeMillis() + connectTimeoutMs;
		IOException last = null;

		while (System.currentTimeMillis() < deadline && !closed) {
			if (q != null && !q.isAlive()) {
				throw new VmException("QEMU exited with code " + q.exitCode() + ". See " + q.logFile());
			}
			VncClient c = new VncClient(host, port);
			try {
				c.connect(3_000);
				LOG.log(System.Logger.Level.INFO, "VM running: " + c.serverName());
				return c;
			} catch (IOException e) {
				last = e;
				c.close();
				Thread.sleep(300);
			}
		}
		if (closed) {
			return null;
		}

		String reason = last == null ? "timeout" : last.getMessage();
		if (q == null) {
			throw new VmException("Could not reach a VM screen at " + host + ":" + port + " (" + reason + "). Start QEMU first.");
		}
		throw new VmException("Could not connect to the VM screen: " + reason);
	}

	private void fail(String message) {
		error = message;
		state = State.FAILED;
		LOG.log(System.Logger.Level.WARNING, "VM failed: " + message);
	}

	/** Call regularly: flips to FAILED if the machine, QEMU or the screen link dies. */
	public void tick() {
		if (state != State.RUNNING) {
			return;
		}
		QemuProcess q = qemu;
		VmDisplay d = display;
		if (q != null && !q.isAlive()) {
			fail("QEMU stopped (exit code " + q.exitCode() + ")");
		} else if (d != null && !d.isConnected()) {
			String why = d.error();
			fail(kind == Kind.BUILTIN
					? (why == null ? "The emulated computer stopped" : why)
					: "Screen link lost: " + why);
		}
	}

	/** Stops the machine. A remote VM keeps running; a built-in or QEMU one is shut down. */
	@Override
	public synchronized void close() {
		closed = true;
		if (display != null) {
			display.close();
			display = null;
		}
		if (qemu != null) {
			qemu.close();
			qemu = null;
		}
		state = State.STOPPED;
	}
}
