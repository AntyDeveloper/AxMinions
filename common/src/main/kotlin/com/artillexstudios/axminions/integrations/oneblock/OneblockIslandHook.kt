package com.artillexstudios.axminions.integrations.oneblock

import org.bukkit.Location
import org.bukkit.inventory.ItemStack
import java.lang.reflect.Method

/**
 * Mink OneBlock: the Miner breaks the island block through OneblockCore's MinionApi (reflection, no compile
 * dependency). null = no island block in range or no OneblockCore; empty = island block there, nothing this time.
 */
object OneblockIslandHook {
    private val method: Method? by lazy {
        runCatching {
            Class.forName("pl.sirox.minkoneblock.api.MinionApi")
                .getMethod("breakIslandBlock", Location::class.java, Double::class.javaPrimitiveType, ItemStack::class.java)
        }.getOrNull()
    }

    @Suppress("UNCHECKED_CAST")
    fun breakNear(center: Location, range: Double, tool: ItemStack?): List<ItemStack>? {
        val m = method ?: return null
        return runCatching { m.invoke(null, center, range, tool) as List<ItemStack>? }.getOrNull()
    }
}
