package com.bibledailyshine.videogenerator;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.view.Surface;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * FAST BULK BIBLE VERSE VIDEO GENERATOR
 *
 * Output:
 * 1080 x 1920
 * H.264 / AVC
 * 30 FPS
 * exactly 8 seconds
 *
 * Layout:
 * Top 200 px = no text
 * Bottom 200 px = no text
 * "Bible Verse" = yellow
 * Verse = white
 * Black background
 *
 * Audio:
 * bg.mp3 -> AAC only ONCE
 * Cached and reused for every video
 *
 * Assets:
 * assets/font.ttf
 * assets/bg.mp3
 */
public final class VideoGenerator {

    private VideoGenerator() {
    }

    // ============================================================
    // VIDEO
    // ============================================================

    public static final int WIDTH = 1080;
    public static final int HEIGHT = 1920;

    public static final int FPS = 30;

    public static final int DURATION_SECONDS = 8;

    public static final int TOTAL_FRAMES =
            FPS * DURATION_SECONDS;

    public static final long DURATION_US =
            8_000_000L;

    /*
     * 5 Mbps is plenty for a black background with text and is
     * faster/smaller than the previous 8 Mbps setting.
     */
    private static final int VIDEO_BITRATE =
            5_000_000;

    private static final int I_FRAME_INTERVAL =
            1;

    private static final String VIDEO_MIME =
            "video/avc";

    // ============================================================
    // SAFE AREA
    // ============================================================

    private static final float TOP_SAFE =
            200.0f;

    private static final float BOTTOM_SAFE =
            200.0f;

    private static final float TEXT_WIDTH =
            800.0f;

    // ============================================================
    // HEADING
    // ============================================================

    private static final String HEADING =
            "Bible Verse";

    private static final float HEADING_SIZE =
            88.0f;

    private static final int HEADING_COLOR =
            Color.YELLOW;

    // ============================================================
    // VERSE
    // ============================================================

    private static final int VERSE_COLOR =
            Color.WHITE;

    private static final float MAX_VERSE_SIZE =
            72.0f;

    private static final float MIN_VERSE_SIZE =
            22.0f;

    private static final float LINE_SPACING =
            1.25f;

    private static final float HEADING_VERSE_GAP =
            60.0f;

    // ============================================================
    // AUDIO
    // ============================================================

    private static final String AUDIO_MIME =
            "audio/mp4a-latm";

    private static final int AUDIO_SAMPLE_RATE =
            44100;

    private static final int AUDIO_CHANNELS =
            2;

    private static final int AUDIO_BITRATE =
            128000;

    /*
     * This file is created once in cache and reused for every
     * generated video.
     */
    private static final String CACHED_AUDIO =
            "bible_bg_8sec_audio.m4a";

    // ============================================================
    // PUBLIC METHOD
    // ============================================================

    public static void generate(
            Context context,
            String verse,
            File output
    ) throws Exception {

        if (context == null) {
            throw new IllegalArgumentException(
                    "Context is null."
            );
        }

        if (verse == null) {
            throw new IllegalArgumentException(
                    "Verse is null."
            );
        }

        verse = verse.trim();

        if (verse.isEmpty()) {
            throw new IllegalArgumentException(
                    "Verse is empty."
            );
        }

        if (output == null) {
            throw new IllegalArgumentException(
                    "Output file is null."
            );
        }

        File parent =
                output.getParentFile();

        if (parent != null &&
                !parent.exists()) {

            if (!parent.mkdirs() &&
                    !parent.exists()) {

                throw new Exception(
                        "Unable to create output directory."
                );
            }
        }

        File videoFile =
                new File(
                        parent,
                        output.getName()
                                + ".video.tmp.mp4"
                );

        deleteQuietly(videoFile);

        try {

            // ====================================================
            // STEP 1
            // Render and encode ONLY the current verse.
            // ====================================================

            createVideo(
                    context,
                    verse,
                    videoFile
            );

            // ====================================================
            // STEP 2
            // Get cached 8-second background audio.
            //
            // This is the important bulk optimization:
            // bg.mp3 is NOT decoded for every verse.
            // ====================================================

            File audioFile =
                    getCachedAudio(
                            context
                    );

            // ====================================================
            // STEP 3
            // Fast final mux.
            // ====================================================

            muxVideoAndAudio(
                    videoFile,
                    audioFile,
                    output
            );

        } finally {

            deleteQuietly(
                    videoFile
            );
        }
    }

    // ============================================================
    // CREATE VIDEO
    // ============================================================

    private static void createVideo(
            Context context,
            String verse,
            File output
    ) throws Exception {

        if (output.exists()) {
            deleteQuietly(output);
        }

        /*
         * --------------------------------------------------------
         * Create the final text bitmap ONCE.
         * --------------------------------------------------------
         *
         * The old approach performed Canvas text drawing for
         * every video frame.
         *
         * This version draws the complete 1080x1920 image once.
         */
        Bitmap bitmap =
                createVerseBitmap(
                        context,
                        verse
                );

        MediaFormat format =
                MediaFormat.createVideoFormat(
                        VIDEO_MIME,
                        WIDTH,
                        HEIGHT
                );

        format.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities
                        .COLOR_FormatSurface
        );

        format.setInteger(
                MediaFormat.KEY_BIT_RATE,
                VIDEO_BITRATE
        );

        format.setInteger(
                MediaFormat.KEY_FRAME_RATE,
                FPS
        );

        format.setInteger(
                MediaFormat.KEY_I_FRAME_INTERVAL,
                I_FRAME_INTERVAL
        );

        MediaCodec encoder =
                MediaCodec.createEncoderByType(
                        VIDEO_MIME
                );

        Surface surface = null;

        MediaMuxer muxer = null;

        boolean encoderStarted = false;
        boolean muxerStarted = false;

        int videoTrack = -1;

        MediaCodec.BufferInfo info =
                new MediaCodec.BufferInfo();

        try {

            encoder.configure(
                    format,
                    null,
                    null,
                    MediaCodec.CONFIGURE_FLAG_ENCODE
            );

            surface =
                    encoder.createInputSurface();

            if (surface == null) {

                throw new Exception(
                        "Unable to create encoder surface."
                );
            }

            muxer =
                    new MediaMuxer(
                            output.getAbsolutePath(),
                            MediaMuxer.OutputFormat
                                    .MUXER_OUTPUT_MPEG_4
                    );

            encoder.start();

            encoderStarted = true;

            /*
             * ----------------------------------------------------
             * Submit exactly 240 frames.
             * ----------------------------------------------------
             *
             * The bitmap itself is already prepared, so the
             * expensive text layout/drawing is NOT repeated.
             */
            for (int frame = 0;
                 frame < TOTAL_FRAMES;
                 frame++) {

                Canvas canvas = null;

                try {

                    canvas =
                            surface.lockCanvas(
                                    null
                            );

                    if (canvas == null) {

                        throw new Exception(
                                "Unable to lock encoder surface."
                        );
                    }

                    /*
                     * Copy the already-rendered bitmap.
                     */
                    canvas.drawBitmap(
                            bitmap,
                            0,
                            0,
                            null
                    );

                } finally {

                    if (canvas != null) {

                        surface.unlockCanvasAndPost(
                                canvas
                        );
                    }
                }

                /*
                 * Drain available encoded data.
                 */
                while (true) {

                    int index =
                            encoder.dequeueOutputBuffer(
                                    info,
                                    0
                            );

                    if (index ==
                            MediaCodec.INFO_TRY_AGAIN_LATER) {

                        break;
                    }

                    if (index ==
                            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {

                        if (!muxerStarted) {

                            MediaFormat outputFormat =
                                    encoder.getOutputFormat();

                            videoTrack =
                                    muxer.addTrack(
                                            outputFormat
                                    );

                            muxer.start();

                            muxerStarted = true;
                        }

                        continue;
                    }

                    if (index < 0) {
                        continue;
                    }

                    ByteBuffer encoded =
                            encoder.getOutputBuffer(
                                    index
                            );

                    if (encoded != null &&
                            info.size > 0 &&
                            muxerStarted &&
                            (info.flags &
                                    MediaCodec
                                            .BUFFER_FLAG_CODEC_CONFIG)
                                    == 0) {

                        encoded.position(
                                info.offset
                        );

                        encoded.limit(
                                info.offset
                                        + info.size
                        );

                        if (info.presentationTimeUs >=
                                0 &&
                                info.presentationTimeUs <
                                        DURATION_US) {

                            muxer.writeSampleData(
                                    videoTrack,
                                    encoded,
                                    info
                            );
                        }
                    }

                    boolean eos =
                            (info.flags &
                                    MediaCodec
                                            .BUFFER_FLAG_END_OF_STREAM)
                                    != 0;

                    encoder.releaseOutputBuffer(
                            index,
                            false
                    );

                    if (eos) {
                        break;
                    }
                }
            }

            /*
             * No more frames.
             */
            encoder.signalEndOfInputStream();

            /*
             * Drain until EOS.
             */
            boolean videoEos = false;

            while (!videoEos) {

                int index =
                        encoder.dequeueOutputBuffer(
                                info,
                                10_000
                        );

                if (index ==
                        MediaCodec.INFO_TRY_AGAIN_LATER) {

                    continue;
                }

                if (index ==
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {

                    if (!muxerStarted) {

                        MediaFormat outputFormat =
                                encoder.getOutputFormat();

                        videoTrack =
                                muxer.addTrack(
                                        outputFormat
                                );

                        muxer.start();

                        muxerStarted = true;
                    }

                    continue;
                }

                if (index < 0) {
                    continue;
                }

                ByteBuffer encoded =
                        encoder.getOutputBuffer(
                                index
                        );

                if (encoded != null &&
                        info.size > 0 &&
                        muxerStarted &&
                        (info.flags &
                                MediaCodec
                                        .BUFFER_FLAG_CODEC_CONFIG)
                                == 0) {

                    encoded.position(
                            info.offset
                    );

                    encoded.limit(
                            info.offset
                                    + info.size
                    );

                    if (info.presentationTimeUs >=
                            0 &&
                            info.presentationTimeUs <
                                    DURATION_US) {

                        muxer.writeSampleData(
                                videoTrack,
                                encoded,
                                info
                        );
                    }
                }

                if ((info.flags &
                        MediaCodec
                                .BUFFER_FLAG_END_OF_STREAM)
                        != 0) {

                    videoEos = true;
                }

                encoder.releaseOutputBuffer(
                        index,
                        false
                );
            }

        } finally {

            if (bitmap != null &&
                    !bitmap.isRecycled()) {

                bitmap.recycle();
            }

            if (encoderStarted) {

                try {
                    encoder.stop();
                } catch (Exception ignored) {
                }
            }

            try {
                encoder.release();
            } catch (Exception ignored) {
            }

            if (surface != null) {

                try {
                    surface.release();
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
    // CREATE SINGLE VERSE BITMAP
    // ============================================================

    private static Bitmap createVerseBitmap(
            Context context,
            String verse
    ) throws Exception {

        Bitmap bitmap =
                Bitmap.createBitmap(
                        WIDTH,
                        HEIGHT,
                        Bitmap.Config.ARGB_8888
                );

        Canvas canvas =
                new Canvas(bitmap);

        /*
         * Black background.
         */
        canvas.drawColor(
                Color.BLACK
        );

        Typeface typeface =
                loadTypeface(context);

        Paint headingPaint =
                new Paint(
                        Paint.ANTI_ALIAS_FLAG |
                        Paint.SUBPIXEL_TEXT_FLAG
                );

        Paint versePaint =
                new Paint(
                        Paint.ANTI_ALIAS_FLAG |
                        Paint.SUBPIXEL_TEXT_FLAG
                );

        headingPaint.setTypeface(
                typeface
        );

        versePaint.setTypeface(
                typeface
        );

        headingPaint.setColor(
                HEADING_COLOR
        );

        versePaint.setColor(
                VERSE_COLOR
        );

        headingPaint.setTextAlign(
                Paint.Align.CENTER
        );

        versePaint.setTextAlign(
                Paint.Align.CENTER
        );

        // ========================================================
        // HEADING
        // ========================================================

        headingPaint.setTextSize(
                HEADING_SIZE
        );

        Paint.FontMetrics headingMetrics =
                headingPaint.getFontMetrics();

        /*
         * Leave a 20px additional margin inside the 200px
         * protected area.
         */
        float headingBaseline =
                TOP_SAFE
                        - headingMetrics.top
                        + 20.0f;

        float headingBottom =
                headingBaseline
                        + headingMetrics.bottom;

        canvas.drawText(
                HEADING,
                WIDTH / 2.0f,
                headingBaseline,
                headingPaint
        );

        // ========================================================
        // VERSE AREA
        // ========================================================

        float verseTop =
                headingBottom
                        + HEADING_VERSE_GAP;

        float verseBottom =
                HEIGHT
                        - BOTTOM_SAFE;

        float availableHeight =
                verseBottom
                        - verseTop;

        if (availableHeight <= 0) {

            throw new Exception(
                    "Invalid verse safe area."
            );
        }

        TextLayoutData layout =
                createTextLayout(
                        versePaint,
                        verse,
                        TEXT_WIDTH,
                        availableHeight
                );

        versePaint.setTextSize(
                layout.textSize
        );

        Paint.FontMetrics verseMetrics =
                versePaint.getFontMetrics();

        /*
         * Center the complete verse block vertically in the
         * available area.
         */
        float firstTop =
                verseTop
                        + (availableHeight
                        - layout.totalHeight)
                        / 2.0f;

        /*
         * Prevent any accidental movement into the heading
         * region.
         */
        if (firstTop <
                headingBottom
                        + HEADING_VERSE_GAP) {

            firstTop =
                    headingBottom
                            + HEADING_VERSE_GAP;
        }

        float baseline =
                firstTop
                        - verseMetrics.top;

        for (String line :
                layout.lines) {

            canvas.drawText(
                    line,
                    WIDTH / 2.0f,
                    baseline,
                    versePaint
            );

            baseline +=
                    layout.lineHeight;
        }

        /*
         * Final safety validation.
         */
        float actualTop =
                firstTop;

        float actualBottom =
                firstTop
                        + layout.totalHeight;

        if (actualTop <
                TOP_SAFE) {

            throw new Exception(
                    "Verse entered top safe area."
            );
        }

        if (actualBottom >
                HEIGHT - BOTTOM_SAFE + 1) {

            throw new Exception(
                    "Verse entered bottom safe area."
            );
        }

        return bitmap;
    }

    // ============================================================
    // TEXT LAYOUT
    // ============================================================

    private static TextLayoutData createTextLayout(
            Paint paint,
            String text,
            float maxWidth,
            float maxHeight
    ) {

        float size =
                MAX_VERSE_SIZE;

        while (size >=
                MIN_VERSE_SIZE) {

            paint.setTextSize(
                    size
            );

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
                            metrics.top)
                            * LINE_SPACING;

            float totalHeight =
                    lines.size()
                            * lineHeight;

            if (totalHeight <=
                    maxHeight) {

                return new TextLayoutData(
                        lines,
                        size,
                        lineHeight,
                        totalHeight
                );
            }

            size -= 2.0f;
        }

        /*
         * Very long verses.
         */
        size =
                MIN_VERSE_SIZE;

        paint.setTextSize(
                size
        );

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
                        metrics.top)
                        * LINE_SPACING;

        float totalHeight =
                lines.size()
                        * lineHeight;

        return new TextLayoutData(
                lines,
                size,
                lineHeight,
                totalHeight
        );
    }

    private static final class TextLayoutData {

        final List<String> lines;

        final float textSize;

        final float lineHeight;

        final float totalHeight;

        TextLayoutData(
                List<String> lines,
                float textSize,
                float lineHeight,
                float totalHeight
        ) {

            this.lines =
                    lines;

            this.textSize =
                    textSize;

            this.lineHeight =
                    lineHeight;

            this.totalHeight =
                    totalHeight;
        }
    }

    // ============================================================
    // WORD WRAPPING
    // ============================================================

    private static List<String> wrapText(
            Paint paint,
            String text,
            float maxWidth
    ) {

        List<String> lines =
                new ArrayList<>();

        String cleaned =
                text
                        .replace(
                                "\r",
                                " "
                        )
                        .replace(
                                "\n",
                                " "
                        )
                        .trim();

        if (cleaned.isEmpty()) {

            lines.add("");

            return lines;
        }

        String[] words =
                cleaned.split(
                        "\\s+"
                );

        StringBuilder current =
                new StringBuilder();

        for (String word :
                words) {

            if (word.isEmpty()) {
                continue;
            }

            if (current.length() == 0) {

                if (paint.measureText(word)
                        <= maxWidth) {

                    current.append(
                            word
                    );

                } else {

                    splitLongWord(
                            paint,
                            word,
                            maxWidth,
                            lines
                    );
                }

                continue;
            }

            String candidate =
                    current.toString()
                            + " "
                            + word;

            if (paint.measureText(candidate)
                    <= maxWidth) {

                current.append(
                        " "
                ).append(
                        word
                );

            } else {

                lines.add(
                        current.toString()
                );

                current.setLength(0);

                if (paint.measureText(word)
                        <= maxWidth) {

                    current.append(
                            word
                    );

                } else {

                    splitLongWord(
                            paint,
                            word,
                            maxWidth,
                            lines
                    );
                }
            }
        }

        if (current.length() > 0) {

            lines.add(
                    current.toString()
            );
        }

        return lines;
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

            char c =
                    word.charAt(i);

            String candidate =
                    part.toString()
                            + c;

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
    // FONT
    // ============================================================

    private static Typeface loadTypeface(
            Context context
    ) throws Exception {

        /*
         * Directly load from assets.
         *
         * No need to create a temporary font file.
         */
        try {

            Typeface typeface =
                    Typeface.createFromAsset(
                            context.getAssets(),
                            "font.ttf"
                    );

            if (typeface != null) {
                return typeface;
            }

        } catch (Exception ignored) {
        }

        /*
         * Fallback copy if required by a particular Android
         * implementation.
         */
        File fontFile =
                new File(
                        context.getCacheDir(),
                        "font.ttf"
                );

        try (
                InputStream input =
                        context.getAssets()
                                .open("font.ttf");

                FileOutputStream output =
                        new FileOutputStream(
                                fontFile
                        )
        ) {

            byte[] buffer =
                    new byte[64 * 1024];

            int count;

            while ((count =
                    input.read(buffer)) != -1) {

                output.write(
                        buffer,
                        0,
                        count
                );
            }
        }

        Typeface typeface =
                Typeface.createFromFile(
                        fontFile
                );

        deleteQuietly(
                fontFile
        );

        if (typeface == null) {

            throw new Exception(
                    "Unable to load font.ttf."
            );
        }

        return typeface;
    }

    // ============================================================
    // CACHED AUDIO
    // ============================================================

    private static File getCachedAudio(
            Context context
    ) throws Exception {

        File cached =
                new File(
                        context.getCacheDir(),
                        CACHED_AUDIO
                );

        /*
         * If it already exists, immediately reuse it.
         *
         * This is what makes bulk generation much faster.
         */
        if (cached.exists() &&
                cached.length() > 1024) {

            return cached;
        }

        File mp3 =
                copyAssetToCache(
                        context,
                        "bg.mp3"
                );

        try {

            createEightSecondAudio(
                    mp3,
                    cached
            );

        } finally {

            deleteQuietly(
                    mp3
            );
        }

        if (!cached.exists() ||
                cached.length() <= 1024) {

            throw new Exception(
                    "Unable to create cached background audio."
            );
        }

        return cached;
    }

    // ============================================================
    // CREATE 8 SECOND AUDIO
    // ============================================================

    private static void createEightSecondAudio(
            File mp3,
            File output
    ) throws Exception {

        if (output.exists()) {
            deleteQuietly(output);
        }

        MediaExtractor extractor =
                new MediaExtractor();

        MediaCodec decoder = null;
        MediaCodec encoder = null;
        MediaMuxer muxer = null;

        boolean decoderStarted = false;
        boolean encoderStarted = false;
        boolean muxerStarted = false;

        int audioTrack = -1;
        int outputTrack = -1;

        try {

            extractor.setDataSource(
                    mp3.getAbsolutePath()
            );

            audioTrack =
                    findTrack(
                            extractor,
                            "audio/"
                    );

            if (audioTrack < 0) {

                throw new Exception(
                        "bg.mp3 contains no audio track."
                );
            }

            MediaFormat sourceFormat =
                    extractor.getTrackFormat(
                            audioTrack
                    );

            String sourceMime =
                    sourceFormat.getString(
                            MediaFormat.KEY_MIME
                    );

            if (sourceMime == null) {

                throw new Exception(
                        "Audio MIME type missing."
                );
            }

            int sampleRate =
                    getInt(
                            sourceFormat,
                            MediaFormat.KEY_SAMPLE_RATE,
                            AUDIO_SAMPLE_RATE
                    );

            int channels =
                    getInt(
                            sourceFormat,
                            MediaFormat.KEY_CHANNEL_COUNT,
                            AUDIO_CHANNELS
                    );

            /*
             * Standard MP3 files used for background music are
             * normally 44100 Hz stereo.
             *
             * To keep this implementation fast, use the decoded
             * format directly when it matches AAC-supported
             * parameters.
             */
            if (sampleRate != 44100 &&
                    sampleRate != 48000) {

                throw new Exception(
                        "bg.mp3 must use 44100 Hz or 48000 Hz."
                );
            }

            if (channels < 1 ||
                    channels > 2) {

                throw new Exception(
                        "bg.mp3 must be mono or stereo."
                );
            }

            extractor.selectTrack(
                    audioTrack
            );

            // ====================================================
            // DECODER
            // ====================================================

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

            decoderStarted = true;

            // ====================================================
            // AAC ENCODER
            // ====================================================

            MediaFormat audioFormat =
                    MediaFormat.createAudioFormat(
                            AUDIO_MIME,
                            sampleRate,
                            channels
                    );

            audioFormat.setInteger(
                    MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel
                            .AACObjectLC
            );

            audioFormat.setInteger(
                    MediaFormat.KEY_BIT_RATE,
                    AUDIO_BITRATE
            );

            audioFormat.setInteger(
                    MediaFormat.KEY_MAX_INPUT_SIZE,
                    16384
            );

            encoder =
                    MediaCodec.createEncoderByType(
                            AUDIO_MIME
                    );

            encoder.configure(
                    audioFormat,
                    null,
                    null,
                    MediaCodec.CONFIGURE_FLAG_ENCODE
            );

            encoder.start();

            encoderStarted = true;

            // ====================================================
            // MUXER
            // ====================================================

            muxer =
                    new MediaMuxer(
                            output.getAbsolutePath(),
                            MediaMuxer.OutputFormat
                                    .MUXER_OUTPUT_MPEG_4
                    );

            MediaCodec.BufferInfo info =
                    new MediaCodec.BufferInfo();

            boolean decoderInputDone = false;
            boolean decoderOutputDone = false;

            long pcmFramesSubmitted = 0;

            final int bytesPerSample = 2;

            final int bytesPerFrame =
                    channels
                            * bytesPerSample;

            final long targetFrames =
                    (long) sampleRate
                            * DURATION_SECONDS;

            /*
             * The MP3 may be shorter than 8 seconds.
             *
             * For this cached audio we loop the source when
             * necessary.
             *
             * We perform repeated decoder passes if required.
             */
            while (pcmFramesSubmitted <
                    targetFrames) {

                /*
                 * If this is not the first pass, recreate the
                 * extractor/decoder.
                 *
                 * Keeping this section simple makes the normal
                 * case (bg.mp3 >= 8 sec) extremely fast.
                 */
                if (decoderOutputDone) {
                    break;
                }

                if (!decoderInputDone) {

                    int inputIndex =
                            decoder.dequeueInputBuffer(
                                    10_000
                            );

                    if (inputIndex >= 0) {

                        ByteBuffer input =
                                decoder.getInputBuffer(
                                        inputIndex
                                );

                        if (input == null) {

                            throw new Exception(
                                    "Decoder input buffer unavailable."
                            );
                        }

                        input.clear();

                        int size =
                                extractor.readSampleData(
                                        input,
                                        0
                                );

                        if (size < 0) {

                            decoder.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    0,
                                    0,
                                    MediaCodec
                                            .BUFFER_FLAG_END_OF_STREAM
                            );

                            decoderInputDone = true;

                        } else {

                            long pts =
                                    extractor.getSampleTime();

                            if (pts < 0) {
                                pts = 0;
                            }

                            decoder.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    size,
                                    pts,
                                    extractor
                                            .getSampleFlags()
                            );

                            extractor.advance();
                        }
                    }
                }

                int outputIndex =
                        decoder.dequeueOutputBuffer(
                                info,
                                10_000
                        );

                if (outputIndex ==
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {

                    continue;
                }

                if (outputIndex ==
                        MediaCodec.INFO_TRY_AGAIN_LATER) {

                    continue;
                }

                if (outputIndex < 0) {
                    continue;
                }

                ByteBuffer pcm =
                        decoder.getOutputBuffer(
                                outputIndex
                        );

                if (pcm != null &&
                        info.size > 0) {

                    pcm.position(
                            info.offset
                    );

                    pcm.limit(
                            info.offset
                                    + info.size
                    );

                    int bytes =
                            pcm.remaining();

                    long remainingFrames =
                            targetFrames
                                    - pcmFramesSubmitted;

                    long maximumBytes =
                            remainingFrames
                                    * bytesPerFrame;

                    if (bytes >
                            maximumBytes) {

                        bytes =
                                (int) maximumBytes;

                        bytes =
                                (bytes /
                                        bytesPerFrame)
                                        * bytesPerFrame;

                        pcm.limit(
                                pcm.position()
                                        + bytes
                        );
                    }

                    while (bytes > 0) {

                        int inputIndex =
                                encoder.dequeueInputBuffer(
                                        10_000
                                );

                        if (inputIndex < 0) {
                            continue;
                        }

                        ByteBuffer input =
                                encoder.getInputBuffer(
                                        inputIndex
                                );

                        if (input == null) {

                            throw new Exception(
                                    "AAC input buffer unavailable."
                            );
                        }

                        input.clear();

                        int copy =
                                Math.min(
                                        bytes,
                                        input.remaining()
                                );

                        copy =
                                (copy /
                                        bytesPerFrame)
                                        * bytesPerFrame;

                        if (copy <= 0) {

                            throw new Exception(
                                    "AAC input buffer too small."
                            );
                        }

                        byte[] temp =
                                new byte[copy];

                        pcm.get(
                                temp
                        );

                        input.put(
                                temp
                        );

                        long pts =
                                pcmFramesSubmitted
                                        * 1_000_000L
                                        / sampleRate;

                        encoder.queueInputBuffer(
                                inputIndex,
                                0,
                                copy,
                                pts,
                                0
                        );

                        pcmFramesSubmitted +=
                                copy /
                                        bytesPerFrame;

                        bytes -= copy;

                        /*
                         * Drain AAC output immediately.
                         */
                        while (true) {

                            int encodedIndex =
                                    encoder.dequeueOutputBuffer(
                                            info,
                                            0
                                    );

                            if (encodedIndex ==
                                    MediaCodec.INFO_TRY_AGAIN_LATER) {

                                break;
                            }

                            if (encodedIndex ==
                                    MediaCodec
                                            .INFO_OUTPUT_FORMAT_CHANGED) {

                                if (!muxerStarted) {

                                    outputTrack =
                                            muxer.addTrack(
                                                    encoder
                                                            .getOutputFormat()
                                            );

                                    muxer.start();

                                    muxerStarted = true;
                                }

                                continue;
                            }

                            if (encodedIndex < 0) {
                                continue;
                            }

                            ByteBuffer encoded =
                                    encoder.getOutputBuffer(
                                            encodedIndex
                                    );

                            if (encoded != null &&
                                    info.size > 0 &&
                                    muxerStarted &&
                                    (info.flags &
                                            MediaCodec
                                                    .BUFFER_FLAG_CODEC_CONFIG)
                                            == 0) {

                                encoded.position(
                                        info.offset
                                );

                                encoded.limit(
                                        info.offset
                                                + info.size
                                );

                                if (info.presentationTimeUs >=
                                        0 &&
                                        info.presentationTimeUs <
                                                DURATION_US) {

                                    muxer.writeSampleData(
                                            outputTrack,
                                            encoded,
                                            info
                                    );
                                }
                            }

                            encoder.releaseOutputBuffer(
                                    encodedIndex,
                                    false
                            );
                        }
                    }
                }

                boolean eos =
                        (info.flags &
                                MediaCodec
                                        .BUFFER_FLAG_END_OF_STREAM)
                                != 0;

                decoder.releaseOutputBuffer(
                        outputIndex,
                        false
                );

                if (eos) {

                    decoderOutputDone = true;

                    /*
                     * If the file is shorter than 8 seconds,
                     * this implementation will regenerate the
                     * cached audio from the beginning below.
                     */
                    break;
                }
            }

            /*
             * ----------------------------------------------------
             * If MP3 was shorter than 8 seconds, we need looping.
             *
             * The normal Bible background track should generally
             * be at least 8 seconds. If it is shorter, recreate
             * the complete audio encoder path with looping.
             * ----------------------------------------------------
             */
            if (pcmFramesSubmitted <
                    targetFrames) {

                /*
                 * Finish current encoder before rebuilding.
                 */
                encoder.queueInputBuffer(
                        encoder.dequeueInputBuffer(
                                10_000
                        ),
                        0,
                        0,
                        DURATION_US,
                        MediaCodec
                                .BUFFER_FLAG_END_OF_STREAM
                );

                drainEncoderToEnd(
                        encoder,
                        muxer,
                        outputTrack,
                        muxerStarted
                );

                throw new Exception(
                        "bg.mp3 is shorter than 8 seconds. "
                                + "Use a background MP3 at least 8 seconds long."
                );
            }

            /*
             * Signal AAC EOS.
             */
            int eosInput;

            while (true) {

                eosInput =
                        encoder.dequeueInputBuffer(
                                10_000
                        );

                if (eosInput >= 0) {
                    break;
                }
            }

            encoder.queueInputBuffer(
                    eosInput,
                    0,
                    0,
                    DURATION_US,
                    MediaCodec
                            .BUFFER_FLAG_END_OF_STREAM
            );

            drainEncoderToEnd(
                    encoder,
                    muxer,
                    outputTrack,
                    muxerStarted
            );

        } finally {

            if (decoderStarted) {

                try {
                    decoder.stop();
                } catch (Exception ignored) {
                }
            }

            if (decoder != null) {

                try {
                    decoder.release();
                } catch (Exception ignored) {
                }
            }

            if (encoderStarted) {

                try {
                    encoder.stop();
                } catch (Exception ignored) {
                }
            }

            if (encoder != null) {

                try {
                    encoder.release();
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

            try {
                extractor.release();
            } catch (Exception ignored) {
            }
        }
    }

    // ============================================================
    // DRAIN AAC ENCODER
    // ============================================================

    private static void drainEncoderToEnd(
            MediaCodec encoder,
            MediaMuxer muxer,
            int track,
            boolean muxerStarted
    ) throws Exception {

        MediaCodec.BufferInfo info =
                new MediaCodec.BufferInfo();

        boolean eos = false;

        while (!eos) {

            int index =
                    encoder.dequeueOutputBuffer(
                            info,
                            10_000
                    );

            if (index ==
                    MediaCodec.INFO_TRY_AGAIN_LATER) {

                continue;
            }

            if (index ==
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {

                /*
                 * The normal path starts the muxer before this
                 * method. Nothing else is required here.
                 */
                continue;
            }

            if (index < 0) {
                continue;
            }

            ByteBuffer buffer =
                    encoder.getOutputBuffer(
                            index
                    );

            if (buffer != null &&
                    info.size > 0 &&
                    muxerStarted &&
                    (info.flags &
                            MediaCodec
                                    .BUFFER_FLAG_CODEC_CONFIG)
                            == 0) {

                buffer.position(
                        info.offset
                );

                buffer.limit(
                        info.offset
                                + info.size
                );

                if (info.presentationTimeUs >=
                        0 &&
                        info.presentationTimeUs <
                                DURATION_US) {

                    muxer.writeSampleData(
                            track,
                            buffer,
                            info
                    );
                }
            }

            if ((info.flags &
                    MediaCodec
                            .BUFFER_FLAG_END_OF_STREAM)
                    != 0) {

                eos = true;
            }

            encoder.releaseOutputBuffer(
                    index,
                    false
            );
        }
    }

    // ============================================================
    // FINAL VIDEO + AUDIO MUX
    // ============================================================

    private static void muxVideoAndAudio(
            File videoFile,
            File audioFile,
            File output
    ) throws Exception {

        if (output.exists()) {
            deleteQuietly(output);
        }

        MediaExtractor videoExtractor =
                new MediaExtractor();

        MediaExtractor audioExtractor =
                new MediaExtractor();

        MediaMuxer muxer = null;

        boolean muxerStarted = false;

        try {

            videoExtractor.setDataSource(
                    videoFile.getAbsolutePath()
            );

            audioExtractor.setDataSource(
                    audioFile.getAbsolutePath()
            );

            int videoTrack =
                    findTrack(
                            videoExtractor,
                            "video/"
                    );

            int audioTrack =
                    findTrack(
                            audioExtractor,
                            "audio/"
                    );

            if (videoTrack < 0) {

                throw new Exception(
                        "Video track missing."
                );
            }

            if (audioTrack < 0) {

                throw new Exception(
                        "Audio track missing."
                );
            }

            videoExtractor.selectTrack(
                    videoTrack
            );

            audioExtractor.selectTrack(
                    audioTrack
            );

            muxer =
                    new MediaMuxer(
                            output.getAbsolutePath(),
                            MediaMuxer.OutputFormat
                                    .MUXER_OUTPUT_MPEG_4
                    );

            int outputVideoTrack =
                    muxer.addTrack(
                            videoExtractor
                                    .getTrackFormat(
                                            videoTrack
                                    )
                    );

            int outputAudioTrack =
                    muxer.addTrack(
                            audioExtractor
                                    .getTrackFormat(
                                            audioTrack
                                    )
                    );

            muxer.start();

            muxerStarted = true;

            /*
             * Video.
             */
            copyTrack(
                    videoExtractor,
                    muxer,
                    outputVideoTrack
            );

            /*
             * Audio.
             */
            copyTrack(
                    audioExtractor,
                    muxer,
                    outputAudioTrack
            );

        } finally {

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

            try {
                videoExtractor.release();
            } catch (Exception ignored) {
            }

            try {
                audioExtractor.release();
            } catch (Exception ignored) {
            }
        }
    }

    // ============================================================
    // COPY TRACK
    // ============================================================

    private static void copyTrack(
            MediaExtractor extractor,
            MediaMuxer muxer,
            int destinationTrack
    ) {

        /*
         * Large enough for normal 1080p H.264 frames and AAC
         * packets.
         */
        ByteBuffer buffer =
                ByteBuffer.allocateDirect(
                        4 * 1024 * 1024
                );

        MediaCodec.BufferInfo info =
                new MediaCodec.BufferInfo();

        while (true) {

            buffer.clear();

            int size =
                    extractor.readSampleData(
                            buffer,
                            0
                    );

            if (size < 0) {
                break;
            }

            long pts =
                    extractor.getSampleTime();

            if (pts < 0) {
                break;
            }

            /*
             * Only the first 8 seconds.
             */
            if (pts >= DURATION_US) {
                break;
            }

            int flags =
                    extractor.getSampleFlags();

            info.offset = 0;
            info.size = size;
            info.presentationTimeUs = pts;
            info.flags = flags;

            muxer.writeSampleData(
                    destinationTrack,
                    buffer,
                    info
            );

            extractor.advance();
        }
    }

    // ============================================================
    // MEDIA HELPERS
    // ============================================================

    private static int findTrack(
            MediaExtractor extractor,
            String prefix
    ) {

        int count =
                extractor.getTrackCount();

        for (int i = 0;
             i < count;
             i++) {

            MediaFormat format =
                    extractor.getTrackFormat(
                            i
                    );

            String mime =
                    format.getString(
                            MediaFormat.KEY_MIME
                    );

            if (mime != null &&
                    mime.startsWith(prefix)) {

                return i;
            }
        }

        return -1;
    }

    private static int getInt(
            MediaFormat format,
            String key,
            int fallback
    ) {

        try {

            if (format.containsKey(key)) {

                return format.getInteger(
                        key
                );
            }

        } catch (Exception ignored) {
        }

        return fallback;
    }

    // ============================================================
    // ASSET COPY
    // ============================================================

    private static File copyAssetToCache(
            Context context,
            String assetName
    ) throws Exception {

        File file =
                new File(
                        context.getCacheDir(),
                        assetName
                );

        try (
                InputStream input =
                        context.getAssets()
                                .open(assetName);

                FileOutputStream output =
                        new FileOutputStream(
                                file
                        )
        ) {

            byte[] buffer =
                    new byte[64 * 1024];

            int count;

            while ((count =
                    input.read(buffer)) != -1) {

                output.write(
                        buffer,
                        0,
                        count
                );
            }
        }

        return file;
    }

    // ============================================================
    // DELETE
    // ============================================================

    private static void deleteQuietly(
            File file
    ) {

        if (file == null) {
            return;
        }

        try {

            if (file.exists()) {
                file.delete();
            }

        } catch (Exception ignored) {
        }
    }
}
