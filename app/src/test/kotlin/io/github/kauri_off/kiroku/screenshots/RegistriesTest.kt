package io.github.kauri_off.kiroku.screenshots

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

class RegistriesTest {
    private val registries: List<Class<*>> by lazy {
        val root =
            File(
                checkNotNull(RegistryBase::class.java.protectionDomain)
                    .codeSource.location
                    .toURI(),
            )
        root
            .walk()
            .filter { it.isFile && it.name.endsWith(".class") && '$' !in it.name }
            .map {
                it
                    .relativeTo(root)
                    .path
                    .removeSuffix(".class")
                    .replace(File.separatorChar, '.')
            }.map { Class.forName(it, false, javaClass.classLoader) }
            .filter { RegistryBase::class.java.isAssignableFrom(it) && !Modifier.isAbstract(it.modifiers) }
            .toList()
    }

    @Test
    fun everyRegistryNamesItsScreenByItsClass() {
        assertTrue(registries.any { it.simpleName == "SampleScreenshots" })
        registries.forEach { assertTrue(it.name, screenName(it).isNotEmpty()) }
    }

    @Test
    fun noTwoRegistriesShareAScreen() {
        val screens = registries.groupBy { screenName(it) }.filterValues { it.size > 1 }
        assertEquals(emptyMap<String, List<Class<*>>>(), screens)
    }

    @Test
    fun screenNamesAreKebabCase() {
        assertEquals("sample", screenName(io.github.kauri_off.kiroku.sample.SampleScreenshots::class.java))
        assertEquals("title-details", screenName(TitleDetailsScreenshots::class.java))
        assertEquals("", screenName(String::class.java))
    }

    @Test
    fun aScreenCannotSkipItsStates() {
        val content = contentCase {}
        val shape = { showcase: Boolean, screen: String, extra: List<ScreenshotCase>, notShown: List<Pair<String, String>> ->
            RegistryShape(screen, showcase, listOf(content) + extra, extra, notShown).problems()
        }
        assertEquals(emptyList<String>(), shape(false, "title", listOf(case("film") {}), listOf("empty" to "never empty")))
        assertTrue(shape(true, "title", emptyList(), emptyList()).single().startsWith("title is a screen"))
        assertTrue(shape(false, "title", listOf(case("loading") {}), emptyList()).single().contains("state case"))
        assertTrue(shape(false, "title", listOf(contentCase("other") {}), emptyList()).any { "content case" in it })
        for (placeholder in listOf(" ", "-", "n/a", "TODO", "TODO later", "none")) {
            assertTrue(placeholder, shape(false, "title", emptyList(), listOf("error" to placeholder)).single().contains("no reason"))
        }
        assertTrue(shape(false, "", emptyList(), emptyList()).single().contains("Screenshots"))
    }

    private class TitleDetailsScreenshots
}
