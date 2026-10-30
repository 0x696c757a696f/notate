package com.alexdremov.notate.data

import android.content.Context
import com.alexdremov.notate.model.PenTool
import com.alexdremov.notate.model.ShapeType
import com.alexdremov.notate.model.ToolbarItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ToolbarShapeButtonsTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("notate_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun shapeTypes(items: List<ToolbarItem>) =
        items.filterIsInstance<ToolbarItem.Pen>().map { it.penTool.shapeType }.filter { it != ShapeType.NONE }

    @Test
    fun `default toolbar has separate shape buttons`() {
        val types = shapeTypes(PreferencesManager.getToolbarItems(context))
        assertEquals(listOf(ShapeType.AUTO) + PenTool.DEFAULT_SHAPE_BUTTONS, types)
    }

    @Test
    fun `old saved toolbar gets shape buttons once, after the last pen`() {
        val old =
            listOf(
                ToolbarItem.Pen(PenTool.defaultPens()[0]),
                ToolbarItem.Eraser(PenTool.defaultPens().first { it.id == "eraser_std" }),
            )
        PreferencesManager.saveToolbarItems(context, old)

        val migrated = PreferencesManager.getToolbarItems(context)
        assertEquals(listOf(ShapeType.AUTO) + PenTool.DEFAULT_SHAPE_BUTTONS, shapeTypes(migrated))
        // Inserted between the pen and the eraser
        assertTrue(migrated.first() is ToolbarItem.Pen && (migrated.first() as ToolbarItem.Pen).penTool.shapeType == ShapeType.NONE)
        assertTrue(migrated.last() is ToolbarItem.Eraser)

        // User removes the polygon button: it must not come back
        val withoutPolygon =
            migrated.filterNot { it is ToolbarItem.Pen && it.penTool.shapeType == ShapeType.POLYGON }
        PreferencesManager.saveToolbarItems(context, withoutPolygon)
        assertTrue(ShapeType.POLYGON !in shapeTypes(PreferencesManager.getToolbarItems(context)))
    }

    @Test
    fun `existing shape pen is not duplicated`() {
        PreferencesManager.saveToolbarItems(context, listOf(ToolbarItem.Pen(PenTool.defaultShapeTool("mine", ShapeType.AUTO))))
        val types = shapeTypes(PreferencesManager.getToolbarItems(context))
        assertEquals(1, types.count { it == ShapeType.AUTO })
    }
}
