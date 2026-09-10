package com.bibledailyshine.videogenerator;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Build;
import android.view.Surface;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates:
 *
 * 1080 x 1920
 * 30 FPS
 * 8 seconds
 * H.264 / AVC
 * Black background
 * Yellow "Bible Verse" heading
 * White verse text
 * font.ttf
 * bg.mp3 -> AAC
 *
 * No FFmpeg is required.
 *
 * Android APIs used:
 * MediaCodec
 * MediaExtractor
 * MediaMuxer
 * Surface / Canvas
 */
public final class VideoGenerator {

    private VideoGenerator() {
    }

    // ============================================================
    // VIDEO SETTINGS
    // ============================================================

    public static final int WIDTH = 1080;
    public static final int HEIGHT = 1920;

    public static final int FPS = 30;

    public static final int DURATION_SECONDS = 8;

    public static final long DURATION_US =
            DURATION_SECONDS * 1_000_000L;

    private static final int VIDEO_BITRATE =
            8_000_000;

    private static final int I_FRAME_INTERVAL =
            1;

    private static final String VIDEO_MIME =
            "video/avc";

    // ============================================================
    // SAFE AREA
    // ============================================================

    /*
     * Absolutely no text above Y=200.
     */
    private static final float TOP_SAFE =
            200.0f;

    /*
     * Absolutely no text below Y=1720.
     */
    private static final float BOTTOM_SAFE =
            200.0f;

    /*
     * Horizontal text area.
     */
    private static final float TEXT_WIDTH =
            760.0f;

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

    private static final float MIN_VERSE_SIZE =
            24.0f;

    private static final float MAX_VERSE_SIZE =
            72.0f;

    /*
     * Extra vertical separation between heading
     * and verse.
     */
    private static final float HEADING_VERSE_GAP =
            60.0f;

    /*
     * Line spacing multiplier.
     */
    private static final float LINE_SPACING =
            1.25f;

    // ============================================================
    // AUDIO
    // ============================================================

    private static final String AUDIO_MIME =
            "audio/mp4a-latm";

    private static final int AUDIO_SAMPLE_RATE =
            44100;

    private static final int AUDIO_CHANNEL_COUNT =
            2;

    private static final int AUDIO_BITRATE =
            128000;

    // ============================================================
    // PUBLIC ENTRY POINT
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

        if (output.exists()) {
            deleteQuietly(output);
        }

        File videoFile =
                new File(
                        parent,
                        output.getName()
                                + ".video.tmp.mp4"
                );

        File audioFile =
                new File(
                        parent,
                        output.getName()
                                + ".audio.tmp.m4a"
                );

        deleteQuietly(videoFile);
        deleteQuietly(audioFile);

        try {

            // ----------------------------------------------------
            // 1. Create H.264 video.
            // ----------------------------------------------------

            createVideo(
                    context,
                    verse,
                    videoFile
            );

            // ----------------------------------------------------
            // 2. Create 8-second AAC audio from bg.mp3.
            // ----------------------------------------------------

            createAudio(
                    context,
                    audioFile
            );

            // ----------------------------------------------------
            // 3. Combine video + audio into final MP4.
            // ----------------------------------------------------

            muxVideoAndAudio(
                    videoFile,
                    audioFile,
                    output
            );

        } finally {

            deleteQuietly(videoFile);
            deleteQuietly(audioFile);
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

        Surface inputSurface = null;

        MediaMuxer muxer = null;

        boolean encoderStarted = false;
        boolean muxerStarted = false;

        int videoTrack = -1;

        try {

            encoder.configure(
                    format,
                    null,
                    null,
                    MediaCodec.CONFIGURE_FLAG_ENCODE
            );

            inputSurface =
                    encoder.createInputSurface();

            if (inputSurface == null) {

                throw new Exception(
                        "Unable to create video encoder surface."
                );
            }

            muxer =
                    new MediaMuxer(
                            output.getAbsolutePath(),
                            MediaMuxer.OutputFormat
                                    .MUXER_OUTPUT_MPEG_4
                    );

            // ----------------------------------------------------
            // Prepare paints.
            // ----------------------------------------------------

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

            headingPaint.setTextSize(
                    HEADING_SIZE
            );

            // ----------------------------------------------------
            // Calculate heading position.
            // ----------------------------------------------------

            Paint.FontMetrics headingMetrics =
                    headingPaint.getFontMetrics();

            /*
             * Baseline is calculated so that the actual glyphs
             * cannot enter the first 200 pixels.
             */
            float headingBaseline =
                    TOP_SAFE
                            - headingMetrics.top
                            + 20.0f;

            /*
             * The bottom of the heading's actual font box.
             */
            float headingBottom =
                    headingBaseline
                            + headingMetrics.bottom;

            // ----------------------------------------------------
            // Available verse area.
            // ----------------------------------------------------

            float verseTop =
                    headingBottom
                            + HEADING_VERSE_GAP;

            float verseBottomLimit =
                    HEIGHT - BOTTOM_SAFE;

            float availableVerseHeight =
                    verseBottomLimit
                            - verseTop;

            if (availableVerseHeight < 100.0f) {

                throw new Exception(
                        "Not enough space for verse."
                );
            }

            // ----------------------------------------------------
            // Automatic verse layout.
            // ----------------------------------------------------

            TextLayout.Result layout =
                    createLayout(
                            versePaint,
                            verse,
                            TEXT_WIDTH,
                            availableVerseHeight
                    );

            versePaint.setTextSize(
                    layout.textSize
            );

            /*
             * Recalculate using final verse font.
             */
            Paint.FontMetrics verseMetrics =
                    versePaint.getFontMetrics();

            float firstVerseBaseline =
                    verseTop
                            - verseMetrics.top;

            float finalVerseBottom =
                    firstVerseBaseline
                            + verseMetrics.bottom
                            + (layout.lines.size() - 1)
                            * layout.lineHeight;

            /*
             * Hard safety check.
             */
            if (firstVerseBaseline +
                    verseMetrics.top <
                    TOP_SAFE) {

                throw new Exception(
                        "Verse entered top safe area."
                );
            }

            if (finalVerseBottom >
                    verseBottomLimit) {

                /*
                 * Try one more time with the exact available
                 * height.
                 */
                float exactHeight =
                        verseBottomLimit
                                - verseTop
                                - 5.0f;

                layout =
                        createLayout(
                                versePaint,
                                verse,
                                TEXT_WIDTH,
                                exactHeight
                        );

                versePaint.setTextSize(
                        layout.textSize
                );

                verseMetrics =
                        versePaint.getFontMetrics();

                firstVerseBaseline =
                        verseTop
                                - verseMetrics.top;

                finalVerseBottom =
                        firstVerseBaseline
                                + verseMetrics.bottom
                                + (layout.lines.size() - 1)
                                * layout.lineHeight;
            }

            if (finalVerseBottom >
                    verseBottomLimit + 0.5f) {

                throw new Exception(
                        "Verse cannot fit inside the 200 px safe areas."
                );
            }

            // ----------------------------------------------------
            // Start encoder.
            // ----------------------------------------------------

            encoder.start();

            encoderStarted = true;

            // ----------------------------------------------------
            // Render exactly 240 frames.
            // ----------------------------------------------------

            final int totalFrames =
                    FPS * DURATION_SECONDS;

            Canvas canvas = null;

            try {

                for (int frame = 0;
                     frame < totalFrames;
                     frame++) {

                    canvas = null;

                    /*
                     * lockCanvas() obtains the encoder's input
                     * surface.
                     */
                    canvas =
                            inputSurface.lockCanvas(
                                    null
                            );

                    if (canvas == null) {

                        throw new Exception(
                                "Unable to lock encoder surface."
                        );
                    }

                    // ------------------------------------------------
                    // Black background.
                    // ------------------------------------------------

                    canvas.drawColor(
                            Color.BLACK
                    );

                    // ------------------------------------------------
                    // Heading.
                    // ------------------------------------------------

                    canvas.drawText(
                            HEADING,
                            WIDTH / 2.0f,
                            headingBaseline,
                            headingPaint
                    );

                    // ------------------------------------------------
                    // Verse.
                    // ------------------------------------------------

                    float y =
                            firstVerseBaseline;

                    for (String line :
                            layout.lines) {

                        canvas.drawText(
                                line,
                                WIDTH / 2.0f,
                                y,
                                versePaint
                        );

                        y += layout.lineHeight;
                    }

                    /*
                     * Posting each frame to the encoder surface.
                     */
                    inputSurface.unlockCanvasAndPost(
                            canvas
                    );

                    canvas = null;

                    /*
                     * Drain any encoded output currently available.
                     */
                    DrainResult result =
                            drainVideoEncoder(
                                    encoder,
                                    muxer,
                                    videoTrack,
                                    muxerStarted,
                                    false
                            );

                    videoTrack =
                            result.track;

                    muxerStarted =
                            result.muxerStarted;
                }

            } finally {

                if (canvas != null) {

                    try {

                        inputSurface.unlockCanvasAndPost(
                                canvas
                        );

                    } catch (Exception ignored) {
                    }
                }
            }

            // ----------------------------------------------------
            // Tell encoder that no more frames are coming.
            // ----------------------------------------------------

            encoder.signalEndOfInputStream();

            // ----------------------------------------------------
            // Drain until EOS.
            // ----------------------------------------------------

            boolean eos = false;

            while (!eos) {

                int index =
                        encoder.dequeueOutputBuffer(
                                new MediaCodec.BufferInfo(),
                                10000
                        );

                /*
                 * The BufferInfo above cannot be reused when an
                 * output buffer is obtained, so use the dedicated
                 * drain method below instead.
                 */

                DrainResult result =
                        drainVideoEncoderBlocking(
                                encoder,
                                muxer,
                                videoTrack,
                                muxerStarted
                        );

                videoTrack =
                        result.track;

                muxerStarted =
                        result.muxerStarted;

                eos =
                        result.eos;
            }

        } finally {

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

            if (inputSurface != null) {

                try {
                    inputSurface.release();
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
    // VIDEO DRAIN RESULT
    // ============================================================

    private static final class DrainResult {

        int track;

        boolean muxerStarted;

        boolean eos;

        DrainResult(
                int track,
                boolean muxerStarted,
                boolean eos
        ) {

            this.track = track;
            this.muxerStarted = muxerStarted;
            this.eos = eos;
        }
    }

    // ============================================================
    // NON-BLOCKING VIDEO DRAIN
    // ============================================================

    private static DrainResult drainVideoEncoder(
            MediaCodec encoder,
            MediaMuxer muxer,
            int track,
            boolean muxerStarted,
            boolean wait
    ) {

        MediaCodec.BufferInfo info =
                new MediaCodec.BufferInfo();

        while (true) {

            int index =
                    encoder.dequeueOutputBuffer(
                            info,
                            wait ? 10000 : 0
                    );

            if (index ==
                    MediaCodec.INFO_TRY_AGAIN_LATER) {

                return new DrainResult(
                        track,
                        muxerStarted,
                        false
                );
            }

            if (index ==
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {

                if (muxerStarted) {

                    continue;
                }

                MediaFormat newFormat =
                        encoder.getOutputFormat();

                track =
                        muxer.addTrack(
                                newFormat
                        );

                muxer.start();

                muxerStarted = true;

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
                            MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
                            == 0) {

                buffer.position(
                        info.offset
                );

                buffer.limit(
                        info.offset
                                + info.size
                );

                if (info.presentationTimeUs >= 0) {

                    muxer.writeSampleData(
                            track,
                            buffer,
                            info
                    );
                }
            }

            boolean eos =
                    (info.flags &
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            != 0;

            encoder.releaseOutputBuffer(
                    index,
                    false
            );

            if (eos) {

                return new DrainResult(
                        track,
                        muxerStarted,
                        true
                );
            }

            if (!wait) {

                /*
                 * Drain all immediately available buffers.
                 */
                continue;
            }
        }
    }

    // ============================================================
    // BLOCKING VIDEO DRAIN
    // ============================================================

    private static DrainResult drainVideoEncoderBlocking(
            MediaCodec encoder,
            MediaMuxer muxer,
            int track,
            boolean muxerStarted
    ) {

        return drainVideoEncoder(
                encoder,
                muxer,
                track,
                muxerStarted,
                true
        );
    }

    // ============================================================
    // TEXT LAYOUT
    // ============================================================

    private static TextLayout.Result createLayout(
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

                return new TextLayout.Result(
                        lines,
                        size,
                        lineHeight,
                        totalHeight
                );
            }

            size -= 2.0f;
        }

        paint.setTextSize(
                MIN_VERSE_SIZE
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

        return new TextLayout.Result(
                lines,
                MIN_VERSE_SIZE,
                lineHeight,
                totalHeight
        );
    }

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
            List<String> lines
    ) {

        StringBuilder part =
                new StringBuilder();

        for (int i = 0;
             i < word.length();
             i++) {

            char character =
                    word.charAt(i);

            String candidate =
                    part.toString()
                            + character;

            if (part.length() > 0 &&
                    paint.measureText(candidate)
                            > maxWidth) {

                lines.add(
                        part.toString()
                );

                part.setLength(0);
            }

            part.append(
                    character
            );
        }

        if (part.length() > 0) {

            lines.add(
                    part.toString()
            );
        }
    }

    // ============================================================
    // LOAD FONT
    // ============================================================

    private static Typeface loadTypeface(
            Context context
    ) throws Exception {

        File fontFile =
                new File(
                        context.getCacheDir(),
                        "bible_verse_font.ttf"
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

        deleteQuietly(fontFile);

        if (typeface == null) {

            throw new Exception(
                    "Unable to load font.ttf."
            );
        }

        return typeface;
    }

    // ============================================================
    // CREATE AUDIO
    // ============================================================

    private static void createAudio(
            Context context,
            File output
    ) throws Exception {

        if (output.exists()) {
            deleteQuietly(output);
        }

        File mp3 =
                copyAssetToCache(
                        context,
                        "bg.mp3"
                );

        try {

            createAudioFromMp3(
                    mp3,
                    output
            );

        } finally {

            deleteQuietly(mp3);
        }
    }

    // ============================================================
    // MP3 -> AAC
    // ============================================================

    private static void createAudioFromMp3(
            File mp3,
            File output
    ) throws Exception {

        if (!mp3.exists()) {

            throw new Exception(
                    "bg.mp3 not found."
            );
        }

        /*
         * First inspect the MP3.
         */
        MediaExtractor inspect =
                new MediaExtractor();

        inspect.setDataSource(
                mp3.getAbsolutePath()
        );

        int audioTrack =
                findTrack(
                        inspect,
                        "audio/"
                );

        if (audioTrack < 0) {

            inspect.release();

            throw new Exception(
                    "bg.mp3 contains no audio track."
            );
        }

        MediaFormat sourceFormat =
                inspect.getTrackFormat(
                        audioTrack
                );

        inspect.release();

        /*
         * Decode and encode repeatedly until the AAC stream
         * contains 8 seconds.
         */
        AudioEncoder encoder =
                new AudioEncoder(
                        output
                );

        long producedUs = 0;

        boolean firstPass = true;

        try {

            while (producedUs <
                    DURATION_US) {

                PassResult pass =
                        decodeOnePass(
                                mp3,
                                encoder,
                                producedUs
                        );

                if (pass.durationUs <= 0) {

                    if (firstPass) {

                        throw new Exception(
                                "Unable to decode audio from bg.mp3."
                        );
                    }

                    break;
                }

                producedUs +=
                        pass.durationUs;

                firstPass = false;

                /*
                 * If bg.mp3 is already long enough, stop.
                 *
                 * If it is shorter than 8 seconds, the extractor
                 * is opened again and the music loops.
                 */
            }

            encoder.endOfStream();

            encoder.drainUntilEnd();

        } finally {

            encoder.release();
        }
    }

    // ============================================================
    // DECODE ONE MP3 PASS
    // ============================================================

    private static PassResult decodeOnePass(
            File mp3,
            AudioEncoder audioEncoder,
            long globalPtsUs
    ) throws Exception {

        MediaExtractor extractor =
                new MediaExtractor();

        MediaCodec decoder = null;

        try {

            extractor.setDataSource(
                    mp3.getAbsolutePath()
            );

            int track =
                    findTrack(
                            extractor,
                            "audio/"
                    );

            if (track < 0) {

                throw new Exception(
                        "No audio track found."
                );
            }

            MediaFormat sourceFormat =
                    extractor.getTrackFormat(
                            track
                    );

            String mime =
                    sourceFormat.getString(
                            MediaFormat.KEY_MIME
                    );

            if (mime == null) {

                throw new Exception(
                        "Audio MIME type missing."
                );
            }

            extractor.selectTrack(
                    track
            );

            decoder =
                    MediaCodec.createDecoderByType(
                            mime
                    );

            decoder.configure(
                    sourceFormat,
                    null,
                    null,
                    0
            );

            decoder.start();

            boolean inputDone = false;
            boolean outputDone = false;

            long firstPtsUs = -1;
            long lastPtsUs = 0;

            MediaCodec.BufferInfo info =
                    new MediaCodec.BufferInfo();

            int sampleRate =
                    getFormatInt(
                            sourceFormat,
                            MediaFormat.KEY_SAMPLE_RATE,
                            AUDIO_SAMPLE_RATE
                    );

            int channels =
                    getFormatInt(
                            sourceFormat,
                            MediaFormat.KEY_CHANNEL_COUNT,
                            AUDIO_CHANNEL_COUNT
                    );

            /*
             * We need to know how many PCM bytes correspond
             * to a duration.
             *
             * Android PCM decoder output is normally signed
             * 16-bit PCM.
             */
            final int bytesPerSample = 2;

            while (!outputDone) {

                // ------------------------------------------------
                // Feed compressed MP3 data.
                // ------------------------------------------------

                if (!inputDone) {

                    int inputIndex =
                            decoder.dequeueInputBuffer(
                                    10000
                            );

                    if (inputIndex >= 0) {

                        ByteBuffer input =
                                decoder.getInputBuffer(
                                        inputIndex
                                );

                        if (input == null) {

                            throw new Exception(
                                    "Decoder input buffer is null."
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

                            inputDone = true;

                        } else {

                            long pts =
                                    extractor
                                            .getSampleTime();

                            if (pts < 0) {
                                pts = 0;
                            }

                            int flags =
                                    extractor
                                            .getSampleFlags();

                            decoder.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    size,
                                    pts,
                                    flags
                            );

                            extractor.advance();
                        }
                    }
                }

                // ------------------------------------------------
                // Drain decoded PCM.
                // ------------------------------------------------

                int outputIndex =
                        decoder.dequeueOutputBuffer(
                                info,
                                10000
                        );

                if (outputIndex ==
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {

                    MediaFormat decodedFormat =
                            decoder.getOutputFormat();

                    sampleRate =
                            getFormatInt(
                                    decodedFormat,
                                    MediaFormat.KEY_SAMPLE_RATE,
                                    sampleRate
                            );

                    channels =
                            getFormatInt(
                                    decodedFormat,
                                    MediaFormat.KEY_CHANNEL_COUNT,
                                    channels
                            );

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

                    long decoderPts =
                            info.presentationTimeUs;

                    if (decoderPts < 0) {
                        decoderPts = lastPtsUs;
                    }

                    if (firstPtsUs < 0) {
                        firstPtsUs = decoderPts;
                    }

                    long relativePts =
                            decoderPts
                                    - firstPtsUs;

                    if (relativePts < 0) {
                        relativePts = 0;
                    }

                    long finalPts =
                            globalPtsUs
                                    + relativePts;

                    /*
                     * Don't encode PCM beyond the required
                     * 8-second output.
                     */
                    long remainingUs =
                            DURATION_US
                                    - finalPts;

                    if (remainingUs > 0) {

                        int bytes =
                                pcm.remaining();

                        long pcmDurationUs =
                                bytesToDurationUs(
                                        bytes,
                                        sampleRate,
                                        channels,
                                        bytesPerSample
                                );

                        if (pcmDurationUs >
                                remainingUs) {

                            long allowedBytes =
                                    durationToBytes(
                                            remainingUs,
                                            sampleRate,
                                            channels,
                                            bytesPerSample
                                    );

                            /*
                             * Keep complete PCM frames.
                             */
                            int frameBytes =
                                    channels *
                                            bytesPerSample;

                            allowedBytes =
                                    (allowedBytes /
                                            frameBytes)
                                            * frameBytes;

                            if (allowedBytes <
                                    bytes) {

                                bytes =
                                        (int)
                                                Math.max(
                                                        0,
                                                        Math.min(
                                                                Integer.MAX_VALUE,
                                                                allowedBytes
                                                        )
                                                );
                            }

                            if (bytes > 0) {

                                pcm.limit(
                                        pcm.position()
                                                + bytes
                                );
                            }
                        }

                        if (bytes > 0) {

                            audioEncoder.queuePcm(
                                    pcm,
                                    finalPts
                            );

                            long duration =
                                    bytesToDurationUs(
                                            bytes,
                                            sampleRate,
                                            channels,
                                            bytesPerSample
                                    );

                            lastPtsUs =
                                    relativePts
                                            + duration;
                        }
                    }
                }

                if ((info.flags &
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        != 0) {

                    outputDone = true;
                }

                decoder.releaseOutputBuffer(
                        outputIndex,
                        false
                );
            }

            long durationUs;

            if (lastPtsUs > 0) {

                durationUs =
                        lastPtsUs;

            } else if (sourceFormat.containsKey(
                    MediaFormat.KEY_DURATION
            )) {

                durationUs =
                        sourceFormat.getLong(
                                MediaFormat.KEY_DURATION
                        );

            } else {

                durationUs = 0;
            }

            return new PassResult(
                    durationUs
            );

        } finally {

            try {
                extractor.release();
            } catch (Exception ignored) {
            }

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
        }
    }

    private static final class PassResult {

        final long durationUs;

        PassResult(
                long durationUs
        ) {

            this.durationUs =
                    durationUs;
        }
    }

    // ============================================================
    // AUDIO ENCODER
    // ============================================================

    private static final class AudioEncoder {

        private final MediaCodec encoder;

        private final MediaMuxer muxer;

        private final MediaCodec.BufferInfo info =
                new MediaCodec.BufferInfo();

        private int track = -1;

        private boolean muxerStarted =
                false;

        private boolean started =
                false;

        AudioEncoder(
                File output
        ) throws Exception {

            MediaFormat format =
                    MediaFormat.createAudioFormat(
                            AUDIO_MIME,
                            AUDIO_SAMPLE_RATE,
                            AUDIO_CHANNEL_COUNT
                    );

            format.setInteger(
                    MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel
                            .AACObjectLC
            );

            format.setInteger(
                    MediaFormat.KEY_BIT_RATE,
                    AUDIO_BITRATE
            );

            format.setInteger(
                    MediaFormat.KEY_MAX_INPUT_SIZE,
                    16384
            );

            encoder =
                    MediaCodec.createEncoderByType(
                            AUDIO_MIME
                    );

            encoder.configure(
                    format,
                    null,
                    null,
                    MediaCodec.CONFIGURE_FLAG_ENCODE
            );

            muxer =
                    new MediaMuxer(
                            output.getAbsolutePath(),
                            MediaMuxer.OutputFormat
                                    .MUXER_OUTPUT_MPEG_4
                    );

            encoder.start();

            started = true;
        }

        void queuePcm(
                ByteBuffer pcm,
                long ptsUs
        ) throws Exception {

            int remaining =
                    pcm.remaining();

            while (remaining > 0) {

                int inputIndex =
                        encoder.dequeueInputBuffer(
                                10000
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
                            "AAC encoder input buffer unavailable."
                    );
                }

                input.clear();

                int count =
                        Math.min(
                                input.remaining(),
                                pcm.remaining()
                        );

                if (count <= 0) {
                    break;
                }

                /*
                 * Copy PCM.
                 */
                byte[] temp =
                        new byte[count];

                pcm.get(temp);

                input.put(temp);

                /*
                 * PTS belongs to the first sample in this
                 * input buffer.
                 *
                 * For subsequent chunks, derive the PTS
                 * from the number of PCM samples already
                 * submitted.
                 */
                long chunkPts =
                        ptsUs
                                + calculatePcmDuration(
                                        temp.length,
                                        AUDIO_SAMPLE_RATE,
                                        AUDIO_CHANNEL_COUNT,
                                        2
                                );

                encoder.queueInputBuffer(
                        inputIndex,
                        0,
                        count,
                        ptsUs,
                        0
                );

                drainAvailable();

                ptsUs =
                        chunkPts;

                remaining =
                        pcm.remaining();
            }
        }

        private void drainAvailable()
                throws Exception {

            while (true) {

                int index =
                        encoder.dequeueOutputBuffer(
                                info,
                                0
                        );

                if (index ==
                        MediaCodec.INFO_TRY_AGAIN_LATER) {

                    return;
                }

                if (index ==
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {

                    if (muxerStarted) {
                        continue;
                    }

                    MediaFormat outputFormat =
                            encoder.getOutputFormat();

                    track =
                            muxer.addTrack(
                                    outputFormat
                            );

                    muxer.start();

                    muxerStarted = true;

                    continue;
                }

                if (index < 0) {
                    continue;
                }

                ByteBuffer data =
                        encoder.getOutputBuffer(
                                index
                        );

                if (data != null &&
                        info.size > 0 &&
                        muxerStarted &&
                        (info.flags &
                                MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
                                == 0) {

                    data.position(
                            info.offset
                    );

                    data.limit(
                            info.offset
                                    + info.size
                    );

                    if (info.presentationTimeUs >=
                            0 &&
                            info.presentationTimeUs
                                    < DURATION_US) {

                        muxer.writeSampleData(
                                track,
                                data,
                                info
                        );
                    }
                }

                encoder.releaseOutputBuffer(
                        index,
                        false
                );
            }
        }

        void endOfStream()
                throws Exception {

            while (true) {

                int inputIndex =
                        encoder.dequeueInputBuffer(
                                10000
                        );

                if (inputIndex >= 0) {

                    encoder.queueInputBuffer(
                            inputIndex,
                            0,
                            0,
                            DURATION_US,
                            MediaCodec
                                    .BUFFER_FLAG_END_OF_STREAM
                    );

                    return;
                }
            }
        }

        void drainUntilEnd()
                throws Exception {

            boolean eos = false;

            while (!eos) {

                int index =
                        encoder.dequeueOutputBuffer(
                                info,
                                10000
                        );

                if (index ==
                        MediaCodec.INFO_TRY_AGAIN_LATER) {

                    continue;
                }

                if (index ==
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {

                    if (!muxerStarted) {

                        MediaFormat format =
                                encoder.getOutputFormat();

                        track =
                                muxer.addTrack(
                                        format
                                );

                        muxer.start();

                        muxerStarted = true;
                    }

                    continue;
                }

                if (index < 0) {
                    continue;
                }

                ByteBuffer data =
                        encoder.getOutputBuffer(
                                index
                        );

                if (data != null &&
                        info.size > 0 &&
                        muxerStarted &&
                        (info.flags &
                                MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
                                == 0) {

                    data.position(
                            info.offset
                    );

                    data.limit(
                            info.offset
                                    + info.size
                    );

                    if (info.presentationTimeUs >=
                            0 &&
                            info.presentationTimeUs
                                    < DURATION_US) {

                        muxer.writeSampleData(
                                track,
                                data,
                                info
                        );
                    }
                }

                if ((info.flags &
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        != 0) {

                    eos = true;
                }

                encoder.releaseOutputBuffer(
                        index,
                        false
                );
            }
        }

        void release() {

            if (started) {

                try {
                    encoder.stop();
                } catch (Exception ignored) {
                }
            }

            try {
                encoder.release();
            } catch (Exception ignored) {
            }

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

    // ============================================================
    // AUDIO HELPERS
    // ============================================================

    private static long bytesToDurationUs(
            long bytes,
            int sampleRate,
            int channels,
            int bytesPerSample
    ) {

        long bytesPerFrame =
                (long) channels
                        * bytesPerSample;

        if (bytesPerFrame <= 0 ||
                sampleRate <= 0) {

            return 0;
        }

        long frames =
                bytes /
                        bytesPerFrame;

        return frames *
                1_000_000L /
                sampleRate;
    }

    private static long durationToBytes(
            long durationUs,
            int sampleRate,
            int channels,
            int bytesPerSample
    ) {

        if (durationUs <= 0 ||
                sampleRate <= 0) {

            return 0;
        }

        long frames =
                durationUs *
                        sampleRate /
                        1_000_000L;

        return frames *
                channels *
                bytesPerSample;
    }

    private static long calculatePcmDuration(
            int bytes,
            int sampleRate,
            int channels,
            int bytesPerSample
    ) {

        return bytesToDurationUs(
                bytes,
                sampleRate,
                channels,
                bytesPerSample
        );
    }

    // ============================================================
    // MEDIA TRACK HELPERS
    // ============================================================

    private static int findTrack(
            MediaExtractor extractor,
            String mimePrefix
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
                    mime.startsWith(
                            mimePrefix
                    )) {

                return i;
            }
        }

        return -1;
    }

    private static int getFormatInt(
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
    // COPY ASSET
    // ============================================================

    private static File copyAssetToCache(
            Context context,
            String assetName
    ) throws Exception {

        File output =
                new File(
                        context.getCacheDir(),
                        assetName
                );

        try (
                InputStream input =
                        context.getAssets()
                                .open(assetName);

                FileOutputStream outputStream =
                        new FileOutputStream(
                                output
                        )
        ) {

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
        }

        return output;
    }

    // ============================================================
    // MUX VIDEO + AUDIO
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
             * Copy video first.
             */
            copyTrack(
                    videoExtractor,
                    muxer,
                    outputVideoTrack,
                    DURATION_US
            );

            /*
             * Copy audio.
             */
            copyTrack(
                    audioExtractor,
                    muxer,
                    outputAudioTrack,
                    DURATION_US
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

    private static void copyTrack(
            MediaExtractor extractor,
            MediaMuxer muxer,
            int destinationTrack,
            long maxDurationUs
    ) {

        ByteBuffer buffer =
                ByteBuffer.allocateDirect(
                        1024 * 1024
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

            if (pts >= maxDurationUs) {
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
