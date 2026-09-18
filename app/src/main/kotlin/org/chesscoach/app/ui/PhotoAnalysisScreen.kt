package org.chesscoach.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.chesscoach.app.AppContainer
import org.chesscoach.coach.model.BoardMarks
import org.chesscoach.coach.model.PositionExplanation
import org.chesscoach.coach.text.TextRenderer
import org.chesscoach.core.MoveNotation
import org.chesscoach.core.Score
import org.chesscoach.core.ScoreFormat
import org.chesscoach.core.catchingNonCancellation
import org.chesscoach.core.mapCatchingNonCancellation
import org.chesscoach.core.vision.BoardRecognizer
import org.chesscoach.core.vision.GrayImage
import org.chesscoach.core.vision.PhotoSource
import org.chesscoach.core.vision.RecognitionResult
import org.chesscoach.engine.SearchLimits

/**
 * Сторона снимка, длиннее которой мы не пускаем в распознавание.
 *
 * Считать по всем мегапикселям незачем, но и ужимать сильно нельзя: скриншот
 * телефона вытянутый, доска занимает от силы половину его высоты, и после
 * уменьшения длинной стороны до тысячи пикселей на клетку остаётся полсотни —
 * по такой клетке силуэт фигуры уже не разобрать.
 */
private const val MAX_PHOTO_SIDE = 1800

private data class PhotoPositionAdvice(val scoreFromWhite: Score, val bestMoveSan: String, val depth: Int)

/**
 * Экран «сфотографируй позицию — получи тот же разбор, что и в партии».
 *
 * Сам он не разбирает фотографию: вся содержательная логика (поиск доски,
 * чтение клеток, проверка легальности) — в чистом :core BoardRecognizer,
 * покрытом собственными тестами. Здесь только источник кадра (галерея или
 * камера), уменьшение до разумного размера и показ того же разбора тренера,
 * что и на экране партии (GameReviewScreen) — тем же способом: explain(fen),
 * BoardMarks.of, TextRenderer.render.
 */
@Composable
fun PhotoAnalysisScreen(container: AppContainer, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var whiteToMove by remember { mutableStateOf(true) }
    var source by remember { mutableStateOf(PhotoSource.SCREEN) }
    var lastImage by remember { mutableStateOf<GrayImage?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var loadingPhoto by remember { mutableStateOf(false) }
    var recognition by remember { mutableStateOf<RecognitionResult?>(null) }
    var recognizing by remember { mutableStateOf(false) }
    var turnedByHand by remember { mutableStateOf(false) }

    fun handleBitmap(bitmap: Bitmap) {
        loadError = null
        loadingPhoto = true
        scope.launch {
            val gray = catchingNonCancellation {
                withContext(Dispatchers.Default) { bitmapToGrayImage(downscale(bitmap, MAX_PHOTO_SIDE)) }
            }.getOrNull()
            loadingPhoto = false
            if (gray == null) {
                loadError = "Не удалось прочитать снимок."
            } else {
                recognition = null
                lastImage = gray
            }
        }
    }

    fun loadFromUri(uri: Uri, whenMissing: String) {
        loadError = null
        loadingPhoto = true
        scope.launch {
            val bitmap = catchingNonCancellation {
                withContext(Dispatchers.IO) {
                    val decoded = ImageDecoder.createSource(context.contentResolver, uri)
                    ImageDecoder.decodeBitmap(decoded) { decoder, _, _ -> decoder.isMutableRequired = true }
                }
            }.getOrNull()
            loadingPhoto = false
            if (bitmap == null) loadError = whenMissing else handleBitmap(bitmap)
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) loadFromUri(uri, "Не удалось открыть изображение из галереи.")
    }

    // Снимок берётся в полном разрешении через файл, а не превью, которое
    // камера отдаёт без файла: на превью клетка настоящей доски занимает
    // несколько десятков пикселей, и силуэт фигуры по ним не разобрать.
    var pendingPhoto by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val uri = pendingPhoto
        pendingPhoto = null
        if (saved && uri != null) loadFromUri(uri, "Не удалось прочитать снимок с камеры.")
    }

    fun takePhoto() {
        val uri = catchingNonCancellation { newPhotoUri(context) }.getOrNull()
        if (uri == null) {
            loadError = "Не удалось подготовить файл для снимка."
            return
        }
        pendingPhoto = uri
        cameraLauncher.launch(uri)
    }

    LaunchedEffect(lastImage, whiteToMove, source) {
        val image = lastImage
        if (image == null) {
            recognition = null
            return@LaunchedEffect
        }
        recognizing = true
        turnedByHand = false
        recognition = withContext(Dispatchers.Default) { BoardRecognizer.recognize(image, whiteToMove, source) }
        recognizing = false
    }

    val recognized = recognition as? RecognitionResult.Recognized
    // Ориентация — единственное, что не следует из самого снимка: развёрнутая на
    // пол-оборота доска даёт такую же законную позицию. Распознаватель выбирает
    // сторону по расположению армий и честно сообщает о выборе, а последнее
    // слово оставлено человеку.
    val shownFen = recognized?.fen?.let { if (turnedByHand) BoardRecognizer.turnedAround(it) else it }

    var advice by remember { mutableStateOf<PhotoPositionAdvice?>(null) }
    var explanation by remember { mutableStateOf<PositionExplanation?>(null) }
    var analyzing by remember { mutableStateOf(false) }
    var engineError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(shownFen) {
        advice = null
        explanation = null
        engineError = null
        val fen = shownFen ?: return@LaunchedEffect

        analyzing = true
        container.engine.analyzer()
            .mapCatchingNonCancellation { it.analyze(fen, SearchLimits(movetimeMs = 600, multiPv = 1)) }
            .onSuccess { result ->
                val line = result.lines.firstOrNull()
                val uci = line?.pv?.firstOrNull()
                if (line != null && uci != null) {
                    advice = PhotoPositionAdvice(
                        scoreFromWhite = ScoreFormat.toWhitePerspective(line.score, MoveNotation.sideToMove(fen)),
                        bestMoveSan = MoveNotation.uciToSanOrRaw(fen, listOf(uci)).first(),
                        depth = line.depth,
                    )
                }
            }
            .onFailure { engineError = it.message ?: "Движок недоступен" }

        if (engineError == null) {
            explanation = catchingNonCancellation { container.coach.explain(fen) }.getOrNull()
        }
        analyzing = false
    }

    val arrows = remember(explanation) { explanation?.let(BoardMarks::of) ?: emptyList() }
    val coachText = remember(explanation) { explanation?.let(TextRenderer::render) }

    Column(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("Назад") }
            Text("Разбор по фото", style = MaterialTheme.typography.titleMedium)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { galleryLauncher.launch("image/*") },
                modifier = Modifier.weight(1f),
            ) { Text("Из галереи") }

            Button(
                onClick = { takePhoto() },
                modifier = Modifier.weight(1f),
            ) { Text("Снять камерой") }
        }

        SourceToggle(source = source, onChange = { source = it })

        SideToggle(whiteToMove = whiteToMove, onChange = { whiteToMove = it })

        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                loadingPhoto -> LoadingLine("Загружаем снимок…")
                loadError != null -> ErrorCard(loadError!!)
                recognizing -> LoadingLine("Ищем доску на снимке…")
            }

            when (val result = recognition) {
                null -> if (!loadingPhoto && loadError == null) {
                    Text(
                        "Выберите фотографию доски или снимите её камерой.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF5F6368),
                    )
                }

                is RecognitionResult.NoBoardFound -> ErrorCard(result.reason)

                is RecognitionResult.NotSure -> NotSureCard(result)

                is RecognitionResult.Recognized -> {
                    CheckPositionBanner()
                    ChessBoard(fen = shownFen ?: result.fen, arrows = arrows)
                    OrientationRow(
                        turnedAutomatically = result.turnedAround != turnedByHand,
                        onTurn = { turnedByHand = !turnedByHand },
                    )
                    PositionAnalysisCard(
                        fen = shownFen ?: result.fen,
                        advice = advice,
                        coachText = coachText,
                        analyzing = analyzing,
                        error = engineError,
                    )
                }
            }
        }
    }
}

/**
 * Откуда снимок: с экрана или с настоящей доски.
 *
 * Спрашиваем, а не угадываем: два случая обрабатываются по-разному, и ошибка
 * в догадке выглядела бы для человека как беспричинный отказ распознать
 * нормальный снимок.
 */
@Composable
private fun SourceToggle(source: PhotoSource, onChange: (PhotoSource) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onChange(PhotoSource.SCREEN) },
                modifier = Modifier.weight(1f),
                colors = toggleColors(selected = source == PhotoSource.SCREEN),
            ) { Text("С экрана") }

            Button(
                onClick = { onChange(PhotoSource.BOARD) },
                modifier = Modifier.weight(1f),
                colors = toggleColors(selected = source == PhotoSource.BOARD),
            ) { Text("Живая доска") }
        }

        Text(
            when (source) {
                PhotoSource.SCREEN -> "Скриншот с телефона или фотография экрана монитора."
                PhotoSource.BOARD -> "Фотография настоящей доски: снимайте сверху, при ровном свете."
            },
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF5F6368),
        )
    }
}

@Composable
private fun SideToggle(whiteToMove: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { onChange(true) },
            modifier = Modifier.weight(1f),
            colors = toggleColors(selected = whiteToMove),
        ) { Text("Ход белых") }

        Button(
            onClick = { onChange(false) },
            modifier = Modifier.weight(1f),
            colors = toggleColors(selected = !whiteToMove),
        ) { Text("Ход чёрных") }
    }
}

@Composable
private fun toggleColors(selected: Boolean) = if (selected) {
    ButtonDefaults.buttonColors()
} else {
    ButtonDefaults.buttonColors(containerColor = Color(0xFFE0E0E0), contentColor = Color(0xFF3C4043))
}

@Composable
private fun LoadingLine(text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text, style = MaterialTheme.typography.bodySmall)
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun ErrorCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFCE8E6)),
    ) {
        Text(message, modifier = Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Просьба сверить позицию перед тем, как читать разбор.
 *
 * Редактора позиции в приложении нет по решению пользователя, значит единственный
 * способ поймать ошибку распознавания — глаза человека. Проверка легальности
 * ловит только невозможные расстановки, а слон, прочитанный как пешка на пустом месте,
 * даёт вполне возможную позицию — и тренер уверенно разберёт чужую.
 */
@Composable
private fun CheckPositionBanner() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF8E1)),
    ) {
        Text(
            "Сверьте доску со снимком: если хотя бы одна фигура стоит не там, " +
                "разбор ниже относится к другой позиции. Исправить её вручную нельзя — переснимите сверху, ровно и без бликов.",
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * Сторона, с которой снята доска, и кнопка её перевернуть.
 *
 * Развернув доску на пол-оборота, получаем такую же законную позицию, поэтому
 * по одному снимку сторону определить нельзя — распознаватель выбирает её по
 * тому, чьи фигуры стоят ближе к нижнему краю. Признак хороший, но не
 * безошибочный, и молча ошибиться тут значит разобрать зеркальную позицию с
 * уверенным видом.
 */
@Composable
private fun OrientationRow(turnedAutomatically: Boolean, onTurn: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (turnedAutomatically) "Снято со стороны чёрных — доска развёрнута." else "Снято со стороны белых.",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF5F6368),
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onTurn) { Text("Перевернуть") }
    }
}

/**
 * NotSure — единственный по-настоящему важный экран здесь: тренер молчит,
 * пока сам не уверен, а не показывает первую попавшуюся расстановку.
 */
@Composable
private fun NotSureCard(result: RecognitionResult.NotSure) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3B0)),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Не уверены в позиции", style = MaterialTheme.typography.labelLarge)
            Text(result.reason, style = MaterialTheme.typography.bodyMedium)
            if (result.unreadableCells.isNotEmpty()) {
                Text(
                    "Проблемные поля: " + result.unreadableCells.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF5F6368),
                )
            }
            Text(
                "Переснимите доску прямо сверху, при ровном свете, без бликов.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF5F6368),
            )
        }
    }
}

@Composable
private fun PositionAnalysisCard(
    fen: String,
    advice: PhotoPositionAdvice?,
    coachText: String?,
    analyzing: Boolean,
    error: String?,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (error != null) Color(0xFFFCE8E6) else Color(0xFFF1F3F4),
        ),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val whiteToMove = MoveNotation.isWhiteToMove(fen)
            Text(
                "Ход " + (if (whiteToMove) "белых" else "чёрных"),
                style = MaterialTheme.typography.labelLarge,
            )

            when {
                error != null -> Text(error, style = MaterialTheme.typography.bodySmall)

                advice != null -> {
                    Text(
                        "Оценка " + ScoreFormat.display(advice.scoreFromWhite),
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text("Лучший ход: " + advice.bestMoveSan, style = MaterialTheme.typography.bodyMedium)

                    when {
                        coachText != null -> Text(coachText, style = MaterialTheme.typography.bodyMedium)
                        analyzing -> Text(
                            "Тренер разбирает позицию…",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF5F6368),
                        )
                        else -> Text(
                            "Для этой позиции тренеру нечего добавить.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF5F6368),
                        )
                    }
                }

                analyzing -> Text("Движок считает…", style = MaterialTheme.typography.bodyMedium)

                else -> Text("Нет данных для этой позиции.")
            }
        }
    }
}

/** Bitmap в GrayImage: без альфы, как того просит интерфейс :core. */
private fun bitmapToGrayImage(bitmap: Bitmap): GrayImage {
    val width = bitmap.width
    val height = bitmap.height
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
    for (i in pixels.indices) {
        pixels[i] = pixels[i] and 0xFFFFFF
    }
    return GrayImage(width, height, pixels)
}

/** Сторона снимка не больше [maxSide]: распознаванию доски мегапиксели ни к чему. */
private fun downscale(bitmap: Bitmap, maxSide: Int): Bitmap {
    val longSide = maxOf(bitmap.width, bitmap.height)
    if (longSide <= maxSide) return bitmap
    val scale = maxSide.toDouble() / longSide
    val newWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
    val newHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
}

/**
 * Адрес временного файла для снимка с камеры.
 *
 * Файл лежит в кэше приложения и отдаётся камере через FileProvider: прямой
 * путь file:// система запрещает передавать другим приложениям начиная с
 * Android 7, а разрешение на съёмку так не требуется вовсе — снимает
 * стороннее приложение камеры, а не мы.
 */
private fun newPhotoUri(context: Context): Uri {
    val folder = File(context.cacheDir, "camera")
    folder.mkdirs()
    val file = File(folder, "board-" + System.currentTimeMillis() + ".jpg")
    return FileProvider.getUriForFile(context, context.packageName + ".photos", file)
}
