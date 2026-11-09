package com.jpc;

import net.minecraft.resources.Identifier;

import net.fabricmc.api.ModInitializer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.jpc.block.ModBlocks;

public class JpcMod implements ModInitializer {
	public static final String MOD_ID = "jpc";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
		ModBlocks.initialize();
		LOGGER.info("JPC (Java Personal Computer) loaded (by MengEi, built with Claude from Anthropic)");
	}
}
