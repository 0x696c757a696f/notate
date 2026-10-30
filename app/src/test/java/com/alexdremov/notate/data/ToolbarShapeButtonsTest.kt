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
    fun `default toolbar has one Shapes and one Lines button`() {
        assertEquals(listOf(ShapeType.AUTO, ShapeType.LINE), shapeTypes(PreferencesManager.getToolbarItems(context)))
    }

    @Test
    fun `old saved toolbar gets both buttons once, after the last pen`() {
        val old =
            listOf(
                ToolbarItem.Pen(PenTool.defaultPens()[0]),
                ToolbarItem.Eraser(PenTool.defaultPens().first { it.id == "eraser_std" }),
            )
        PreferencesManager.saveToolbarItems(context, old)

        val migrated = PreferencesManager.getToolbarItems(context)
        assertEquals(listOf(ShapeType.AUTO, ShapeType.LINE), shapeTypes(migrated))
        assertTrue(migrated.last() is ToolbarItem.Eraser)

        // User removes the Lines button: it must not come back
        PreferencesManager.saveToolbarItems(context, migrated.filterNot { it.id == "line_tool" })
        assertEquals(listOf(ShapeType.AUTO), shapeTypes(PreferencesManager.getToolbarItems(context)))
    }

    @Test
    fun `toolbar that already has a shape pen only gains the Lines button`() {
        PreferencesManager.saveToolbarItems(context, listOf(ToolbarItem.Pen(PenTool.defaultShapeTool("mine", ShapeType.POLYGON))))
        assertEquals(listOf(ShapeType.POLYGON, ShapeType.LINE), shapeTypes(PreferencesManager.getToolbarItems(context)))
    }

    @Test
    fun `line and shape families do not mix`() {
        assertTrue(ShapeType.entries.filter { it.isLine }.toSet() == setOf(ShapeType.LINE, ShapeType.ARROW, ShapeType.DOUBLE_ARROW))
    }
}
