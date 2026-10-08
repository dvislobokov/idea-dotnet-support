package io.github.dotnetsupport

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.welcome.WelcomePage

/** The page about the plugin: packed into it, self-contained, shown once per version. */
class WelcomePageTest : BasePlatformTestCase() {
    fun testThePageIsPackedAndStandsAlone() {
        val page = WelcomePage.html(dark = true)
        assertNotNull("welcome/index.html is packed from docs/demo.html by the build", page)
        page!!
        assertTrue(page.contains("""<html lang="ru" data-host="ide" data-theme="dark">"""))
        assertTrue(WelcomePage.html(dark = false)!!.contains("""data-theme="light""""))
        // inside the IDE there is no network: nothing is loaded from outside
        val external = Regex("""(?:src|href)\s*=\s*"(https?:)?//[^"]*"""").findAll(page).map { it.value }.toList()
        assertEquals(emptyList<String>(), external)
        assertFalse("no @import of fonts", page.contains("@import"))
        // links into the repository are hidden there
        for (link in Regex("""<a [^>]*href="\.\./[^>]*>""").findAll(page)) assertTrue(link.value, link.value.contains("class=\"repo\""))
        for (anchor in Regex("""href="#([\w-]+)"""").findAll(page).map { it.groupValues[1] }.toSet()) {
            assertTrue("no element with id $anchor", page.contains("id=\"$anchor\""))
        }
    }

    fun testShownOncePerVersion() {
        assertTrue("a new installation", WelcomePage.isNewFor(null, "0.1.0"))
        assertTrue("a new minor version", WelcomePage.isNewFor("0.1.149", "0.2.0"))
        assertFalse("a release of the same line only notifies", WelcomePage.isNewFor("0.1.148", "0.1.149"))
        assertTrue(WelcomePage.isUpdate("0.1.148", "0.1.149"))
        assertFalse(WelcomePage.isUpdate("0.1.0", "0.1.0"))
        assertFalse(WelcomePage.isNewFor("0.1.0", "0.1.0"))
        assertFalse("the version is not known: nothing to record", WelcomePage.isNewFor(null, null))
    }

    fun testTheActionIsInTheMenu() {
        val actions = ActionManager.getInstance()
        val menu = actions.getAction("DotNet.MainMenu") as DefaultActionGroup
        assertTrue(menu.childActionsOrStubs.any { actions.getId(it) == "DotNet.Welcome" })
    }
}
