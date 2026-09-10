package com.bibledailyshine.videogenerator;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.view.Surface;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public final class VideoGenerator {

    private VideoGenerator() {
    }

    // ============================================================
    // VIDEO SETTINGS
    // ============================================================

    private static final int VIDEO_WIDTH = 1080;
    private static final int VIDEO_HEIGHT = 1920;

    private static final int FPS = 30;
    private static final int TOTAL_FRAMES = 240; // 8 seconds

    private static final long FRAME_DURATION_US = 1_000_000L / FPS;

    private static final int VIDEO_BITRATE = 5_000_000;

    private static final String MIME_VIDEO = "video/avc";
    private static final String MIME_AUDIO = "audio/mp4a-latm";

    private static final String HEADING = "Bible Verse";

    private static final int SAFE_TOP = 200;
    private static final int SAFE_BOTTOM = 200;

    private static final int SIDE_MARGIN = 100;

    private static final int HEADING_COLOR = Color.YELLOW;
    private static final int VERSE_COLOR = Color.WHITE;

    private static final int HEADING_SIZE = 100;

    private static final float HEADING_GAP = 70f;

    /*
     * Change this version if you replace bg.mp3 inside a new APK.
     * This prevents an old cached AAC file from being reused.
     */
    private static final String AUDIO_CACHE_NAME =
            "bible_bg_audio_8sec_v2.m4a";

    // ============================================================
    // PUBLIC API
    // ============================================================

    /**
     * Generates one 1080x1920, 8-second MP4.
     *
     * MainActivity can continue using:
     *
     * VideoGenerator.generate(context, verse, outputFile);
     */
    public static void generate(
            Context context,
            String verse,
            File outputFile
    ) throws Exception {

        if (context == null) {
            throw new IllegalArgumentException("Context is null");
        }

        if (verse == null || verse.trim().isEmpty()) {
            throw new IllegalArgumentException("Verse is empty");
        }

        if (outputFile == null) {
            throw new IllegalArgumentException("Output file is null");
        }

        File parent = outputFile.getParentFile();

        if (parent != null && !parent.exists()) {
            if (!parent.mkdirs() && !parent.exists()) {
                throw new IOException(
                        "Cannot create output directory: " +
                                parent.getAbsolutePath()
                );
            }
        }

        if (outputFile.exists()) {
            //noinspection ResultOfMethodCallIgnored
            outputFile.delete();
        }

        /*
         * Create/cache AAC only once.
         *
         * For bulk generation all subsequent videos reuse this file.
         */
        File audioFile = getCachedAudio(context);

        /*
         * Create final MP4 directly.
         *
         * There is NO temporary video-only MP4.
         */
        generateVideoWithAudio(
                context,
                verse.trim(),
                audioFile,
                outputFile
        );
    }

    // ============================================================
    // MAIN VIDEO GENERATION
    // ============================================================

    private static void generateVideoWithAudio(
            Context context,
            String verse,
            File audioFile,
            File outputFile
    ) throws Exception {

        Bitmap textBitmap = null;
        MediaCodec encoder = null;
        Surface inputSurface = null;

        MediaExtractor audioExtractor = null;
        MediaMuxer muxer = null;

        boolean muxerStarted = false;
        boolean videoFormatReceived = false;
        boolean videoSampleWritten = false;

        int videoTrackIndex = -1;
        int audioTrackIndex = -1;

        try {

            // ----------------------------------------------------
            // Render text ONCE.
            // ----------------------------------------------------

            textBitmap = createTextBitmap(context, verse);

            // ----------------------------------------------------
            // H.264 encoder
            // ----------------------------------------------------

            MediaFormat videoFormat =
                    MediaFormat.createVideoFormat(
                            MIME_VIDEO,
                            VIDEO_WIDTH,
                            VIDEO_HEIGHT
                    );

            videoFormat.setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfoCompat.COLOR_FORMAT_SURFACE
            );

            videoFormat.setInteger(
                    MediaFormat.KEY_BIT_RATE,
                    VIDEO_BITRATE
            );

            videoFormat.setInteger(
                    MediaFormat.KEY_FRAME_RATE,
                    FPS
            );

            videoFormat.setInteger(
                    MediaFormat.KEY_I_FRAME_INTERVAL,
                    1
            );

            /*
             * Some Android encoders accept these parameters,
             * some ignore them.
             */
            try {
                videoFormat.setInteger(
                        MediaFormat.KEY_PROFILE,
                        MediaCodecInfoCompat.AVC_PROFILE_BASELINE
                );
            } catch (Exception ignored) {
            }

            try {
                videoFormat.setInteger(
                        MediaFormat.KEY_LEVEL,
                        MediaCodecInfoCompat.AVC_LEVEL_31
                );
            } catch (Exception ignored) {
            }

            encoder = MediaCodec.createEncoderByType(MIME_VIDEO);

            encoder.configure(
                    videoFormat,
                    null,
                    null,
                    MediaCodec.CONFIGURE_FLAG_ENCODE
            );

            inputSurface = encoder.createInputSurface();

            encoder.start();

            // ----------------------------------------------------
            // Open cached AAC audio
            // ----------------------------------------------------

            audioExtractor = new MediaExtractor();
            audioExtractor.setDataSource(audioFile.getAbsolutePath());

            int audioSourceTrack = findAudioTrack(audioExtractor);

            if (audioSourceTrack < 0) {
                throw new IOException(
                        "Audio track missing from cached AAC file"
                );
            }

            audioExtractor.selectTrack(audioSourceTrack);

            MediaFormat sourceAudioFormat =
                    audioExtractor.getTrackFormat(audioSourceTrack);

            // ----------------------------------------------------
            // Final MP4 muxer
            // ----------------------------------------------------

            muxer = new MediaMuxer(
                    outputFile.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            );

            // ----------------------------------------------------
            // Render 240 frames
            // ----------------------------------------------------

            for (int frame = 0; frame < TOTAL_FRAMES; frame++) {

                drawBitmapToSurface(
                        inputSurface,
                        textBitmap
                );

                /*
                 * Drain encoder.
                 *
                 * The first calls are important because the encoder
                 * will eventually send INFO_OUTPUT_FORMAT_CHANGED.
                 *
                 * At that moment we add BOTH tracks and start muxer.
                 */
                while (true) {

                    MediaCodec.BufferInfo bufferInfo =
                            new MediaCodec.BufferInfo();

                    int outputIndex =
                            encoder.dequeueOutputBuffer(
                                    bufferInfo,
                                    frame == 0 ? 10_000 : 0
                            );

                    if (outputIndex ==
                            MediaCodec.INFO_TRY_AGAIN_LATER) {

                        break;
                    }

                    if (outputIndex ==
                            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {

                        if (videoFormatReceived) {
                            throw new IllegalStateException(
                                    "Video output format changed twice"
                            );
                        }

                        MediaFormat actualVideoFormat =
                                encoder.getOutputFormat();

                        if (!MIME_VIDEO.equals(
                                actualVideoFormat.getString(
                                        MediaFormat.KEY_MIME
                                )
                        )) {
                            throw new IOException(
                                    "Encoder returned invalid video MIME: " +
                                            actualVideoFormat
                        );
                        }

                        videoTrackIndex =
                                muxer.addTrack(actualVideoFormat);

                        /*
                         * Add the cached AAC track BEFORE starting muxer.
                         */
                        MediaFormat audioFormat =
                                audioExtractor.getTrackFormat(
                                        audioSourceTrack
                                );

                        audioTrackIndex =
                                muxer.addTrack(audioFormat);

                        muxer.start();

                        muxerStarted = true;
                        videoFormatReceived = true;

                        continue;
                    }

                    if (outputIndex >= 0) {

                        ByteBuffer encodedBuffer =
                                encoder.getOutputBuffer(outputIndex);

                        if (encodedBuffer != null &&
                                bufferInfo.size > 0 &&
                                muxerStarted) {

                            if ((bufferInfo.flags &
                                    MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {

                                encodedBuffer.position(
                                        bufferInfo.offset
                                );

                                encodedBuffer.limit(
                                        bufferInfo.offset +
                                                bufferInfo.size
                                );

                                /*
                                 * Safety: only write valid video samples.
                                 */
                                if (bufferInfo.presentationTimeUs
                                        < 8_000_000L) {

                                    muxer.writeSampleData(
                                            videoTrackIndex,
                                            encodedBuffer,
                                            bufferInfo
                                    );

                                    videoSampleWritten = true;
                                }
                            }
                        }

                        encoder.releaseOutputBuffer(
                                outputIndex,
                                false
                        );

                        if ((bufferInfo.flags &
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {

                            break;
                        }

                    } else {

                        /*
                         * Unexpected negative result.
                         * Just continue.
                         */
                        break;
                    }
                }
            }

            // ----------------------------------------------------
            // Tell Surface encoder that input is finished.
            // ----------------------------------------------------

            encoder.signalEndOfInputStream();

            // ----------------------------------------------------
            // Drain remaining video until EOS.
            // ----------------------------------------------------

            boolean videoEOS = false;

            long drainStart = System.currentTimeMillis();

            while (!videoEOS) {

                MediaCodec.BufferInfo bufferInfo =
                        new MediaCodec.BufferInfo();

                int outputIndex =
                        encoder.dequeueOutputBuffer(
                                bufferInfo,
                                20_000
                        );

                if (outputIndex ==
                        MediaCodec.INFO_TRY_AGAIN_LATER) {

                    /*
                     * Prevent an endless wait if an encoder behaves badly.
                     */
                    if (System.currentTimeMillis() - drainStart > 15_000) {
                        throw new IOException(
                                "H.264 encoder timed out while finishing"
                        );
                    }

                    continue;
                }

                if (outputIndex ==
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {

                    if (!videoFormatReceived) {

                        MediaFormat actualVideoFormat =
                                encoder.getOutputFormat();

                        videoTrackIndex =
                                muxer.addTrack(actualVideoFormat);

                        MediaFormat audioFormat =
                                audioExtractor.getTrackFormat(
                                        audioSourceTrack
                                );

                        audioTrackIndex =
                                muxer.addTrack(audioFormat);

                        muxer.start();

                        muxerStarted = true;
                        videoFormatReceived = true;
                    }

                    continue;
                }

                if (outputIndex >= 0) {

                    ByteBuffer encodedBuffer =
                            encoder.getOutputBuffer(outputIndex);

                    if (encodedBuffer != null &&
                            bufferInfo.size > 0 &&
                            muxerStarted) {

                        if ((bufferInfo.flags &
                                MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {

                            encodedBuffer.position(
                                    bufferInfo.offset
                            );

                            encodedBuffer.limit(
                                    bufferInfo.offset +
                                            bufferInfo.size
                            );

                            if (bufferInfo.presentationTimeUs
                                    < 8_000_000L) {

                                muxer.writeSampleData(
                                        videoTrackIndex,
                                        encodedBuffer,
                                        bufferInfo
                                );

                                videoSampleWritten = true;
                            }
                        }
                    }

                    if ((bufferInfo.flags &
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {

                        videoEOS = true;
                    }

                    encoder.releaseOutputBuffer(
                            outputIndex,
                            false
                    );

                } else {

                    /*
                     * Other INFO_* values.
                     */
                }
            }

            // ----------------------------------------------------
            // IMPORTANT VALIDATION
            // ----------------------------------------------------

            if (!videoFormatReceived) {
                throw new IOException(
                        "H.264 encoder never produced a video output format"
                );
            }

            if (!videoSampleWritten) {
                throw new IOException(
                        "H.264 encoder produced no video samples"
                );
            }

            if (!muxerStarted) {
                throw new IOException(
                        "MediaMuxer was never started"
                );
            }

            // ----------------------------------------------------
            // Write cached AAC samples.
            //
            // Audio is exactly 8 seconds.
            // ----------------------------------------------------

            writeAudioSamples(
                    audioExtractor,
                    audioTrackIndex,
                    muxer
            );

        } finally {

            // ----------------------------------------------------
            // Release encoder
            // ----------------------------------------------------

            if (encoder != null) {
                try {
                    encoder.stop();
                } catch (Exception ignored) {
                }

                try {
                    encoder.release();
                } catch (Exception ignored) {
                }
            }

            // ----------------------------------------------------
            // Release Surface
            // ----------------------------------------------------

            if (inputSurface != null) {
                try {
                    inputSurface.release();
                } catch (Exception ignored) {
                }
            }

            // ----------------------------------------------------
            // Release extractor
            // ----------------------------------------------------

            if (audioExtractor != null) {
                try {
                    audioExtractor.release();
                } catch (Exception ignored) {
                }
            }

            // ----------------------------------------------------
            // Stop/release muxer
            // ----------------------------------------------------

            if (muxer != null) {

                if (muxerStarted) {
                    try {
                        muxer.stop();
                    } catch (Exception ignored) {
                    }
                }

                try {
                    muxer.release();
                } catch (Exception ignored) {
                }
            }

            // ----------------------------------------------------
            // Bitmap
            // ----------------------------------------------------

            if (textBitmap != null &&
                    !textBitmap.isRecycled()) {

                textBitmap.recycle();
            }
        }

        // --------------------------------------------------------
        // Final file validation
        // --------------------------------------------------------

        if (!outputFile.exists() ||
                outputFile.length() < 10_000) {

            throw new IOException(
                    "Video generation failed. Output MP4 is missing or empty: " +
                            outputFile.getAbsolutePath()
            );
        }
    }

    // ============================================================
    // DRAW BITMAP TO MEDIACODEC SURFACE
    // ============================================================

    private static void drawBitmapToSurface(
            Surface surface,
            Bitmap bitmap
    ) throws Exception {

        Canvas canvas = null;

        try {

            canvas = surface.lockCanvas(null);

            if (canvas == null) {
                throw new IOException(
                        "MediaCodec input surface returned null Canvas"
                );
            }

            /*
             * Black background.
             */
            canvas.drawColor(Color.BLACK);

            /*
             * Bitmap is already 1080x1920, so draw directly.
             */
            Paint paint = new Paint(
                    Paint.ANTI_ALIAS_FLAG |
                            Paint.FILTER_BITMAP_FLAG
            );

            canvas.drawBitmap(
                    bitmap,
                    0f,
                    0f,
                    paint
            );

        } finally {

            if (canvas != null) {
                surface.unlockCanvasAndPost(canvas);
            }
        }
    }

    // ============================================================
    // CREATE TEXT BITMAP
    // ============================================================

    private static Bitmap createTextBitmap(
            Context context,
            String verse
    ) throws Exception {

        Bitmap bitmap = Bitmap.createBitmap(
                VIDEO_WIDTH,
                VIDEO_HEIGHT,
                Bitmap.Config.ARGB_8888
        );

        Canvas canvas = new Canvas(bitmap);

        canvas.drawColor(Color.BLACK);

        Typeface typeface;

        try {
            typeface = Typeface.createFromAsset(
                    context.getAssets(),
                    "font.ttf"
            );
        } catch (Exception e) {

            /*
             * Fallback so video generation does not completely fail
             * if font.ttf is accidentally missing.
             */
            typeface = Typeface.create(
                    Typeface.DEFAULT,
                    Typeface.NORMAL
            );
        }

        // --------------------------------------------------------
        // Heading
        // --------------------------------------------------------

        Paint headingPaint = new Paint(
                Paint.ANTI_ALIAS_FLAG |
                        Paint.SUBPIXEL_TEXT_FLAG
        );

        headingPaint.setTypeface(typeface);
        headingPaint.setColor(HEADING_COLOR);
        headingPaint.setTextAlign(Paint.Align.CENTER);
        headingPaint.setTextSize(HEADING_SIZE);

        Paint.FontMetrics headingMetrics =
                headingPaint.getFontMetrics();

        float headingHeight =
                headingMetrics.bottom -
                        headingMetrics.top;

        /*
         * Heading is inside top safe area.
         */
        float headingBaseline =
                SAFE_TOP -
                        headingMetrics.top;

        canvas.drawText(
                HEADING,
                VIDEO_WIDTH / 2f,
                headingBaseline,
                headingPaint
        );

        float verseTop =
                headingBaseline +
                        headingMetrics.bottom +
                        HEADING_GAP;

        // --------------------------------------------------------
        // Verse
        // --------------------------------------------------------

        Paint versePaint = new Paint(
                Paint.ANTI_ALIAS_FLAG |
                        Paint.SUBPIXEL_TEXT_FLAG
        );

        versePaint.setTypeface(typeface);
        versePaint.setColor(VERSE_COLOR);
        versePaint.setTextAlign(Paint.Align.CENTER);

        float maxWidth =
                VIDEO_WIDTH -
                        (SIDE_MARGIN * 2);

        /*
         * Keep verse below heading and above bottom safe area.
         */
        float maxVerseHeight =
                VIDEO_HEIGHT -
                        SAFE_BOTTOM -
                        verseTop -
                        40f;

        if (maxVerseHeight < 100f) {
            maxVerseHeight = 100f;
        }

        TextLayoutResult layout =
                createVerseLayout(
                        versePaint,
                        verse,
                        maxWidth,
                        maxVerseHeight
                );

        versePaint.setTextSize(layout.textSize);

        float firstBaseline =
                verseTop +
                        ((maxVerseHeight -
                                layout.totalHeight) / 2f) -
                        layout.fontTop;

        /*
         * Additional safety.
         */
        float lowestPossible =
                firstBaseline +
                        (layout.lines.size() - 1) *
                                layout.lineHeight +
                        layout.fontBottom;

        if (lowestPossible >
                VIDEO_HEIGHT - SAFE_BOTTOM) {

            firstBaseline -=
                    lowestPossible -
                            (VIDEO_HEIGHT - SAFE_BOTTOM);
        }

        for (int i = 0;
             i < layout.lines.size();
             i++) {

            String line =
                    layout.lines.get(i);

            float baseline =
                    firstBaseline +
                            i * layout.lineHeight;

            canvas.drawText(
                    line,
                    VIDEO_WIDTH / 2f,
                    baseline,
                    versePaint
            );
        }

        return bitmap;
    }

    // ============================================================
    // TEXT LAYOUT
    // ============================================================

    private static TextLayoutResult createVerseLayout(
            Paint paint,
            String text,
            float maxWidth,
            float maxHeight
    ) {

        /*
         * Start large and automatically shrink.
         */
        float size = 72f;

        while (size >= 24f) {

            paint.setTextSize(size);

            List<String> lines =
                    wrapText(
                            paint,
                            text,
                            maxWidth
                    );

            Paint.FontMetrics metrics =
                    paint.getFontMetrics();

            float lineHeight =
                    (metrics.bottom -
                            metrics.top) *
                            1.25f;

            float totalHeight =
                    lineHeight *
                            lines.size();

            if (totalHeight <= maxHeight) {

                return new TextLayoutResult(
                        lines,
                        size,
                        lineHeight,
                        totalHeight,
                        metrics.top,
                        metrics.bottom
                );
            }

            size -= 2f;
        }

        // --------------------------------------------------------
        // Minimum size fallback
        // --------------------------------------------------------

        paint.setTextSize(24f);

        List<String> lines =
                wrapText(
                        paint,
                        text,
                        maxWidth
                );

        Paint.FontMetrics metrics =
                paint.getFontMetrics();

        float lineHeight =
                (metrics.bottom -
                        metrics.top) *
                        1.25f;

        float totalHeight =
                lineHeight *
                        lines.size();

        return new TextLayoutResult(
                lines,
                24f,
                lineHeight,
                totalHeight,
                metrics.top,
                metrics.bottom
        );
    }

    private static List<String> wrapText(
            Paint paint,
            String text,
            float maxWidth
    ) {

        List<String> result =
                new ArrayList<>();

        String cleaned =
                text.replace("\r", " ")
                        .replace("\n", " ")
                        .trim();

        if (cleaned.isEmpty()) {
            result.add("");
            return result;
        }

        String[] words =
                cleaned.split("\\s+");

        StringBuilder current =
                new StringBuilder();

        for (String word : words) {

            if (current.length() == 0) {

                if (paint.measureText(word)
                        <= maxWidth) {

                    current.append(word);

                } else {

                    splitLongWord(
                            paint,
                            word,
                            maxWidth,
                            result
                    );
                }

                continue;
            }

            String candidate =
                    current +
                            " " +
                            word;

            if (paint.measureText(candidate)
                    <= maxWidth) {

                current.append(" ")
                        .append(word);

            } else {

                result.add(
                        current.toString()
                );

                current.setLength(0);

                if (paint.measureText(word)
                        <= maxWidth) {

                    current.append(word);

                } else {

                    splitLongWord(
                            paint,
                            word,
                            maxWidth,
                            result
                    );
                }
            }
        }

        if (current.length() > 0) {
            result.add(
                    current.toString()
            );
        }

        return result;
    }

    private static void splitLongWord(
            Paint paint,
            String word,
            float maxWidth,
            List<String> output
    ) {

        StringBuilder part =
                new StringBuilder();

        for (int i = 0;
             i < word.length();
             i++) {

            char c = word.charAt(i);

            String candidate =
                    part.toString() + c;

            if (part.length() > 0 &&
                    paint.measureText(candidate)
                            > maxWidth) {

                output.add(
                        part.toString()
                );

                part.setLength(0);
            }

            part.append(c);
        }

        if (part.length() > 0) {
            output.add(
                    part.toString()
            );
        }
    }

    // ============================================================
    // AUDIO CACHE
    // ============================================================

    private static File getCachedAudio(
            Context context
    ) throws Exception {

        File cached =
                new File(
                        context.getCacheDir(),
                        AUDIO_CACHE_NAME
                );

        if (cached.exists() &&
                cached.length() > 10_000) {

            return cached;
        }

        if (cached.exists()) {
            //noinspection ResultOfMethodCallIgnored
            cached.delete();
        }

        encodeBackgroundMusicToAac(
                context,
                cached
        );

        if (!cached.exists() ||
                cached.length() < 10_000) {

            throw new IOException(
                    "AAC audio cache was not created"
            );
        }

        return cached;
    }

    // ============================================================
    // MP3 -> AAC
    //
    // Creates an exactly 8-second AAC/M4A cache.
    //
    // If bg.mp3 is shorter than 8 seconds, it loops.
    // If longer, only the first 8 seconds are used.
    // ============================================================

    private static void encodeBackgroundMusicToAac(
            Context context,
            File output
    ) throws Exception {

        MediaExtractor extractor = null;
        MediaCodec decoder = null;
        MediaCodec encoder = null;
        MediaMuxer muxer = null;

        boolean muxerStarted = false;

        int outputTrack = -1;

        try {

            // ----------------------------------------------------
            // Open MP3 asset
            // ----------------------------------------------------

            File mp3File =
                    copyAssetToCache(
                            context,
                            "bg.mp3",
                            "source_bg_music.mp3"
                    );

            extractor =
                    new MediaExtractor();

            extractor.setDataSource(
                    mp3File.getAbsolutePath()
            );

            int sourceTrack =
                    findAudioTrack(extractor);

            if (sourceTrack < 0) {
                throw new IOException(
                        "No audio track found in bg.mp3"
                );
            }

            extractor.selectTrack(sourceTrack);

            MediaFormat sourceFormat =
                    extractor.getTrackFormat(
                            sourceTrack
                    );

            String sourceMime =
                    sourceFormat.getString(
                            MediaFormat.KEY_MIME
                    );

            if (sourceMime == null ||
                    !sourceMime.startsWith("audio/")) {

                throw new IOException(
                        "bg.mp3 is not an audio track"
                );
            }

            // ----------------------------------------------------
            // Read sample rate/channels
            // ----------------------------------------------------

            int sampleRate =
                    getFormatInteger(
                            sourceFormat,
                            MediaFormat.KEY_SAMPLE_RATE,
                            44100
                    );

            int channels =
                    getFormatInteger(
                            sourceFormat,
                            MediaFormat.KEY_CHANNEL_COUNT,
                            2
                    );

            if (sampleRate <= 0) {
                sampleRate = 44100;
            }

            if (channels <= 0) {
                channels = 2;
            }

            // ----------------------------------------------------
            // Decoder
            // ----------------------------------------------------

            decoder =
                    MediaCodec.createDecoderByType(
                            sourceMime
                    );

            decoder.configure(
                    sourceFormat,
                    null,
                    null,
                    0
            );

            decoder.start();

            // ----------------------------------------------------
            // Decode only enough PCM for 8 seconds.
            //
            // 16-bit PCM:
            // sampleRate * channels * 2 bytes * 8 seconds
            // ----------------------------------------------------

            long targetPcmBytesLong =
                    (long) sampleRate *
                            8L *
                            channels *
                            2L;

            if (targetPcmBytesLong >
                    Integer.MAX_VALUE) {

                throw new IOException(
                        "Audio PCM buffer is too large"
                );
            }

            int targetPcmBytes =
                    (int) targetPcmBytesLong;

            ByteArrayOutputStream pcmOutput =
                    new ByteArrayOutputStream(
                            targetPcmBytes
                    );

            boolean inputEOS = false;
            boolean outputEOS = false;

            byte[] tempBuffer =
                    new byte[64 * 1024];

            while (!outputEOS &&
                    pcmOutput.size() < targetPcmBytes) {

                // ------------------------------------------------
                // Feed decoder
                // ------------------------------------------------

                if (!inputEOS) {

                    int inputIndex =
                            decoder.dequeueInputBuffer(
                                    10_000
                            );

                    if (inputIndex >= 0) {

                        ByteBuffer inputBuffer =
                                decoder.getInputBuffer(
                                        inputIndex
                                );

                        if (inputBuffer != null) {

                            int sampleSize =
                                    extractor.readSampleData(
                                            inputBuffer,
                                            0
                                    );

                            if (sampleSize < 0) {

                                decoder.queueInputBuffer(
                                        inputIndex,
                                        0,
                                        0,
                                        0,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                );

                                inputEOS = true;

                            } else {

                                long presentationTimeUs =
                                        extractor.getSampleTime();

                                decoder.queueInputBuffer(
                                        inputIndex,
                                        0,
                                        sampleSize,
                                        presentationTimeUs,
                                        0
                                );

                                extractor.advance();
                            }
                        }
                    }
                }

                // ------------------------------------------------
                // Get decoder output
                // ------------------------------------------------

                MediaCodec.BufferInfo info =
                        new MediaCodec.BufferInfo();

                int outputIndex =
                        decoder.dequeueOutputBuffer(
                                info,
                                10_000
                        );

                if (outputIndex ==
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {

                    MediaFormat decodedFormat =
                            decoder.getOutputFormat();

                    sampleRate =
                            getFormatInteger(
                                    decodedFormat,
                                    MediaFormat.KEY_SAMPLE_RATE,
                                    sampleRate
                            );

                    channels =
                            getFormatInteger(
                                    decodedFormat,
                                    MediaFormat.KEY_CHANNEL_COUNT,
                                    channels
                            );

                    targetPcmBytesLong =
                            (long) sampleRate *
                                    8L *
                                    channels *
                                    2L;

                    if (targetPcmBytesLong >
                            Integer.MAX_VALUE) {

                        throw new IOException(
                                "Decoded PCM buffer is too large"
                        );
                    }

                    targetPcmBytes =
                            (int) targetPcmBytesLong;

                    continue;
                }

                if (outputIndex >= 0) {

                    ByteBuffer outputBuffer =
                            decoder.getOutputBuffer(
                                    outputIndex
                            );

                    if (outputBuffer != null &&
                            info.size > 0) {

                        outputBuffer.position(
                                info.offset
                        );

                        outputBuffer.limit(
                                info.offset +
                                        info.size
                        );

                        int remaining =
                                outputBuffer.remaining();

                        int wanted =
                                Math.min(
                                        remaining,
                                        targetPcmBytes -
                                                pcmOutput.size()
                                );

                        if (wanted > 0) {

                            while (wanted > 0) {

                                int count =
                                        Math.min(
                                                wanted,
                                                tempBuffer.length
                                        );

                                outputBuffer.get(
                                        tempBuffer,
                                        0,
                                        count
                                );

                                pcmOutput.write(
                                        tempBuffer,
                                        0,
                                        count
                                );

                                wanted -= count;
                            }
                        }
                    }

                    if ((info.flags &
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {

                        outputEOS = true;
                    }

                    decoder.releaseOutputBuffer(
                            outputIndex,
                            false
                    );
                }
            }

            byte[] sourcePcm =
                    pcmOutput.toByteArray();

            if (sourcePcm.length == 0) {
                throw new IOException(
                        "bg.mp3 produced no PCM audio"
                );
            }

            // ----------------------------------------------------
            // Create exactly 8 seconds of PCM.
            // ----------------------------------------------------

            byte[] finalPcm =
                    new byte[targetPcmBytes];

            if (sourcePcm.length >= targetPcmBytes) {

                System.arraycopy(
                        sourcePcm,
                        0,
                        finalPcm,
                        0,
                        targetPcmBytes
                );

            } else {

                int position = 0;

                while (position <
                        targetPcmBytes) {

                    int count =
                            Math.min(
                                    sourcePcm.length,
                                    targetPcmBytes -
                                            position
                            );

                    System.arraycopy(
                            sourcePcm,
                            0,
                            finalPcm,
                            position,
                            count
                    );

                    position += count;
                }
            }

            // ----------------------------------------------------
            // AAC encoder
            // ----------------------------------------------------

            MediaFormat aacFormat =
                    MediaFormat.createAudioFormat(
                            MIME_AUDIO,
                            sampleRate,
                            channels
                    );

            aacFormat.setInteger(
                    MediaFormat.KEY_AAC_PROFILE,
                    2
            );

            /*
             * 128 kbps is plenty for background music and
             * considerably smaller/faster than high bitrates.
             */
            aacFormat.setInteger(
                    MediaFormat.KEY_BIT_RATE,
                    128_000
            );

            aacFormat.setInteger(
                    MediaFormat.KEY_MAX_INPUT_SIZE,
                    16384
            );

            encoder =
                    MediaCodec.createEncoderByType(
                            MIME_AUDIO
                    );

            encoder.configure(
                    aacFormat,
                    null,
                    null,
                    MediaCodec.CONFIGURE_FLAG_ENCODE
            );

            encoder.start();

            // ----------------------------------------------------
            // AAC muxer
            // ----------------------------------------------------

            muxer =
                    new MediaMuxer(
                            output.getAbsolutePath(),
                            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                    );

            int pcmPosition = 0;

            boolean encoderInputEOS = false;
            boolean encoderOutputEOS = false;

            long audioPtsUs = 0;

            /*
             * Number of bytes per PCM audio frame.
             */
            int bytesPerFrame =
                    channels * 2;

            while (!encoderOutputEOS) {

                // ------------------------------------------------
                // Feed PCM to AAC encoder
                // ------------------------------------------------

                if (!encoderInputEOS) {

                    int inputIndex =
                            encoder.dequeueInputBuffer(
                                    10_000
                            );

                    if (inputIndex >= 0) {

                        ByteBuffer inputBuffer =
                                encoder.getInputBuffer(
                                        inputIndex
                                );

                        if (inputBuffer == null) {
                            throw new IOException(
                                    "AAC encoder input buffer is null"
                            );
                        }

                        inputBuffer.clear();

                        int capacity =
                                inputBuffer.remaining();

                        int remaining =
                                finalPcm.length -
                                        pcmPosition;

                        if (remaining <= 0) {

                            encoder.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    0,
                                    audioPtsUs,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            );

                            encoderInputEOS = true;

                        } else {

                            /*
                             * Keep PCM chunks aligned to complete
                             * audio frames.
                             */
                            int count =
                                    Math.min(
                                            capacity,
                                            remaining
                                    );

                            count -=
                                    count %
                                            bytesPerFrame;

                            if (count <= 0) {
                                count =
                                        Math.min(
                                                remaining,
                                                capacity
                                        );
                            }

                            inputBuffer.put(
                                    finalPcm,
                                    pcmPosition,
                                    count
                            );

                            long frameCount =
                                    count /
                                            bytesPerFrame;

                            long durationUs =
                                    (frameCount *
                                            1_000_000L) /
                                            sampleRate;

                            encoder.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    count,
                                    audioPtsUs,
                                    0
                            );

                            pcmPosition += count;
                            audioPtsUs += durationUs;
                        }
                    }
                }

                // ------------------------------------------------
                // Drain AAC encoder
                // ------------------------------------------------

                MediaCodec.BufferInfo info =
                        new MediaCodec.BufferInfo();

                int outputIndex =
                        encoder.dequeueOutputBuffer(
                                info,
                                10_000
                        );

                if (outputIndex ==
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {

                    if (muxerStarted) {
                        throw new IllegalStateException(
                                "AAC output format changed twice"
                        );
                    }

                    MediaFormat actualAacFormat =
                            encoder.getOutputFormat();

                    outputTrack =
                            muxer.addTrack(
                                    actualAacFormat
                            );

                    muxer.start();

                    muxerStarted = true;

                    continue;
                }

                if (outputIndex >= 0) {

                    ByteBuffer outputBuffer =
                            encoder.getOutputBuffer(
                                    outputIndex
                            );

                    if (outputBuffer != null &&
                            info.size > 0 &&
                            muxerStarted) {

                        if ((info.flags &
                                MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {

                            outputBuffer.position(
                                    info.offset
                            );

                            outputBuffer.limit(
                                    info.offset +
                                            info.size
                            );

                            muxer.writeSampleData(
                                    outputTrack,
                                    outputBuffer,
                                    info
                            );
                        }
                    }

                    if ((info.flags &
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {

                        encoderOutputEOS = true;
                    }

                    encoder.releaseOutputBuffer(
                            outputIndex,
                            false
                    );
                }
            }

        } finally {

            if (decoder != null) {
                try {
                    decoder.stop();
                } catch (Exception ignored) {
                }

                try {
                    decoder.release();
                } catch (Exception ignored) {
                }
            }

            if (encoder != null) {
                try {
                    encoder.stop();
                } catch (Exception ignored) {
                }

                try {
                    encoder.release();
                } catch (Exception ignored) {
                }
            }

            if (extractor != null) {
                try {
                    extractor.release();
                } catch (Exception ignored) {
                }
            }

            if (muxer != null) {

                if (muxerStarted) {
                    try {
                        muxer.stop();
                    } catch (Exception ignored) {
                    }
                }

                try {
                    muxer.release();
                } catch (Exception ignored) {
                }
            }
        }
    }

    // ============================================================
    // WRITE AUDIO SAMPLES
    // ============================================================

    private static void writeAudioSamples(
            MediaExtractor extractor,
            int audioTrackIndex,
            MediaMuxer muxer
    ) throws Exception {

        ByteBuffer buffer =
                ByteBuffer.allocateDirect(
                        1024 * 1024
                );

        MediaCodec.BufferInfo info =
                new MediaCodec.BufferInfo();

        long maxPts =
                8_000_000L;

        boolean wroteAudio = false;

        while (true) {

            buffer.clear();

            int sampleSize =
                    extractor.readSampleData(
                            buffer,
                            0
                    );

            if (sampleSize < 0) {
                break;
            }

            long sampleTime =
                    extractor.getSampleTime();

            if (sampleTime < 0) {
                break;
            }

            if (sampleTime >= maxPts) {
                break;
            }

            int flags =
                    extractor.getSampleFlags();

            info.set(
                    0,
                    sampleSize,
                    sampleTime,
                    flags
            );

            muxer.writeSampleData(
                    audioTrackIndex,
                    buffer,
                    info
            );

            wroteAudio = true;

            if (!extractor.advance()) {
                break;
            }
        }

        if (!wroteAudio) {
            throw new IOException(
                    "No AAC audio samples were written"
            );
        }
    }

    // ============================================================
    // FIND AUDIO TRACK
    // ============================================================

    private static int findAudioTrack(
            MediaExtractor extractor
    ) {

        for (int i = 0;
             i < extractor.getTrackCount();
             i++) {

            MediaFormat format =
                    extractor.getTrackFormat(i);

            String mime =
                    format.getString(
                            MediaFormat.KEY_MIME
                    );

            if (mime != null &&
                    mime.startsWith("audio/")) {

                return i;
            }
        }

        return -1;
    }

    // ============================================================
    // COPY ASSET TO CACHE
    // ============================================================

    private static File copyAssetToCache(
            Context context,
            String assetName,
            String cacheName
    ) throws Exception {

        File output =
                new File(
                        context.getCacheDir(),
                        cacheName
                );

        if (output.exists() &&
                output.length() > 10_000) {

            return output;
        }

        if (output.exists()) {
            //noinspection ResultOfMethodCallIgnored
            output.delete();
        }

        try (java.io.InputStream input =
                     context.getAssets().open(assetName);
             java.io.FileOutputStream outputStream =
                     new java.io.FileOutputStream(output)) {

            byte[] buffer =
                    new byte[64 * 1024];

            int count;

            while ((count =
                    input.read(buffer)) != -1) {

                outputStream.write(
                        buffer,
                        0,
                        count
                );
            }

            outputStream.flush();
        }

        if (!output.exists() ||
                output.length() == 0) {

            throw new IOException(
                    "Could not copy asset: " +
                            assetName
            );
        }

        return output;
    }

    // ============================================================
    // FORMAT INTEGER HELPER
    // ============================================================

    private static int getFormatInteger(
            MediaFormat format,
            String key,
            int defaultValue
    ) {

        try {

            if (format.containsKey(key)) {
                return format.getInteger(key);
            }

        } catch (Exception ignored) {
        }

        return defaultValue;
    }

    // ============================================================
    // TEXT RESULT
    // ============================================================

    private static final class TextLayoutResult {

        final List<String> lines;
        final float textSize;
        final float lineHeight;
        final float totalHeight;
        final float fontTop;
        final float fontBottom;

        TextLayoutResult(
                List<String> lines,
                float textSize,
                float lineHeight,
                float totalHeight,
                float fontTop,
                float fontBottom
        ) {

            this.lines = lines;
            this.textSize = textSize;
            this.lineHeight = lineHeight;
            this.totalHeight = totalHeight;
            this.fontTop = fontTop;
            this.fontBottom = fontBottom;
        }
    }

    // ============================================================
    // SMALL MEDIACODEC CONSTANT HOLDER
    //
    // Keeps this file compatible without depending on newer
    // MediaCodec constant names.
    // ============================================================

    private static final class MediaCodecInfoCompat {

        /*
         * MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
         */
        static final int COLOR_FORMAT_SURFACE = 0x7F000789;

        /*
         * AVC Baseline profile.
         */
        static final int AVC_PROFILE_BASELINE = 1;

        /*
         * AVC Level 3.1.
         */
        static final int AVC_LEVEL_31 = 256;
    }
}
