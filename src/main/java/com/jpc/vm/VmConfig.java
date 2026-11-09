package com.jpc.vm;

import java.nio.file.Path;

/**
 * Everything needed to start one virtual machine.
 *
 * @param arch        32-bit or 64-bit x86
 * @param ramMb       guest RAM in megabytes
 * @param cpus        guest CPU cores
 * @param iso         boot ISO, or null to boot only from the disk
 * @param disk        virtual hard disk (qcow2), or null for none. Created if missing.
 * @param diskSizeGb  size used when the disk file has to be created
 * @param qemuDir     folder that contains the QEMU binaries, or null to search PATH
 */
public record VmConfig(
		CpuArch arch,
		int ramMb,
		int cpus,
		Path iso,
		Path disk,
		int diskSizeGb,
		Path qemuDir
) {
	public VmConfig {
		if (arch == null) {
			throw new IllegalArgumentException("arch is required");
		}
		if (ramMb < 16) {
			throw new IllegalArgumentException("ramMb must be at least 16");
		}
		if (cpus < 1) {
			throw new IllegalArgumentException("cpus must be at least 1");
		}
		if (iso == null && disk == null) {
			throw new IllegalArgumentException("need an iso or a disk to boot from");
		}
	}

	/** Sensible defaults: boot the given ISO with no disk. */
	public static VmConfig forIso(CpuArch arch, Path iso) {
		int ram = arch == CpuArch.X86 ? 512 : 1024;
		return new VmConfig(arch, ram, 2, iso, null, 8, null);
	}

	public VmConfig withDisk(Path disk, int sizeGb) {
		return new VmConfig(arch, ramMb, cpus, iso, disk, sizeGb, qemuDir);
	}

	public VmConfig withRam(int ramMb) {
		return new VmConfig(arch, ramMb, cpus, iso, disk, diskSizeGb, qemuDir);
	}

	public VmConfig withCpus(int cpus) {
		return new VmConfig(arch, ramMb, cpus, iso, disk, diskSizeGb, qemuDir);
	}

	public VmConfig withQemuDir(Path qemuDir) {
		return new VmConfig(arch, ramMb, cpus, iso, disk, diskSizeGb, qemuDir);
	}
}
