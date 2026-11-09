package com.jpc.client;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import net.minecraft.client.Minecraft;

import net.fabricmc.api.ClientModInitializer;

import com.jpc.JpcMod;
import com.jpc.block.ComputerBlock;
import com.jpc.vm.BundledImages;
import com.jpc.vm.IsoLibrary;

public class JpcModClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// Make the folder for disk images now, so it is there to drop files into before the first start.
		try {
			Path isoDir = ClientConfig.load().isoDir();
			IsoLibrary.prepareFolder(isoDir);
			// KolibriOS ships inside the mod: put it in the folder once, so the boot menu is not empty.
			List<String> added = BundledImages.install(isoDir);
			if (!added.isEmpty()) {
				JpcMod.LOGGER.info("Added to {}: {}", isoDir, String.join(", ", added));
			}
		} catch (IOException | RuntimeException e) {
			JpcMod.LOGGER.warn("Could not prepare the images folder: {}", e.getMessage());
		}

		// Right-clicking a computer block: the boot menu if the computer is off, else straight to its screen.
		ComputerBlock.clientOpenHook = pos -> Minecraft.getInstance().gui.setScreen(
				VmManager.shouldShowBootMenu() ? new BootMenuScreen() : new VmScreen());
	}
}
