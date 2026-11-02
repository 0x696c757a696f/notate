package app.notate.editor

import android.media.ExifInterface
import app.notate.editor.image.ImageImport
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ImageImportTest {
    @Test fun smallImagesAreNotScaled() {
        assertThat(ImageImport.scaledSize(1000, 800)).isEqualTo(1000 to 800)
        assertThat(ImageImport.scaledSize(4096, 100)).isEqualTo(4096 to 100)
    }

    @Test fun longestSideIsCappedKeepingRatio() {
        assertThat(ImageImport.scaledSize(8192, 4096)).isEqualTo(4096 to 2048)
        assertThat(ImageImport.scaledSize(3000, 6000)).isEqualTo(2048 to 4096)
    }

    @Test fun quarterTurnsSwapAxes() {
        assertThat(ImageImport.swapsAxes(ExifInterface.ORIENTATION_ROTATE_90)).isTrue()
        assertThat(ImageImport.swapsAxes(ExifInterface.ORIENTATION_ROTATE_270)).isTrue()
        assertThat(ImageImport.swapsAxes(ExifInterface.ORIENTATION_NORMAL)).isFalse()
        assertThat(ImageImport.swapsAxes(ExifInterface.ORIENTATION_ROTATE_180)).isFalse()
    }

    @Test fun orientationMatrixRotatesPortraitPhotoUpright() {
        val m = ImageImport.orientationMatrix(ExifInterface.ORIENTATION_ROTATE_90)
        val p = floatArrayOf(4f, 0f)                      // top-right corner of a 4×2 landscape bitmap
        m.mapPoints(p)
        assertThat(p[0]).isWithin(0.001f).of(0f)         // a 90° turn maps it below the origin
        assertThat(p[1]).isWithin(0.001f).of(4f)
        assertThat(ImageImport.orientationMatrix(ExifInterface.ORIENTATION_NORMAL).isIdentity).isTrue()
    }
}
