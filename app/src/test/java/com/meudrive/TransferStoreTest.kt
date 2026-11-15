package com.meudrive

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

class TransferStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private fun store(): TransferStore = TransferStore(File(temp.root, "stage")) { rel ->
        val root = File(temp.root, "drive").apply { mkdirs() }.canonicalFile
        val file = File(root, rel).canonicalFile
        file.takeIf { it == root || it.path.startsWith(root.path + File.separator) }
    }
    @Test fun interruptedBlockIsRolledBackAndCanResumeAfterRestart() {
        val s = store(); val upload = s.start("", "video.mp4", 10)
        s.append(upload.id, 0, 4, ByteArrayInputStream(byteArrayOf(1,2,3,4)))
        try { s.append(upload.id, 4, 6, ByteArrayInputStream(byteArrayOf(5,6))); fail() } catch (_: IllegalStateException) {}
        val restarted = store()
        assertEquals(4, restarted.state(upload.id).offset)
        val done = restarted.append(upload.id, 4, 6, ByteArrayInputStream(byteArrayOf(5,6,7,8,9,10)))
        assertTrue(done.done)
        assertArrayEquals(byteArrayOf(1,2,3,4,5,6,7,8,9,10), File(temp.root, "drive/video.mp4").readBytes())
        assertTrue(restarted.append(upload.id, 4, 6, ByteArrayInputStream(byteArrayOf())).done)
    }
    @Test fun collisionsNeverOverwriteExistingFiles() {
        File(temp.root, "drive").mkdirs(); File(temp.root, "drive/a.txt").writeText("old")
        val s = store(); val t = s.start("", "a.txt", 3)
        val done = s.append(t.id, 0, 3, ByteArrayInputStream("new".toByteArray()))
        assertEquals("a (1).txt", done.name)
        assertEquals("old", File(temp.root, "drive/a.txt").readText())
    }
    @Test fun zeroByteFilesAreCommitted() { val s = store(); assertTrue(s.start("", "empty", 0).done); assertEquals(0, File(temp.root, "drive/empty").length()) }
    @Test fun rejectsTraversalInvalidOffsetsAndOversizedChunks() {
        val s = store()
        for (name in listOf("..", "../x", "a/b", "a\\b", "x\n")) {
            try { s.start("", name, 1); fail(name) } catch (_: IllegalArgumentException) {}
        }
        try { s.start("../outside", "x", 1); fail() } catch (_: IllegalStateException) {}
        val t = s.start("", "x", 2)
        try { s.append(t.id, 1, 1, ByteArrayInputStream(byteArrayOf(1))); fail() } catch (_: IllegalArgumentException) {}
        try { s.append(t.id, 0, TransferStore.CHUNK + 1L, ByteArrayInputStream(byteArrayOf(1))); fail() } catch (_: IllegalArgumentException) {}
        assertEquals(0, s.state(t.id).offset)
    }
    @Test fun refusesFinalCommitIfRootChanges() {
        var dir = File(temp.root,"a").apply { mkdirs() }
        val s = TransferStore(File(temp.root,"stage")) { dir }
        val t=s.start("", "x", 2)
        dir=File(temp.root,"b").apply { mkdirs() }
        try { s.append(t.id,0,2,ByteArrayInputStream(byteArrayOf(1,2)));fail() } catch (_: IllegalStateException) {}
        assertFalse(File(dir,"x").exists())
    }
    @Test fun parsesDownloadRanges() {
        assertEquals(ByteRange(10,19), ByteRange.parse("bytes=10-19",100))
        assertEquals(ByteRange(10,99), ByteRange.parse("bytes=10-",100))
        assertEquals(ByteRange(90,99), ByteRange.parse("bytes=-10",100))
        assertEquals(ByteRange(0,99), ByteRange.parse("bytes=-999",100))
        for (header in listOf("bytes=100-", "bytes=8-2", "bytes=0-1,4-5", "bytes=-0", "bytes=no", "bytes=-")) assertNull(header,ByteRange.parse(header,100))
        assertNull(ByteRange.parse("bytes=0-",0))
    }
}
