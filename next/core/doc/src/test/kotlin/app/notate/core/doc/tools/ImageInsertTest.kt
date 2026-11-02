package app.notate.core.doc.tools

import app.notate.core.geometry.Rect
import app.notate.core.model.AssetId
import app.notate.core.model.ImageItem
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ImageInsertTest {
    private val asset = AssetId("A1")
    private val pageW = 595.28f
    private val pageH = 841.89f

    @Test fun widthIsCappedAtSixtyPercentAndRatioKept() = runTest {
        val r = ToolRig(this)
        val e = ImageInsert.place(r.editor, r.p1, asset, 3000, 1500, Rect(0f, 0f, pageW, pageH))!!
        val img = e.added.single() as ImageItem
        assertThat(img.frame.width).isWithin(0.01f).of(pageW * 0.6f)
        assertThat(img.frame.height / img.frame.width).isWithin(0.001f).of(0.5f)
        assertThat(img.asset).isEqualTo(asset)
        assertThat(img.locked).isFalse()
        assertThat(img.opacity).isEqualTo(1f)
    }

    @Test fun smallImageIsNotScaledUp() = runTest {
        val r = ToolRig(this)
        val img = ImageInsert.place(r.editor, r.p1, asset, 100, 80, Rect(0f, 0f, pageW, pageH))!!.added.single() as ImageItem
        assertThat(img.frame.width).isEqualTo(100f)
        assertThat(img.frame.height).isEqualTo(80f)
    }

    @Test fun centredOnViewport() = runTest {
        val r = ToolRig(this)
        val img = ImageInsert.place(r.editor, r.p1, asset, 200, 100, Rect(100f, 300f, 500f, 700f))!!.added.single() as ImageItem
        assertThat(img.frame.center.x).isWithin(0.01f).of(300f)
        assertThat(img.frame.center.y).isWithin(0.01f).of(500f)
    }

    @Test fun staysInsidePageWhenViewportHangsOver() = runTest {
        val r = ToolRig(this)
        val img = ImageInsert.place(r.editor, r.p1, asset, 400, 300, Rect(-500f, -500f, 100f, 100f))!!.added.single() as ImageItem
        assertThat(img.frame.l).isAtLeast(0f)
        assertThat(img.frame.t).isAtLeast(0f)
        val img2 = ImageInsert.place(r.editor, r.p1, asset, 400, 300, Rect(500f, 800f, 1500f, 1800f))!!.added.single() as ImageItem
        assertThat(img2.frame.r).isAtMost(pageW + 0.01f)
        assertThat(img2.frame.b).isAtMost(pageH + 0.01f)
    }

    @Test fun veryTallImageFitsPageHeight() = runTest {
        val r = ToolRig(this)
        val img = ImageInsert.place(r.editor, r.p1, asset, 500, 5000, Rect(0f, 0f, pageW, pageH))!!.added.single() as ImageItem
        assertThat(img.frame.height).isAtMost(pageH)
        assertThat(img.frame.t).isAtLeast(0f)
        assertThat(img.frame.b).isAtMost(pageH + 0.01f)
    }

    @Test fun zeroSizeOrUnknownPageGivesNull() = runTest {
        val r = ToolRig(this)
        assertThat(ImageInsert.place(r.editor, r.p1, asset, 0, 10, Rect(0f, 0f, 10f, 10f))).isNull()
        assertThat(ImageInsert.place(r.editor, app.notate.core.model.PageId(99), asset, 10, 10, Rect(0f, 0f, 10f, 10f))).isNull()
    }

    @Test fun editAppliesAndUndoes() = runTest {
        val r = ToolRig(this)
        r.editor.apply(ImageInsert.place(r.editor, r.p1, asset, 200, 100, Rect(0f, 0f, pageW, pageH))!!)
        assertThat(r.items()).hasSize(1)
        r.editor.undo()
        assertThat(r.items()).isEmpty()
    }
}
