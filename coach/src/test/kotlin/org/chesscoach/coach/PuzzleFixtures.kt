package org.chesscoach.coach

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Задачи Lichess с проставленными темами, собранные скриптом
 * tools/fixtures/harvest_puzzles.py.
 *
 * Ценность именно в том, что тему разметили не мы: если детектор вилки не видит
 * вилку там, где Lichess написал "fork", спорить не с чем. Позиции, придуманные
 * по памяти, такой опоры не дают — они подтверждают лишь то, что мы сами про
 * них подумали.
 */
data class PuzzleFixture(
    val id: String,
    val theme: String,
    val themes: List<String>,
    val rating: Int?,
    val fen: String,
    val solution: List<String>,
)

object PuzzleFixtures {

    private const val RESOURCE = "/puzzle_fixtures.json"

    fun load(): List<PuzzleFixture> {
        val raw = PuzzleFixtures::class.java.getResourceAsStream(RESOURCE)
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: return emptyList()

        return Json.parseToJsonElement(raw).jsonArray.map { element ->
            val fields = element.jsonObject
            PuzzleFixture(
                id = fields.getValue("id").jsonPrimitive.content,
                theme = fields.getValue("theme").jsonPrimitive.content,
                themes = fields["themes"]?.jsonArray.orEmptyStrings(),
                rating = fields["rating"]?.jsonPrimitive?.content?.toIntOrNull(),
                fen = fields.getValue("fen").jsonPrimitive.content,
                solution = fields["solution"]?.jsonArray.orEmptyStrings(),
            )
        }
    }

    fun of(theme: String): List<PuzzleFixture> = load().filter { it.theme == theme }

    private fun kotlinx.serialization.json.JsonArray?.orEmptyStrings(): List<String> =
        this?.map { it.jsonPrimitive.content } ?: emptyList()
}
