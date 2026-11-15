package com.meudrive

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.*
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.io.File

class DriveServerTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var server: DriveServer
    private val key = Cfg.hash("test-password")
    @Before fun start() {
        val ctx = mock(Context::class.java)
        val prefs = mock(SharedPreferences::class.java)
        `when`(ctx.getSharedPreferences("cfg", 0)).thenReturn(prefs)
        `when`(prefs.getString("pwhash", null)).thenReturn(key)
        `when`(prefs.getString("devname", null)).thenReturn("A06 test")
        `when`(ctx.getExternalFilesDir(null)).thenReturn(temp.root)
        `when`(ctx.filesDir).thenReturn(temp.root)
        server = DriveServer(ctx); server.start(5000, true)
    }
    @After fun stop() { server.stop() }
    private data class Reply(val code: Int, val bytes: ByteArray, val range: String?) { fun json(): JSONObject { assertEquals(String(bytes), 200, code); return JSONObject(String(bytes)) } }
    private fun call(path: String, method: String = "GET", body: ByteArray? = null, auth: Boolean = true, range: String? = null, origin: String? = null): Reply {
        val c = URL("http://127.0.0.1:${Cfg.PORT}$path").openConnection(java.net.Proxy.NO_PROXY) as HttpURLConnection
        c.connectTimeout=3000;c.readTimeout=5000;c.requestMethod=method
        if(auth)c.setRequestProperty("X-Key",key)
        if(range!=null)c.setRequestProperty("Range",range)
        if(origin!=null)c.setRequestProperty("Origin",origin)
        try {
            if(body!=null){c.doOutput=true;c.setRequestProperty("Content-Type",if(method=="PUT")"application/octet-stream" else "application/x-www-form-urlencoded");c.setFixedLengthStreamingMode(body.size);c.outputStream.use{it.write(body)}}
            val code=c.responseCode
            val bytes=(if(code<400)c.inputStream else c.errorStream)?.use{it.readBytes()}?:byteArrayOf()
            return Reply(code,bytes,c.getHeaderField("Content-Range"))
        }finally{c.disconnect()}
    }
    @Test fun realHttpUploadResumeDownloadAndCollision() {
        val data=ByteArray(600000){(it%251).toByte()}
        val start=call("/api/transfer/start","POST","name=video.mp4&path=Transferencias&size=${data.size}".toByteArray()).json()
        val id=start.getString("id")
        assertEquals(200,call("/api/transfer/chunk?id=$id&offset=0","PUT",data.copyOfRange(0,300000)).code)
        assertEquals(300000,call("/api/transfer/status?id=$id").json().getLong("offset"))
        assertEquals(400,call("/api/transfer/chunk?id=$id&offset=0","PUT",byteArrayOf(1)).code)
        assertTrue(call("/api/transfer/chunk?id=$id&offset=300000","PUT",data.copyOfRange(300000,data.size)).json().getBoolean("done"))
        val downloaded=call("/api/download?path=Transferencias/video.mp4")
        assertArrayEquals(data,downloaded.bytes)
        val part=call("/api/download?path=Transferencias/video.mp4",range="bytes=299999-300010")
        assertEquals(206,part.code);assertEquals("bytes 299999-300010/600000",part.range)
        assertArrayEquals(data.copyOfRange(299999,300011),part.bytes)
        assertEquals(416,call("/api/download?path=Transferencias/video.mp4",range="bytes=600000-").code)
    }
    @Test fun protectsAuthPathsMethodsAndCrossOriginWrites() {
        assertEquals(401,call("/api/list",auth=false).code)
        assertEquals(405,call("/api/delete?path=Transferencias").code)
        assertEquals(400,call("/api/list?path="+URLEncoder.encode("../", "UTF-8")).code)
        assertEquals(400,call("/api/mkdir","POST","path=&name=..".toByteArray()).code)
        assertEquals(403,call("/api/mkdir","POST","path=&name=x".toByteArray(),origin="http://attacker.test").code)
        assertFalse(File(temp.root,"MeuDrive/x").exists())
    }
}
