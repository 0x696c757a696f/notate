package app.notate.core.doc.select

import app.notate.core.geometry.Rect
import app.notate.core.geometry.Vec2
import app.notate.core.model.AssetId
import app.notate.core.model.ImageItem
import app.notate.core.model.ItemId
import app.notate.core.model.Stroke
import app.notate.core.store.AssetKind
import app.notate.core.testing.stroke
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.File
import java.nio.file.Files

class InkClipboardTest {
    @Test fun pasteInSameDocumentGetsNewIdsAndCentersOnPoint() = runTest {
        val rig = SelectRig(this, listOf(stroke(1, points = floatArrayOf(100f, 100f, 200f, 100f))))
        val clip = InkClipboard()
        rig.controller.selectByRect(rig.p1, Rect(90f, 80f, 210f, 120f))
        rig.controller.copy(clip)
        val ids = clip.paste(rig.editor, rig.p1, Vec2(400f, 500f))
        assertThat(ids).hasSize(1)
        assertThat(ids.single()).isNotEqualTo(ItemId(1))
        val pasted = rig.items().first { it.id == ids.single() } as Stroke
        assertThat(pasted.bounds.center.x).isWithin(1e-2f).of(400f)
        assertThat(pasted.bounds.center.y).isWithin(1e-2f).of(500f)
        assertThat(pasted.order).isGreaterThan(rig.items().first { it.id == ItemId(1) }.order)
        rig.editor.undo()
        assertThat(rig.items()).hasSize(1)
    }

    @Test fun pasteIntoAnotherDocumentCopiesImageAsset() = runTest {
        val file = Files.createTempFile("img", ".png").toFile().apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val src = SelectRig(this)
        val assetId = src.store.putAsset(AssetKind.IMAGE, "image/png", file, "png")
        val img = ImageItem(ItemId(1), 1, assetId, Rect(10f, 10f, 110f, 110f), 0f, 1f, false, Rect(10f, 10f, 110f, 110f))
        src.editor.apply(app.notate.core.model.edit.Edit.Items(src.p1, emptyList(), listOf(img)))
        val clip = InkClipboard()
        src.controller.selectByRect(src.p1, Rect(0f, 0f, 120f, 120f))
        src.controller.copy(clip)

        val dst = SelectRig(this, docId = "01TESTDOC000000000000000B")
        assertThat(dst.editor.assetFile(assetId)).isNull()
        val ids = clip.paste(dst.editor, dst.p1, Vec2(300f, 300f))
        assertThat(ids).hasSize(1)
        val f: File? = dst.editor.assetFile(assetId)
        assertThat(f).isNotNull()
        assertThat(f!!.readBytes()).isEqualTo(byteArrayOf(1, 2, 3, 4))
        assertThat((dst.items().single() as ImageItem).asset).isEqualTo(AssetId(assetId.value))
    }

    @Test fun emptyClipboardPastesNothing() = runTest {
        val rig = SelectRig(this)
        assertThat(InkClipboard().paste(rig.editor, rig.p1, Vec2(0f, 0f))).isEmpty()
    }
}
