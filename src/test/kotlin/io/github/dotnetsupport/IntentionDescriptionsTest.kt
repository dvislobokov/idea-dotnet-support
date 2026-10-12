package io.github.dotnetsupport

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every intention of `plugin.xml` has its `intentionDescriptions/<class>/description.html` with the before / after templates (0.1.166):
 * without them Settings | Editor | Intentions and the search of the settings fail with "Intention Description Dir URL is null", which
 * the user saw for the `switch` actions of 0.1.162.
 */
class IntentionDescriptionsTest {
    @Test
    fun everyIntentionHasItsDescription() {
        val xml = javaClass.classLoader.getResource("META-INF/plugin.xml")!!.readText()
        val classes = Regex("""<intentionAction>.*?<className>([\w.]+)</className>""", RegexOption.DOT_MATCHES_ALL).findAll(xml).map { it.groupValues[1] }.toList()
        assertTrue(classes.size > 20)
        val missing = classes.map { it.substringAfterLast('.') }.filter { name ->
            listOf("description.html", "before.cs.template", "after.cs.template").any { javaClass.classLoader.getResource("intentionDescriptions/$name/$it") == null }
        }
        assertTrue("no description, before / after: $missing", missing.isEmpty())
    }
}
