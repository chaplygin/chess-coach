package org.chesscoach.core

/**
 * Метка релиза на GitHub: build-57, где 57 — versionCode сборки.
 *
 * Сравнивается номер, а не строка: build-9 больше build-10 лексикографически,
 * и приложение перестало бы видеть обновления на десятой сборке.
 */
object ReleaseTag {
    private const val PREFIX = "build-"

    fun of(buildNumber: Int): String = PREFIX + buildNumber

    fun buildNumber(tag: String): Int? =
        if (tag.startsWith(PREFIX)) tag.removePrefix(PREFIX).toIntOrNull() else null

    /** Метку чужого формата обновлением не считаем: ставить непонятно что хуже, чем не ставить. */
    fun isNewer(tag: String, installedBuild: Long): Boolean {
        val build = buildNumber(tag) ?: return false
        return build > installedBuild
    }
}
