package com.meudrive
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
class PeerOperationsTest {
    @get:Rule val temp=TemporaryFolder()
    @Test fun batchDeletionReportsEachResultAndNeverRemovesFoldersOrOutsideFiles() {
        val root=temp.newFolder("drive").canonicalFile
        File(root,"remove").writeText("remove");File(root,"keep").mkdir();File(root,"keep/child").writeText("keep")
        val ops=PeerOperations({ rel -> File(root,rel).canonicalFile.takeIf {it==root||it.path.startsWith(root.path+File.separator)} },{ emptyList() },{"key"},{"test"})
        try {
            val result=ops.deleteBatch(ops.selected("[\"remove\",\"keep\",\"../outside\"]"))
            assertFalse(result.getBoolean("ok"));assertTrue(result.getJSONArray("results").getJSONObject(0).getBoolean("ok"))
            assertFalse(result.getJSONArray("results").getJSONObject(1).getBoolean("ok"));assertTrue(File(root,"keep/child").exists())
            try {ops.send("arbitrary-address",listOf("keep/child"));fail()}catch(_:IllegalArgumentException){}
        }finally{ops.close()}
    }
    @Test fun heartbeatOnlyListsActiveClientsAndSeparatesServers(){
        var now=0L;val registry=PeerRegistry{now}
        registry.heartbeat("client-id","Notebook")
        val peers=registry.list(listOf(Peer("A06","192.168.1.1",8080)))
        assertEquals("server",peers.getJSONObject(0).getString("kind"));assertEquals("client",peers.getJSONObject(1).getString("kind"))
        now=PeerRegistry.TTL+1;assertEquals(0,registry.list(emptyList()).length())
    }
}
