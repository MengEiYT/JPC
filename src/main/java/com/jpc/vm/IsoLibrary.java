package com.jpc.vm;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * The folder where the player drops disk images ("isos" in the game folder): finds them, says what kind each one is,
 * and can create an empty hard disk next to them. Plain Java, no game classes.
 */
public final class IsoLibrary {
	/** What a file is, which decides how the computer uses it. */
	public enum Kind {
		/** A CD-ROM image (.iso): boots from the CD drive. */
		CDROM("CD"),
		/** A floppy image (.img and friends, 2.88 MB or smaller). */
		FLOPPY("Floppy"),
		/** A raw hard disk image: boots from, or is attached as, the hard disk. */
		DISK("Disk");

		public final String label;

		Kind(String label) {
			this.label = label;
		}
	}

	/** One image in the folder. */
	public record Entry(Path path, String name, long size, Kind kind) {
		/** Text for a list row, for example {@code alpine.iso  (CD, 58 MB)}. */
		public String label() {
			return name + "  (" + kind.label + ", " + humanSize(size) + ")";
		}
	}

	/** Biggest standard floppy (2.88 MB). Bigger .img files are treated as hard disks. */
	public static final long FLOPPY_MAX_BYTES = 2_949_120L;

	private static final int MAX_ENTRIES = 300;
	private static final String HELP_FILE = "put-your-iso-files-here.txt";

	private IsoLibrary() {
	}

	/** Decides what a file is from its name and size, or returns null if it is not a disk image. */
	public static Kind classify(String fileName, long size) {
		String n = fileName.toLowerCase(Locale.ROOT);
		int dot = n.lastIndexOf('.');
		if (dot < 0 || n.startsWith(".")) {
			return null;
		}
		return switch (n.substring(dot + 1)) {
			case "iso" -> Kind.CDROM;
			case "img", "ima", "flp", "vfd" -> size <= FLOPPY_MAX_BYTES ? Kind.FLOPPY : Kind.DISK;
			case "hdd", "raw", "hd" -> Kind.DISK;
			default -> null;
		};
	}

	/** All disk images in the folder (not its subfolders): CDs first, then floppies, then disks, each by name. */
	public static List<Entry> scan(Path dir) {
		List<Entry> out = new ArrayList<>();
		if (dir == null || !Files.isDirectory(dir)) {
			return out;
		}
		try (Stream<Path> files = Files.list(dir)) {
			files.limit(5000).forEach(p -> {
				try {
					if (!Files.isRegularFile(p) || out.size() >= MAX_ENTRIES) {
						return;
					}
					String name = p.getFileName().toString();
					long size = Files.size(p);
					Kind kind = classify(name, size);
					if (kind != null && size > 0) {
						out.add(new Entry(p, name, size, kind));
					}
				} catch (IOException ignored) {
					// unreadable file: leave it out of the list
				}
			});
		} catch (IOException | RuntimeException e) {
			return out;
		}
		sort(out);
		return out;
	}

	/** Describes one file (for example the image an older config points to), or null if it is not a usable image. */
	public static Entry describe(Path file) {
		try {
			if (file == null || !Files.isRegularFile(file)) {
				return null;
			}
			String name = file.getFileName().toString();
			long size = Files.size(file);
			Kind kind = classify(name, size);
			return kind == null || size <= 0 ? null : new Entry(file, name, size, kind);
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	/** The list plus one more file (ignored if it is null, unusable, or already listed), in list order. */
	public static List<Entry> withExtra(List<Entry> list, Path extra) {
		Entry e = describe(extra);
		if (e == null) {
			return list;
		}
		Path want = e.path().toAbsolutePath().normalize();
		for (Entry x : list) {
			if (x.path().toAbsolutePath().normalize().equals(want)) {
				return list;
			}
		}
		List<Entry> out = new ArrayList<>(list);
		out.add(e);
		sort(out);
		return out;
	}

	private static void sort(List<Entry> out) {
		out.sort(Comparator.comparing((Entry e) -> e.kind().ordinal())
				.thenComparing(e -> e.name().toLowerCase(Locale.ROOT))
				.thenComparing(Entry::name));
	}

	/**
	 * Creates the folder if it is missing, with a short note inside so it is easy to recognise in a file manager.
	 * An existing folder is left alone.
	 */
	public static void prepareFolder(Path dir) throws IOException {
		if (Files.isDirectory(dir)) {
			return;
		}
		Files.createDirectories(dir);
		String note = "JPC: put your disk images in this folder.\n\n"
				+ "  .iso            CD images (most operating systems)\n"
				+ "  .img            floppy images, or bigger disk images\n"
				+ "  .hdd / .raw     hard disk images (the boot menu can make an empty one for you)\n\n"
				+ "Then right-click the computer block in the game and pick what to start.\n"
				+ "Only 32-bit x86 systems run (no 64-bit ones).\n";
		Files.writeString(dir.resolve(HELP_FILE), note, StandardCharsets.UTF_8);
	}

	/**
	 * Creates an empty hard disk image {@code disk1.hdd}, {@code disk2.hdd}, ... of the given size. The file is sparse
	 * where the file system allows it, so it takes little space until the guest writes to it.
	 *
	 * @return the new file
	 */
	public static Path createBlankDisk(Path dir, int megabytes) throws IOException {
		if (megabytes < 1 || megabytes > 8000) {
			throw new IOException("Disk size must be between 1 MB and 8000 MB");
		}
		Files.createDirectories(dir);
		for (int i = 1; i < 1000; i++) {
			Path file = dir.resolve("disk" + i + ".hdd");
			try {
				Files.createFile(file);
			} catch (java.nio.file.FileAlreadyExistsException e) {
				continue;
			}
			try (RandomAccessFile f = new RandomAccessFile(file.toFile(), "rw")) {
				f.setLength(megabytes * 1024L * 1024L);
			} catch (IOException e) {
				Files.deleteIfExists(file);    // do not leave a half-made disk behind
				throw e;
			}
			return file;
		}
		throw new IOException("Too many disk images in " + dir);
	}

	/** "812 KB", "1.4 MB", "58 MB", "1.5 GB". */
	public static String humanSize(long bytes) {
		if (bytes < 1024L * 1024L) {
			return Math.max(1, (bytes + 1023) / 1024) + " KB";
		}
		if (bytes < 10L * 1024L * 1024L) {
			return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
		}
		if (bytes < 1024L * 1024L * 1024L) {
			return Math.round(bytes / (1024.0 * 1024.0)) + " MB";
		}
		return String.format(Locale.ROOT, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
	}
}
