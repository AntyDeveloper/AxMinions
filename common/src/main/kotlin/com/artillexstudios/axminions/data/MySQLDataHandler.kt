package com.artillexstudios.axminions.data

import com.artillexstudios.axapi.serializers.Serializers
import com.artillexstudios.axminions.api.config.Config
import com.artillexstudios.axminions.api.data.DataHandler
import com.artillexstudios.axminions.api.minions.Direction
import com.artillexstudios.axminions.api.minions.miniontype.MinionType
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.inventory.ItemStack
import java.sql.Connection
import java.sql.Statement
import java.sql.Types
import java.util.UUID

/**
 * MySQL / MariaDB storage, safe for several servers (sectors) on one database.
 *
 * Every row that points at a place is scoped by `database.server-id`: the world is stored as "<server-id>:<world>",
 * so two sectors that both have a world called "oneblock" never see each other's minions or locations.
 * Only the owner table (extra slots) and the island counters are shared; both are changed with atomic statements.
 */
class MySQLDataHandler : DataHandler {
    private lateinit var dataSource: HikariDataSource
    private lateinit var serverId: String

    override fun getType(): String = "MySQL"

    private fun worldKey(name: String) = "$serverId:$name"

    private fun <T> query(block: (Connection) -> T): T = dataSource.connection.use(block)

    private fun exec(sql: String) = query { c -> c.createStatement().use { it.executeUpdate(sql) } }

    override fun setup() {
        serverId = Config.DATABASE_SERVER_ID().trim().ifEmpty { "default" }
        require(serverId.length <= 40 && !serverId.contains(':')) { "database.server-id must be at most 40 characters and must not contain ':'" }

        val config = HikariConfig()
        config.poolName = "axminions-pool"
        config.driverClassName = "com.mysql.cj.jdbc.Driver"
        config.jdbcUrl = "jdbc:mysql://${Config.DATABASE_ADDRESS()}:${Config.DATABASE_PORT()}/${Config.DATABASE_NAME()}" +
                "?useSSL=${Config.DATABASE_SSL()}&allowPublicKeyRetrieval=true&characterEncoding=utf8&connectionTimeZone=UTC"
        config.username = Config.DATABASE_USERNAME()
        config.password = Config.DATABASE_PASSWORD()
        config.maximumPoolSize = Config.DATABASE_POOL_SIZE()
        config.isAutoCommit = true
        dataSource = HikariDataSource(config)

        val engine = "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
        exec("CREATE TABLE IF NOT EXISTS `axminions_types`(`id` INT AUTO_INCREMENT PRIMARY KEY, `name` VARCHAR(64) NOT NULL, UNIQUE KEY `uq_type_name`(`name`)) $engine;")
        exec("CREATE TABLE IF NOT EXISTS `axminions_users`(`uuid` CHAR(36) PRIMARY KEY, `name` VARCHAR(16), `island_slots` INT NOT NULL DEFAULT 0) $engine;")
        exec("CREATE TABLE IF NOT EXISTS `axminions_worlds`(`id` INT AUTO_INCREMENT PRIMARY KEY, `name` VARCHAR(128) NOT NULL, UNIQUE KEY `uq_world_name`(`name`)) $engine;")
        exec("CREATE TABLE IF NOT EXISTS `axminions_locations`(`id` INT AUTO_INCREMENT PRIMARY KEY, `x` INT NOT NULL, `y` INT NOT NULL, `z` INT NOT NULL, `world_id` INT NOT NULL, UNIQUE KEY `uq_location`(`x`, `y`, `z`, `world_id`), FOREIGN KEY(`world_id`) REFERENCES `axminions_worlds`(`id`)) $engine;")
        exec("CREATE TABLE IF NOT EXISTS `axminions_minions`(`id` INT AUTO_INCREMENT PRIMARY KEY, `location_id` INT NOT NULL, `chest_location_id` INT NULL, `owner_id` CHAR(36) NOT NULL, `type_id` INT NOT NULL, `direction` TINYINT, `level` SMALLINT, `storage` DOUBLE, `actions` BIGINT, `tool` LONGTEXT, `charge` BIGINT NOT NULL DEFAULT 0, UNIQUE KEY `uq_minion_location`(`location_id`), FOREIGN KEY(`location_id`) REFERENCES `axminions_locations`(`id`), FOREIGN KEY(`chest_location_id`) REFERENCES `axminions_locations`(`id`), FOREIGN KEY(`owner_id`) REFERENCES `axminions_users`(`uuid`), FOREIGN KEY(`type_id`) REFERENCES `axminions_types`(`id`)) $engine;")
        if (Config.ISLAND_LIMIT() > 0) {
            exec("CREATE TABLE IF NOT EXISTS `axminions_island_counter`(`island` VARCHAR(256) PRIMARY KEY, `placed` INT NOT NULL DEFAULT 0) $engine;")
        }
    }

    override fun insertType(minionType: MinionType) {
        query { c ->
            c.prepareStatement("INSERT INTO `axminions_types`(`name`) VALUES(?) ON DUPLICATE KEY UPDATE `name` = `name`;").use {
                it.setString(1, minionType.getName())
                it.executeUpdate()
            }
        }
    }

    // One query for the whole world: location, linked chest and both world names come with the minion row.
    // (Per-minion lookups were 3+ queries each, thousands of round trips to the database on every start.)
    override fun loadMinionsForWorld(minionType: MinionType, world: World) {
        query { c ->
            c.prepareStatement(
                "SELECT m.*, l.x AS lx, l.y AS ly, l.z AS lz, " +
                        "cl.x AS cx, cl.y AS cy, cl.z AS cz, cw.name AS cworld " +
                        "FROM `axminions_minions` m " +
                        "JOIN `axminions_locations` l ON m.`location_id` = l.`id` " +
                        "LEFT JOIN `axminions_locations` cl ON m.`chest_location_id` = cl.`id` " +
                        "LEFT JOIN `axminions_worlds` cw ON cl.`world_id` = cw.`id` " +
                        "WHERE l.`world_id` = (SELECT `id` FROM `axminions_worlds` WHERE `name` = ?) " +
                        "AND m.`type_id` = (SELECT `id` FROM `axminions_types` WHERE `name` = ?);"
            ).use { statement ->
                statement.setString(1, worldKey(world.name))
                statement.setString(2, minionType.getName())
                statement.executeQuery().use { rs ->
                    while (rs.next()) {
                        val ownerId = UUID.fromString(rs.getString("owner_id"))
                        val tool = rs.getString("tool")
                        val location = Location(world, rs.getInt("lx").toDouble(), rs.getInt("ly").toDouble(), rs.getInt("lz").toDouble())

                        // A chest of another sector's world is not loaded here, like a minion of another sector.
                        val chestWorldName = rs.getString("cworld")
                        val chestLocation = if (chestWorldName != null && chestWorldName.startsWith("$serverId:")) {
                            Bukkit.getWorld(chestWorldName.removePrefix("$serverId:"))?.let {
                                Location(it, rs.getInt("cx").toDouble(), rs.getInt("cy").toDouble(), rs.getInt("cz").toDouble())
                            }
                        } else null

                        com.artillexstudios.axminions.minions.Minion(
                            location,
                            ownerId,
                            Bukkit.getOfflinePlayer(ownerId),
                            minionType,
                            rs.getShort("level").toInt(),
                            if (tool != null) Serializers.ITEM_STACK.deserialize(tool) else ItemStack(Material.AIR),
                            chestLocation,
                            Direction.entries[rs.getByte("direction").toInt()],
                            rs.getLong("actions"),
                            rs.getDouble("storage"),
                            rs.getInt("location_id"),
                            rs.getInt("chest_location_id"), // NULL reads as 0, like H2
                            rs.getLong("charge")
                        )
                    }
                }
            }
        }
    }

    // LAST_INSERT_ID(id) makes getGeneratedKeys return the id of the existing row as well
    override fun getLocationID(location: Location): Int {
        val worldName = location.world?.name ?: return 0
        return query { c ->
            var worldId = 0
            c.prepareStatement("INSERT INTO `axminions_worlds`(`name`) VALUES(?) ON DUPLICATE KEY UPDATE `id` = LAST_INSERT_ID(`id`);", Statement.RETURN_GENERATED_KEYS).use { st ->
                st.setString(1, worldKey(worldName))
                st.executeUpdate()
                st.generatedKeys.use { if (it.next()) worldId = it.getInt(1) }
            }
            if (worldId == 0) return@query 0

            c.prepareStatement("INSERT INTO `axminions_locations`(`x`, `y`, `z`, `world_id`) VALUES (?,?,?,?) ON DUPLICATE KEY UPDATE `id` = LAST_INSERT_ID(`id`);", Statement.RETURN_GENERATED_KEYS).use { st ->
                st.setInt(1, location.blockX)
                st.setInt(2, location.blockY)
                st.setInt(3, location.blockZ)
                st.setInt(4, worldId)
                st.executeUpdate()
                st.generatedKeys.use { if (it.next()) it.getInt(1) else 0 }
            }
        }
    }

    override fun getLocation(locationId: Int): Location? = query { c ->
        c.prepareStatement("SELECT * FROM `axminions_locations` WHERE `id` = ?;").use { st ->
            st.setInt(1, locationId)
            st.executeQuery().use { rs ->
                if (!rs.next()) null
                else Location(getWorld(rs.getInt("world_id")), rs.getInt("x").toDouble(), rs.getInt("y").toDouble(), rs.getInt("z").toDouble())
            }
        }
    }

    override fun getWorld(worldId: Int): World? = query { c ->
        c.prepareStatement("SELECT `name` FROM `axminions_worlds` WHERE `id` = ?;").use { st ->
            st.setInt(1, worldId)
            st.executeQuery().use { rs ->
                if (!rs.next()) null
                else {
                    val stored = rs.getString("name")
                    // a world of another sector is simply not loaded here
                    if (!stored.startsWith("$serverId:")) null else Bukkit.getWorld(stored.removePrefix("$serverId:"))
                }
            }
        }
    }

    override fun saveMinion(minion: com.artillexstudios.axminions.api.minions.Minion) {
        val locationId = getLocationID(minion.getLocation())
        if (locationId == 0) return
        val linkedChestId = minion.getLinkedChest()?.let { getLocationID(it) }
        val extra = getExtraSlots(minion.getOwnerUUID())
        val owner = minion.getOwnerUUID().toString()

        query { c ->
            c.prepareStatement("INSERT INTO `axminions_users`(`uuid`, `name`, `island_slots`) VALUES (?,?,?) ON DUPLICATE KEY UPDATE `name` = VALUES(`name`);").use { st ->
                st.setString(1, owner)
                st.setString(2, minion.getOwner()?.name ?: "---")
                st.setInt(3, extra)
                st.executeUpdate()
            }

            c.prepareStatement("INSERT INTO `axminions_types`(`name`) VALUES(?) ON DUPLICATE KEY UPDATE `name` = `name`;").use {
                it.setString(1, minion.getType().getName())
                it.executeUpdate()
            }
            var typeId = 0
            c.prepareStatement("SELECT `id` FROM `axminions_types` WHERE `name` = ?;").use { st ->
                st.setString(1, minion.getType().getName())
                st.executeQuery().use { if (it.next()) typeId = it.getInt(1) }
            }

            c.prepareStatement(
                "INSERT INTO `axminions_minions`(`location_id`, `chest_location_id`, `owner_id`, `type_id`, `direction`, `level`, `storage`, `actions`, `tool`, `charge`) " +
                        "VALUES(?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE `chest_location_id` = VALUES(`chest_location_id`), `owner_id` = VALUES(`owner_id`), " +
                        "`type_id` = VALUES(`type_id`), `direction` = VALUES(`direction`), `level` = VALUES(`level`), `storage` = VALUES(`storage`), " +
                        "`actions` = VALUES(`actions`), `tool` = VALUES(`tool`), `charge` = VALUES(`charge`);"
            ).use { st ->
                st.setInt(1, locationId)
                if (linkedChestId == null || linkedChestId == 0) st.setNull(2, Types.INTEGER) else st.setInt(2, linkedChestId)
                st.setString(3, owner)
                st.setInt(4, typeId)
                st.setByte(5, minion.getDirection().ordinal.toByte())
                st.setInt(6, minion.getLevel())
                st.setDouble(7, minion.getStorage())
                st.setLong(8, minion.getActionAmount())
                if (minion.getTool() == null || minion.getTool()?.type == Material.AIR) st.setNull(9, Types.LONGVARCHAR)
                else st.setString(9, Serializers.ITEM_STACK.serialize(minion.getTool()))
                st.setLong(10, minion.getCharge())
                st.executeUpdate()
            }
        }
    }

    override fun deleteMinion(minion: com.artillexstudios.axminions.api.minions.Minion) {
        query { c ->
            c.prepareStatement("DELETE FROM `axminions_minions` WHERE `location_id` = ?;").use {
                it.setInt(1, minion.getLocationId())
                it.executeUpdate()
            }
            // a location row only goes away when nothing points at it any more (as a minion or as a linked chest)
            for (id in listOf(minion.getLocationId(), minion.getChestLocationId()).filter { it != 0 }.distinct()) {
                c.prepareStatement(
                    "DELETE FROM `axminions_locations` WHERE `id` = ? AND NOT EXISTS (SELECT 1 FROM `axminions_minions` WHERE `location_id` = ? OR `chest_location_id` = ?);"
                ).use {
                    it.setInt(1, id); it.setInt(2, id); it.setInt(3, id)
                    it.executeUpdate()
                }
            }
        }
    }

    override fun getMinionAmount(uuid: UUID): Int = query { c ->
        c.prepareStatement("SELECT COUNT(*) FROM `axminions_minions` WHERE `owner_id` = ?;").use { st ->
            st.setString(1, uuid.toString())
            st.executeQuery().use { if (it.next()) it.getInt(1) else 0 }
        }
    }

    override fun isMinion(location: Location): Boolean = query { c ->
        c.prepareStatement(
            "SELECT 1 FROM `axminions_minions` m JOIN `axminions_locations` l ON m.`location_id` = l.`id` " +
                    "JOIN `axminions_worlds` w ON l.`world_id` = w.`id` WHERE l.`x` = ? AND l.`y` = ? AND l.`z` = ? AND w.`name` = ? LIMIT 1;"
        ).use { st ->
            st.setInt(1, location.blockX)
            st.setInt(2, location.blockY)
            st.setInt(3, location.blockZ)
            st.setString(4, worldKey(location.world?.name ?: "---"))
            st.executeQuery().use { it.next() }
        }
    }

    // atomic, two sectors counting the same island never lose an update
    override fun islandPlace(island: String) {
        query { c ->
            c.prepareStatement("INSERT INTO `axminions_island_counter`(`island`, `placed`) VALUES(?, 1) ON DUPLICATE KEY UPDATE `placed` = `placed` + 1;").use {
                it.setString(1, island)
                it.executeUpdate()
            }
        }
    }

    override fun islandReset(island: String) {
        query { c ->
            c.prepareStatement("UPDATE `axminions_island_counter` SET `placed` = 0 WHERE `island` = ?;").use {
                it.setString(1, island)
                it.executeUpdate()
            }
        }
    }

    override fun islandBreak(island: String) {
        query { c ->
            c.prepareStatement("UPDATE `axminions_island_counter` SET `placed` = GREATEST(`placed` - 1, 0) WHERE `island` = ?;").use {
                it.setString(1, island)
                it.executeUpdate()
            }
        }
    }

    override fun getIsland(island: String): Int = query { c ->
        c.prepareStatement("SELECT `placed` FROM `axminions_island_counter` WHERE `island` = ?;").use { st ->
            st.setString(1, island)
            st.executeQuery().use { if (it.next()) it.getInt("placed") else 0 }
        }
    }

    override fun addUser(uuid: UUID, name: String) {
        query { c ->
            c.prepareStatement("INSERT IGNORE INTO `axminions_users`(`uuid`, `name`, `island_slots`) VALUES (?,?,0);").use {
                it.setString(1, uuid.toString())
                it.setString(2, name.take(16))
                it.executeUpdate()
            }
        }
    }

    override fun addExtraSlot(user: UUID, amount: Int) {
        query { c ->
            c.prepareStatement("UPDATE `axminions_users` SET `island_slots` = `island_slots` + ? WHERE `uuid` = ?;").use {
                it.setInt(1, amount)
                it.setString(2, user.toString())
                it.executeUpdate()
            }
        }
    }

    override fun getExtraSlots(user: UUID): Int = query { c ->
        c.prepareStatement("SELECT `island_slots` FROM `axminions_users` WHERE `uuid` = ?;").use { st ->
            st.setString(1, user.toString())
            st.executeQuery().use { if (it.next()) it.getInt("island_slots") else 0 }
        }
    }

    override fun disable() {
        if (::dataSource.isInitialized) dataSource.close()
    }
}
