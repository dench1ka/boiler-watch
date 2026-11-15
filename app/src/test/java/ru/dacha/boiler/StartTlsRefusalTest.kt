package ru.dacha.boiler

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference
import javax.mail.MessagingException
import kotlin.concurrent.thread

/** Actual TCP protocol test: credentials must never be sent if STARTTLS is unavailable. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25])
class StartTlsRefusalTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun refusesCleartextAuthenticationWhenServerDoesNotOfferTls() {
        val report = temporary.newFolder().apply {
            File(this, "photo.jpg").writeBytes(byteArrayOf(1, 2, 3))
            File(this, "report.json").writeText(JSONObject().put("captured", 1L).put("slot", 0L)
                .put("recipient", "owner@example.org").put("id", "tls-refusal").toString())
        }
        val commands = Collections.synchronizedList(mutableListOf<String>())
        val serverFailure = AtomicReference<Throwable?>()
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 5000
            val responder = thread(isDaemon = true, name = "smtp-refusal-fixture") {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val output = socket.getOutputStream().bufferedWriter(Charsets.US_ASCII)
                        fun reply(value: String) { output.write(value + "\r\n"); output.flush() }
                        reply("220 localhost test SMTP")
                        val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                        while (true) {
                            val command = reader.readLine() ?: break
                            commands.add(command)
                            when {
                                command.startsWith("EHLO") -> reply("250-localhost\r\n250 AUTH PLAIN LOGIN")
                                command.startsWith("AUTH") -> reply("535 Authentication rejected")
                                command == "QUIT" -> { reply("221 Bye"); break }
                                else -> reply("502 Unsupported")
                            }
                        }
                    }
                } catch (e: Throwable) { serverFailure.set(e) }
            }
            val settings = SmtpConnection("127.0.0.1", server.localPort, "fixture-user", "fixture-password", "sender@example.org", false)
            assertThrows(MessagingException::class.java) { Mailer.send(settings, report) }
            responder.join(6000)
            assertFalse("Fixture server did not finish", responder.isAlive)
            serverFailure.get()?.let { throw AssertionError("Fixture server failed", it) }
            assertTrue(commands.any { it.startsWith("EHLO") })
            assertFalse(commands.any { it.startsWith("AUTH") })
            assertFalse(commands.any { it.contains("fixture-password") })
        }
    }
}
