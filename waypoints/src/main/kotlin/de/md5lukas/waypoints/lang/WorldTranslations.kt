package de.md5lukas.waypoints.lang

import de.md5lukas.waypoints.events.ConfigReloadEvent
import de.md5lukas.waypoints.util.registerEvents
import net.kyori.adventure.text.Component
import org.bukkit.World
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener

class WorldTranslations(
    private val tl: TranslationLoader
) : Listener {

    init {
        tl.plugin.registerEvents(this)
    }

    private val warned: MutableSet<World> = mutableSetOf()

    fun getWorldName(world: World): Component {
        if (world in warned)
            return Component.text(world.name)

        val key = "worlds.${world.name}"
        return if (key in tl) {
            Component.text(tl[key])
        } else {
            warned.add(world)
            tl.plugin.slF4JLogger.warn("The world {} has no translation present. Using actual name as a fallback.", world.name)
            Component.text(world.name)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    @EventHandler
    private fun onReload(e: ConfigReloadEvent) {
        warned.clear()
    }
}