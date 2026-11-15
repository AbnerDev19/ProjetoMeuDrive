package com.meudrive
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
class StorageBoundaryTest {
    @get:Rule val temp=TemporaryFolder()
    @Test fun fallbackRootInsideAndroidDataStillSharesItsOwnFiles(){
        val root=File(temp.root,"Android/data/com.meudrive/files/MeuDrive").apply {mkdirs()}
        assertEquals(File(root,"foto.jpg").canonicalFile,StorageBoundary.resolve(root,"foto.jpg"))
    }
    @Test fun sharesAndroidMediaWithoutSharingPrivateDataOrStaging(){
        assertNotNull(StorageBoundary.resolve(temp.root,"Android/media/com.example/foto.jpg"))
        for(path in listOf("Android/data/com.other/file","Android/obb/file","../outside",".transfers/file",".local-transfers/file")) assertNull(path,StorageBoundary.resolve(temp.root,path))
    }
    @Test fun galleryIncludesAppMedia(){
        val root=temp.root;File(root,"Android/media/example").mkdirs();File(root,"Android/media/example/photo.jpg").writeText("fixture")
        File(root,"Android/data/example").mkdirs();File(root,"Android/data/example/private.jpg").writeText("private")
        val index=MediaIndex({root},{StorageBoundary.resolve(root,it)})
        assertEquals(listOf("Android/media/example/photo.jpg"),index.page("all",0,60).items.map {it.path})
    }
}
