package com.jpc.vm;

/** Which kind of x86 machine to emulate. */
public enum CpuArch {
	/** 32-bit x86 (i386 and up). */
	X86("qemu-system-i386", "32-bit x86"),
	/** 64-bit x86 (x86_64). Also boots 32-bit operating systems. */
	X86_64("qemu-system-x86_64", "64-bit x86");

	/** Name of the QEMU system emulator binary, without .exe. */
	public final String binary;
	public final String label;

	CpuArch(String binary, String label) {
		this.binary = binary;
		this.label = label;
	}
}
