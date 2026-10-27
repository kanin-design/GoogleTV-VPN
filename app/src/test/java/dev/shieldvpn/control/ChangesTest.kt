package dev.shieldvpn.control

import dev.shieldvpn.vpn.AppSettings
import dev.shieldvpn.vpn.Connection
import dev.shieldvpn.vpn.Login
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The parts of a phone's change set that need no Android: values, words, checks and paths. */
class ChangesTest {

    private val state = CoreState(
        connections = listOf(Connection("p1", "Home", "s1", killMode = "Always on")),
        logins = mapOf("p1" to Login("me", hasPassword = true)),
        settings = AppSettings(customDns = listOf("1.1.1.1"), startupId = "p1", boot = true),
    )

    @Test
    fun valuesAreReadByPath() {
        assertEquals(true, Schema.value(state, "settings.killSwitch"))
        assertEquals(listOf("1.1.1.1"), Schema.value(state, "settings.customDns"))
        assertEquals("60", Schema.value(state, "settings.timeout"))
        assertEquals("p1", Schema.value(state, "settings.startup"))
        assertEquals("Always on", Schema.value(state, "profiles.p1.killMode"))
        assertEquals(true, Schema.value(state, "profiles.p1.startup"))
        assertNull(Schema.value(state, "profiles.nope.killMode"))
        assertNull(Schema.value(state, "settings.nothing"))
    }

    @Test
    fun thePasswordNeverLeavesAsItself() {
        assertEquals("saved", Schema.value(state, "profiles.p1.password"))
        val json = Schema.settingsJson(state).toString()
        assertFalse(json.contains("me\""))
    }

    @Test
    fun everySettingHasWordsAndAValue() {
        Schema.groups.flatMap { it.items }.forEach { item ->
            assertTrue(item.path, item.label.isNotBlank() && item.help.isNotBlank())
            // Each one is read and written by path; a missing branch reads as null.
            if (item.path != "settings.startup") assertTrue(item.path, Schema.value(CoreState(), item.path) != null)
        }
    }

    @Test
    fun itemsReadOnlyTheirOwnType() {
        val sw = Schema.item("settings.killSwitch")!!
        assertEquals(false, sw.read(false).getOrNull())
        assertTrue(sw.read("off").isFailure)
        val lines = Schema.item("settings.customDns")!!
        assertEquals(listOf("9.9.9.9", "1.1.1.1"), lines.read(JSONArray(listOf(" 9.9.9.9", "", "1.1.1.1"))).getOrNull())
        val port = Schema.profileItem("port")!!
        assertEquals(1194, port.read(1194).getOrNull())
        val secret = Schema.profileItem("password")!!
        assertNull(secret.read(JSONObject.NULL).getOrNull())
        // An empty password is no password: forgetting it, which asks the TV.
        assertNull(secret.read("").getOrNull())
        assertEquals("forget a saved password", secret.askTv!!(secret.read("").getOrNull()))
    }

    @Test
    fun checksAreTheTvs() {
        val dns = Schema.item("settings.customDns")!!
        assertEquals("9.9.9 is not an IP address", dns.check(listOf("1.1.1.1", "9.9.9")))
        assertNull(dns.check(listOf("1.1.1.1", "2606:4700::1111")))
        assertEquals("A number from 1 to 65535", Schema.profileItem("port")!!.check(70000))
        assertEquals("A profile needs a name", Schema.profileItem("name")!!.check("  "))
        assertTrue(Validate.isIpAddress("2606:4700::1111"))
        assertTrue(Validate.isIpAddress("2001:db8:0:0:0:0:0:1"))
        assertFalse(Validate.isIpAddress("zz::1"))
        assertFalse(Validate.isIpAddress("\u0661.\u0661.\u0661.\u0661"))
        assertFalse(Validate.isIpAddress("1.1.1.256"))
        assertFalse(Validate.isIpAddress("1:2:3"))
        assertEquals("The PIN has 4 to 8 digits", Validate.pinProblem("12a4"))
        assertNull(Validate.pinProblem("1234"))
    }

    @Test
    fun valuesAreShownAsTheTvSaysThem() {
        val s = state
        assertEquals("On", Schema.item("settings.killSwitch")!!.show(true, s))
        assertEquals("All apps, except the ones you choose", Schema.item("settings.appMode")!!.show(AppSettings.APPS_SKIP, s))
        assertEquals("1.1.1.1, 9.9.9.9", Schema.item("settings.customDns")!!.show(listOf("1.1.1.1", "9.9.9.9"), s))
        assertEquals("None", Schema.item("settings.customDns")!!.show(emptyList<String>(), s))
        assertEquals("Home", Schema.item("settings.startup")!!.show("p1", s))
        assertEquals("Off", Schema.item("settings.startup")!!.show("", s))
        assertEquals("60 s", Schema.item("settings.timeout")!!.show("60", s))
    }

    @Test
    fun theTvIsAskedForWhatCanLeakOrLock() {
        val kill = Schema.item("settings.killSwitch")!!
        assertEquals("turn off the kill switch", kill.askTv!!(false))
        assertNull(kill.askTv!!(true))
        assertEquals("turn off the PIN lock", Schema.item("settings.pinMode")!!.askTv!!("Off"))
        assertEquals("never block traffic for this profile", Schema.profileItem("killMode")!!.askTv!!("Off"))
        assertEquals("forget a saved password", Schema.profileItem("password")!!.askTv!!(null))
        assertNull(Schema.profileItem("password")!!.askTv!!("new one"))
    }

    @Test
    fun sameComparesAsThePanelSendsThem() {
        assertTrue(Changes.same(listOf("1.1.1.1"), listOf("1.1.1.1")))
        assertFalse(Changes.same(listOf("1.1.1.1"), emptyList<String>()))
        assertTrue(Changes.same(1194, 1194L))
        assertTrue(Changes.same("60", 60))
        assertTrue(Changes.same(null, null))
        assertFalse(Changes.same(true, "true"))
    }

    @Test
    fun reasonsReadAsOneSentence() {
        assertEquals("a", Words.and(listOf("a")))
        assertEquals("a and b", Words.and(listOf("a", "b")))
        assertEquals("a, b and c", Words.and(listOf("a", "b", "c")))
    }
}
