package app.notate.web

import android.webkit.WebResourceResponse
import app.notate.web.sandbox.SandboxClient
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
class SandboxClientTest {
    private val root = Files.createTempDirectory("webroot").toFile()
    private val id = "01HXYZABCDEFGHJKMNPQRSTVWX"
    private val origin = "https://01hxyzabcdefghjk.webdoc.notate/"
    private var network = false

    private val listener = object : SandboxClient.Listener {
        override fun onDocumentLink(path: String, fragment: String?) = false
        override fun onExternalLink(url: String) = Unit
        override fun onPageStarted(url: String) = Unit
        override fun onPageFinished(url: String) = Unit
        override fun onScaleChanged(scale: Float) = Unit
        override fun onRendererGone() = Unit
    }
    private val client = SandboxClient(id, root, { network }, listener, layoutWidthCss = { 800f })

    init {
        File(root, "ch1.xhtml").writeText("<html><head><meta name=\"viewport\" content=\"width=device-width\"/></head><body>é</body></html>")
        File(root, "css").mkdirs()
        File(root, "css/book.css").writeText("body{color:#000}")
        File(root, "clip.mp4").writeBytes(ByteArray(1000) { it.toByte() })
        File(root.parentFile, "secret.txt").writeText("secret")
    }

    @After fun cleanup() {
        root.deleteRecursively()
    }

    private fun text(r: WebResourceResponse) = r.data.readBytes().toString(Charsets.UTF_8)

    @Test fun sameOriginFileIsServedWithItsMimeType() {
        val r = client.respond(origin + "css/book.css", "GET", emptyMap())!!
        assertThat(r.statusCode).isEqualTo(200)
        assertThat(r.mimeType).isEqualTo("text/css")
        assertThat(text(r)).isEqualTo("body{color:#000}")
    }

    @Test fun missingFileIs404() {
        assertThat(client.respond(origin + "nope.html", "GET", emptyMap())!!.statusCode).isEqualTo(404)
    }

    @Test fun parentDirectoryAndEncodedTraversalAreRefused() {
        assertThat(client.respond(origin + "../secret.txt", "GET", emptyMap())!!.statusCode).isEqualTo(404)
        assertThat(client.respond(origin + "css/%2e%2e/%2e%2e/secret.txt", "GET", emptyMap())!!.statusCode).isEqualTo(404)
        assertThat(client.respond(origin + "css%2f..%2f..%2fsecret.txt", "GET", emptyMap())!!.statusCode).isEqualTo(404)
    }

    @Test fun externalRequestsAre403WhileTheNetworkIsOff() {
        assertThat(client.respond("https://example.com/x.js", "GET", emptyMap())!!.statusCode).isEqualTo(403)
        network = true
        assertThat(client.respond("https://example.com/x.js", "GET", emptyMap())).isNull()   // handed to the network
    }

    @Test fun countsBlockedRequestsAndListsMissingResources() {
        client.respond("https://example.com/a.js", "GET", emptyMap())
        client.respond("https://example.com/b.js", "GET", emptyMap())
        client.respond(origin + "nope.html", "GET", emptyMap())
        client.respond(origin + "nope.html", "GET", emptyMap())
        client.respond(origin + "css/book.css", "GET", emptyMap())
        assertThat(client.blockedRequests).isEqualTo(2)
        assertThat(client.failedResources).containsExactly("nope.html")
        network = true
        client.respond("https://example.com/c.js", "GET", emptyMap())
        assertThat(client.blockedRequests).isEqualTo(2)       // allowed requests are not counted
    }

    @Test fun anotherDocumentsOriginIsExternal() {
        assertThat(client.respond("https://zzzzzzzzzzzzzzzz.webdoc.notate/ch1.xhtml", "GET", emptyMap())!!.statusCode).isEqualTo(403)
    }

    @Test fun rangeRequestsReturnPartialContent() {
        val r = client.respond(origin + "clip.mp4", "GET", mapOf("Range" to "bytes=10-19"))!!
        assertThat(r.statusCode).isEqualTo(206)
        assertThat(r.responseHeaders["Content-Range"]).isEqualTo("bytes 10-19/1000")
        assertThat(r.data.readBytes().toList()).isEqualTo((10..19).map { it.toByte() })
        assertThat(client.respond(origin + "clip.mp4", "GET", mapOf("Range" to "bytes=5000-"))!!.statusCode).isEqualTo(416)
    }

    @Test fun mainFrameHtmlGetsTheLockedViewport() {
        val main = client.respond(origin + "ch1.xhtml", "GET", emptyMap(), mainFrame = true)!!
        val body = text(main)
        assertThat(body).contains("width=800")
        assertThat(body).doesNotContain("device-width")
        assertThat(body).contains("é")
        val frame = client.respond(origin + "ch1.xhtml", "GET", emptyMap(), mainFrame = false)!!
        assertThat(text(frame)).contains("device-width")
    }

    @Test fun onlyReadMethodsAreServed() {
        assertThat(client.respond(origin + "ch1.xhtml", "POST", emptyMap())!!.statusCode).isEqualTo(405)
    }
}
