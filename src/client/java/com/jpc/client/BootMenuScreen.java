package com.jpc.client;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import com.jpc.JpcMod;
import com.jpc.vm.BootSetup;
import com.jpc.vm.BundledImages;
import com.jpc.vm.IsoLibrary;
import com.jpc.vm.IsoLibrary.Entry;

/**
 * Shown when the computer block is used while the computer is off: pick what to start, like choosing a USB stick
 * to boot from, and set RAM, CPU limit and an extra hard disk. Images come from the "isos" folder in the game folder.
 */
public class BootMenuScreen extends Screen {
	private static final int ROW_HEIGHT = 22;
	private static final int MAX_ROWS = 8;
	private static final int INFO_COLOR = 0xFFAAAAAA;
	private static final int WARN_COLOR = 0xFFFFAA55;

	private final BootSetup setup;
	private final Path isoDir;
	private final Path configuredImage;
	private final Path configuredDisk;

	private final List<Button> rowButtons = new ArrayList<>();
	private Button prevButton;
	private Button nextButton;
	private Button ramButton;
	private Button cpuButton;
	private Button diskButton;
	private Button sizeButton;
	private Button createButton;
	private Button startButton;

	private String message;
	private boolean warning;
	private int listLeft;
	private int listWidth;
	private int thirdWidth;
	private int listTop;
	private int navY;
	private int msgY;

	public BootMenuScreen() {
		super(Component.literal("Choose what the computer starts"));
		ClientConfig cfg = ClientConfig.load();
		this.isoDir = cfg.isoDir();
		this.configuredImage = cfg.pickedImage();
		this.configuredDisk = cfg.pickedDisk();
		this.setup = new BootSetup(maxRamMb(), cfg.ramMb, cfg.maxCpuPercent);
		try {
			IsoLibrary.prepareFolder(isoDir);
			BundledImages.install(isoDir);
		} catch (IOException | RuntimeException e) {
			JpcMod.LOGGER.warn("Could not create the image folder {}: {}", isoDir, e.getMessage());
		}
		rescan(configuredImage, configuredDisk);
		say("RAM comes out of the game's memory (at most " + setup.maxRamMb() + " MB with the current setting).", false);
	}

	/** Guest RAM is a Java array inside the game, so it must fit next to the game itself. */
	private static int maxRamMb() {
		long gameMb = Runtime.getRuntime().maxMemory() / (1024L * 1024L);
		return (int) Math.max(16, gameMb / 2 - 200);
	}

	// ------------------------------------------------------------------ layout

	@Override
	protected void init() {
		rowButtons.clear();

		listWidth = Math.min(this.width - 20, 380);
		listLeft = (this.width - listWidth) / 2;

		// From the bottom up, so short screens lose list rows rather than buttons.
		int actionsY = this.height - 24;
		msgY = actionsY - 11;
		int settingsB = msgY - 22;
		int settingsA = settingsB - 22;
		navY = settingsA - 24;
		listTop = 34;
		int rows = Math.max(1, Math.min(MAX_ROWS, (navY - 4 - listTop) / ROW_HEIGHT));
		setup.setRowsPerPage(rows);

		for (int r = 0; r < rows; r++) {
			final int row = r;
			Button b = Button.builder(Component.literal(""), button -> {
						setup.pickRow(row);
						refresh();
					})
					.bounds(listLeft, listTop + r * ROW_HEIGHT, listWidth, 20)
					.build();
			this.addRenderableWidget(b);
			rowButtons.add(b);
		}

		prevButton = Button.builder(Component.literal("< Prev"), button -> {
					setup.prevPage();
					refresh();
				})
				.bounds(listLeft, navY, 64, 20)
				.build();
		nextButton = Button.builder(Component.literal("Next >"), button -> {
					setup.nextPage();
					refresh();
				})
				.bounds(listLeft + listWidth - 64, navY, 64, 20)
				.build();
		this.addRenderableWidget(prevButton);
		this.addRenderableWidget(nextButton);

		int third = (listWidth - 8) / 3;
		thirdWidth = third;
		int x1 = listLeft;
		int x2 = listLeft + third + 4;
		int x3 = listLeft + 2 * (third + 4);

		ramButton = Button.builder(Component.literal("RAM"), button -> {
					setup.cycleRam();
					refresh();
				})
				.bounds(x1, settingsA, third, 20)
				.build();
		cpuButton = Button.builder(Component.literal("CPU"), button -> {
					setup.cycleCpu();
					refresh();
				})
				.bounds(x2, settingsA, third, 20)
				.build();
		diskButton = Button.builder(Component.literal("Hard disk"), button -> {
					setup.cycleDisk();
					refresh();
				})
				.bounds(x3, settingsA, third, 20)
				.build();
		sizeButton = Button.builder(Component.literal("New disk size"), button -> {
					setup.cycleNewDiskSize();
					refresh();
				})
				.bounds(x1, settingsB, third, 20)
				.build();
		createButton = Button.builder(Component.literal("Create disk"), button -> createDisk())
				.bounds(x2, settingsB, third, 20)
				.build();
		Button refreshButton = Button.builder(Component.literal("Refresh list"), button -> {
					rescan(null, null);
					say("Found " + setup.entries().size() + " image(s).", false);
					refresh();
				})
				.bounds(x3, settingsB, third, 20)
				.build();
		for (Button b : new Button[] {ramButton, cpuButton, diskButton, sizeButton, createButton, refreshButton}) {
			this.addRenderableWidget(b);
		}

		int half = (listWidth - 4) / 2;
		startButton = Button.builder(Component.literal("Start"), button -> start())
				.bounds(listLeft, actionsY, half, 20)
				.build();
		this.addRenderableWidget(startButton);
		this.addRenderableWidget(Button.builder(Component.literal("Cancel"), button -> this.onClose())
				.bounds(listLeft + half + 4, actionsY, half, 20)
				.build());

		refresh();
	}

	/** Puts the current state into the buttons' texts and on/off states. */
	private void refresh() {
		for (int r = 0; r < rowButtons.size(); r++) {
			Button b = rowButtons.get(r);
			Entry e = setup.rowEntry(r);
			b.visible = e != null;
			if (e != null) {
				b.setMessage(Component.literal(rowText(e)));
			}
		}
		prevButton.active = setup.page() > 0;
		nextButton.active = setup.page() < setup.pageCount() - 1;

		ramButton.setMessage(label("RAM: " + setup.ramMb() + " MB"));
		cpuButton.setMessage(label("CPU limit: " + setup.cpuPercent() + "%"));

		Entry picked = setup.selected();
		boolean bootingDisk = picked != null && picked.kind() == IsoLibrary.Kind.DISK;
		diskButton.active = !bootingDisk && !setup.diskChoices().isEmpty();
		String diskPrefix = "Hard disk: ";
		String diskText = bootingDisk ? "booting a disk"
				: setup.disk() != null ? fit(setup.disk().name(), thirdWidth - 8 - this.font.width(diskPrefix)) : "none";
		diskButton.setMessage(label(diskPrefix + diskText));

		sizeButton.setMessage(label("New disk: " + setup.newDiskMb() + " MB"));
		startButton.active = setup.canStart();
	}

	private String rowText(Entry e) {
		String prefix = setup.isSelected(e) ? "> " : "  ";
		String suffix = "  (" + e.kind().label + ", " + IsoLibrary.humanSize(e.size()) + ")";
		int avail = listWidth - 16;
		String name = e.name();
		boolean cut = false;
		while (this.font.width(prefix + name + (cut ? "..." : "") + suffix) > avail && name.length() > 3) {
			name = name.substring(0, name.length() - 1);
			cut = true;
		}
		return prefix + name + (cut ? "..." : "") + suffix;
	}

	/** Button text that is cut with "..." if it would not fit in one of the three settings columns. */
	private Component label(String text) {
		return Component.literal(fit(text, thirdWidth - 8));
	}

	private String fit(String s, int pixels) {
		boolean cut = false;
		while (this.font.width(s + (cut ? "..." : "")) > pixels && s.length() > 3) {
			s = s.substring(0, s.length() - 1);
			cut = true;
		}
		return s + (cut ? "..." : "");
	}

	// ----------------------------------------------------------------- actions

	private void rescan(Path pick, Path disk) {
		List<Entry> list = IsoLibrary.scan(isoDir);
		// An image an older config points to (outside the folder) stays available.
		list = IsoLibrary.withExtra(list, configuredImage);
		list = IsoLibrary.withExtra(list, configuredDisk);
		setup.setEntries(list, pick, disk);
	}

	private void say(String text, boolean isWarning) {
		message = text;
		warning = isWarning;
	}

	private void createDisk() {
		try {
			Path file = IsoLibrary.createBlankDisk(isoDir, setup.newDiskMb());
			rescan(null, null);
			setup.attach(file);
			say("Created " + file.getFileName() + " (" + setup.newDiskMb() + " MB) and attached it as the hard disk.", false);
		} catch (IOException | RuntimeException e) {
			say("Could not create the disk: " + e.getMessage(), true);
		}
		refresh();
	}

	private void start() {
		BootSetup.Launch launch = setup.launch();
		if (launch == null) {
			say("Pick an image first.", true);
			return;
		}
		ClientConfig cfg = ClientConfig.load();
		cfg.applyLaunch(launch);
		if (!cfg.save()) {
			JpcMod.LOGGER.warn("The boot menu choice could not be saved; it is used for this start only");
		}
		VmManager.restart(cfg);
		this.minecraft.gui.setScreen(new VmScreen());
	}

	// ----------------------------------------------------------------- drawing

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractRenderState(graphics, mouseX, mouseY, delta);

		centered(graphics, "Choose what the computer starts", 8, 0xFFFFFFFF);
		centered(graphics, fit("Images folder: " + isoDir, this.width - 20), 20, INFO_COLOR);

		if (setup.entries().isEmpty()) {
			centered(graphics, "No disk images found.", listTop + 4, 0xFFFFFFFF);
			centered(graphics, "Put .iso files into the folder above,", listTop + 18, INFO_COLOR);
			centered(graphics, "then press Refresh list.", listTop + 30, INFO_COLOR);
		} else if (setup.pageCount() > 1) {
			centered(graphics, "Page " + (setup.page() + 1) + " of " + setup.pageCount(), navY + 6, INFO_COLOR);
		}

		if (message != null && !message.isEmpty()) {
			centered(graphics, fit(message, this.width - 12), msgY, warning ? WARN_COLOR : INFO_COLOR);
		}
	}

	private void centered(GuiGraphicsExtractor graphics, String text, int y, int color) {
		graphics.text(this.font, text, (this.width - this.font.width(text)) / 2, y, color, true);
	}

	// ----------------------------------------------------------------- closing

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(null);
	}
}
