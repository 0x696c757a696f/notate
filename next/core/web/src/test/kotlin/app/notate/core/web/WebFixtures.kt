package app.notate.core.web

import org.kxml2.io.KXmlParser
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object WebFixtures {
    val parserFactory: () -> XmlPullParser = { KXmlParser() }

    fun zip(entries: Map<String, String>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            for ((name, body) in entries) {
                zos.putNextEntry(ZipEntry(name)); zos.write(body.toByteArray(Charsets.UTF_8)); zos.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    fun write(root: File, files: Map<String, String>) = files.forEach { (rel, body) ->
        File(root, rel).also { it.parentFile.mkdirs() }.writeText(body)
    }

    const val CONTAINER = """<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>"""

    fun opf(fixed: Boolean = false, epub2: Boolean = false) = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="${if (epub2) "2.0" else "3.0"}" unique-identifier="uid">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>交互物理</dc:title>
    <dc:creator>Dr. Lin</dc:creator>
    ${if (fixed) "<meta property=\"rendition:layout\">pre-paginated</meta><meta property=\"rendition:viewport\">width=1200, height=1600</meta>" else ""}
    ${if (epub2) "<meta name=\"cover\" content=\"cov\"/>" else ""}
  </metadata>
  <manifest>
    ${if (epub2) "<item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>" else "<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>"}
    <item id="cov" href="img/cover.png" media-type="image/png" ${if (epub2) "" else "properties=\"cover-image\""}/>
    <item id="c1" href="text/ch%201.xhtml" media-type="application/xhtml+xml" properties="scripted"/>
    <item id="c2" href="text/ch2.xhtml" media-type="application/xhtml+xml" properties="scripted mathml"/>
    <item id="notes" href="text/notes.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine${if (epub2) " toc=\"ncx\"" else ""}>
    <itemref idref="c1"/>
    <itemref idref="notes" linear="no"/>
    <itemref idref="c2"/>
  </spine>
</package>"""

    const val NAV = """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body>
  <nav epub:type="landmarks"><ol><li><a href="text/ch2.xhtml">Landmark</a></li></ol></nav>
  <nav epub:type="toc"><ol>
    <li><a href="text/ch%201.xhtml">第一章&nbsp;运动学</a>
      <ol><li><a href="text/ch%201.xhtml#s1">1.1 速度</a></li></ol></li>
    <li><a href="text/ch2.xhtml#sec">第二章 <span>动力学</span></a></li>
  </ol></nav>
</body></html>"""

    const val NCX = """<?xml version="1.0"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/"><navMap>
  <navPoint id="p1"><navLabel><text>Chapter One</text></navLabel><content src="text/ch%201.xhtml"/>
    <navPoint id="p11"><navLabel><text>Section 1.1</text></navLabel><content src="text/ch%201.xhtml#s1"/></navPoint>
  </navPoint>
  <navPoint id="p2"><navLabel><text>Chapter Two</text></navLabel><content src="text/ch2.xhtml"/></navPoint>
</navMap></ncx>"""

    fun epubFiles(fixed: Boolean = false, epub2: Boolean = false): Map<String, String> = linkedMapOf(
        "mimetype" to "application/epub+zip",
        "META-INF/container.xml" to CONTAINER,
        "OEBPS/content.opf" to opf(fixed, epub2),
        (if (epub2) "OEBPS/toc.ncx" else "OEBPS/nav.xhtml") to (if (epub2) NCX else NAV),
        "OEBPS/img/cover.png" to "png",
        "OEBPS/text/ch 1.xhtml" to "<html><body><script type=\"module\" src=\"../js/app.mjs\"></script></body></html>",
        "OEBPS/text/ch2.xhtml" to "<html><body><math><mi>x</mi></math></body></html>",
        "OEBPS/text/notes.xhtml" to "<html><body/></html>",
    )

    fun epub(fixed: Boolean = false, epub2: Boolean = false): ByteArray = zip(epubFiles(fixed, epub2))
}
