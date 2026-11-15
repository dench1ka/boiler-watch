package ru.dacha.boiler

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25])
class TelegramApiTest {
    @get:Rule val temporary=TemporaryFolder()
    private val token="123456:"+"a".repeat(32)
    private class Connection(val status:Int, val body:String):HttpURLConnection(URL("https://example.invalid")) {
        val output=ByteArrayOutputStream()
        var disconnected=false
        override fun connect() {}
        override fun disconnect() {disconnected=true}
        override fun usingProxy()=false
        override fun getOutputStream()=output
        override fun getInputStream()=ByteArrayInputStream(body.toByteArray())
        override fun getErrorStream()=ByteArrayInputStream(body.toByteArray())
        override fun getResponseCode()=status
    }
    @Test fun pollingUsesOffsetAndNoRedirects() {
        val c=Connection(200,"{\"ok\":true,\"result\":[]}")
        var url:URL?=null
        TelegramApi(token) {url=it;c}.updates(42)
        val payload=JSONObject(c.output.toString("UTF-8"))
        assertEquals(42L,payload.getLong("offset"));assertEquals(20,payload.getInt("timeout"))
        assertEquals("https",url!!.protocol);assertEquals("api.telegram.org",url!!.host)
        assertFalse(c.instanceFollowRedirects);assertTrue(c.disconnected)
    }
    @Test fun rejectedTokenNeverAppearsInExceptionMessage() {
        val c=Connection(401,"{\"ok\":false,\"error_code\":401,\"description\":\"$token\"}")
        val e=assertThrows(TelegramException::class.java) {TelegramApi(token) {c}.message(123,"hello")}
        assertEquals(401,e.code);assertFalse(e.message!!.contains(token));assertTrue(c.disconnected)
    }
    @Test fun rateLimitPreservesRetryAfter() {
        val c=Connection(429,"{\"ok\":false,\"error_code\":429,\"parameters\":{\"retry_after\":25}}")
        val e=assertThrows(TelegramException::class.java) {TelegramApi(token) {c}.message(123,"hello")}
        assertEquals(25,e.retryAfter)
    }
    @Test fun sendsJpegMultipartWithDestinationAndCaption() {
        val c=Connection(200,"{\"ok\":true,\"result\":{}}")
        val file=temporary.newFile("photo.jpg").apply {writeText("jpeg-fixture")}
        TelegramApi(token) {c}.photo(123,file,"Котёл")
        val body=c.output.toString("UTF-8")
        assertTrue(body.contains("name=\"chat_id\"\r\n\r\n123"))
        assertTrue(body.contains("image/jpeg"));assertTrue(body.contains("jpeg-fixture"));assertTrue(body.contains("Котёл"))
        assertTrue(c.disconnected)
    }
    @Test fun doesNotFollowRedirectToAnotherHost() {
        val c=Connection(302,"redirected")
        assertThrows(TelegramException::class.java) {TelegramApi(token) {c}.message(123,"hello")}
        assertFalse(c.instanceFollowRedirects);assertTrue(c.disconnected)
    }
    @Test fun invalidTokenNeverOpensConnection() {
        assertThrows(IllegalArgumentException::class.java) {TelegramApi("wrong") {fail("Network must not open");error("unexpected")}.updates(0)}
    }
}
