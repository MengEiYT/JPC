package com.jpc.vm;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Disk images that ship inside the mod jar (KolibriOS) and get copied into the images folder the first time the mod
 * runs, so the boot menu is not empty on a fresh install. Plain Java, no game classes.
 *
 * <p>A copy happens once per image: a hidden marker file in the folder remembers what was installed, so an image the
 * player deleted does not come back, and a file the player put there under the same name is never overwritten. An
 * image that is not in the jar is simply skipped, so the mod works the same without it.
 */
public final class BundledImages {
	/** One image that can be in the jar: where it is inside the jar, and the file name it gets in the images folder. */
	public record Image(String resource, String fileName) {
	}

	/** Everything the mod knows how to ship. Add the file under src/main/resources to make an entry active. */
	public static final List<Image> KNOWN = List.of(
			new Image("/jpc/bundled/kolibri.img", "KolibriOS.img"));

	/** Hidden file in the images folder listing the file names that were installed already (one per line). */
	public static final String MARKER = ".jpc-installed";

	/** The marker's name before the mod was renamed from IsoCraft to JPC. Still read (and then moved), so nothing comes back. */
	public static final String OLD_MARKER = ".isocraft-installed";

	private BundledImages() {
	}

	/** Installs the bundled images from the mod jar. See {@link #install(Path, List, Function)}. */
	public static List<String> install(Path dir) {
		return install(dir, KNOWN, BundledImages.class::getResourceAsStream);
	}

	/**
	 * Copies each image that is available, not installed before, and not already present, into {@code dir}.
	 *
	 * @param opener gives the bytes of a resource, or null when there is no such resource
	 * @return the file names that were copied this time (empty most of the time)
	 */
	public static List<String> install(Path dir, List<Image> images, Function<String, InputStream> opener) {
		List<String> installed = new ArrayList<>();
		if (dir == null) {
			return installed;
		}
		Set<String> done = readMarker(dir);
		// A marker from the IsoCraft days: its list is already in "done", so write it under the new name and drop the old file.
		boolean markerChanged = Files.isRegularFile(dir.resolve(OLD_MARKER));

		for (Image image : images) {
			if (done.contains(image.fileName())) {
				continue;
			}
			Path target = dir.resolve(image.fileName());
			Path part = dir.resolve(image.fileName() + ".part");
			try (InputStream in = opener.apply(image.resource())) {
				if (in == null) {
					continue; // not in this jar: nothing to do, and nothing to remember
				}
				if (Files.exists(target)) {
					// The player already has a file by this name. Leave it alone and do not try again.
					done.add(image.fileName());
					markerChanged = true;
					continue;
				}
				Files.createDirectories(dir);
				long bytes = Files.copy(in, part, StandardCopyOption.REPLACE_EXISTING);
				if (bytes <= 0) {
					Files.deleteIfExists(part);
					continue;
				}
				Files.move(part, target, StandardCopyOption.ATOMIC_MOVE);
				done.add(image.fileName());
				markerChanged = true;
				installed.add(image.fileName());
			} catch (IOException | RuntimeException e) {
				// Not marked as done, so the next start tries again.
				try {
					Files.deleteIfExists(part);
				} catch (IOException ignored) {
					// nothing more to do about it
				}
			}
		}

		if (markerChanged) {
			writeMarker(dir, done);
			try {
				Files.deleteIfExists(dir.resolve(OLD_MARKER));
			} catch (IOException | RuntimeException e) {
				// Still readable next time, and written again under the new name: harmless.
			}
		}
		return installed;
	}

	private static Set<String> readMarker(Path dir) {
		Set<String> done = new LinkedHashSet<>();
		readNames(dir.resolve(MARKER), done);
		readNames(dir.resolve(OLD_MARKER), done);
		return done;
	}

	private static void readNames(Path marker, Set<String> done) {
		try {
			if (Files.isRegularFile(marker)) {
				for (String line : Files.readAllLines(marker, StandardCharsets.UTF_8)) {
					String name = line.trim();
					if (!name.isEmpty()) {
						done.add(name);
					}
				}
			}
		} catch (IOException | RuntimeException e) {
			// An unreadable marker counts as empty: worst case an image is offered again.
		}
	}

	private static void writeMarker(Path dir, Set<String> done) {
		try {
			Files.createDirectories(dir);
			Files.writeString(dir.resolve(MARKER), String.join("\n", done) + "\n", StandardCharsets.UTF_8);
		} catch (IOException | RuntimeException e) {
			// Without the marker the image is offered again next start, and only if the file is missing.
		}
	}
}
