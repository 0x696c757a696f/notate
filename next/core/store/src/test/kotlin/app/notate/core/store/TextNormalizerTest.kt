package app.notate.core.store

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TextNormalizerTest {
    @Test fun lowerCaseAndWhitespace() {
        assertThat(TextNormalizer.normalize("  Hello \n WORLD\t")).isEqualTo("hello world")
    }

    @Test fun removesAccents() {
        assertThat(TextNormalizer.normalize("Café Résumé Ångström")).isEqualTo("cafe resume angstrom")
    }

    @Test fun foldsFullWidth() {
        assertThat(TextNormalizer.normalize("ＡＢＣ１２３")).isEqualTo("abc123")
    }

    @Test fun splitsCjkPerCharacter() {
        assertThat(TextNormalizer.normalize("定积分公式")).isEqualTo("定 积 分 公 式")
        assertThat(TextNormalizer.normalize("x积分y")).isEqualTo("x 积 分 y")
    }

    @Test fun keepsHangulComposed() {
        assertThat(TextNormalizer.normalize("한글")).isEqualTo("한글")
    }

    @Test fun matchQuery() {
        assertThat(TextNormalizer.toMatchQuery("Integ")).isEqualTo("\"integ*\"")
        assertThat(TextNormalizer.toMatchQuery("积分 Cafe")).isEqualTo("\"积 分*\" \"cafe*\"")
        assertThat(TextNormalizer.toMatchQuery("say \"hi\"")).isEqualTo("\"say*\" \"hi*\"")
        assertThat(TextNormalizer.toMatchQuery("   \"\"  ")).isEmpty()
    }
}
