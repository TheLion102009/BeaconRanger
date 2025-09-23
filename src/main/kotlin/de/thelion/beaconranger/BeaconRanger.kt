package de.thelion.beaconranger

import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.block.Beacon
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level

class BeaconRanger : JavaPlugin(), CommandExecutor, TabCompleter, Listener {

    companion object {
        @JvmStatic
        lateinit var instance: BeaconRanger
            private set
    }

    // Plugin components
    private var updateTask: Any? = null
    private var beaconRange: Int = 100
    private val beaconLocations = ConcurrentHashMap<org.bukkit.Location, Long>()
    private val debug: Boolean get() = config.getBoolean("debug", false)
    private var setEffectRangeMethod: Method? = null

    // Folia detection
    private val isFolia: Boolean by lazy {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer")
            true
        } catch (e: ClassNotFoundException) {
            false
        }
    }

    override fun onEnable() {
        try {
            instance = this
            logger.info("${ChatColor.GOLD}BeaconRanger v${description.version} wird gestartet...")
            logger.info("Server-Typ erkannt: ${if (isFolia) "Folia 1.21.x" else "Paper/Bukkit 1.20.x-1.21.x"}")

            // Load configuration
            saveDefaultConfig()
            loadConfig()

            // Initialize reflection method
            initializeReflection()

            // Register events and commands
            server.pluginManager.registerEvents(this, this)
            getCommand("beaconranger")?.setExecutor(this)
            getCommand("beaconranger")?.tabCompleter = this

            // Start update task if interval is greater than 0
            val updateInterval = config.getLong("update-interval", 300)
            if (updateInterval > 0) {
                startUpdateTask(updateInterval)
            }

            // Initial beacon scan (only for Paper/Bukkit)
            if (!isFolia) {
                server.scheduler.runTask(this, this::scanForBeacons)
            } else {
                logger.info("Folia-Modus: Beacons werden nur durch Events erkannt (kein globaler Scan)")
            }

            logger.info("${ChatColor.GREEN}BeaconRanger erfolgreich aktiviert!")

        } catch (e: Exception) {
            logger.log(Level.SEVERE, "Fehler beim Aktivieren des Plugins", e)
            server.pluginManager.disablePlugin(this)
        }
    }

    private fun startUpdateTask(intervalSeconds: Long) {
        if (isFolia) {
            // Folia: Use global region scheduler for beacon management
            try {
                val globalScheduler = server.javaClass.getMethod("getGlobalRegionScheduler").invoke(server)
                val runAtFixedRate = globalScheduler.javaClass.getMethod(
                    "runAtFixedRate",
                    org.bukkit.plugin.Plugin::class.java,
                    java.util.function.Consumer::class.java,
                    Long::class.javaPrimitiveType,
                    Long::class.javaPrimitiveType
                )

                updateTask = runAtFixedRate.invoke(
                    globalScheduler,
                    this,
                    java.util.function.Consumer<Any?> { _ -> updateBeaconsForFolia() },
                    20L, // Initial delay (1 second)
                    intervalSeconds * 20L // Repeat interval
                )

                if (debug) logger.info("Folia Update-Task gestartet ($intervalSeconds Sekunden)")
            } catch (e: Exception) {
                logger.log(Level.WARNING, "Fehler beim Starten des Folia Update-Tasks", e)
            }
        } else {
            // Paper/Bukkit: Use standard scheduler
            updateTask = server.scheduler.runTaskTimer(this, this::updateBeacons, 20L, intervalSeconds * 20L)
            if (debug) logger.info("Bukkit Update-Task gestartet ($intervalSeconds Sekunden)")
        }
    }

    private fun initializeReflection() {
        try {
            setEffectRangeMethod = Beacon::class.java.getDeclaredMethod("setEffectRange", Double::class.javaPrimitiveType)
            logger.info("setEffectRange Methode gefunden - direkte Reichweiten-Kontrolle verfügbar")
        } catch (e: NoSuchMethodException) {
            logger.info("setEffectRange Methode nicht verfügbar - verwende Fallback-Methode")
        }
    }

    private fun loadConfig() {
        reloadConfig()
        beaconRange = config.getInt("beacon-range", 100).coerceIn(10, 1000)

        // Save default values if they don't exist
        config.addDefault("beacon-range", 100)
        config.addDefault("update-interval", 300)
        config.addDefault("load-beacon-chunks", true)
        config.addDefault("debug", false)
        config.options().copyDefaults(true)
        saveConfig()

        if (debug) {
            logger.info("Konfiguration geladen - Reichweite: $beaconRange")
        }
    }

    // Paper/Bukkit beacon scanning (can access all worlds safely)
    private fun scanForBeacons() {
        beaconLocations.clear()
        val loadChunks = config.getBoolean("load-beacon-chunks", true)

        for (world in server.worlds) {
            try {
                for (chunk in world.loadedChunks) {
                    for (tileEntity in chunk.tileEntities) {
                        if (tileEntity is Beacon) {
                            val loc = tileEntity.location
                            beaconLocations[loc] = System.currentTimeMillis()
                            if (loadChunks) {
                                try {
                                    chunk.isForceLoaded = true
                                } catch (e: Exception) {
                                    if (debug) logger.log(Level.WARNING, "Fehler beim Force-Loading von Chunk", e)
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (debug) logger.log(Level.WARNING, "Fehler beim Scannen von Welt ${world.name}", e)
            }
        }

        if (debug) {
            logger.info("${beaconLocations.size} Beacons gefunden")
        }
    }

    // Paper/Bukkit beacon updates
    private fun updateBeacons() {
        if (debug) logger.info("Aktualisiere Beacons...")
        val toRemove = mutableSetOf<org.bukkit.Location>()

        beaconLocations.keys.forEach { loc ->
            try {
                val block = loc.block
                if (block.type == Material.BEACON) {
                    updateBeaconRange(block.state as Beacon)
                } else {
                    toRemove.add(loc)
                }
            } catch (e: Exception) {
                if (debug) logger.log(Level.WARNING, "Fehler beim Aktualisieren von Beacon bei $loc", e)
                toRemove.add(loc)
            }
        }

        toRemove.forEach { beaconLocations.remove(it) }
    }

    // Folia beacon updates (region-safe)
    private fun updateBeaconsForFolia() {
        if (debug) logger.info("Aktualisiere Beacons (Folia-Modus)...")
        val toRemove = mutableSetOf<org.bukkit.Location>()

        // In Folia, we can only safely update beacons that we know exist
        // We don't scan for new ones, only update tracked ones
        beaconLocations.keys.forEach { loc ->
            try {
                // Schedule region-specific update for each beacon
                scheduleRegionTask(loc) {
                    try {
                        val block = loc.block
                        if (block.type == Material.BEACON) {
                            updateBeaconRange(block.state as Beacon)
                        } else {
                            toRemove.add(loc)
                        }
                    } catch (e: Exception) {
                        if (debug) logger.log(Level.WARNING, "Fehler beim Aktualisieren von Beacon bei $loc", e)
                        toRemove.add(loc)
                    }
                }
            } catch (e: Exception) {
                if (debug) logger.log(Level.WARNING, "Fehler beim Planen der Beacon-Aktualisierung", e)
            }
        }

        // Clean up removed beacons
        toRemove.forEach { beaconLocations.remove(it) }
    }

    private fun updateBeaconRange(beacon: Beacon) {
        try {
            // Methode 1: Versuche setEffectRange falls verfügbar
            if (setEffectRangeMethod != null) {
                setEffectRangeMethod!!.invoke(beacon, beaconRange.toDouble())
                beacon.update(true, false)

                if (debug) {
                    logger.info("Beacon-Reichweite direkt gesetzt: ${beacon.location} -> $beaconRange Blöcke")
                }
                return
            }

            // Methode 2: Fallback - Beacon einfach aktualisieren
            beacon.update(true, false)

            if (debug) {
                logger.info("Beacon aktualisiert: ${beacon.location}")
            }

        } catch (e: Exception) {
            if (debug) {
                logger.warning("Fehler beim Aktualisieren des Beacons bei ${beacon.location}: ${e.message}")
            }
        }
    }

    @EventHandler
    fun onBlockPlace(event: BlockPlaceEvent) {
        if (event.block.type == Material.BEACON) {
            val beacon = event.block.state as? Beacon ?: return
            val location = beacon.location
            beaconLocations[location] = System.currentTimeMillis()

            // Schedule beacon update based on server type
            if (isFolia) {
                // Folia: Use region scheduler for the specific location
                scheduleRegionTask(location, 20L) {
                    try {
                        val updatedBeacon = location.block.state as? Beacon
                        if (updatedBeacon != null) {
                            updateBeaconRange(updatedBeacon)
                        }
                    } catch (e: Exception) {
                        if (debug) logger.log(Level.WARNING, "Fehler beim Aktualisieren des neuen Beacons", e)
                    }
                }
            } else {
                // Paper/Bukkit: Use standard scheduler
                server.scheduler.runTaskLater(this, Runnable {
                    updateBeaconRange(beacon)
                }, 20L)

                // Handle chunk loading for Paper/Bukkit
                if (config.getBoolean("load-beacon-chunks", true)) {
                    try {
                        event.block.chunk.isForceLoaded = true
                    } catch (e: Exception) {
                        if (debug) logger.log(Level.WARNING, "Fehler beim Force-Loading von Chunk", e)
                    }
                }
            }

            if (debug) {
                logger.info("Neuer Beacon platziert bei $location")
            }
        }
    }

    @EventHandler
    fun onBlockBreak(event: BlockBreakEvent) {
        if (event.block.type == Material.BEACON) {
            val location = event.block.location
            beaconLocations.remove(location)

            // Handle chunk unloading for Paper/Bukkit only
            if (!isFolia && config.getBoolean("load-beacon-chunks", true)) {
                val chunk = event.block.chunk
                val hasOtherBeacons = beaconLocations.keys.any {
                    it.chunk.x == chunk.x && it.chunk.z == chunk.z && it.world == chunk.world
                }
                if (!hasOtherBeacons) {
                    try {
                        chunk.isForceLoaded = false
                    } catch (e: Exception) {
                        if (debug) logger.log(Level.WARNING, "Fehler beim Entladen von Chunk", e)
                    }
                }
            }

            if (debug) {
                logger.info("Beacon entfernt bei $location")
            }
        }
    }

    // Folia-specific region task scheduling
    private fun scheduleRegionTask(location: org.bukkit.Location, delay: Long = 0L, task: () -> Unit) {
        if (!isFolia) {
            // Fallback for non-Folia
            if (delay > 0) {
                server.scheduler.runTaskLater(this, task, delay)
            } else {
                server.scheduler.runTask(this, task)
            }
            return
        }

        try {
            val regionScheduler = server.javaClass.getMethod("getRegionScheduler").invoke(server)

            if (delay > 0) {
                val runDelayed = regionScheduler.javaClass.getMethod(
                    "runDelayed",
                    org.bukkit.plugin.Plugin::class.java,
                    org.bukkit.Location::class.java,
                    java.util.function.Consumer::class.java,
                    Long::class.javaPrimitiveType
                )
                runDelayed.invoke(regionScheduler, this, location, java.util.function.Consumer<Any?> { _ -> task() }, delay)
            } else {
                val run = regionScheduler.javaClass.getMethod(
                    "run",
                    org.bukkit.plugin.Plugin::class.java,
                    org.bukkit.Location::class.java,
                    java.util.function.Consumer::class.java
                )
                run.invoke(regionScheduler, this, location, java.util.function.Consumer<Any?> { _ -> task() })
            }
        } catch (e: Exception) {
            if (debug) logger.log(Level.WARNING, "Fehler beim Planen der Region-Aufgabe", e)
            // Fallback: Run immediately
            task()
        }
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        if (!sender.hasPermission("beaconranger.admin")) {
            sender.sendMessage("${ChatColor.RED}Du hast keine Berechtigung für diesen Befehl!")
            return true
        }

        when (args.getOrNull(0)?.lowercase()) {
            "reload" -> {
                loadConfig()
                if (!isFolia) {
                    // Only scan in Paper/Bukkit
                    server.scheduler.runTask(this, this::scanForBeacons)
                } else {
                    // In Folia, just update existing beacons
                    sender.sendMessage("${ChatColor.YELLOW}Folia-Modus: Nur bestehende Beacons werden aktualisiert")
                }
                sender.sendMessage("${ChatColor.GREEN}Konfiguration wurde neu geladen!")
            }
            "size" -> {
                if (args.size > 1) {
                    try {
                        val newRange = args[1].toInt().coerceIn(10, 1000)
                        beaconRange = newRange
                        config.set("beacon-range", newRange)
                        saveConfig()

                        // Update existing beacons
                        if (isFolia) {
                            updateBeaconsForFolia()
                        } else {
                            updateBeacons()
                        }

                        sender.sendMessage("${ChatColor.GREEN}Beacon-Reichweite auf $newRange Blöcke gesetzt!")
                    } catch (e: NumberFormatException) {
                        sender.sendMessage("${ChatColor.RED}Bitte gib eine Zahl zwischen 10 und 1000 an!")
                    }
                } else {
                    sender.sendMessage("${ChatColor.YELLOW}Aktuelle Beacon-Reichweite: $beaconRange Blöcke")
                }
            }
            "info" -> {
                val chunkInfo = if (sender is Player) {
                    val chunk = sender.location.chunk
                    "Aktueller Chunk: ${chunk.x}, ${chunk.z} - "
                } else ""

                sender.sendMessage("${ChatColor.GOLD}=== BeaconRanger Info ===")
                sender.sendMessage("${ChatColor.YELLOW}Server-Typ: ${if (isFolia) "Folia 1.21.x" else "Paper/Bukkit 1.20.x-1.21.x"}")
                sender.sendMessage("${ChatColor.YELLOW}${chunkInfo}Reichweite: $beaconRange Blöcke")
                sender.sendMessage("${ChatColor.YELLOW}Geladene Beacons: ${beaconLocations.size}")
                sender.sendMessage("${ChatColor.YELLOW}Reichweiten-Methode: ${if (setEffectRangeMethod != null) "Direkt" else "Fallback"}")
                if (isFolia) {
                    sender.sendMessage("${ChatColor.AQUA}Folia-Modus: Region-basierte Verarbeitung")
                }
            }
            else -> showHelp(sender)
        }
        return true
    }

    private fun showHelp(sender: CommandSender) {
        sender.sendMessage("${ChatColor.GOLD}=== BeaconRanger Hilfe ===")
        sender.sendMessage("${ChatColor.YELLOW}/beac reload ${ChatColor.GRAY}- Lädt die Konfiguration neu")
        sender.sendMessage("${ChatColor.YELLOW}/beac size [Zahl] ${ChatColor.GRAY}- Ändert die Beacon-Reichweite (10-1000)")
        sender.sendMessage("${ChatColor.YELLOW}/beac info ${ChatColor.GRAY}- Zeigt Informationen an")
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<String>): List<String> {
        return try {
            if (!sender.hasPermission("beaconranger.admin")) {
                return emptyList()
            }

            if (args.size == 1) {
                return listOf("reload", "size", "info").filter { it.startsWith(args[0], ignoreCase = true) }
            }

            emptyList()
        } catch (e: Exception) {
            // Fallback bei Fehlern - verhindert den zip file error
            if (debug) logger.log(Level.WARNING, "Fehler bei Tab-Completion", e)
            emptyList()
        }
    }

    override fun onDisable() {
        try {
            // Cancel update task
            if (updateTask != null) {
                if (isFolia) {
                    try {
                        // Try to cancel Folia task
                        updateTask!!.javaClass.getMethod("cancel").invoke(updateTask)
                    } catch (e: Exception) {
                        if (debug) logger.log(Level.WARNING, "Fehler beim Beenden des Folia-Tasks", e)
                    }
                } else {
                    // Cancel Bukkit task
                    (updateTask as? BukkitTask)?.cancel()
                }
                updateTask = null
            }

            // Unload force-loaded chunks (only in Paper/Bukkit)
            if (!isFolia) {
                try {
                    for (world in server.worlds) {
                        for (chunk in world.loadedChunks) {
                            if (chunk.isForceLoaded) {
                                chunk.isForceLoaded = false
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (debug) logger.log(Level.WARNING, "Fehler beim Entladen der Chunks", e)
                }
            }

            beaconLocations.clear()
            logger.info("${ChatColor.GOLD}BeaconRanger wurde deaktiviert")
        } catch (e: Exception) {
            logger.log(Level.SEVERE, "Fehler beim Deaktivieren des Plugins", e)
        }
    }
}
