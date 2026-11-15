package ru.dacha.boiler

import org.junit.Assert.*
import org.junit.Test

class MailerTest {
    @Test fun connectionDescriptionNeverExposesCredentials() {
        val settings = SmtpConnection("host", 465, "secret-user", "secret-password", "from@example.org", true)
        assertFalse(settings.toString().contains("secret-user"))
        assertFalse(settings.toString().contains("secret-password"))
    }
    @Test fun acceptsSingleMailbox() { Mailer.validateAddress("owner@example.org") }
    @Test fun rejectsMissingDomain() { assertThrows(Exception::class.java) { Mailer.validateAddress("owner") } }
    @Test fun rejectsEmptyRecipient() { assertThrows(Exception::class.java) { Mailer.validateAddress("") } }
    @Test fun rejectsMultipleRecipients() { assertThrows(Exception::class.java) { Mailer.validateAddress("one@example.org,two@example.org") } }
    @Test fun rejectsHeaderInjection() { assertThrows(Exception::class.java) { Mailer.validateAddress("one@example.org\r\nBcc: other@example.org") } }
    @Test fun rejectsGroupAddress() { assertThrows(Exception::class.java) { Mailer.validateAddress("group:one@example.org;") } }
}
