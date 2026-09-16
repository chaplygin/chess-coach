package org.chesscoach.lichess

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent

/**
 * Открывает страницу входа Lichess.
 *
 * Используется вкладка браузера, а не окно внутри приложения: пароль от чужого
 * сервиса пользователь должен вводить в настоящем браузере, где видно адресную
 * строку и замок. Встроенное окно неотличимо от подделки, и приложениям,
 * которые так делают, верить нельзя.
 */
fun openLichessAuthorization(context: Context, uri: Uri) {
    CustomTabsIntent.Builder()
        .setShowTitle(true)
        .build()
        .launchUrl(context, uri)
}
