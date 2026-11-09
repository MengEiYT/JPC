package com.jpc.block;

import net.minecraft.references.BlockItemId;
import net.minecraft.resources.Identifier;

import com.jpc.JpcMod;

public class ModBlockItemIds {
	public static final BlockItemId COMPUTER = create("computer");

	private static BlockItemId create(String name) {
		Identifier id = JpcMod.id(name);
		return BlockItemId.create(id, id);
	}
}
