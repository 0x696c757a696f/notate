@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.alexdremov.notate.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.alexdremov.notate.model.ActionType
import com.alexdremov.notate.model.PenTool
import com.alexdremov.notate.model.StylusButtonAction
import com.alexdremov.notate.model.Tag
import com.alexdremov.notate.model.ToolbarItem
import com.alexdremov.notate.model.TwoFingerTapAction
import com.alexdremov.notate.ui.input.Handedness
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber

/** What a finger does on the page. */
enum class FingerMode { SCROLL, OFF }

/** Screen rotation policy. AUTO follows the sensor even when the system's auto-rotate toggle is off (Boox default). */
enum class ScreenOrientationMode { AUTO, PORTRAIT, LANDSCAPE }

@Serializable
enum class SortOption(
    val displayName: String,
) {
    NAME_ASC("Name (A-Z)"),
    NAME_DESC("Name (Z-A)"),
    DATE_NEWEST("Date (Newest)"),
    DATE_OLDEST("Date (Oldest)"),
}

@Serializable
data class ProjectConfig(
    @ProtoNumber(1)
    val id: String,
    @ProtoNumber(2)
    val name: String,
    @ProtoNumber(3)
    val uri: String,
)

@Serializable
data class FavoriteColors(
    @ProtoNumber(1)
    val colors: List<Int>,
)

object PreferencesManager {
    private const val PREFS_NAME = "notate_prefs"
    private const val KEY_PROJECTS = "projects_list"
    private const val KEY_TOOLBAR_ITEMS = "toolbar_items_config"
    private const val KEY_SHAPE_BUTTONS_MIGRATED = "toolbar_shape_buttons_migrated_v1"
    private const val KEY_ZOOM_BUTTON_MIGRATED = "toolbar_zoom_button_migrated_v1"
    private const val KEY_GUIDES_BUTTON_MIGRATED = "toolbar_guides_button_migrated_v1"
    private const val KEY_PEN_WIDTH_MIGRATED = "toolbar_pen_width_04mm_migrated_v1"
    private const val KEY_COLORS = "favorite_colors"
    private const val KEY_TAGS = "defined_tags"
    private const val KEY_SORT_OPTION = "browser_sort_option"
    private const val KEY_SCRIBBLE_TO_ERASE = "scribble_to_erase"
    private const val KEY_PALM_REJECTION = "palm_rejection_enabled"
    private const val KEY_FINGER_MODE = "finger_mode"
    private const val KEY_SCREEN_ORIENTATION = "screen_orientation"
    private const val KEY_HANDEDNESS = "handedness"
    private const val KEY_SHOW_FINGER_MIGRATION_BANNER = "show_finger_migration_banner"
    private const val KEY_DISTRACTION_FREE = "distraction_free_enabled"
    private const val KEY_OPEN_DAILY_ON_START = "open_daily_on_start"
    private const val KEY_FAVORITES = "favorite_notes"
    private const val KEY_RECENTS = "recent_notes"
    private const val MAX_RECENTS = 12
    private const val KEY_SHAPE_PERFECTION_ENABLED = "shape_perfection_enabled"
    private const val KEY_SHAPE_PERFECTION_DELAY = "shape_perfection_delay"
    private const val KEY_ANGLE_SNAPPING = "angle_snapping_enabled"
    private const val KEY_AXIS_LOCKING = "axis_locking_enabled"
    private const val KEY_COLLAPSIBLE_TOOLBAR = "collapsible_toolbar_enabled"
    private const val KEY_TOOLBAR_COLLAPSE_TIMEOUT = "toolbar_collapse_timeout"
    private const val KEY_MIN_LOG_LEVEL = "min_log_level_to_show"
    private const val KEY_PDF_EXPORT_SCALE = "pdf_export_scale"
    private const val KEY_SYNC_PDF_TYPE = "sync_pdf_type"
    private const val KEY_FIXED_PAGE_CENTER_HORIZONTAL = "fixed_page_center_horizontal"
    private const val KEY_BACKGROUND_OCR_INDEXING = "background_ocr_indexing"
    private const val KEY_TWO_FINGER_TAP_ACTION = "two_finger_tap_action"
    private const val KEY_STYLUS_BUTTON_ACTION = "stylus_button_action"

    // Debug Preferences
    private const val KEY_DEBUG_USE_SIMPLE_RENDERER = "debug_use_simple_renderer"
    private const val KEY_DEBUG_SHOW_RAM_USAGE = "debug_show_ram_usage"
    private const val KEY_DEBUG_SHOW_TILES = "debug_show_tiles"
    private const val KEY_DEBUG_SHOW_BOUNDING_BOX = "debug_show_bounding_box"
    private const val KEY_DEBUG_SHOW_REGIONS = "debug_show_regions"
    private const val KEY_DEBUG_ENABLE_PROFILING = "debug_enable_profiling"

    private const val KEY_FLOAT_WINDOW_RECT = "float_window_rect"

    private val protoBuf = ProtoBuf

    private fun getPrefs(context: Context): SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getSortOption(context: Context): SortOption {
        val name = getPrefs(context).getString(KEY_SORT_OPTION, SortOption.DATE_NEWEST.name)
        return try {
            SortOption.valueOf(name ?: SortOption.DATE_NEWEST.name)
        } catch (e: Exception) {
            SortOption.DATE_NEWEST
        }
    }

    fun saveSortOption(
        context: Context,
        option: SortOption,
    ) {
        getPrefs(context).edit().putString(KEY_SORT_OPTION, option.name).apply()
    }

    fun getTwoFingerTapAction(context: Context): TwoFingerTapAction {
        val name = getPrefs(context).getString(KEY_TWO_FINGER_TAP_ACTION, TwoFingerTapAction.UNDO.name)
        return try {
            TwoFingerTapAction.valueOf(name ?: TwoFingerTapAction.UNDO.name)
        } catch (e: Exception) {
            TwoFingerTapAction.UNDO
        }
    }

    fun setTwoFingerTapAction(
        context: Context,
        action: TwoFingerTapAction,
    ) {
        getPrefs(context).edit().putString(KEY_TWO_FINGER_TAP_ACTION, action.name).apply()
    }

    fun getStylusButtonAction(context: Context): StylusButtonAction {
        val name = getPrefs(context).getString(KEY_STYLUS_BUTTON_ACTION, StylusButtonAction.TEMPORARY_ERASER.name)
        return try {
            StylusButtonAction.valueOf(name ?: StylusButtonAction.TEMPORARY_ERASER.name)
        } catch (e: Exception) {
            StylusButtonAction.TEMPORARY_ERASER
        }
    }

    fun setStylusButtonAction(
        context: Context,
        action: StylusButtonAction,
    ) {
        getPrefs(context).edit().putString(KEY_STYLUS_BUTTON_ACTION, action.name).apply()
    }

    fun getPdfExportScale(context: Context): Float = getPrefs(context).getFloat(KEY_PDF_EXPORT_SCALE, 2.0f)

    fun setPdfExportScale(
        context: Context,
        scale: Float,
    ) {
        getPrefs(context).edit().putFloat(KEY_PDF_EXPORT_SCALE, scale).apply()
    }

    fun getSyncPdfType(context: Context): String = getPrefs(context).getString(KEY_SYNC_PDF_TYPE, "VECTOR") ?: "VECTOR"

    fun setSyncPdfType(
        context: Context,
        type: String,
    ) {
        getPrefs(context).edit().putString(KEY_SYNC_PDF_TYPE, type).apply()
    }

    fun isFixedPageCenterHorizontalEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_FIXED_PAGE_CENTER_HORIZONTAL, true)

    fun setFixedPageCenterHorizontalEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        getPrefs(context).edit().putBoolean(KEY_FIXED_PAGE_CENTER_HORIZONTAL, enabled).apply()
    }

    fun isBackgroundOcrIndexingEnabled(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_BACKGROUND_OCR_INDEXING, true)

    fun setBackgroundOcrIndexingEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        getPrefs(context).edit().putBoolean(KEY_BACKGROUND_OCR_INDEXING, enabled).apply()
    }

    fun getMinLogLevel(context: Context): Int = getPrefs(context).getInt(KEY_MIN_LOG_LEVEL, 4) // Default to NONE (4)

    fun setMinLogLevel(
        context: Context,
        level: Int,
    ) {
        getPrefs(context).edit().putInt(KEY_MIN_LOG_LEVEL, level).apply()
    }

    // --- Debug Preferences Accessors ---

    fun isDebugSimpleRendererEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_DEBUG_USE_SIMPLE_RENDERER, false)

    fun setDebugSimpleRendererEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        getPrefs(context).edit().putBoolean(KEY_DEBUG_USE_SIMPLE_RENDERER, enabled).apply()
    }

    fun isDebugRamUsageEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_DEBUG_SHOW_RAM_USAGE, false)

    fun setDebugRamUsageEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        getPrefs(context).edit().putBoolean(KEY_DEBUG_SHOW_RAM_USAGE, enabled).apply()
    }

    fun isDebugShowTilesEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_DEBUG_SHOW_TILES, false)

    fun setDebugShowTilesEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        getPrefs(context).edit().putBoolean(KEY_DEBUG_SHOW_TILES, enabled).apply()
    }

    fun isDebugShowRegionsEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_DEBUG_SHOW_REGIONS, false)

    fun setDebugShowRegionsEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        getPrefs(context).edit().putBoolean(KEY_DEBUG_SHOW_REGIONS, enabled).apply()
    }

    fun isDebugBoundingBoxEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_DEBUG_SHOW_BOUNDING_BOX, false)

    fun setDebugBoundingBoxEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        getPrefs(context).edit().putBoolean(KEY_DEBUG_SHOW_BOUNDING_BOX, enabled).apply()
    }

    fun isDebugProfilingEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_DEBUG_ENABLE_PROFILING, false)

    fun setDebugProfilingEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        getPrefs(context).edit().putBoolean(KEY_DEBUG_ENABLE_PROFILING, enabled).apply()
    }

    fun isScribbleToEraseEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_SCRIBBLE_TO_ERASE, true)

    fun setScribbleToEraseEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        getPrefs(context).edit().putBoolean(KEY_SCRIBBLE_TO_ERASE, enabled).apply()
    }

    /**
     * Replaces the old "Palm Rejection (stylus only)" switch, which swallowed every finger touch. Palm
     * rejection is now automatic; the only finger choice left is scroll-and-zoom or off. The old switch
     * being on maps to SCROLL as well, and shows a one-time banner explaining the change.
     */
    fun migratePalmRejectionSetting(context: Context) {
        val prefs = getPrefs(context)
        if (prefs.contains(KEY_FINGER_MODE)) return
        val wasOn = prefs.getBoolean(KEY_PALM_REJECTION, false)
        prefs
            .edit()
            .putString(KEY_FINGER_MODE, FingerMode.SCROLL.name)
            .putBoolean(KEY_SHOW_FINGER_MIGRATION_BANNER, wasOn)
            .remove(KEY_PALM_REJECTION)
            .apply()
    }

    fun getFingerMode(context: Context): FingerMode {
        migratePalmRejectionSetting(context)
        val name = getPrefs(context).getString(KEY_FINGER_MODE, FingerMode.SCROLL.name)
        return try {
            FingerMode.valueOf(name ?: FingerMode.SCROLL.name)
        } catch (e: Exception) {
            FingerMode.SCROLL
        }
    }

    fun setFingerMode(
        context: Context,
        mode: FingerMode,
    ) {
        migratePalmRejectionSetting(context)
        getPrefs(context).edit().putString(KEY_FINGER_MODE, mode.name).apply()
    }

    fun getScreenOrientation(context: Context): ScreenOrientationMode =
        try {
            ScreenOrientationMode.valueOf(
                getPrefs(context).getString(KEY_SCREEN_ORIENTATION, ScreenOrientationMode.AUTO.name) ?: ScreenOrientationMode.AUTO.name,
            )
        } catch (e: Exception) {
            ScreenOrientationMode.AUTO
        }

    fun setScreenOrientation(
        context: Context,
        mode: ScreenOrientationMode,
    ) {
        getPrefs(context).edit().putString(KEY_SCREEN_ORIENTATION, mode.name).apply()
    }

    fun isFingerOff(context: Context): Boolean = getFingerMode(context) == FingerMode.OFF

    fun getHandedness(context: Context): Handedness {
        val name = getPrefs(context).getString(KEY_HANDEDNESS, Handedness.RIGHT.name)
        return try {
            Handedness.valueOf(name ?: Handedness.RIGHT.name)
        } catch (e: Exception) {
            Handedness.RIGHT
        }
    }

    fun setHandedness(
        context: Context,
        handedness: Handedness,
    ) {
        getPrefs(context).edit().putString(KEY_HANDEDNESS, handedness.name).apply()
    }

    /** True once after an upgrade from the old switch being on; reading it clears it. */
    fun consumeFingerMigrationBanner(context: Context): Boolean {
        migratePalmRejectionSetting(context)
        val prefs = getPrefs(context)
        val show = prefs.getBoolean(KEY_SHOW_FINGER_MIGRATION_BANNER, false)
        if (show) prefs.edit().putBoolean(KEY_SHOW_FINGER_MIGRATION_BANNER, false).apply()
        return show
    }

    fun isDistractionFreeEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_DISTRACTION_FREE, false)

    fun setDistractionFreeEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        getPrefs(context).edit().putBoolean(KEY_DISTRACTION_FREE, enabled).apply()
    }

    fun isOpenDailyOnStartEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_OPEN_DAILY_ON_START, false)

    fun setOpenDailyOnStartEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        getPrefs(context).edit().putBoolean(KEY_OPEN_DAILY_ON_START, enabled).apply()
    }

    fun getFavorites(context: Context): Set<String> = getPrefs(context).getStringSet(KEY_FAVORITES, emptySet())?.toSet() ?: emptySet()

    fun isFavorite(
        context: Context,
        key: String,
    ): Boolean = getFavorites(context).contains(key)

    fun setFavorite(
        context: Context,
        key: String,
        favorite: Boolean,
    ) {
        val updated = HashSet(getFavorites(context))
        if (favorite) updated.add(key) else updated.remove(key)
        getPrefs(context).edit().putStringSet(KEY_FAVORITES, updated).apply()
    }

    fun getRecents(context: Context): List<String> {
        val data = getPrefs(context).getString(KEY_RECENTS, null)
        if (data.isNullOrEmpty()) return emptyList()
        return data.split("\n").filter { it.isNotEmpty() }
    }

    fun addRecent(
        context: Context,
        key: String,
    ) {
        if (key.isEmpty()) return
        val current = getRecents(context).toMutableList()
        current.remove(key)
        current.add(0, key)
        val capped = current.take(MAX_RECENTS)
        getPrefs(context).edit().putString(KEY_RECENTS, capped.joinToString("\n")).apply()
    }

    fun isCollapsibleToolbarEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_COLLAPSIBLE_TOOLBAR, false)

    fun setCollapsibleToolbarEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        getPrefs(context).edit().putBoolean(KEY_COLLAPSIBLE_TOOLBAR, enabled).apply()
    }

    fun getToolbarCollapseTimeout(context: Context): Long {
        return getPrefs(context).getLong(KEY_TOOLBAR_COLLAPSE_TIMEOUT, 3000L) // Default 3000ms
    }

    fun setToolbarCollapseTimeout(
        context: Context,
        timeoutMs: Long,
    ) {
        getPrefs(context).edit().putLong(KEY_TOOLBAR_COLLAPSE_TIMEOUT, timeoutMs).apply()
    }

    fun isAngleSnappingEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_ANGLE_SNAPPING, true)

    fun setAngleSnappingEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        getPrefs(context).edit().putBoolean(KEY_ANGLE_SNAPPING, enabled).apply()
    }

    fun isAxisLockingEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_AXIS_LOCKING, true)

    fun setAxisLockingEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        getPrefs(context).edit().putBoolean(KEY_AXIS_LOCKING, enabled).apply()
    }

    fun isShapePerfectionEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_SHAPE_PERFECTION_ENABLED, true)

    fun setShapePerfectionEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        getPrefs(context).edit().putBoolean(KEY_SHAPE_PERFECTION_ENABLED, enabled).apply()
    }

    fun getShapePerfectionDelay(context: Context): Long {
        return getPrefs(context).getLong(KEY_SHAPE_PERFECTION_DELAY, 600L) // Default 600ms
    }

    fun setShapePerfectionDelay(
        context: Context,
        delayMs: Long,
    ) {
        getPrefs(context).edit().putLong(KEY_SHAPE_PERFECTION_DELAY, delayMs).apply()
    }

    fun getProjects(context: Context): List<ProjectConfig> {
        val data = getPrefs(context).getString(KEY_PROJECTS, null) ?: return emptyList()
        return try {
            val bytes = Base64.decode(data, Base64.DEFAULT)
            protoBuf.decodeFromByteArray(ListSerializer(ProjectConfig.serializer()), bytes)
        } catch (e: Exception) {
            // Fallback to JSON for migration
            try {
                val projects = Json.decodeFromString<List<ProjectConfig>>(data)
                saveProjects(context, projects) // Resave in new format
                projects
            } catch (jsonError: Exception) {
                emptyList()
            }
        }
    }

    fun addProject(
        context: Context,
        config: ProjectConfig,
    ) {
        val current = getProjects(context).toMutableList()
        current.add(config)
        saveProjects(context, current)
    }

    fun removeProject(
        context: Context,
        id: String,
    ) {
        val current = getProjects(context).toMutableList()
        current.removeAll { it.id == id }
        saveProjects(context, current)
    }

    fun updateProject(
        context: Context,
        config: ProjectConfig,
    ) {
        val current = getProjects(context).toMutableList()
        val index = current.indexOfFirst { it.id == config.id }
        if (index != -1) {
            current[index] = config
            saveProjects(context, current)
        }
    }

    private fun saveProjects(
        context: Context,
        list: List<ProjectConfig>,
    ) {
        val bytes = protoBuf.encodeToByteArray(ListSerializer(ProjectConfig.serializer()), list)
        val string = Base64.encodeToString(bytes, Base64.DEFAULT)
        getPrefs(context).edit().putString(KEY_PROJECTS, string).apply()
    }

    // --- Tags Persistence ---

    fun getTags(context: Context): List<Tag> {
        val data = getPrefs(context).getString(KEY_TAGS, null) ?: return emptyList()
        return try {
            val bytes = Base64.decode(data, Base64.DEFAULT)
            protoBuf.decodeFromByteArray(ListSerializer(Tag.serializer()), bytes).sortedBy { it.order }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveTags(
        context: Context,
        tags: List<Tag>,
    ) {
        val bytes = protoBuf.encodeToByteArray(ListSerializer(Tag.serializer()), tags)
        val string = Base64.encodeToString(bytes, Base64.DEFAULT)
        getPrefs(context).edit().putString(KEY_TAGS, string).apply()
    }

    // --- Toolbox Persistence ---

    fun saveToolbarItems(
        context: Context,
        items: List<com.alexdremov.notate.model.ToolbarItem>,
    ) {
        val bytes = protoBuf.encodeToByteArray(ListSerializer(ToolbarItem.serializer()), items)
        val string = Base64.encodeToString(bytes, Base64.DEFAULT)
        getPrefs(context).edit().putString(KEY_TOOLBAR_ITEMS, string).apply()
    }

    fun getToolbarItems(context: Context): List<com.alexdremov.notate.model.ToolbarItem> {
        val data = getPrefs(context).getString(KEY_TOOLBAR_ITEMS, null)
        if (data != null) {
            return try {
                val bytes = Base64.decode(data, Base64.DEFAULT)
                val items = protoBuf.decodeFromByteArray(ListSerializer(ToolbarItem.serializer()), bytes)
                var migrated = migrateShapeButtons(context, items)
                migrated = migrateActionButton(context, migrated, ActionType.ZOOM_WINDOW, KEY_ZOOM_BUTTON_MIGRATED)
                migrated = migrateActionButton(context, migrated, ActionType.GUIDES, KEY_GUIDES_BUTTON_MIGRATED)
                migrateDefaultPenWidth(context, migrated)
            } catch (e: Exception) {
                // Fallback to JSON
                try {
                    val items = Json.decodeFromString<List<com.alexdremov.notate.model.ToolbarItem>>(data)
                    saveToolbarItems(context, items) // Resave
                    items
                } catch (jsonError: Exception) {
                    defaultToolbarItems()
                }
            }
        }
        // Defaults already include the Shapes and Lines buttons; don't re-add them if the user removes them later.
        getPrefs(context)
            .edit()
            .putBoolean(KEY_SHAPE_BUTTONS_MIGRATED, true)
            .putBoolean(KEY_ZOOM_BUTTON_MIGRATED, true)
            .putBoolean(KEY_GUIDES_BUTTON_MIGRATED, true)
            .putBoolean(KEY_PEN_WIDTH_MIGRATED, true)
            .apply()
        return defaultToolbarItems()
    }

    /**
     * One-time upgrade of pens still at the old, too heavy default widths (10 px pens, 6 px shapes and lines)
     * to [PenTool.DEFAULT_PEN_WIDTH_MM]. Widths the user picked themselves are kept.
     */
    private fun migrateDefaultPenWidth(
        context: Context,
        items: List<ToolbarItem>,
    ): List<ToolbarItem> {
        val prefs = getPrefs(context)
        if (prefs.getBoolean(KEY_PEN_WIDTH_MIGRATED, false)) return items

        val newWidth = PenTool.mmToDefaultPx(PenTool.DEFAULT_PEN_WIDTH_MM)
        var changed = false
        val result =
            items.map { item ->
                val tool = (item as? ToolbarItem.Pen)?.penTool ?: return@map item
                val oldDefault = if (tool.isShapeTool) 6f else 10f
                val isWritingPen = tool.type == com.alexdremov.notate.model.ToolType.PEN &&
                    tool.strokeType != com.alexdremov.notate.model.StrokeType.HIGHLIGHTER
                if (isWritingPen && tool.width == oldDefault) {
                    changed = true
                    ToolbarItem.Pen(tool.copy(width = newWidth))
                } else {
                    item
                }
            }
        if (changed) saveToolbarItems(context, result)
        prefs.edit().putBoolean(KEY_PEN_WIDTH_MIGRATED, true).apply()
        return result
    }

    /**
     * One-time upgrade for toolbars saved before the Shapes and Lines buttons existed: adds whichever is missing
     * right after the last pen. Runs once, so a button the user removes afterwards stays removed.
     */
    private fun migrateShapeButtons(
        context: Context,
        items: List<ToolbarItem>,
    ): List<ToolbarItem> {
        val prefs = getPrefs(context)
        if (prefs.getBoolean(KEY_SHAPE_BUTTONS_MIGRATED, false)) return items

        val shapePens = items.filterIsInstance<ToolbarItem.Pen>().map { it.penTool }.filter { it.isShapeTool }
        val toAdd = mutableListOf<ToolbarItem>()
        if (shapePens.none { !it.shapeType.isLine } && items.none { it.id == "shape_tool" }) {
            toAdd.add(ToolbarItem.Pen(PenTool.defaultShapeTool("shape_tool")))
        }
        if (shapePens.none { it.shapeType.isLine } && items.none { it.id == "line_tool" }) {
            toAdd.add(ToolbarItem.Pen(PenTool.defaultShapeTool("line_tool", com.alexdremov.notate.model.ShapeType.LINE)))
        }

        val result = items.toMutableList()
        if (toAdd.isNotEmpty()) {
            result.addAll(result.indexOfLast { it is ToolbarItem.Pen } + 1, toAdd)
            saveToolbarItems(context, result)
        }
        prefs.edit().putBoolean(KEY_SHAPE_BUTTONS_MIGRATED, true).apply()
        return result
    }

    /**
     * One-time upgrade for toolbars saved before [action] existed (zoom writing window, ruler/protractor):
     * adds its button after the last action button (or at the end). Removing it later is respected.
     */
    private fun migrateActionButton(
        context: Context,
        items: List<ToolbarItem>,
        action: ActionType,
        flagKey: String,
    ): List<ToolbarItem> {
        val prefs = getPrefs(context)
        if (prefs.getBoolean(flagKey, false)) return items

        val button = ToolbarItem.Action(action)
        val result = items.toMutableList()
        if (items.none { it.id == button.id }) {
            val lastAction = result.indexOfLast { it is ToolbarItem.Action }
            result.add(if (lastAction >= 0) lastAction + 1 else result.size, button)
            saveToolbarItems(context, result)
        }
        prefs.edit().putBoolean(flagKey, true).apply()
        return result
    }

    private fun defaultToolbarItems(): List<com.alexdremov.notate.model.ToolbarItem> {
        val items = mutableListOf<com.alexdremov.notate.model.ToolbarItem>()
        val defaultPens = PenTool.defaultPens()

        defaultPens.forEach { tool ->
            when (tool.type) {
                com.alexdremov.notate.model.ToolType.PEN -> {
                    items.add(
                        com.alexdremov.notate.model.ToolbarItem
                            .Pen(tool),
                    )
                }

                com.alexdremov.notate.model.ToolType.ERASER -> {
                    items.add(
                        com.alexdremov.notate.model.ToolbarItem
                            .Eraser(tool),
                    )
                }

                com.alexdremov.notate.model.ToolType.SELECT -> {
                    items.add(
                        com.alexdremov.notate.model.ToolbarItem
                            .Select(tool),
                    )
                }

                com.alexdremov.notate.model.ToolType.TEXT -> {
                    items.add(
                        com.alexdremov.notate.model.ToolbarItem
                            .Pen(tool), // Text is treated as a Pen tool in ToolbarItem for now or needs a new wrapper
                    )
                }
            }
        }

        items.add(
            com.alexdremov.notate.model.ToolbarItem
                .Action(com.alexdremov.notate.model.ActionType.UNDO),
        )
        items.add(
            com.alexdremov.notate.model.ToolbarItem
                .Action(com.alexdremov.notate.model.ActionType.REDO),
        )
        items.add(
            com.alexdremov.notate.model.ToolbarItem
                .Action(com.alexdremov.notate.model.ActionType.ZOOM_WINDOW),
        )
        items.add(
            com.alexdremov.notate.model.ToolbarItem
                .Action(com.alexdremov.notate.model.ActionType.GUIDES),
        )
        items.add(
            com.alexdremov.notate.model.ToolbarItem
                .Widget(com.alexdremov.notate.model.WidgetType.PAGE_NAVIGATION),
        )
        return items
    }

    // --- Favorite Colors Persistence ---

    fun saveFavoriteColors(
        context: Context,
        colors: List<Int>,
    ) {
        val wrapper = FavoriteColors(colors)
        val bytes = protoBuf.encodeToByteArray(FavoriteColors.serializer(), wrapper)
        val string = Base64.encodeToString(bytes, Base64.DEFAULT)
        getPrefs(context).edit().putString(KEY_COLORS, string).apply()
    }

    fun getFavoriteColors(context: Context): List<Int> {
        val data = getPrefs(context).getString(KEY_COLORS, null) ?: return defaultColors()
        return try {
            val bytes = Base64.decode(data, Base64.DEFAULT)
            protoBuf.decodeFromByteArray(FavoriteColors.serializer(), bytes).colors
        } catch (e: Exception) {
            // Fallback to JSON
            try {
                val colors = Json.decodeFromString<List<Int>>(data)
                saveFavoriteColors(context, colors) // Resave
                colors
            } catch (jsonError: Exception) {
                defaultColors()
            }
        }
    }

    private fun defaultColors(): List<Int> =
        listOf(
            android.graphics.Color.BLACK,
            android.graphics.Color.parseColor("#424242"), // Dark Gray
            android.graphics.Color.parseColor("#1A237E"), // Navy Blue
            android.graphics.Color.parseColor("#B71C1C"), // Dark Red
            android.graphics.Color.parseColor("#1B5E20"), // Forest Green
            android.graphics.Color.parseColor("#2196F3"), // Blue
            android.graphics.Color.parseColor("#4CAF50"), // Green
            android.graphics.Color.parseColor("#BBDEFB"), // Pastel Blue
            android.graphics.Color.parseColor("#FFCDD2"), // Pastel Pink
            android.graphics.Color.parseColor("#FFF9C4"), // Pastel Yellow
        )

    fun saveFloatingWindowRect(
        context: Context,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
    ) {
        val str = "$x,$y,$w,$h"
        getPrefs(context).edit().putString(KEY_FLOAT_WINDOW_RECT, str).apply()
    }

    fun getFloatingWindowRect(context: Context): IntArray? {
        val str = getPrefs(context).getString(KEY_FLOAT_WINDOW_RECT, null) ?: return null
        return try {
            val parts = str.split(",")
            if (parts.size == 4) {
                intArrayOf(parts[0].toInt(), parts[1].toInt(), parts[2].toInt(), parts[3].toInt())
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}
