package com.alexdremov.notate.webdoc

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object WebDocTestFixtures {
    fun zip(entries: Map<String, String>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            for ((name, body) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(body.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    val CONTAINER =
        """<?xml version="1.0"?>
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
        </container>"""

    fun opf(fixed: Boolean = false) =
        """<?xml version="1.0" encoding="UTF-8"?>
        <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">
          <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
            <dc:title>交互物理</dc:title>
            ${if (fixed) "<meta property=\"rendition:layout\">pre-paginated</meta>" else ""}
          </metadata>
          <manifest>
            <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
            <item id="c1" href="text/ch%201.xhtml" media-type="application/xhtml+xml" properties="scripted"/>
            <item id="c2" href="text/ch2.xhtml" media-type="application/xhtml+xml" properties="scripted mathml"/>
            <item id="notes" href="text/notes.xhtml" media-type="application/xhtml+xml"/>
            <item id="js" href="js/app.mjs" media-type="text/javascript"/>
          </manifest>
          <spine>
            <itemref idref="c1"/>
            <itemref idref="notes" linear="no"/>
            <itemref idref="c2"/>
          </spine>
        </package>"""

    val NAV =
        """<?xml version="1.0" encoding="UTF-8"?>
        <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body>
          <nav epub:type="toc"><ol>
            <li><a href="text/ch%201.xhtml">第一章&nbsp;运动学</a></li>
            <li><a href="text/ch2.xhtml#sec">第二章 <span>动力学</span></a></li>
          </ol></nav>
        </body></html>"""

    fun epub(fixed: Boolean = false) =
        zip(
            linkedMapOf(
                "mimetype" to "application/epub+zip",
                "META-INF/container.xml" to CONTAINER,
                "OEBPS/content.opf" to opf(fixed),
                "OEBPS/nav.xhtml" to NAV,
                "OEBPS/text/ch 1.xhtml" to "<html><body><script type=\"module\" src=\"../js/app.mjs\"></script></body></html>",
                "OEBPS/text/ch2.xhtml" to "<html><body><math><mi>x</mi></math></body></html>",
                "OEBPS/text/notes.xhtml" to "<html><body/></html>",
                "OEBPS/js/app.mjs" to "export const a = 1;",
            ),
        )
}
