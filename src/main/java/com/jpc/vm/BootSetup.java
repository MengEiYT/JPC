package com.jpc.vm;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.jpc.vm.IsoLibrary.Entry;
import com.jpc.vm.IsoLibrary.Kind;

/**
 * The state of the boot menu: which image is picked, which page of the list is showing, and the machine settings
 * (RAM, CPU limit, extra hard disk). Plain Java, so the screen is only drawing and button wiring.
 */
public final class BootSetup {
	/** What the menu decided. {@code disk} is an extra hard disk to attach, or null. */
	public record Launch(Entry boot, Entry disk, int ramMb, int cpuPercent) {
	}

	private static final int[] RAM_STEPS = {16, 32, 64, 128, 256, 512, 1024};
	private static final int[] CPU_STEPS = {100, 80, 60, 40, 25};
	private static final int[] NEW_DISK_STEPS = {128, 256, 512, 1024, 2048};

	private final int[] ramOptions;
	private List<Entry> entries = List.of();
	private int selected = -1;
	private Entry disk;
	private int rowsPerPage = 5;
	private int page;
	private int ramMb;
	private int cpuPercent;
	private int newDiskMb = 512;

	/**
	 * @param maxRamMb   the most guest RAM that fits in the game's memory
	 * @param ramMb      current RAM setting
	 * @param cpuPercent current CPU limit
	 */
	public BootSetup(int maxRamMb, int ramMb, int cpuPercent) {
		List<Integer> options = new ArrayList<>();
		for (int step : RAM_STEPS) {
			if (step <= maxRamMb || options.isEmpty()) {
				options.add(step);
			}
		}
		this.ramOptions = options.stream().mapToInt(Integer::intValue).toArray();
		this.ramMb = Math.max(ramOptions[0], Math.min(ramMb, ramOptions[ramOptions.length - 1]));
		this.cpuPercent = Math.max(10, Math.min(100, cpuPercent));
	}

	// ----------------------------------------------------------------- list

	/**
	 * Puts a fresh scan of the folder into the menu, keeping the pick when its file is still there.
	 *
	 * @param pickPath path of the image to preselect, or null (then the previous pick stays, else the first entry)
	 * @param diskPath path of the extra hard disk to preselect, or null (then the previous one stays)
	 */
	public void setEntries(List<Entry> list, Path pickPath, Path diskPath) {
		Path currentPick = pickPath != null ? pickPath : selected() == null ? null : selected().path();
		Path currentDisk = diskPath != null ? diskPath : disk == null ? null : disk.path();
		entries = List.copyOf(list);
		selected = indexOf(currentPick);
		if (selected < 0 && !entries.isEmpty()) {
			selected = 0;
		}
		disk = null;
		int d = indexOf(currentDisk);
		if (d >= 0 && entries.get(d).kind() == Kind.DISK && d != selected) {
			disk = entries.get(d);
		}
		if (selected >= 0) {
			page = selected / rowsPerPage;
		}
		clampPage();
	}

	private int indexOf(Path p) {
		if (p == null) {
			return -1;
		}
		Path want = p.toAbsolutePath().normalize();
		for (int i = 0; i < entries.size(); i++) {
			if (entries.get(i).path().toAbsolutePath().normalize().equals(want)) {
				return i;
			}
		}
		return -1;
	}

	public List<Entry> entries() {
		return entries;
	}

	public void setRowsPerPage(int rows) {
		rowsPerPage = Math.max(1, rows);
		if (selected >= 0) {
			page = selected / rowsPerPage;
		}
		clampPage();
	}

	public int rowsPerPage() {
		return rowsPerPage;
	}

	public int page() {
		return page;
	}

	public int pageCount() {
		return Math.max(1, (entries.size() + rowsPerPage - 1) / rowsPerPage);
	}

	public void nextPage() {
		page = Math.min(pageCount() - 1, page + 1);
	}

	public void prevPage() {
		page = Math.max(0, page - 1);
	}

	private void clampPage() {
		page = Math.max(0, Math.min(page, pageCount() - 1));
	}

	/** The entry shown in row {@code row} of the current page, or null for an empty row. */
	public Entry rowEntry(int row) {
		int i = page * rowsPerPage + row;
		return row >= 0 && i < entries.size() ? entries.get(i) : null;
	}

	/** Picks what is shown in row {@code row} of the current page. */
	public void pickRow(int row) {
		int i = page * rowsPerPage + row;
		if (row >= 0 && i < entries.size()) {
			selected = i;
			if (disk != null && disk.equals(entries.get(i))) {
				disk = null;        // a disk that is being booted cannot also be the extra disk
			}
		}
	}

	public Entry selected() {
		return selected >= 0 && selected < entries.size() ? entries.get(selected) : null;
	}

	public boolean isSelected(Entry e) {
		Entry s = selected();
		return s != null && s.equals(e);
	}

	// ------------------------------------------------------------- settings

	public int ramMb() {
		return ramMb;
	}

	/** Next RAM size; after the biggest one that fits, back to the smallest. */
	public void cycleRam() {
		for (int option : ramOptions) {
			if (option > ramMb) {
				ramMb = option;
				return;
			}
		}
		ramMb = ramOptions[0];
	}

	public int maxRamMb() {
		return ramOptions[ramOptions.length - 1];
	}

	public int cpuPercent() {
		return cpuPercent;
	}

	/** Next CPU limit, 100% then lower; after the lowest, back to 100%. */
	public void cycleCpu() {
		for (int step : CPU_STEPS) {
			if (step < cpuPercent) {
				cpuPercent = step;
				return;
			}
		}
		cpuPercent = CPU_STEPS[0];
	}

	/** The extra hard disk that is attached next to the CD or floppy, or null. */
	public Entry disk() {
		return disk;
	}

	/** Disks that can be attached: every disk image except the one being booted. */
	public List<Entry> diskChoices() {
		List<Entry> out = new ArrayList<>();
		Entry boot = selected();
		for (Entry e : entries) {
			if (e.kind() == Kind.DISK && !e.equals(boot)) {
				out.add(e);
			}
		}
		return out;
	}

	/** None, then each disk in turn, then none again. */
	public void cycleDisk() {
		List<Entry> choices = diskChoices();
		if (choices.isEmpty()) {
			disk = null;
			return;
		}
		if (disk == null) {
			disk = choices.get(0);
			return;
		}
		int i = choices.indexOf(disk);
		disk = i < 0 || i + 1 >= choices.size() ? null : choices.get(i + 1);
	}

	/** Attaches the given file as the extra hard disk, if it is a disk in the list. */
	public void attach(Path path) {
		int i = indexOf(path);
		if (i >= 0 && entries.get(i).kind() == Kind.DISK && i != selected) {
			disk = entries.get(i);
		}
	}

	public int newDiskMb() {
		return newDiskMb;
	}

	public void cycleNewDiskSize() {
		for (int step : NEW_DISK_STEPS) {
			if (step > newDiskMb) {
				newDiskMb = step;
				return;
			}
		}
		newDiskMb = NEW_DISK_STEPS[0];
	}

	/** Whether the pick can be started right now. */
	public boolean canStart() {
		return selected() != null;
	}

	/** What to start, or null if nothing is picked. */
	public Launch launch() {
		Entry boot = selected();
		if (boot == null) {
			return null;
		}
		return new Launch(boot, boot.kind() == Kind.DISK ? null : disk, ramMb, cpuPercent);
	}
}
