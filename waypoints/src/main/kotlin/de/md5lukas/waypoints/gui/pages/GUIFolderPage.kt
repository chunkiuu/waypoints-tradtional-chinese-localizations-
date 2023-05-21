package de.md5lukas.waypoints.gui.pages

import com.okkero.skedule.SynchronizationContext
import com.okkero.skedule.switchContext
import com.okkero.skedule.withSynchronizationContext
import de.md5lukas.kinvs.GUIPattern
import de.md5lukas.kinvs.items.GUIItem
import de.md5lukas.signgui.SignGUI
import de.md5lukas.waypoints.WaypointsPermissions
import de.md5lukas.waypoints.api.Folder
import de.md5lukas.waypoints.api.Type
import de.md5lukas.waypoints.api.WaypointHolder
import de.md5lukas.waypoints.api.WaypointsPlayer
import de.md5lukas.waypoints.api.gui.GUIDisplayable
import de.md5lukas.waypoints.api.gui.GUIFolder
import de.md5lukas.waypoints.gui.WaypointsGUI
import de.md5lukas.waypoints.gui.items.CycleSortItem
import de.md5lukas.waypoints.gui.items.ToggleGlobalsItem
import de.md5lukas.waypoints.util.asSingletonList
import de.md5lukas.waypoints.util.checkFolderName
import de.md5lukas.waypoints.util.checkMaterialForCustomIcon
import de.md5lukas.waypoints.util.checkWorldAvailability
import de.md5lukas.waypoints.util.component1
import de.md5lukas.waypoints.util.component2
import de.md5lukas.waypoints.util.isLocationOutOfBounds
import de.md5lukas.waypoints.util.onClickSuspending
import de.md5lukas.waypoints.util.parseLocationString
import de.md5lukas.waypoints.util.placeholder
import de.md5lukas.waypoints.util.plainDisplayName
import de.md5lukas.waypoints.util.replaceInputText
import de.md5lukas.waypoints.util.scheduler
import net.wesjd.anvilgui.AnvilGUI
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.inventory.ItemStack

class GUIFolderPage(wpGUI: WaypointsGUI, private val guiFolder: GUIFolder) :
    ListingPage<GUIDisplayable>(
        wpGUI,
        wpGUI.extendApi { guiFolder.type.getBackgroundItem() },
        { wpGUI.getListingContent(guiFolder) },
        wpGUI::toGUIContent) {

  private companion object {
    /**
     * spotless:off
     * Overview / Folder
     * p = Previous
     * f = Create Folder / Delete Folder
     * s = Cycle Sort
     * d = Deselect active waypoint / Edit description
     * i = None / Folder Icon
     * t = Toggle Globals / Rename
     * w = Create Waypoint / Create waypoint in folder
     * b = None / Back
     * n = Next
     * spotless:on
     */
    val controlsPattern = GUIPattern("pfsditwbn")
  }

  private val isOverview = guiFolder is WaypointHolder
  private val isPlayerOverview = guiFolder is WaypointsPlayer

  private val canModify =
      when (guiFolder.type) {
        Type.PRIVATE ->
            wpGUI.isOwner && wpGUI.viewer.hasPermission(WaypointsPermissions.MODIFY_PRIVATE)
        Type.DEATH -> false
        Type.PUBLIC -> wpGUI.viewer.hasPermission(WaypointsPermissions.MODIFY_PUBLIC)
        Type.PERMISSION -> wpGUI.viewer.hasPermission(WaypointsPermissions.MODIFY_PERMISSION)
      }

  override fun update() {
    wpGUI.skedule {
      updateListingContent()
      updateControls()
    }
  }

  private suspend fun updateControls(update: Boolean = true) {
    applyPattern(
        controlsPattern,
        4,
        0,
        background,
        'p' to GUIItem(wpGUI.translations.GENERAL_PREVIOUS.item) { previousPage() },
        'f' to
            if (canModify) {
              if (isOverview) {
                GUIItem(wpGUI.translations.OVERVIEW_CREATE_FOLDER.item) {
                  wpGUI.openCreateFolder(guiFolder as WaypointHolder)
                }
              } else {
                GUIItem(wpGUI.translations.FOLDER_DELETE.item) {
                  val nameResolver = "name" placeholder guiFolder.name
                  wpGUI.open(
                      ConfirmPage(
                          wpGUI,
                          wpGUI.translations.FOLDER_DELETE_CONFIRM_QUESTION.getItem(nameResolver),
                          wpGUI.translations.FOLDER_DELETE_CONFIRM_FALSE.getItem(nameResolver),
                          wpGUI.translations.FOLDER_DELETE_CONFIRM_TRUE.getItem(nameResolver),
                      ) {
                        if (it) {
                          wpGUI.skedule {
                            (guiFolder as Folder).delete()
                            switchContext(SynchronizationContext.SYNC)
                            wpGUI.goBack()
                            wpGUI.goBack()
                          }
                        } else {
                          wpGUI.goBack()
                        }
                      })
                }
              }
            } else {
              background
            },
        's' to
            CycleSortItem(wpGUI) {
              listingContent.sortWith(it)
              wpGUI.skedule { updateListingInInventory() }
            },
        'd' to
            if (isOverview) {
              GUIItem(wpGUI.translations.OVERVIEW_DESELECT.item) {
                wpGUI.plugin.pointerManager.disable(wpGUI.viewer) { true }
              }
            } else if (canModify &&
                wpGUI.plugin.server.pluginManager.isPluginEnabled("ProtocolLib") &&
                guiFolder is Folder) {
              GUIItem(wpGUI.translations.FOLDER_EDIT_DESCRIPTION.item) {
                wpGUI.viewer.closeInventory()
                val builder =
                    SignGUI.newBuilder().plugin(wpGUI.plugin).player(wpGUI.viewer).onClose { lines
                      ->
                      wpGUI.skedule {
                        if (lines.all(String::isBlank)) {
                          guiFolder.setDescription(null)
                        } else {
                          guiFolder.setDescription(lines.joinToString("\n"))
                        }
                        updateControls()
                        switchContext(SynchronizationContext.SYNC)
                        wpGUI.gui.open()
                      }
                    }
                guiFolder.description?.let { description -> builder.lines(description.split('\n')) }
                builder.open()
              }
            } else {
              background
            },
        'i' to
            if (isOverview) {
              background
            } else {
              wpGUI.extendApi {
                GUIItem(
                    (guiFolder as Folder).getItem(wpGUI.viewer),
                    if (canModify) {
                      {
                        val newMaterial = wpGUI.viewer.inventory.itemInMainHand.type

                        if (checkMaterialForCustomIcon(wpGUI.plugin, newMaterial)) {
                          wpGUI.skedule {
                            guiFolder.setMaterial(newMaterial)
                            updateControls()
                          }
                        } else {
                          wpGUI.translations.FOLDER_NEW_ICON_INVALID.send(wpGUI.viewer)
                        }
                      }
                    } else null)
              }
            },
        't' to
            if (wpGUI.isOwner && isPlayerOverview) {
              if (wpGUI.plugin.waypointsConfig.general.features.globalWaypoints) {
                ToggleGlobalsItem(wpGUI) { wpGUI.skedule { updateListingContent() } }
              } else {
                background
              }
            } else {
              if (guiFolder is Folder && canModify) {
                GUIItem(wpGUI.translations.FOLDER_RENAME.item) {
                  wpGUI.viewer.closeInventory()
                  AnvilGUI.Builder()
                      .plugin(wpGUI.plugin)
                      .itemLeft(
                          ItemStack(Material.PAPER).also { it.plainDisplayName = guiFolder.name })
                      .onClickSuspending(wpGUI.scheduler) { slot, (isOutputInvalid, name) ->
                        if (slot != AnvilGUI.Slot.OUTPUT || isOutputInvalid)
                            return@onClickSuspending emptyList()

                        val holder = wpGUI.getHolderForType(guiFolder.type)

                        if (checkFolderName(wpGUI.plugin, holder, name)) {
                          guiFolder.setName(name)

                          updateControls()
                        } else {
                          when (guiFolder.type) {
                            Type.PRIVATE -> wpGUI.translations.FOLDER_NAME_DUPLICATE_PRIVATE
                            Type.PUBLIC -> wpGUI.translations.FOLDER_NAME_DUPLICATE_PUBLIC
                            Type.PERMISSION -> wpGUI.translations.FOLDER_NAME_DUPLICATE_PERMISSION
                            else ->
                                throw IllegalArgumentException(
                                    "Folders of the type ${guiFolder.type} have no name")
                          }.send(wpGUI.viewer)
                        }

                        return@onClickSuspending AnvilGUI.ResponseAction.close().asSingletonList()
                      }
                      .onClose { wpGUI.schedule { wpGUI.gui.open() } }
                      .open(wpGUI.viewer)
                }
              } else {
                background
              }
            },
        'w' to
            if (canModify &&
                (checkWorldAvailability(wpGUI.plugin, wpGUI.viewer.world) ||
                    wpGUI.viewer.hasPermission(WaypointsPermissions.MODIFY_ANYWHERE))) {
              GUIItem(wpGUI.translations.OVERVIEW_SET_WAYPOINT.item) {
                if (it.isShiftClick) {
                  var parsedLocation: Location? = null
                  AnvilGUI.Builder()
                      .plugin(wpGUI.plugin)
                      .scheduler(wpGUI.scheduler)
                      .itemLeft(
                          ItemStack(Material.PAPER).also { stack ->
                            stack.plainDisplayName =
                                wpGUI.translations.WAYPOINT_CREATE_ENTER_COORDINATES.rawText
                          })
                      .onClick { slot, (isOutputInvalid, coordinates) ->
                        if (slot != AnvilGUI.Slot.OUTPUT || isOutputInvalid)
                            return@onClick emptyList()

                        parsedLocation = parseLocationString(wpGUI.viewer, coordinates)

                        return@onClick parsedLocation
                            .let { location ->
                              if (location === null) {
                                wpGUI.translations.WAYPOINT_CREATE_COORDINATES_INVALID_FORMAT.send(
                                    wpGUI.viewer)
                                replaceInputText(coordinates)
                              } else if (isLocationOutOfBounds(location)) {
                                wpGUI.translations.WAYPOINT_CREATE_COORDINATES_OUT_OF_BOUNDS.send(
                                    wpGUI.viewer)
                                replaceInputText(coordinates)
                              } else {
                                AnvilGUI.ResponseAction.close()
                              }
                            }
                            .asSingletonList()
                      }
                      .onClose {
                        parsedLocation.let { location ->
                          if (location === null) {
                            wpGUI.goBack()
                            wpGUI.schedule { wpGUI.gui.open() }
                          } else {
                            wpGUI.openCreateWaypoint(
                                guiFolder.type,
                                if (guiFolder is Folder) guiFolder else null,
                                location)
                          }
                        }
                      }
                      .open(wpGUI.viewer)
                } else {
                  wpGUI.openCreateWaypoint(
                      guiFolder.type, if (guiFolder is Folder) guiFolder else null)
                }
              }
            } else {
              background
            },
        'b' to
            if (isPlayerOverview) {
              background
            } else {
              GUIItem(wpGUI.translations.GENERAL_BACK.item) { wpGUI.goBack() }
            },
        'n' to GUIItem(wpGUI.translations.GENERAL_NEXT.item) { nextPage() },
    )

    if (update) {
      withSynchronizationContext(SynchronizationContext.SYNC) { wpGUI.gui.update() }
    }
  }

  override suspend fun init() {
    super.init()
    updateListingInInventory()
    updateControls(false)
  }
}
