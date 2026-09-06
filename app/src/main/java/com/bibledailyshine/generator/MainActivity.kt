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
import android.os.Environment
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    companion object {
        private const val VIDEO_WIDTH = 1080
        private const val VIDEO_HEIGHT = 1920
        private const val VIDEO_DURATION = 8
        private const val FPS = 30

        private const val TOP_RESERVED = 200
        private const val BOTTOM_RESERVED = 200

        private const val VERSE_MAX_WIDTH = 680
        private const val VERSE_MAX_HEIGHT = 1320

        private const val MAX_FONT_SIZE = 80f
        private const val MIN_FONT_SIZE = 28f

        private const val HEADING_SIZE = 72f
        private const val REFERENCE_SIZE = 48f

        private const val MUSIC_VOLUME = 0.20f

        private const val CREATE_ZIP_REQUEST = 5001
    }

    private lateinit var verseInput: EditText
    private lateinit var generateButton: Button
    private lateinit var saveButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView

    private var generatedZip: File? = null
    private var isGenerating = false

    private var customTypeface: Typeface? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        loadFont()
        createUserInterface()
    }

    private fun loadFont() {
        try {
            assets.open("font.ttf").use { input ->
                val tempFont = File(cacheDir, "font.ttf")

                FileOutputStream(tempFont).use { output ->
                    input.copyTo(output)
                }

                customTypeface = Typeface.createFromFile(tempFont)
            }
        } catch (e: Exception) {
            customTypeface = Typeface.create(
                Typeface.SANS_SERIF,
                Typeface.NORMAL
            )
        }
    }

    private fun createUserInterface() {

        val rootScroll = ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            isFillViewport = true
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dp(20),
                dp(20),
                dp(20),
                dp(24)
            )
        }

        rootScroll.addView(
            container,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val title = TextView(this).apply {
            text = "Bible Verse Video Generator"
            textSize = 25f
            setTextColor(Color.YELLOW)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(12))
        }

        container.addView(
            title,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val instructions = TextView(this).apply {
            text =
                "Paste all Bible verses below.\n" +
                "Use ONE verse per line.\n\n" +
                "Each line creates one separate 8-second video."
            textSize = 16f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(16))
        }

        container.addView(
            instructions,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        verseInput = EditText(this).apply {
            setBackgroundColor(Color.rgb(25, 25, 25))
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)

            hint =
                "Verse 1 — John 3:16\n" +
                "Verse 2 — Psalm 23:1\n" +
                "Verse 3 — Philippians 4:13"

            textSize = 17f

            gravity = Gravity.TOP or Gravity.START

            setPadding(
                dp(14),
                dp(14),
                dp(14),
                dp(14)
            )

            minLines = 12
            maxLines = 20

            isSingleLine = false

            inputType =
                android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES

            setSelectAllOnFocus(false)
        }

        container.addView(
            verseInput,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(280)
            ).apply {
                bottomMargin = dp(16)
            }
        )

        generateButton = Button(this).apply {
            text = "GENERATE VIDEOS"
            textSize = 17f
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.YELLOW)
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
        }

        container.addView(
            generateButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(58)
            ).apply {
                bottomMargin = dp(12)
            }
        )

        saveButton = Button(this).apply {
            text = "SAVE ZIP"
            textSize = 17f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.DKGRAY)
            typeface = Typeface.DEFAULT_BOLD
            isEnabled = false
        }

        container.addView(
            saveButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(58)
            ).apply {
                bottomMargin = dp(16)
            }
        )

        progressBar = ProgressBar(
            this,
            null,
            android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 100
            progress = 0
            visibility = View.GONE
        }

        container.addView(
            progressBar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(8)
            ).apply {
                bottomMargin = dp(12)
            }
        )

        statusText = TextView(this).apply {
            text = "Ready"
            textSize = 15f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
        }

        container.addView(
            statusText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        setContentView(rootScroll)

        generateButton.setOnClickListener {
            startGeneration()
        }

        saveButton.setOnClickListener {
            saveGeneratedZip()
        }
    }

    private fun startGeneration() {

        if (isGenerating) {
            return
        }

        val rawText = verseInput.text.toString()

        val lines = rawText
            .split("\n")
            .map {
                it
                    .replace("\r", "")
                    .trim()
            }
            .filter {
                it.isNotBlank()
            }

        if (lines.isEmpty()) {
            Toast.makeText(
                this,
                "Please enter at least one Bible verse.",
                Toast.LENGTH_LONG
            ).show()

            return
        }

        isGenerating = true

        generateButton.isEnabled = false
        saveButton.isEnabled = false
        progressBar.visibility = View.VISIBLE
        progressBar.progress = 0

        statusText.text =
            "Preparing ${lines.size} video${if (lines.size == 1) "" else "s"}..."

        thread {

            try {

                val outputDirectory = File(
                    cacheDir,
                    "BibleVerseVideos"
                )

                if (outputDirectory.exists()) {
                    outputDirectory.deleteRecursively()
                }

                outputDirectory.mkdirs()

                val musicFile = copyAssetToCache(
                    "bg.mp3",
                    "background_music.mp3"
                )

                for (index in lines.indices) {

                    val line = lines[index]

                    runOnUiThread {
                        statusText.text =
                            "Generating ${index + 1} of ${lines.size}..."
                    }

                    val parsed = parseVerse(line)

                    val frameFile = File(
                        cacheDir,
                        "frame_${index + 1}.png"
                    )

                    createFrame(
                        verseText = parsed.first,
                        reference = parsed.second,
                        output = frameFile
                    )

                    val videoFile = File(
                        outputDirectory,
                        "${index + 1}.mp4"
                    )

                    val success = createVideo(
                        frameFile,
                        musicFile,
                        videoFile
                    )

                    frameFile.delete()

                    if (!success) {
                        throw Exception(
                            "Failed to create video ${index + 1}"
                        )
                    }

                    val progress =
                        ((index + 1) * 100) / lines.size

                    runOnUiThread {
                        progressBar.progress = progress
                    }
                }

                runOnUiThread {
                    statusText.text = "Creating ZIP..."
                }

                val zipFile = File(
                    cacheDir,
                    "BibleVerseVideos.zip"
                )

                if (zipFile.exists()) {
                    zipFile.delete()
                }

                zipDirectory(
                    outputDirectory,
                    zipFile
                )

                generatedZip = zipFile

                runOnUiThread {

                    progressBar.progress = 100

                    statusText.text =
                        "Completed ${lines.size} video${if (lines.size == 1) "" else "s"}."

                    saveButton.isEnabled = true
                    generateButton.isEnabled = true

                    Toast.makeText(
                        this,
                        "Videos generated successfully.",
                        Toast.LENGTH_LONG
                    ).show()
                }

            } catch (e: Exception) {

                e.printStackTrace()

                runOnUiThread {

                    progressBar.progress = 0

                    statusText.text =
                        "Error: ${e.message ?: "Unknown error"}"

                    generateButton.isEnabled = true
                    saveButton.isEnabled =
                        generatedZip?.exists() == true

                    Toast.makeText(
                        this,
                        "Generation failed: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }

            } finally {

                isGenerating = false
            }
        }
    }

    private fun parseVerse(line: String): Pair<String, String> {

        val separators = listOf(
            " — ",
            " – ",
            " - "
        )

        for (separator in separators) {

            val position = line.lastIndexOf(separator)

            if (position > 0 && position < line.length - separator.length) {

                val verse = line.substring(
                    0,
                    position
                ).trim()

                val reference = line.substring(
                    position + separator.length
                ).trim()

                if (verse.isNotBlank() && reference.isNotBlank()) {
                    return Pair(
                        verse,
                        reference
                    )
                }
            }
        }

        val emDash = line.lastIndexOf("—")

        if (emDash > 0 && emDash < line.length - 1) {

            val verse = line.substring(
                0,
                emDash
            ).trim()

            val reference = line.substring(
                emDash + 1
            ).trim()

            if (verse.isNotBlank() && reference.isNotBlank()) {
                return Pair(
                    verse,
                    reference
                )
            }
        }

        val enDash = line.lastIndexOf("–")

        if (enDash > 0 && enDash < line.length - 1) {

            val verse = line.substring(
                0,
                enDash
            ).trim()

            val reference = line.substring(
                enDash + 1
            ).trim()

            if (verse.isNotBlank() && reference.isNotBlank()) {
                return Pair(
                    verse,
                    reference
                )
            }
        }

        return Pair(
            line.trim(),
            ""
        )
    }

    private fun createFrame(
        verseText: String,
        reference: String,
        output: File
    ) {

        val bitmap = Bitmap.createBitmap(
            VIDEO_WIDTH,
            VIDEO_HEIGHT,
            Bitmap.Config.ARGB_8888
        )

        val canvas = Canvas(bitmap)

        canvas.drawColor(Color.BLACK)

        val paint = Paint(
            Paint.ANTI_ALIAS_FLAG or
                Paint.SUBPIXEL_TEXT_FLAG
        )

        paint.typeface =
            customTypeface ?: Typeface.DEFAULT

        paint.textAlign = Paint.Align.CENTER

        // ------------------------------------------------------------
        // HEADING
        // ------------------------------------------------------------

        paint.textSize = HEADING_SIZE
        paint.color = Color.YELLOW

        val headingBaseline = 285f

        canvas.drawText(
            "Bible Verse",
            VIDEO_WIDTH / 2f,
            headingBaseline,
            paint
        )

        // ------------------------------------------------------------
        // BIBLE REFERENCE
        // ------------------------------------------------------------

        if (reference.isNotBlank()) {

            paint.textSize = REFERENCE_SIZE
            paint.color = Color.YELLOW

            val referenceBaseline = 365f

            val referenceLines =
                wrapText(
                    reference,
                    paint,
                    VERSE_MAX_WIDTH
                )

            var y = referenceBaseline

            for (textLine in referenceLines) {

                canvas.drawText(
                    textLine,
                    VIDEO_WIDTH / 2f,
                    y,
                    paint
                )

                y += paint.fontSpacing
            }
        }

        // ------------------------------------------------------------
        // VERSE
        // ------------------------------------------------------------

        var fontSize = MAX_FONT_SIZE

        var verseLines: List<String>

        while (true) {

            paint.textSize = fontSize
            paint.color = Color.WHITE

            verseLines = wrapText(
                verseText,
                paint,
                VERSE_MAX_WIDTH
            )

            val totalHeight =
                verseLines.size * paint.fontSpacing

            if (
                totalHeight <= VERSE_MAX_HEIGHT ||
                fontSize <= MIN_FONT_SIZE
            ) {
                break
            }

            fontSize -= 2f
        }

        paint.textSize = fontSize
        paint.color = Color.WHITE

        verseLines = wrapText(
            verseText,
            paint,
            VERSE_MAX_WIDTH
        )

        val totalHeight =
            verseLines.size * paint.fontSpacing

        val availableTop =
            TOP_RESERVED + 200f

        val availableBottom =
            VIDEO_HEIGHT - BOTTOM_RESERVED

        val availableHeight =
            availableBottom - availableTop

        var startY =
            availableTop +
                (availableHeight - totalHeight) / 2f -
                paint.ascent()

        val minBaseline =
            TOP_RESERVED +
                (-paint.ascent()) +
                20f

        val maxBaseline =
            VIDEO_HEIGHT -
                BOTTOM_RESERVED -
                totalHeight +
                (-paint.ascent())

        startY = startY.coerceIn(
            minBaseline,
            maxBaseline
        )

        for (textLine in verseLines) {

            canvas.drawText(
                textLine,
                VIDEO_WIDTH / 2f,
                startY,
                paint
            )

            startY += paint.fontSpacing
        }

        FileOutputStream(output).use { stream ->

            bitmap.compress(
                Bitmap.CompressFormat.PNG,
                100,
                stream
            )
        }

        bitmap.recycle()
    }

    private fun wrapText(
        text: String,
        paint: Paint,
        maxWidth: Int
    ): List<String> {

        val result = mutableListOf<String>()

        val paragraphs =
            text.replace("\r", "")
                .split("\n")

        for (paragraph in paragraphs) {

            val cleaned =
                paragraph.trim()

            if (cleaned.isEmpty()) {
                result.add("")
                continue
            }

            val words =
                cleaned.split(
                    Regex("\\s+")
                )

            var current = ""

            for (word in words) {

                if (word.isEmpty()) {
                    continue
                }

                val test =
                    if (current.isEmpty()) {
                        word
                    } else {
                        "$current $word"
                    }

                if (
                    paint.measureText(test) <= maxWidth
                ) {

                    current = test

                } else {

                    if (current.isNotEmpty()) {
                        result.add(current)
                    }

                    if (
                        paint.measureText(word) <= maxWidth
                    ) {

                        current = word

                    } else {

                        val splitParts =
                            splitLongWord(
                                word,
                                paint,
                                maxWidth
                            )

                        if (splitParts.isNotEmpty()) {

                            result.addAll(
                                splitParts.dropLast(1)
                            )

                            current =
                                splitParts.last()
                        }
                    }
                }
            }

            if (current.isNotEmpty()) {
                result.add(current)
            }
        }

        return result
    }

    private fun splitLongWord(
        word: String,
        paint: Paint,
        maxWidth: Int
    ): List<String> {

        val parts = mutableListOf<String>()

        var current = ""

        for (character in word) {

            val test =
                current + character

            if (
                current.isEmpty() ||
                paint.measureText(test) <= maxWidth
            ) {

                current = test

            } else {

                parts.add(current)
                current = character.toString()
            }
        }

        if (current.isNotEmpty()) {
            parts.add(current)
        }

        return parts
    }

    private fun createVideo(
        frameFile: File,
        musicFile: File,
        outputFile: File
    ): Boolean {

        if (outputFile.exists()) {
            outputFile.delete()
        }

        val framePath =
            ffmpegPath(frameFile.absolutePath)

        val musicPath =
            ffmpegPath(musicFile.absolutePath)

        val outputPath =
            ffmpegPath(outputFile.absolutePath)

        val command = """
            -y
            -loop 1
            -framerate $FPS
            -i "$framePath"
            -stream_loop -1
            -i "$musicPath"
            -map 0:v:0
            -map 1:a:0
            -t $VIDEO_DURATION
            -vf "scale=$VIDEO_WIDTH:$VIDEO_HEIGHT:force_original_aspect_ratio=disable,format=yuv420p"
            -c:v libopenh264
            -b:v 5M
            -r $FPS
            -pix_fmt yuv420p
            -c:a aac
            -b:a 192k
            -ar 44100
            -ac 2
            -af "volume=$MUSIC_VOLUME"
            -movflags +faststart
            "$outputPath"
        """.trimIndent()
            .replace("\n", " ")
            .replace(Regex("\\s+"), " ")

        val session =
            FFmpegKit.execute(command)

        return ReturnCode.isSuccess(
            session.returnCode
        ) && outputFile.exists() &&
            outputFile.length() > 0
    }

    private fun ffmpegPath(path: String): String {

        return path
            .replace("\\", "/")
            .replace("'", "'\\''")
    }

    private fun copyAssetToCache(
        assetName: String,
        outputName: String
    ): File {

        val output =
            File(cacheDir, outputName)

        assets.open(assetName).use { input ->

            FileOutputStream(output).use { outputStream ->
                input.copyTo(outputStream)
            }
        }

        return output
    }

    private fun zipDirectory(
        directory: File,
        zipFile: File
    ) {

        ZipOutputStream(
            BufferedInputStream(
                FileInputStream(
                    File.createTempFile(
                        "zip_source",
                        ".tmp",
                        cacheDir
                    )
                )
            ).let {
                FileOutputStream(
                    zipFile
                )
            }
        ).use { zip ->

            directory.listFiles()
                ?.sortedBy {
                    it.nameWithoutExtension
                        .toIntOrNull()
                        ?: Int.MAX_VALUE
                }
                ?.forEach { file ->

                    if (
                        file.isFile &&
                        file.extension.equals(
                            "mp4",
                            ignoreCase = true
                        )
                    ) {

                        FileInputStream(file).use { input ->

                            val entry =
                                ZipEntry(file.name)

                            zip.putNextEntry(entry)

                            input.copyTo(zip)

                            zip.closeEntry()
                        }
                    }
                }
        }
    }

    private fun saveGeneratedZip() {

        val zip = generatedZip

        if (
            zip == null ||
            !zip.exists()
        ) {

            Toast.makeText(
                this,
                "Please generate videos first.",
                Toast.LENGTH_LONG
            ).show()

            return
        }

        val intent =
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {

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
            CREATE_ZIP_REQUEST
        )
    }

    @Deprecated("Deprecated in Android API")
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
            requestCode == CREATE_ZIP_REQUEST &&
            resultCode == Activity.RESULT_OK
        ) {

            val destination =
                data?.data ?: return

            val source =
                generatedZip ?: return

            try {

                contentResolver.openOutputStream(
                    destination
                )?.use { output ->

                    FileInputStream(source).use { input ->

                        input.copyTo(output)
                    }
                }

                Toast.makeText(
                    this,
                    "ZIP saved successfully.",
                    Toast.LENGTH_LONG
                ).show()

                statusText.text =
                    "ZIP saved successfully."

            } catch (e: Exception) {

                Toast.makeText(
                    this,
                    "Save failed: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun dp(value: Int): Int {

        return (
            value *
                resources.displayMetrics.density
            ).toInt()
    }
}
