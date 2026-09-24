package com.ingeint.checkin

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.text.Normalizer

/**
 * CLAUDE.md regla 3 / docs/06 "Reglas de discreción #6": ninguna cadena visible del
 * rol niño puede contener glucosa, glicemia, glucemia, diabetes, azúcar, sensor o
 * insulina (en cualquier acentuación/mayúscula). Este test NO SE DESACTIVA.
 */
class ChildStringsForbiddenWordsTest {
    private val forbiddenWords =
        listOf("glucosa", "glicemia", "glucemia", "diabetes", "azucar", "sensor", "insulina")

    @Test
    fun `strings_child xml no contiene palabras prohibidas`() {
        val file = childStringsFile()
        assertTrue("No se encontró ${file.path}", file.exists())

        // Se ignoran los comentarios XML: el propio archivo documenta la regla citando
        // las palabras prohibidas como ejemplo, y eso no debe contarse como una fuga real.
        val withoutComments = file.readText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        val content = normalize(withoutComments)
        val found = forbiddenWords.filter { content.contains(it) }

        assertTrue(
            "res/values/strings_child.xml contiene palabra(s) prohibida(s): $found. " +
                "Ver CLAUDE.md regla 3 y docs/06 'Reglas de discreción'.",
            found.isEmpty(),
        )
    }

    /** Sin tildes y en minúsculas, para detectar "Azúcar", "AZUCAR", "Diabetes", etc. */
    private fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}"), "") // quita marcas diacríticas (tildes)
            .lowercase()

    private fun childStringsFile(): File {
        val candidates =
            listOf(
                File("src/main/res/values/strings_child.xml"), // working dir = android/app
                File("app/src/main/res/values/strings_child.xml"), // working dir = android/
            )
        return candidates.firstOrNull { it.exists() }
            ?: error("No se encontró strings_child.xml (probé: ${candidates.map { it.path }})")
    }
}
