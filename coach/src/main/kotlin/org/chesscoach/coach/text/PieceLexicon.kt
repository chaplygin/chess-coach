package org.chesscoach.coach.text

import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Side

/**
 * Род фигуры на русском.
 *
 * От него зависит форма любого прилагательного или краткого причастия рядом
 * с названием фигуры ("потерян" / "потеряна"), поэтому это отдельный тип, а
 * не флаг внутри строки шаблона.
 */
enum class Gender { MASCULINE, FEMININE }

/**
 * Падежи, которые реально нужны разбору позиции: фигура называется
 * (именительный), её теряют или бьют ею (винительный, творительный), к ней
 * подступают (дательный), её не хватает или что-то происходит рядом с ней
 * (родительный).
 */
enum class GrammaticalCase { NOMINATIVE, ACCUSATIVE, GENITIVE, DATIVE, INSTRUMENTAL }

/**
 * Склонение одной фигуры по падежам.
 *
 * [genitivePlural] нужен для счёта: по-русски после двух, трёх и четырёх идёт
 * родительный единственного ("две пешки"), а после пяти и дальше —
 * родительный множественного ("пять пешек"). Без отдельной формы тренер
 * написал бы "пять пешки", и это заметит любой читатель.
 */
data class PieceWord(
    val gender: Gender,
    private val forms: Map<GrammaticalCase, String>,
    val genitivePlural: String,
) {
    fun case(case: GrammaticalCase): String = forms.getValue(case)
}

/**
 * Пара форм прилагательного или краткого причастия по родам: "связанный" /
 * "связанная", "потерян" / "потеряна".
 *
 * Хранится отдельно от рода конкретной фигуры и согласуется с ней через
 * [agree] — так одно и то же слово подходит для подстановки любой из шести
 * фигур, и зашивать пару "потерян/потеряна" прямо в текст шаблона не нужно.
 */
data class AgreeingWord(val masculine: String, val feminine: String) {
    fun agree(gender: Gender): String = when (gender) {
        Gender.MASCULINE -> masculine
        Gender.FEMININE -> feminine
    }
}

/**
 * Лексемы шести фигур: род и падежи, а не куски строк для склейки.
 *
 * Формы вроде "потерян/потеряна" в шаблон не зашиваются: при подстановке
 * другой фигуры получилась бы безграмотность. Согласование всегда идёт через
 * [PieceWord.gender] здесь и [AgreeingWord.agree] — оба намеренно раздельны.
 */
object PieceLexicon {

    private val king = PieceWord(
        Gender.MASCULINE,
        mapOf(
            GrammaticalCase.NOMINATIVE to "король",
            GrammaticalCase.ACCUSATIVE to "короля",
            GrammaticalCase.GENITIVE to "короля",
            GrammaticalCase.DATIVE to "королю",
            GrammaticalCase.INSTRUMENTAL to "королём",
        ),
        genitivePlural = "королей",
    )

    private val queen = PieceWord(
        Gender.MASCULINE,
        mapOf(
            GrammaticalCase.NOMINATIVE to "ферзь",
            GrammaticalCase.ACCUSATIVE to "ферзя",
            GrammaticalCase.GENITIVE to "ферзя",
            GrammaticalCase.DATIVE to "ферзю",
            GrammaticalCase.INSTRUMENTAL to "ферзём",
        ),
        genitivePlural = "ферзей",
    )

    private val rook = PieceWord(
        Gender.FEMININE,
        mapOf(
            GrammaticalCase.NOMINATIVE to "ладья",
            GrammaticalCase.ACCUSATIVE to "ладью",
            GrammaticalCase.GENITIVE to "ладьи",
            GrammaticalCase.DATIVE to "ладье",
            GrammaticalCase.INSTRUMENTAL to "ладьёй",
        ),
        genitivePlural = "ладей",
    )

    private val bishop = PieceWord(
        Gender.MASCULINE,
        mapOf(
            GrammaticalCase.NOMINATIVE to "слон",
            GrammaticalCase.ACCUSATIVE to "слона",
            GrammaticalCase.GENITIVE to "слона",
            GrammaticalCase.DATIVE to "слону",
            GrammaticalCase.INSTRUMENTAL to "слоном",
        ),
        genitivePlural = "слонов",
    )

    private val knight = PieceWord(
        Gender.MASCULINE,
        mapOf(
            GrammaticalCase.NOMINATIVE to "конь",
            GrammaticalCase.ACCUSATIVE to "коня",
            GrammaticalCase.GENITIVE to "коня",
            GrammaticalCase.DATIVE to "коню",
            GrammaticalCase.INSTRUMENTAL to "конём",
        ),
        genitivePlural = "коней",
    )

    private val pawn = PieceWord(
        Gender.FEMININE,
        mapOf(
            GrammaticalCase.NOMINATIVE to "пешка",
            GrammaticalCase.ACCUSATIVE to "пешку",
            GrammaticalCase.GENITIVE to "пешки",
            GrammaticalCase.DATIVE to "пешке",
            GrammaticalCase.INSTRUMENTAL to "пешкой",
        ),
        genitivePlural = "пешек",
    )

    fun word(type: PieceType): PieceWord = when (type) {
        PieceType.KING -> king
        PieceType.QUEEN -> queen
        PieceType.ROOK -> rook
        PieceType.BISHOP -> bishop
        PieceType.KNIGHT -> knight
        PieceType.PAWN -> pawn
        // Пустая клетка сюда попасть не должна: вызывающий код обязан
        // отсеять её раньше, иначе это не текст, а ошибка в фактах.
        PieceType.NONE -> error("У пустой клетки нет названия на русском")
    }

    fun name(type: PieceType, case: GrammaticalCase = GrammaticalCase.NOMINATIVE): String = word(type).case(case)

    fun genderOf(type: PieceType): Gender = word(type).gender

    /**
     * Фигура с числом: "пешка", "две пешки", "пять пешек".
     *
     * Русский счёт требует трёх разных форм, и написать "2 пешки" цифрой было
     * бы проще, но в тексте тренера цифра посреди фразы выглядит как опечатка.
     * Числительное согласуется по роду: "две пешки", но "два коня".
     */
    fun counted(type: PieceType, count: Int): String {
        val word = word(type)
        if (count == 1) return word.case(GrammaticalCase.NOMINATIVE)

        val numeral = numeral(count, word.gender)
        // Разница в материале больше восьми фигур одного вида невозможна, но
        // если такое придёт — лучше показать цифру, чем промолчать.
        val noun = if (count in 2..4) word.case(GrammaticalCase.GENITIVE) else word.genitivePlural
        return "$numeral $noun"
    }

    /** Определение "лишний" в нужной форме: род важен только в единственном числе. */
    fun spare(type: PieceType, count: Int): String =
        if (count == 1) Agreements.SPARE.agree(genderOf(type)) else "лишние"

    private fun numeral(count: Int, gender: Gender): String = when (count) {
        2 -> if (gender == Gender.FEMININE) "две" else "два"
        3 -> "три"
        4 -> "четыре"
        5 -> "пять"
        6 -> "шесть"
        7 -> "семь"
        8 -> "восемь"
        else -> count.toString()
    }

    /** Слова, которыми детекторы описывают судьбу фигуры — согласуются по роду фигуры, а не зашиты в текст. */
    object Agreements {
        val LOST = AgreeingWord(masculine = "потерян", feminine = "потеряна")
        val TRAPPED = AgreeingWord(masculine = "пойман", feminine = "поймана")
        val PINNED_ADJECTIVE = AgreeingWord(masculine = "связанный", feminine = "связанная")
        val PINNED_SHORT = AgreeingWord(masculine = "связан", feminine = "связана")
        val OVERLOADED_ADJECTIVE = AgreeingWord(masculine = "перегруженный", feminine = "перегруженная")
        val MUST_COVER = AgreeingWord(masculine = "должен", feminine = "должна")
        val SPARE = AgreeingWord(masculine = "лишний", feminine = "лишняя")
    }
}

/**
 * Сторона как грамматическое лицо: "белые" / "чёрные".
 *
 * Это существительное множественного числа, а не средний род на "оно" — а
 * значит, оно и склоняется ("у чёрных", а не "у чёрные"), и требует глагола
 * во множественном числе ("держат", а не "держит"). Строку по месту в шаблоне
 * чинить нельзя: та же ошибка тут же вылезла бы в следующем шаблоне, который
 * про неё не знает — как и вышло с "Чёрные прочно держит центр".
 */
object SideLexicon {

    private val white = mapOf(
        GrammaticalCase.NOMINATIVE to "белые",
        GrammaticalCase.ACCUSATIVE to "белых",
        GrammaticalCase.GENITIVE to "белых",
        GrammaticalCase.DATIVE to "белым",
        GrammaticalCase.INSTRUMENTAL to "белыми",
    )

    private val black = mapOf(
        GrammaticalCase.NOMINATIVE to "чёрные",
        GrammaticalCase.ACCUSATIVE to "чёрных",
        GrammaticalCase.GENITIVE to "чёрных",
        GrammaticalCase.DATIVE to "чёрным",
        GrammaticalCase.INSTRUMENTAL to "чёрными",
    )

    fun name(side: Side, case: GrammaticalCase = GrammaticalCase.NOMINATIVE): String =
        (if (side == Side.WHITE) white else black).getValue(case)
}
