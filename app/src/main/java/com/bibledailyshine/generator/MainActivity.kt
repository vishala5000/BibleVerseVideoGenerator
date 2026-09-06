package com.bibledailyshine.generator

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
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
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    companion object {
        private const val WIDTH = 1080
        private const val HEIGHT = 1920
        private const val TEXT_WIDTH = 680
        private const val TEXT_HEIGHT = 1320
        private const val SAFE_TOP = 200
        private const val SAFE_BOTTOM = 200
        private const val HEADING = "Bible Verse"
        private const val HEADING_Y = 200f
        private const val BLOCK_TOP = 400f
        private const val BLOCK_BOTTOM = 1720f
        private const val VIDEO_SECONDS = 8
        private const val FPS = 30
        private const val CREATE_ZIP_REQUEST = 9001
    }

    private lateinit var input: EditText
    private lateinit var generateButton: Button
    private lateinit var saveButton: Button
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView

    private var generatedZip: File? = null
    private var generating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 24, 28, 24)
            setBackgroundColor(android.graphics.Color.BLACK)
        }

        val title = TextView(this).apply {
            text = "Bible Verse Video Generator"
            textSize = 24f
            setTextColor(android.graphics.Color.YELLOW)
            gravity = Gravity.CENTER
        }
        root.addView(title, LinearLayout.LayoutParams(-1, -2))

        val info = TextView(this).apply {
            text = "Enter ONE Bible verse per line. Format: Verse text — Book Chapter:Verse\nExample: Be strong and courageous. Do not be afraid; do not be discouraged. — Joshua 1:9"
            textSize = 15f
            setTextColor(android.graphics.Color.LTGRAY)
            setPadding(0, 18, 0, 18)
        }
        root.addView(info, LinearLayout.LayoutParams(-1, -2))

        input = EditText(this).apply {
            hint = "Enter Bible verses, one per line..."
            setHintTextColor(android.graphics.Color.GRAY)
            setTextColor(android.graphics.Color.WHITE)
            textSize = 17f
            gravity = Gravity.TOP or Gravity.START
            setPadding(20, 20, 20, 20)
            setBackgroundColor(android.graphics.Color.rgb(28, 28, 28))
            minLines = 10
            maxLines = 18
        }
        root.addView(input, LinearLayout.LayoutParams(-1, 0, 1f))

        generateButton = Button(this).apply {
            text = "GENERATE VIDEOS"
            textSize = 16f
        }
        root.addView(generateButton, LinearLayout.LayoutParams(-1, 58).apply { topMargin = 18 })

        saveButton = Button(this).apply {
            text = "SAVE ALL VIDEOS AS ZIP"
            textSize = 16f
            visibility = View.GONE
        }
        root.addView(saveButton, LinearLayout.LayoutParams(-1, 58).apply { topMargin = 10 })

        progress = ProgressBar(this).apply {
            isIndeterminate = false
            max = 100
            progress = 0
            visibility = View.GONE
        }
        root.addView(progress, LinearLayout.LayoutParams(-1, 12).apply { topMargin = 12 })

        status = TextView(this).apply {
            text = "Ready."
            textSize = 14f
            setTextColor(android.graphics.Color.LTGRAY)
            setPadding(0, 12, 0, 0)
        }
        root.addView(status, LinearLayout.LayoutParams(-1, -2))

        generateButton.setOnClickListener { startGeneration() }
        saveButton.setOnClickListener { saveZip() }

        val scroll = ScrollView(this).apply {
            addView(root)
        }
        setContentView(scroll)
    }

    private fun startGeneration() {
        if (generating) return

        val verses = input.text.toString()
            .replace("\r\n", "\n")
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (verses.isEmpty()) {
            toast("Enter at least one Bible verse.")
            return
        }

        generating = true
        generateButton.isEnabled = false
        saveButton.visibility = View.GONE
        progress.visibility = View.VISIBLE
        progress.progress = 0
        status.text = "Preparing..."

        Thread {
            try {
                val work = File(cacheDir, "bible_video_work").apply {
                    deleteRecursively()
                    mkdirs()
                }
                val audio = File(work, "bg.mp3")
                val font = File(work, "font.ttf")
                copyAsset("bg.mp3", audio)
                copyAsset("font.ttf", font)

                val videos = mutableListOf<File>()
                verses.forEachIndexed { index, line ->
                    val number = index + 1
                    runOnUiThread {
                        progress.progress = ((index.toFloat() / verses.size) * 85f).roundToInt()
                        status.text = "Generating $number/${verses.size}..."
                    }

                    val parsed = parseVerse(line)
                    val png = File(work, "frame_$number.png")
                    renderFrame(parsed.first, parsed.second, font, png)

                    val out = File(work, "$number.mp4")
                    createVideo(png, audio, out)
                    if (!out.exists() || out.length() < 1024) {
                        throw IllegalStateException("Video $number was not created.")
                    }
                    videos += out
                }

                runOnUiThread {
                    progress.progress = 90
                    status.text = "Creating ZIP..."
                }

                val zip = File(work, "BibleVerseVideos.zip")
                ZipOutputStream(FileOutputStream(zip)).use { zos ->
                    videos.forEach { video ->
                        zos.putNextEntry(ZipEntry(video.name))
                        FileInputStream(video).use { inputStream -> inputStream.copyTo(zos) }
                        zos.closeEntry()
                    }
                }

                generatedZip = zip
                runOnUiThread {
                    progress.progress = 100
                    status.text = "Finished: ${videos.size} video(s). Tap SAVE ALL VIDEOS AS ZIP."
                    saveButton.visibility = View.VISIBLE
                    generateButton.isEnabled = true
                    generating = false
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "Error: ${e.message ?: "Unknown error"}"
                    generateButton.isEnabled = true
                    generating = false
                    progress.visibility = View.GONE
                    toast(e.message ?: "Generation failed")
                }
            }
        }.start()
    }

    private fun createVideo(frame: File, audio: File, output: File) {
        fun q(path: String): String = "'" + path.replace("'", "'\\''") + "'"

        val command = buildString {
            append("-y ")
            append("-loop 1 -framerate $FPS -i ${q(frame.absolutePath)} ")
            append("-stream_loop -1 -i ${q(audio.absolutePath)} ")
            append("-map 0:v:0 -map 1:a:0 ")
            append("-t $VIDEO_SECONDS ")
            append("-vf \\"scale=$WIDTH:$HEIGHT,format=yuv420p\\" ")
            append("-c:v libopenh264 -b:v 5M -r $FPS -pix_fmt yuv420p ")
            append("-c:a aac -b:a 192k -ar 44100 -ac 2 ")
            append("-movflags +faststart ")
            append(q(output.absolutePath))
        }

        val session = FFmpegKit.execute(command)
        if (!ReturnCode.isSuccess(session.returnCode)) {
            val logs = session.allLogsAsString
            throw IllegalStateException("FFmpeg failed: ${logs.takeLast(1200)}")
        }
    }

    private fun parseVerse(line: String): Pair<String, String> {
        val regex = Regex("\\s+[—–-]\\s+")
        val match = regex.find(line)
        return if (match != null) {
            val verse = line.substring(0, match.range.first).trim()
            val reference = line.substring(match.range.last + 1).trim()
            verse to reference
        } else {
            line.trim() to ""
        }
    }

    private fun renderFrame(verse: String, reference: String, fontFile: File, output: File) {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.BLACK)

        val typeface = Typeface.createFromFile(fontFile)

        val headingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.YELLOW
            textSize = 72f
            typeface = typeface
            textAlign = Paint.Align.CENTER
        }
        drawCenteredSingleLine(canvas, HEADING, WIDTH / 2f, HEADING_Y - headingPaint.ascent(), headingPaint)

        var verseSize = 72f
        var referenceSize = 54f
        var layout = calculateLayout(verse, reference, typeface, verseSize, referenceSize)
        while (layout.height > TEXT_HEIGHT && verseSize > 28f) {
            verseSize -= 2f
            referenceSize = max(40f, referenceSize - 1f)
            layout = calculateLayout(verse, reference, typeface, verseSize, referenceSize)
        }

        // If the verse still cannot fit, keep reducing until it fits the 1320px block.
        while (layout.height > TEXT_HEIGHT && verseSize > 18f) {
            verseSize -= 1f
            referenceSize = max(32f, referenceSize - 0.5f)
            layout = calculateLayout(verse, reference, typeface, verseSize, referenceSize)
        }

        if (layout.height > TEXT_HEIGHT) {
            throw IllegalArgumentException("A verse is too long to fit inside the fixed 680 x 1320 text area even at minimum font size.")
        }

        var y = BLOCK_TOP + (TEXT_HEIGHT - layout.height) / 2f

        if (reference.isNotBlank()) {
            val refPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.YELLOW
                textSize = referenceSize
                this.typeface = typeface
                textAlign = Paint.Align.CENTER
            }
            y = drawWrapped(canvas, layout.referenceLines, WIDTH / 2f, y, refPaint, referenceSize * 1.20f)
            y += verseSize * 0.40f
        }

        val versePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = verseSize
            this.typeface = typeface
            textAlign = Paint.Align.CENTER
        }
        drawWrapped(canvas, layout.verseLines, WIDTH / 2f, y, versePaint, verseSize * 1.20f)

        // Safety checks: no text is intentionally placed outside y=200..1720.
        // The heading baseline starts at 200 and the content block ends at 1720.
        FileOutputStream(output).use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    private data class Layout(
        val referenceLines: List<String>,
        val verseLines: List<String>,
        val height: Float
    )

    private fun calculateLayout(
        verse: String,
        reference: String,
        typeface: Typeface,
        verseSize: Float,
        referenceSize: Float
    ): Layout {
        val versePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = verseSize
            this.typeface = typeface
        }
        val refPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = referenceSize
            this.typeface = typeface
        }

        val verseLines = wrapText(verse, versePaint, TEXT_WIDTH)
        val referenceLines = wrapText(reference, refPaint, TEXT_WIDTH)

        val verseLineHeight = verseSize * 1.20f
        val refLineHeight = referenceSize * 1.20f
        val gap = if (referenceLines.isNotEmpty()) verseSize * 0.40f else 0f
        val height = referenceLines.size * refLineHeight + gap + verseLines.size * verseLineHeight
        return Layout(referenceLines, verseLines, height)
    }

    private fun wrapText(text: String, paint: Paint, maxWidth: Int): List<String> {
        if (text.isBlank()) return emptyList()
        val result = mutableListOf<String>()
        val paragraphs = text.replace("\\n", "\n").split('\n')
        for (paragraph in paragraphs) {
            val words = paragraph.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (words.isEmpty()) continue
            var line = ""
            for (word in words) {
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (paint.measureText(candidate) <= maxWidth) {
                    line = candidate
                } else {
                    if (line.isNotEmpty()) result += line
                    if (paint.measureText(word) <= maxWidth) {
                        line = word
                    } else {
                        var part = ""
                        for (ch in word) {
                            val c = part + ch
                            if (paint.measureText(c) <= maxWidth) {
                                part = c
                            } else {
                                if (part.isNotEmpty()) result += part
                                part = ch.toString()
                            }
                        }
                        line = part
                    }
                }
            }
            if (line.isNotEmpty()) result += line
        }
        return result
    }

    private fun drawCenteredSingleLine(canvas: Canvas, text: String, centerX: Float, baseline: Float, paint: Paint) {
        canvas.drawText(text, centerX, baseline, paint)
    }

    private fun drawWrapped(
        canvas: Canvas,
        lines: List<String>,
        centerX: Float,
        startY: Float,
        paint: Paint,
        lineHeight: Float
    ): Float {
        var y = startY
        for (line in lines) {
            canvas.drawText(line, centerX, y - paint.ascent(), paint)
            y += lineHeight
        }
        return y
    }

    private fun copyAsset(name: String, destination: File) {
        assets.open(name).use { inputStream ->
            FileOutputStream(destination).use { outputStream -> inputStream.copyTo(outputStream) }
        }
    }

    private fun saveZip() {
        val zip = generatedZip ?: run {
            toast("Generate videos first.")
            return
        }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            putExtra(Intent.EXTRA_TITLE, "BibleVerseVideos.zip")
        }
        startActivityForResult(intent, CREATE_ZIP_REQUEST)
    }

    @Deprecated("Deprecated in Android API, retained for broad device compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == CREATE_ZIP_REQUEST && resultCode == Activity.RESULT_OK && data?.data != null) {
            val target: Uri = data.data!!
            val zip = generatedZip ?: return
            try {
                contentResolver.openOutputStream(target)?.use { output ->
                    FileInputStream(zip).use { inputStream -> inputStream.copyTo(output) }
                }
                toast("ZIP saved successfully.")
                status.text = "ZIP saved successfully."
            } catch (e: Exception) {
                toast("Could not save ZIP: ${e.message}")
            }
        }
    }

    private fun toast(message: String) {
        runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    }
}
