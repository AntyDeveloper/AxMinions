package com.artillexstudios.axminions.integrations.prices

import com.artillexstudios.axminions.api.integrations.types.PricesIntegration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import java.io.File
import java.util.EnumMap

/**
 * Ceny skupu prosto z plikow sklepow UltimateShop (plugins/UltimateShop/shops/*.yml) - bez zadnego "ShopGUIPlus".
 * Liczy tylko zwykle przedmioty vanilla (bez custom model data / nazwy / hooka) i cene w Vault, odswieza co 10 min.
 */
class UltimateShopIntegration(private val plugin: Plugin) : PricesIntegration {

    @Volatile
    private var sell: Map<Material, Double> = emptyMap()

    override fun getPrice(itemStack: ItemStack): Double {
        if (itemStack.type.isAir) return -1.0
        val meta = if (itemStack.hasItemMeta()) itemStack.itemMeta else null
        if (meta != null && (meta.hasCustomModelData() || meta.hasItemModel() || meta.hasDisplayName())) return -1.0
        val unit = sell[itemStack.type] ?: return -1.0
        return unit * itemStack.amount
    }

    override fun register() {
        reload()
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, Runnable { reload() }, 20L * 60)
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, Runnable { reload() }, 20L * 60 * 10, 20L * 60 * 10)
    }

    private fun reload() {
        val dir = File(plugin.dataFolder.parentFile, "UltimateShop/shops")
        val result = EnumMap<Material, Double>(Material::class.java)
        dir.listFiles { _, name -> name.endsWith(".yml") }?.forEach { file ->
            val items = YamlConfiguration.loadConfiguration(file).getConfigurationSection("items") ?: return@forEach
            for (id in items.getKeys(false)) read(items.getConfigurationSection(id), result)
        }
        sell = result
    }

    private fun read(item: ConfigurationSection?, out: MutableMap<Material, Double>) {
        if (item == null) return
        val products = item.getConfigurationSection("products") ?: return
        if (products.getKeys(false).size != 1) return
        val product = products.getConfigurationSection(products.getKeys(false).first()) ?: return
        if (product.contains("hook-plugin") || product.contains("hook-item")) return
        val material = Material.matchMaterial(product.getString("material", "") ?: "") ?: return
        val amount = maxOf(1, product.getInt("amount", 1))
        val prices = item.getConfigurationSection("sell-prices") ?: return
        var total = 0.0
        for (key in prices.getKeys(false)) {
            val price = prices.getConfigurationSection(key) ?: continue
            if ("Vault".equals(price.getString("economy-plugin", "Vault"), ignoreCase = true)) total += price.getDouble("amount", 0.0)
        }
        if (total > 0) out.merge(material, total / amount, ::maxOf)
    }
}
