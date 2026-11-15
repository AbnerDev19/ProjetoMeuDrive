package com.meudrive

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class MediaIndexTest {
    @get:Rule val temp = TemporaryFolder()
    private fun index(root: File) = MediaIndex({ root }) { StorageBoundary.resolve(root, it) }
    @Test fun recursivelyIndexesAndPaginatesAuthorizedMediaWithoutSymlinkDuplicates() {
        val root=temp.newFolder("shared")
        File(root,"DCIM/Camera").mkdirs()
        File(root,"DCIM/Camera/photo.jpg").writeText("photo")
        File(root,"DCIM/Camera/video.mp4").writeText("video")
        File(root,"doc.pdf").writeText("pdf")
        File(root,"ignore.svg").writeText("<svg/>")
        File(root,"Android/data/private.jpg").apply { parentFile!!.mkdirs(); writeText("private") }
        val outside=temp.newFolder("outside");File(outside,"secret.jpg").writeText("secret")
        Files.createSymbolicLink(File(root,"escape").toPath(),outside.toPath())
        Files.createSymbolicLink(File(root,"duplicate").toPath(),File(root,"DCIM").toPath())
        val index=index(root)
        val first=index.page("all",0,2)
        assertEquals(3,first.total);assertEquals(2,first.items.size);assertEquals(2,first.nextOffset);assertFalse(first.partial)
        val second=index.page("all",first.nextOffset!!,2)
        assertEquals(1,second.items.size);assertNull(second.nextOffset)
        assertEquals(3,(first.items+second.items).map { it.path }.distinct().size)
        assertEquals("DCIM/Camera/photo.jpg",index.page("photo",0,20).items.single().path)
        File(root,"new.png").writeText("png")
        assertEquals(1,index.page("photo",0,20).total)
        assertEquals(2,index.page("photo",0,20,true).total)
    }
    @Test fun safeMimeAndPageValidation() {
        assertEquals("application/pdf",MediaIndex.mime(File("x.PDF")))
        assertEquals("video/mp4",MediaIndex.mime(File("x.mp4")))
        assertNull(MediaIndex.mime(File("active.svg")))
        val index=index(temp.newFolder("shared"))
        for (kind in listOf("html","../")) try { index.page(kind,0,20);fail() } catch (_: IllegalArgumentException) {}
        try { index.page("all",-1,20);fail() } catch (_: IllegalArgumentException) {}
        try { index.page("all",0,1000);fail() } catch (_: IllegalArgumentException) {}
    }
}
