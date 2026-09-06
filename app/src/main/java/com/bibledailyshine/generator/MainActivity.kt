package com.bibledailyshine.generator

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
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
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    companion object {
        private const val VIDEO_WIDTH = 1080
        private const val VIDEO_HEIGHT = 1920
        private const val VIDEO_DURATION = 8
        private const val VIDEO_FPS = 30

        private const val TOP_RESERVED = 200
        private const val BOTTOM_RESERVED = 200

        private const val VERSE_MAX_WIDTH = 680
        private const val VERSE_MAX_HEIGHT = 1320

        private const val MAX_VERSE_FONT_SIZE = 80
        private const val MIN_VERSE_FONT_SIZE = 28

        private const val MUSIC_VOLUME = 0.20f

        private const val SAVE_ZIP_REQUEST = 1001
    }

    private lateinit var verseInput: EditText
    private lateinit var generateButton: Button
    private lateinit var saveButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView

    private var generatedZip: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        buildUserInterface()
    }

    private fun buildUserInterface() {

        val scrollView = ScrollView(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.BLACK)
        }

        scrollView.addView(root)

        val title = TextView(this).apply {
            text = "Bible Verse Video Generator"
            textSize = 25f
            setTextColor(Color.YELLOW)
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 10, 0, 22)
        }

        root.addView(
            title,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val instruction = TextView(this).apply {
            text =
                "Paste many Bible verses below.\n\n" +
                "One verse per line = one video.\n\n" +
                "Format:\n" +
                "Verse text — Bible Reference\n\n" +
                "Example:\n" +
                "Be strong and courageous. Do not be afraid; do not be discouraged. — Joshua 1:9\n" +
                "I can do all things through Christ who strengthens me. — Philippians 4:13\n" +
                "The Lord is my shepherd; I shall not want. — Psalm 23:1"

            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 18)
        }

        root.addView(
            instruction,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        verseInput = EditText(this).apply {

            hint = "Paste your Bible verses here..."
            textSize = 17f

            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)

            setBackgroundColor(Color.rgb(30, 30, 30))

            gravity = Gravity.TOP or Gravity.START

            setPadding(18, 18, 18, 18)

            minLines = 12
            maxLines = 40

            isSingleLine = false

            inputType =
                android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                        android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }

        root.addView(
            verseInput,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                600
            ).apply {
                bottomMargin = 18
            }
        )

        generateButton = Button(this).apply {
            text = "GENERATE VIDEOS"
            textSize = 18f
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.YELLOW)
        }

        root.addView(
            generateButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                64
            ).apply {
                bottomMargin = 14
            }
        )

        progressBar = ProgressBar(
            this,
            null,
            android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 100
            progress = 0
        }

        root.addView(
            progressBar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                12
            ).apply {
                bottomMargin = 12
            }
        )

        statusText = TextView(this).apply {
            text = "Ready"
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 18)
        }

        root.addView(
            statusText,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        saveButton = Button(this).apply {
            text = "SAVE ZIP"
            textSize = 18f
            isEnabled = false
        }

        root.addView(
            saveButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                64
            )
        )

        generateButton.setOnClickListener {
            startGeneration()
        }

        saveButton.setOnClickListener {
            saveGeneratedZip()
        }

        setContentView(scrollView)
    }

    private fun startGeneration() {

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
                "Please paste at least one Bible verse.",
                Toast.LENGTH_LONG
            ).show()

            return
        }

        generateButton.isEnabled = false
        saveButton.isEnabled = false

        progressBar.progress = 0

        statusText.text =
            "Preparing ${lines.size} video(s)..."

        Thread {

            try {

                val outputDirectory = File(
                    cacheDir,
                    "BibleVerseVideos"
                )

                if (outputDirectory.exists()) {
                    outputDirectory.deleteRecursively()
                }

                if (!outputDirectory.mkdirs() &&
                    !outputDirectory.exists()
                ) {
                    throw Exception(
                        "Could not create output directory."
                    )
                }

                val frameDirectory = File(
                    cacheDir,
                    "BibleVerseFrames"
                )

                if (frameDirectory.exists()) {
                    frameDirectory.deleteRecursively()
                }

                if (!frameDirectory.mkdirs() &&
                    !frameDirectory.exists()
                ) {
                    throw Exception(
                        "Could not create frame directory."
                    )
                }

                val musicFile = File(
                    cacheDir,
                    "background_music.mp3"
                )

                copyAssetToCache(
                    "bg.mp3",
                    musicFile
                )

                for (index in lines.indices) {

                    val videoNumber = index + 1
                    val line = lines[index]

                    runOnUiThread {

                        statusText.text =
                            "Generating video $videoNumber of ${lines.size}..."

                        progressBar.progress =
                            ((index.toFloat() / lines.size.toFloat()) * 100f)
                                .toInt()
                    }

                    val parsed = parseVerse(line)

                    val frameFile = File(
                        frameDirectory,
                        "frame_$videoNumber.png"
                    )

                    createFrame(
                        verse = parsed.first,
                        reference = parsed.second,
                        outputFile = frameFile
                    )

                    val outputFile = File(
                        outputDirectory,
                        "$videoNumber.mp4"
                    )

                    val success = createVideo(
                        frameFile = frameFile,
                        musicFile = musicFile,
                        outputFile = outputFile
                    )

                    if (!success) {
                        throw Exception(
                            "FFmpeg failed while generating video $videoNumber."
                        )
                    }
                }

                runOnUiThread {

                    statusText.text =
                        "Creating ZIP file..."

                    progressBar.progress = 100
                }

                val zipFile = File(
                    cacheDir,
                    "BibleVerseVideos.zip"
                )

                if (zipFile.exists()) {
                    zipFile.delete()
                }

                zipDirectory(
                    directory = outputDirectory,
                    zipFile = zipFile
                )

                generatedZip = zipFile

                runOnUiThread {

                    generateButton.isEnabled = true
                    saveButton.isEnabled = true

                    statusText.text =
                        "Completed ${lines.size} video(s).\n\n" +
                                "BibleVerseVideos.zip is ready."

                    Toast.makeText(
                        this,
                        "Videos generated successfully.",
                        Toast.LENGTH_LONG
                    ).show()
                }

            } catch (e: Exception) {

                e.printStackTrace()

                runOnUiThread {

                    generateButton.isEnabled = true
                    saveButton.isEnabled = false

                    progressBar.progress = 0

                    statusText.text =
                        "Error:\n${e.message ?: "Unknown error"}"

                    Toast.makeText(
                        this,
                        e.message ?: "Generation failed.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

        }.start()
    }

    private fun parseVerse(
        line: String
    ): Pair<String, String> {

        val separators = listOf(
            " — ",
            " – ",
            " - ",
            "—",
            "–"
        )

        for (separator in separators) {

            val position = line.lastIndexOf(separator)

            if (
                position > 0 &&
                position < line.length - separator.length
            ) {

                val verse = line
                    .substring(0, position)
                    .trim()

                val reference = line
                    .substring(position + separator.length)
                    .trim()

                if (
                    verse.isNotBlank() &&
                    reference.isNotBlank()
                ) {
                    return Pair(
                        verse,
                        reference
                    )
                }
            }
        }

        return Pair(
            line.trim(),
            ""
        )
    }

    private fun createFrame(
        verse: String,
        reference: String,
        outputFile: File
    ) {

        val bitmap = Bitmap.createBitmap(
            VIDEO_WIDTH,
            VIDEO_HEIGHT,
            Bitmap.Config.ARGB_8888
        )

        val canvas = Canvas(bitmap)

        canvas.drawColor(Color.BLACK)

        val customTypeface = loadCustomTypeface()

        val headingPaint = Paint(
            Paint.ANTI_ALIAS_FLAG
        ).apply {
            color = Color.YELLOW
            textAlign = Paint.Align.CENTER
            typeface = customTypeface
            textSize = 100f
            isSubpixelText = true
        }

        val referencePaint = Paint(
            Paint.ANTI_ALIAS_FLAG
        ).apply {
            color = Color.YELLOW
            textAlign = Paint.Align.CENTER
            typeface = customTypeface
            textSize = 54f
            isSubpixelText = true
        }

        val versePaint = Paint(
            Paint.ANTI_ALIAS_FLAG
        ).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = customTypeface
            isSubpixelText = true
        }

        /*
         * STRICT TOP RESERVED AREA:
         *
         * 0 - 199 px contains absolutely no text.
         *
         * Heading begins at 310 px.
         */
        canvas.drawText(
            "Bible Verse",
            VIDEO_WIDTH / 2f,
            310f,
            headingPaint
        )

        if (reference.isNotBlank()) {

            canvas.drawText(
                reference,
                VIDEO_WIDTH / 2f,
                390f,
                referencePaint
            )
        }

        /*
         * Verse area starts well below the heading/reference.
         *
         * Bottom 200 px are strictly reserved.
         */
        val verseTop =
            if (reference.isNotBlank()) {
                450
            } else {
                400
            }

        val verseBottom =
            VIDEO_HEIGHT - BOTTOM_RESERVED

        val availableHeight =
            min(
                VERSE_MAX_HEIGHT,
                verseBottom - verseTop
            )

        val bestLayout = findBestTextLayout(
            text = verse,
            typeface = customTypeface,
            maxWidth = VERSE_MAX_WIDTH,
            maxHeight = availableHeight
        )

        val fontSize = bestLayout.first
        val lines = bestLayout.second

        versePaint.textSize = fontSize

        val metrics = versePaint.fontMetrics

        val lineHeight =
            (metrics.bottom - metrics.top) * 1.18f

        val totalTextHeight =
            lineHeight * lines.size

        var y =
            verseTop +
                    (availableHeight - totalTextHeight) / 2f -
                    metrics.top

        for (textLine in lines) {

            canvas.drawText(
                textLine,
                VIDEO_WIDTH / 2f,
                y,
                versePaint
            )

            y += lineHeight
        }

        FileOutputStream(outputFile).use { output ->

            bitmap.compress(
                Bitmap.CompressFormat.PNG,
                100,
                output
            )
        }

        bitmap.recycle()
    }

    private fun findBestTextLayout(
        text: String,
        typeface: Typeface,
        maxWidth: Int,
        maxHeight: Int
    ): Pair<Float, List<String>> {

        var fontSize =
            MAX_VERSE_FONT_SIZE.toFloat()

        while (fontSize >= MIN_VERSE_FONT_SIZE) {

            val paint = Paint(
                Paint.ANTI_ALIAS_FLAG
            ).apply {
                this.typeface = typeface
                textSize = fontSize
            }

            val lines = wrapText(
                text = text,
                paint = paint,
                maxWidth = maxWidth
            )

            val metrics = paint.fontMetrics

            val lineHeight =
                (metrics.bottom - metrics.top) * 1.18f

            val totalHeight =
                lineHeight * lines.size

            val widestLine =
                lines.maxOfOrNull {
                    paint.measureText(it)
                } ?: 0f

            if (
                widestLine <= maxWidth &&
                totalHeight <= maxHeight
            ) {
                return Pair(
                    fontSize,
                    lines
                )
            }

            fontSize -= 2f
        }

        val minimumPaint = Paint(
            Paint.ANTI_ALIAS_FLAG
        ).apply {
            typeface = typeface
            textSize = MIN_VERSE_FONT_SIZE.toFloat()
        }

        return Pair(
            MIN_VERSE_FONT_SIZE.toFloat(),
            wrapText(
                text = text,
                paint = minimumPaint,
                maxWidth = maxWidth
            )
        )
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

            val words =
                paragraph
                    .trim()
                    .split(Regex("\\s+"))
                    .filter {
                        it.isNotBlank()
                    }

            if (words.isEmpty()) {
                continue
            }

            var currentLine = ""

            for (word in words) {

                val testLine =
                    if (currentLine.isEmpty()) {
                        word
                    } else {
                        "$currentLine $word"
                    }

                if (
                    paint.measureText(testLine) <= maxWidth
                ) {

                    currentLine = testLine

                } else {

                    if (currentLine.isNotEmpty()) {
                        result.add(currentLine)
                    }

                    /*
                     * If one individual word is wider than
                     * 680px, split it character by character.
                     */
                    if (
                        paint.measureText(word) <= maxWidth
                    ) {

                        currentLine = word

                    } else {

                        var chunk = ""

                        for (character in word) {

                            val testChunk =
                                chunk + character

                            if (
                                paint.measureText(
                                    testChunk
                                ) <= maxWidth
                            ) {

                                chunk = testChunk

                            } else {

                                if (chunk.isNotEmpty()) {
                                    result.add(chunk)
                                }

                                chunk =
                                    character.toString()
                            }
                        }

                        currentLine = chunk
                    }
                }
            }

            if (currentLine.isNotEmpty()) {
                result.add(currentLine)
            }
        }

        return result
    }

    private fun loadCustomTypeface(): Typeface {

        return try {

            val fontFile = File(
                cacheDir,
                "font.ttf"
            )

            if (!fontFile.exists()) {

                copyAssetToCache(
                    "font.ttf",
                    fontFile
                )
            }

            Typeface.createFromFile(fontFile)

        } catch (e: Exception) {

            Typeface.DEFAULT
        }
    }

    private fun copyAssetToCache(
        assetName: String,
        destination: File
    ) {

        destination.parentFile?.mkdirs()

        assets.open(assetName).use { input ->

            FileOutputStream(destination).use { output ->

                input.copyTo(output)
            }
        }
    }

    private fun createVideo(
        frameFile: File,
        musicFile: File,
        outputFile: File
    ): Boolean {

        if (!frameFile.exists()) {
            throw Exception(
                "Frame file does not exist."
            )
        }

        if (!musicFile.exists()) {
            throw Exception(
                "Background music file does not exist."
            )
        }

        if (outputFile.exists()) {
            outputFile.delete()
        }

        val framePath =
            frameFile.absolutePath
                .replace("'", "'\\''")

        val musicPath =
            musicFile.absolutePath
                .replace("'", "'\\''")

        val outputPath =
            outputFile.absolutePath
                .replace("'", "'\\''")

        val command =
            "-y " +
                    "-loop 1 " +
                    "-framerate $VIDEO_FPS " +
                    "-i '$framePath' " +
                    "-stream_loop -1 " +
                    "-i '$musicPath' " +
                    "-map 0:v:0 " +
                    "-map 1:a:0 " +
                    "-t $VIDEO_DURATION " +
                    "-vf \"scale=$VIDEO_WIDTH:$VIDEO_HEIGHT:force_original_aspect_ratio=disable,format=yuv420p\" " +
                    "-c:v libopenh264 " +
                    "-b:v 5M " +
                    "-r $VIDEO_FPS " +
                    "-pix_fmt yuv420p " +
                    "-c:a aac " +
                    "-b:a 192k " +
                    "-ar 44100 " +
                    "-ac 2 " +
                    "-af \"volume=$MUSIC_VOLUME\" " +
                    "-movflags +faststart " +
                    "'$outputPath'"

        val session = FFmpegKit.execute(command)

        val success =
            ReturnCode.isSuccess(
                session.returnCode
            )

        if (!success) {

            val logs =
                session.allLogsAsString

            throw Exception(
                "FFmpeg error:\n$logs"
            )
        }

        return outputFile.exists() &&
                outputFile.length() > 0
    }

    private fun zipDirectory(
        directory: File,
        zipFile: File
    ) {

        if (!directory.exists()) {
            throw Exception(
                "Video directory does not exist."
            )
        }

        ZipOutputStream(
            FileOutputStream(zipFile)
        ).use { zip ->

            directory
                .listFiles()
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

        val intent = Intent(
            Intent.ACTION_CREATE_DOCUMENT
        ).apply {

            addCategory(
                Intent.CATEGORY_OPENABLE
            )

            type = "application/zip"

            putExtra(
                Intent.EXTRA_TITLE,
                "BibleVerseVideos.zip"
            )
        }

        startActivityForResult(
            intent,
            SAVE_ZIP_REQUEST
        )
    }

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
            requestCode == SAVE_ZIP_REQUEST &&
            resultCode == RESULT_OK
        ) {

            val destinationUri =
                data?.data

            val sourceZip =
                generatedZip

            if (
                destinationUri == null ||
                sourceZip == null ||
                !sourceZip.exists()
            ) {

                Toast.makeText(
                    this,
                    "Could not save ZIP file.",
                    Toast.LENGTH_LONG
                ).show()

                return
            }

            try {

                contentResolver
                    .openOutputStream(
                        destinationUri
                    )
                    ?.use { output ->

                        FileInputStream(
                            sourceZip
                        ).use { input ->

                            input.copyTo(output)
                        }
                    }

                Toast.makeText(
                    this,
                    "BibleVerseVideos.zip saved successfully.",
                    Toast.LENGTH_LONG
                ).show()

            } catch (e: Exception) {

                Toast.makeText(
                    this,
                    "Save failed: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
