package com.bibledailyshine.generator

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    companion object {

        private const val VIDEO_WIDTH = 1080
        private const val VIDEO_HEIGHT = 1920

        private const val VIDEO_DURATION_SECONDS = 8

        private const val VIDEO_FPS = 30

        private const val TOP_RESERVED = 200
        private const val BOTTOM_RESERVED = 200

        private const val TEXT_LEFT = 200
        private const val TEXT_RIGHT = 200

        private const val MAX_TEXT_WIDTH = 680

        private const val VERSE_AREA_TOP = 400
        private const val VERSE_AREA_BOTTOM = 1720

        private const val MAX_VERSE_HEIGHT =
            VERSE_AREA_BOTTOM - VERSE_AREA_TOP

        private const val MAX_VERSE_FONT_SIZE = 80f
        private const val MIN_VERSE_FONT_SIZE = 28f

        private const val HEADING_FONT_SIZE = 82f
        private const val REFERENCE_FONT_SIZE = 52f

        private const val HEADING_TEXT = "Bible Verse"

        private const val HEADING_COLOR = Color.YELLOW
        private const val REFERENCE_COLOR = Color.YELLOW
        private const val VERSE_COLOR = Color.WHITE
        private const val BACKGROUND_COLOR = Color.BLACK

        private const val MUSIC_VOLUME = 0.20f

        private const val CREATE_DOCUMENT_REQUEST = 5001

        private const val ASSET_FONT = "font.ttf"
        private const val ASSET_MUSIC = "bg.mp3"
    }

    private lateinit var verseInput: EditText
    private lateinit var generateButton: Button
    private lateinit var saveButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView

    private val executor: ExecutorService =
        Executors.newSingleThreadExecutor()

    private val generating = AtomicBoolean(false)

    private val generatedVideos = mutableListOf<File>()

    private var currentZipFile: File? = null

    private var customTypeface: Typeface? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        customTypeface = loadFont()

        buildUserInterface()
    }

    override fun onDestroy() {
        super.onDestroy()

        generating.set(false)

        executor.shutdownNow()
    }

    private fun buildUserInterface() {

        val rootScroll = ScrollView(this)

        rootScroll.setBackgroundColor(Color.rgb(18, 18, 18))

        val root = LinearLayout(this)

        root.orientation = LinearLayout.VERTICAL

        root.setPadding(
            dp(18),
            dp(18),
            dp(18),
            dp(24)
        )

        root.gravity = Gravity.CENTER_HORIZONTAL

        rootScroll.addView(
            root,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val title = TextView(this)

        title.text = "Bible Verse Video Generator"

        title.textSize = 24f

        title.setTextColor(Color.WHITE)

        title.gravity = Gravity.CENTER

        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD)

        root.addView(
            title,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10)
            }
        )

        val subtitle = TextView(this)

        subtitle.text =
            "Enter one Bible verse per line.\nEach line creates one separate 8-second video."

        subtitle.textSize = 15f

        subtitle.setTextColor(Color.LTGRAY)

        subtitle.gravity = Gravity.CENTER

        root.addView(
            subtitle,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(16)
            }
        )

        verseInput = EditText(this)

        verseInput.setTextColor(Color.WHITE)

        verseInput.setHintTextColor(Color.GRAY)

        verseInput.setHint(
            "Example:\nBe strong and courageous. Do not be afraid; do not be discouraged. — Joshua 1:9"
        )

        verseInput.textSize = 16f

        verseInput.gravity = Gravity.TOP or Gravity.START

        verseInput.setPadding(
            dp(14),
            dp(14),
            dp(14),
            dp(14)
        )

        verseInput.setSingleLine(false)

        verseInput.minLines = 10

        verseInput.maxLines = 20

        verseInput.isVerticalScrollBarEnabled = true

        verseInput.setBackgroundColor(
            Color.rgb(35, 35, 35)
        )

        root.addView(
            verseInput,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(260)
            ).apply {
                bottomMargin = dp(14)
            }
        )

        generateButton = Button(this)

        generateButton.text = "GENERATE VIDEOS"

        generateButton.textSize = 16f

        generateButton.setTextColor(Color.BLACK)

        generateButton.setBackgroundColor(
            Color.rgb(255, 214, 0)
        )

        generateButton.setOnClickListener {
            startGeneration()
        }

        root.addView(
            generateButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(54)
            ).apply {
                bottomMargin = dp(10)
            }
        )

        saveButton = Button(this)

        saveButton.text = "SAVE ALL VIDEOS AS ZIP"

        saveButton.textSize = 16f

        saveButton.isEnabled = false

        saveButton.setOnClickListener {
            saveZip()
        }

        root.addView(
            saveButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(54)
            ).apply {
                bottomMargin = dp(16)
            }
        )

        progressBar = ProgressBar(
            this,
            null,
            android.R.attr.progressBarStyleHorizontal
        )

        progressBar.max = 100

        progressBar.progress = 0

        root.addView(
            progressBar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(8)
            ).apply {
                bottomMargin = dp(12)
            }
        )

        statusText = TextView(this)

        statusText.text =
            "Ready. Add your Bible verses and press Generate Videos."

        statusText.textSize = 14f

        statusText.setTextColor(Color.LTGRAY)

        statusText.gravity = Gravity.CENTER

        root.addView(
            statusText,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        setContentView(rootScroll)
    }

    private fun startGeneration() {

        if (generating.get()) {
            return
        }

        val rawText =
            verseInput.text?.toString()?.trim().orEmpty()

        if (rawText.isBlank()) {

            Toast.makeText(
                this,
                "Enter at least one Bible verse.",
                Toast.LENGTH_LONG
            ).show()

            return
        }

        val lines = rawText
            .split("\n")
            .map {
                it.trim()
                    .replace("\r", "")
            }
            .filter {
                it.isNotBlank()
            }

        if (lines.isEmpty()) {

            Toast.makeText(
                this,
                "No valid verses found.",
                Toast.LENGTH_LONG
            ).show()

            return
        }

        if (!assetExists(ASSET_FONT)) {

            showError(
                "Missing font.ttf in app assets."
            )

            return
        }

        if (!assetExists(ASSET_MUSIC)) {

            showError(
                "Missing bg.mp3 in app assets."
            )

            return
        }

        generating.set(true)

        generatedVideos.clear()

        currentZipFile = null

        generateButton.isEnabled = false

        saveButton.isEnabled = false

        progressBar.progress = 0

        setStatus(
            "Preparing ${lines.size} video(s)..."
        )

        executor.execute {

            try {

                val outputDirectory =
                    File(
                        cacheDir,
                        "generated_videos"
                    )

                if (outputDirectory.exists()) {
                    outputDirectory.deleteRecursively()
                }

                if (!outputDirectory.mkdirs()) {
                    throw Exception(
                        "Unable to create output directory."
                    )
                }

                val temporaryDirectory =
                    File(
                        cacheDir,
                        "video_temp"
                    )

                if (temporaryDirectory.exists()) {
                    temporaryDirectory.deleteRecursively()
                }

                if (!temporaryDirectory.mkdirs()) {
                    throw Exception(
                        "Unable to create temporary directory."
                    )
                }

                val musicFile =
                    copyAssetToCache(
                        ASSET_MUSIC,
                        "background_music.mp3"
                    )

                val total = lines.size

                for (index in lines.indices) {

                    if (!generating.get()) {
                        throw InterruptedException(
                            "Generation cancelled."
                        )
                    }

                    val item =
                        parseVerse(lines[index])

                    val number = index + 1

                    updateProgress(
                        ((index.toFloat() / total.toFloat()) * 100f)
                            .toInt()
                    )

                    setStatus(
                        "Creating video $number of $total..."
                    )

                    val frameFile =
                        File(
                            temporaryDirectory,
                            "frame_$number.png"
                        )

                    val videoFile =
                        File(
                            outputDirectory,
                            "$number.mp4"
                        )

                    createFrame(
                        item = item,
                        output = frameFile
                    )

                    createVideo(
                        frame = frameFile,
                        music = musicFile,
                        output = videoFile
                    )

                    if (!videoFile.exists() ||
                        videoFile.length() <= 0L
                    ) {

                        throw Exception(
                            "Video $number was not created."
                        )
                    }

                    generatedVideos.add(videoFile)

                    updateProgress(
                        ((number.toFloat() / total.toFloat()) * 100f)
                            .toInt()
                    )

                    setStatus(
                        "Completed $number of $total"
                    )
                }

                setStatus(
                    "Creating ZIP file..."
                )

                val zipFile =
                    File(
                        cacheDir,
                        "BibleVerseVideos.zip"
                    )

                if (zipFile.exists()) {
                    zipFile.delete()
                }

                createZip(
                    generatedVideos,
                    zipFile
                )

                currentZipFile = zipFile

                updateProgress(100)

                setStatus(
                    "Finished. ${generatedVideos.size} video(s) ready."
                )

                runOnUiThread {
                    saveButton.isEnabled = true
                }

            } catch (e: InterruptedException) {

                setStatus(
                    "Generation cancelled."
                )

            } catch (e: Exception) {

                showError(
                    e.message ?: "Unknown generation error."
                )

            } finally {

                generating.set(false)

                runOnUiThread {
                    generateButton.isEnabled = true
                }
            }
        }
    }

    private data class VerseItem(
        val verse: String,
        val reference: String
    )

    private fun parseVerse(
        input: String
    ): VerseItem {

        val cleaned =
            input.trim()

        val separators = listOf(
            " — ",
            " – ",
            " - ",
            "—",
            "–"
        )

        for (separator in separators) {

            val position =
                cleaned.lastIndexOf(separator)

            if (position > 0 &&
                position < cleaned.length - separator.length
            ) {

                val verse =
                    cleaned.substring(
                        0,
                        position
                    ).trim()

                val reference =
                    cleaned.substring(
                        position + separator.length
                    ).trim()

                if (verse.isNotBlank() &&
                    reference.isNotBlank()
                ) {

                    return VerseItem(
                        verse = verse,
                        reference = reference
                    )
                }
            }
        }

        return VerseItem(
            verse = cleaned,
            reference = ""
        )
    }

    private fun createFrame(
        item: VerseItem,
        output: File
    ) {

        val bitmap =
            Bitmap.createBitmap(
                VIDEO_WIDTH,
                VIDEO_HEIGHT,
                Bitmap.Config.ARGB_8888
            )

        val canvas =
            Canvas(bitmap)

        canvas.drawColor(
            BACKGROUND_COLOR
        )

        val typeface =
            customTypeface
                ?: Typeface.DEFAULT

        val headingPaint =
            Paint(
                Paint.ANTI_ALIAS_FLAG or
                        Paint.SUBPIXEL_TEXT_FLAG
            )

        headingPaint.color =
            HEADING_COLOR

        headingPaint.textSize =
            HEADING_FONT_SIZE

        headingPaint.typeface =
            Typeface.create(
                typeface,
                Typeface.BOLD
            )

        headingPaint.textAlign =
            Paint.Align.CENTER

        headingPaint.isDither = true

        drawCenteredText(
            canvas = canvas,
            text = HEADING_TEXT,
            paint = headingPaint,
            centerX = VIDEO_WIDTH / 2f,
            baselineY = 290f
        )

        if (item.reference.isNotBlank()) {

            val referencePaint =
                Paint(
                    Paint.ANTI_ALIAS_FLAG or
                            Paint.SUBPIXEL_TEXT_FLAG
                )

            referencePaint.color =
                REFERENCE_COLOR

            referencePaint.textSize =
                REFERENCE_FONT_SIZE

            referencePaint.typeface =
                Typeface.create(
                    typeface,
                    Typeface.BOLD
                )

            referencePaint.textAlign =
                Paint.Align.CENTER

            referencePaint.isDither = true

            drawCenteredText(
                canvas = canvas,
                text = item.reference,
                paint = referencePaint,
                centerX = VIDEO_WIDTH / 2f,
                baselineY = 365f
            )
        }

        val versePaint =
            Paint(
                Paint.ANTI_ALIAS_FLAG or
                        Paint.SUBPIXEL_TEXT_FLAG
            )

        versePaint.color =
            VERSE_COLOR

        versePaint.typeface =
            typeface

        versePaint.textAlign =
            Paint.Align.CENTER

        versePaint.isDither = true

        val availableWidth =
            min(
                MAX_TEXT_WIDTH,
                VIDEO_WIDTH - TEXT_LEFT - TEXT_RIGHT
            ).toFloat()

        val result =
            findBestTextLayout(
                text = item.verse,
                basePaint = versePaint,
                availableWidth = availableWidth,
                maxHeight = MAX_VERSE_HEIGHT.toFloat()
            )

        versePaint.textSize =
            result.textSize

        drawTextLines(
            canvas = canvas,
            lines = result.lines,
            paint = versePaint,
            centerX = VIDEO_WIDTH / 2f,
            top = VERSE_AREA_TOP.toFloat(),
            bottom = VERSE_AREA_BOTTOM.toFloat()
        )

        val stream =
            FileOutputStream(output)

        stream.use {
            bitmap.compress(
                Bitmap.CompressFormat.PNG,
                100,
                it
            )
        }

        bitmap.recycle()
    }

    private data class TextLayout(
        val lines: List<String>,
        val textSize: Float,
        val lineHeight: Float
    )

    private fun findBestTextLayout(
        text: String,
        basePaint: Paint,
        availableWidth: Float,
        maxHeight: Float
    ): TextLayout {

        var fontSize =
            MAX_VERSE_FONT_SIZE

        while (
            fontSize >= MIN_VERSE_FONT_SIZE
        ) {

            basePaint.textSize =
                fontSize

            val lines =
                wrapText(
                    text = text,
                    paint = basePaint,
                    maxWidth = availableWidth
                )

            val metrics =
                basePaint.fontMetrics

            val lineHeight =
                (
                    metrics.descent -
                            metrics.ascent
                    ) * 1.18f

            val totalHeight =
                lines.size * lineHeight

            if (totalHeight <= maxHeight) {

                return TextLayout(
                    lines = lines,
                    textSize = fontSize,
                    lineHeight = lineHeight
                )
            }

            fontSize -= 1f
        }

        basePaint.textSize =
            MIN_VERSE_FONT_SIZE

        val finalLines =
            wrapText(
                text = text,
                paint = basePaint,
                maxWidth = availableWidth
            )

        val metrics =
            basePaint.fontMetrics

        val finalLineHeight =
            (
                metrics.descent -
                        metrics.ascent
                ) * 1.12f

        return TextLayout(
            lines = finalLines,
            textSize = MIN_VERSE_FONT_SIZE,
            lineHeight = finalLineHeight
        )
    }

    private fun wrapText(
        text: String,
        paint: Paint,
        maxWidth: Float
    ): List<String> {

        val paragraphs =
            text.replace(
                "\r",
                ""
            ).split("\n")

        val result =
            mutableListOf<String>()

        for (paragraph in paragraphs) {

            val words =
                paragraph
                    .trim()
                    .split(
                        Regex("\\s+")
                    )
                    .filter {
                        it.isNotBlank()
                    }

            if (words.isEmpty()) {
                result.add("")
                continue
            }

            var current =
                StringBuilder()

            for (word in words) {

                val candidate =
                    if (current.isEmpty()) {
                        word
                    } else {
                        "${current} $word"
                    }

                if (
                    paint.measureText(candidate)
                        <= maxWidth
                ) {

                    if (current.isNotEmpty()) {
                        current.append(" ")
                    }

                    current.append(word)

                } else {

                    if (current.isNotEmpty()) {

                        result.add(
                            current.toString()
                        )

                        current =
                            StringBuilder()
                    }

                    if (
                        paint.measureText(word)
                            <= maxWidth
                    ) {

                        current.append(word)

                    } else {

                        splitLongWord(
                            word,
                            paint,
                            maxWidth,
                            result
                        )
                    }
                }
            }

            if (current.isNotEmpty()) {
                result.add(
                    current.toString()
                )
            }
        }

        return if (result.isEmpty()) {
            listOf("")
        } else {
            result
        }
    }

    private fun splitLongWord(
        word: String,
        paint: Paint,
        maxWidth: Float,
        output: MutableList<String>
    ) {

        var current =
            StringBuilder()

        for (character in word) {

            val candidate =
                current.toString() + character

            if (
                paint.measureText(candidate)
                    <= maxWidth
            ) {

                current.append(character)

            } else {

                if (current.isNotEmpty()) {

                    output.add(
                        current.toString()
                    )

                    current =
                        StringBuilder()
                }

                current.append(character)
            }
        }

        if (current.isNotEmpty()) {

            output.add(
                current.toString()
            )
        }
    }

    private fun drawTextLines(
        canvas: Canvas,
        lines: List<String>,
        paint: Paint,
        centerX: Float,
        top: Float,
        bottom: Float
    ) {

        if (lines.isEmpty()) {
            return
        }

        val metrics =
            paint.fontMetrics

        val lineHeight =
            (
                metrics.descent -
                        metrics.ascent
                ) * 1.18f

        val totalHeight =
            lines.size * lineHeight

        val availableHeight =
            bottom - top

        val firstBaseline =
            top +
                    (
                        availableHeight -
                                totalHeight
                        ) / 2f -
                    metrics.ascent

        var baseline =
            firstBaseline

        for (line in lines) {

            canvas.drawText(
                line,
                centerX,
                baseline,
                paint
            )

            baseline += lineHeight
        }
    }

    private fun drawCenteredText(
        canvas: Canvas,
        text: String,
        paint: Paint,
        centerX: Float,
        baselineY: Float
    ) {

        canvas.drawText(
            text,
            centerX,
            baselineY,
            paint
        )
    }

    private fun createVideo(
        frame: File,
        music: File,
        output: File
    ) {

        if (output.exists()) {
            output.delete()
        }

        val framePath =
            ffmpegPath(frame)

        val musicPath =
            ffmpegPath(music)

        val outputPath =
            ffmpegPath(output)

        val command =
            """
            -y
            -loop 1
            -framerate $VIDEO_FPS
            -i "$framePath"
            -stream_loop -1
            -i "$musicPath"
            -map 0:v:0
            -map 1:a:0
            -t $VIDEO_DURATION_SECONDS
            -vf "scale=$VIDEO_WIDTH:$VIDEO_HEIGHT:force_original_aspect_ratio=disable,format=yuv420p"
            -c:v libopenh264
            -b:v 5M
            -r $VIDEO_FPS
            -pix_fmt yuv420p
            -c:a aac
            -b:a 192k
            -ar 44100
            -ac 2
            -af "volume=$MUSIC_VOLUME"
            -movflags +faststart
            "$outputPath"
            """.trimIndent()
                .replace(
                    Regex("\\s+"),
                    " "
                )

        val session =
            FFmpegKit.execute(
                command
            )

        val returnCode =
            session.returnCode

        if (!ReturnCode.isSuccess(returnCode)) {

            val logs =
                session.allLogsAsString

            throw Exception(
                "FFmpeg failed.\n\n$logs"
            )
        }

        if (!output.exists()) {

            throw Exception(
                "FFmpeg completed but output file was not created."
            )
        }

        if (output.length() < 1024L) {

            throw Exception(
                "Generated video file is invalid."
            )
        }
    }

    private fun createZip(
        files: List<File>,
        zipFile: File
    ) {

        ZipOutputStream(
            BufferedOutputStream(
                FileOutputStream(zipFile)
            )
        ).use { zip ->

            val buffer =
                ByteArray(64 * 1024)

            for (file in files) {

                if (!file.exists()) {
                    continue
                }

                val entry =
                    ZipEntry(file.name)

                zip.putNextEntry(entry)

                BufferedInputStream(
                    FileInputStream(file)
                ).use { input ->

                    while (true) {

                        val count =
                            input.read(buffer)

                        if (count <= 0) {
                            break
                        }

                        zip.write(
                            buffer,
                            0,
                            count
                        )
                    }
                }

                zip.closeEntry()
            }
        }
    }

    private fun saveZip() {

        val zip =
            currentZipFile

        if (
            zip == null ||
            !zip.exists()
        ) {

            Toast.makeText(
                this,
                "Generate the videos first.",
                Toast.LENGTH_LONG
            ).show()

            return
        }

        val intent =
            Intent(
                Intent.ACTION_CREATE_DOCUMENT
            ).apply {

                addCategory(
                    Intent.CATEGORY_OPENABLE
                )

                type =
                    "application/zip"

                putExtra(
                    Intent.EXTRA_TITLE,
                    "BibleVerseVideos.zip"
                )
            }

        startActivityForResult(
            intent,
            CREATE_DOCUMENT_REQUEST
        )
    }

    @Deprecated("Handled for compatibility with Android API levels.")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {

        super.onActivityResult(
            requestCode,
            resultCode,
            data
        )

        if (
            requestCode != CREATE_DOCUMENT_REQUEST ||
            resultCode != Activity.RESULT_OK
        ) {
            return
        }

        val uri =
            data?.data

        if (uri == null) {

            Toast.makeText(
                this,
                "Save location was not selected.",
                Toast.LENGTH_LONG
            ).show()

            return
        }

        val zip =
            currentZipFile

        if (
            zip == null ||
            !zip.exists()
        ) {

            Toast.makeText(
                this,
                "ZIP file no longer exists.",
                Toast.LENGTH_LONG
            ).show()

            return
        }

        executor.execute {

            try {

                contentResolver.openOutputStream(
                    uri
                ).use { output ->

                    if (output == null) {
                        throw Exception(
                            "Unable to open selected location."
                        )
                    }

                    FileInputStream(
                        zip
                    ).use { input ->

                        val buffer =
                            ByteArray(64 * 1024)

                        while (true) {

                            val count =
                                input.read(buffer)

                            if (count <= 0) {
                                break
                            }

                            output.write(
                                buffer,
                                0,
                                count
                            )
                        }

                        output.flush()
                    }
                }

                runOnUiThread {

                    Toast.makeText(
                        this,
                        "BibleVerseVideos.zip saved successfully.",
                        Toast.LENGTH_LONG
                    ).show()

                    setStatus(
                        "ZIP saved successfully."
                    )
                }

            } catch (e: Exception) {

                showError(
                    "Unable to save ZIP: ${e.message}"
                )
            }
        }
    }

    private fun copyAssetToCache(
        assetName: String,
        outputName: String
    ): File {

        val output =
            File(
                cacheDir,
                outputName
            )

        assets.open(
            assetName
        ).use { input ->

            FileOutputStream(
                output
            ).use { outputStream ->

                val buffer =
                    ByteArray(64 * 1024)

                while (true) {

                    val count =
                        input.read(buffer)

                    if (count <= 0) {
                        break
                    }

                    outputStream.write(
                        buffer,
                        0,
                        count
                    )
                }

                outputStream.flush()
            }
        }

        return output
    }

    private fun loadFont(): Typeface? {

        return try {

            val fontFile =
                File(
                    cacheDir,
                    ASSET_FONT
                )

            if (!fontFile.exists()) {

                assets.open(
                    ASSET_FONT
                ).use { input ->

                    FileOutputStream(
                        fontFile
                    ).use { output ->

                        val buffer =
                            ByteArray(16 * 1024)

                        while (true) {

                            val count =
                                input.read(buffer)

                            if (count <= 0) {
                                break
                            }

                            output.write(
                                buffer,
                                0,
                                count
                            )
                        }
                    }
                }
            }

            Typeface.createFromFile(
                fontFile
            )

        } catch (_: Exception) {

            Typeface.DEFAULT
        }
    }

    private fun assetExists(
        assetName: String
    ): Boolean {

        return try {

            assets.open(
                assetName
            ).use {
                true
            }

        } catch (_: Exception) {

            false
        }
    }

    private fun ffmpegPath(
        file: File
    ): String {

        return file.absolutePath
            .replace(
                "\\",
                "/"
            )
            .replace(
                "'",
                "'\\''"
            )
    }

    private fun updateProgress(
        progress: Int
    ) {

        val safeProgress =
            progress.coerceIn(
                0,
                100
            )

        runOnUiThread {

            progressBar.progress =
                safeProgress
        }
    }

    private fun setStatus(
        message: String
    ) {

        runOnUiThread {

            statusText.text =
                message
        }
    }

    private fun showError(
        message: String
    ) {

        runOnUiThread {

            statusText.text =
                "Error: $message"

            Toast.makeText(
                this,
                message,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun dp(
        value: Int
    ): Int {

        return (
            value *
                    resources.displayMetrics.density
            ).toInt()
    }
}
