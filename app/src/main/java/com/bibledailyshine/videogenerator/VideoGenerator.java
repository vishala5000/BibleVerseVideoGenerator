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
import android.opengl.EGL14;
import android.opengl.EGLExt;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.view.Surface;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

public final class VideoGenerator {

    // ============================================================
    // VIDEO SETTINGS
    // ============================================================

    private static final int WIDTH = 1080;
    private static final int HEIGHT = 1920;

    private static final int FPS = 30;
    private static final int DURATION_SECONDS = 8;
    private static final int TOTAL_FRAMES =
            FPS * DURATION_SECONDS;

    private static final long VIDEO_DURATION_US =
            8_000_000L;

    private static final int VIDEO_BITRATE =
            5_000_000;

    // ============================================================
    // SAFE AREA
    // ============================================================

    private static final float SAFE_TOP = 200f;
    private static final float SAFE_BOTTOM = 1720f;

    private static final float TEXT_WRAP_WIDTH = 680f;

    // ============================================================
    // TEXT SETTINGS
    // ============================================================

    private static final float HEADING_SIZE = 100f;

    private static final float MAX_VERSE_SIZE = 76f;
    private static final float MIN_VERSE_SIZE = 26f;

    /*
     * Tight line spacing so long Bible verses look cleaner.
     */
    private static final float LINE_SPACING_MULTIPLIER = 1.02f;

    /*
     * Space between heading and verse.
     */
    private static final float HEADING_GAP = 55f;

    // ============================================================
    // ANIMATION
    // ============================================================

    private static final long HEADING_ANIMATION_US =
            450_000L;

    private static final long VERSE_ANIMATION_US =
            650_000L;

    private static final float ENTRANCE_SHIFT =
            18f;

    private static final float BREATHING_SCALE =
            0.006f;

    // ============================================================
    // AUDIO
    // ============================================================

    private static final int AAC_SAMPLE_RATE =
            44100;

    private static final int AAC_CHANNELS =
            2;

    private static final int AAC_BITRATE =
            192000;

    private static final String AUDIO_CACHE_NAME =
            "bible_daily_shine_bg_v5.m4a";

    // ============================================================
    // OPENGL
    // ============================================================

    private static final String VERTEX_SHADER =
            "attribute vec4 aPosition;" +
            "attribute vec2 aTexCoord;" +
            "varying vec2 vTexCoord;" +
            "void main() {" +
            "    gl_Position = aPosition;" +
            "    vTexCoord = aTexCoord;" +
            "}";

    private static final String FRAGMENT_SHADER =
            "precision mediump float;" +
            "uniform sampler2D uTexture;" +
            "uniform float uAlpha;" +
            "varying vec2 vTexCoord;" +
            "void main() {" +
            "    vec4 color = texture2D(" +
            "        uTexture," +
            "        vTexCoord" +
            "    );" +
            "    color.a *= uAlpha;" +
            "    gl_FragColor = color;" +
            "}";

    private static final float[] TEX_COORDS = {
            0f, 1f,
            1f, 1f,
            0f, 0f,
            1f, 0f
    };

    // ============================================================
    // INSTANCE
    // ============================================================

    private final Context context;

    private Typeface typeface;

    private VideoGenerator(
            Context context
    ) {

        this.context =
                context.getApplicationContext();

        /*
         * IMPORTANT:
         *
         * Do NOT use Typeface.createFromStream().
         *
         * createFromAsset() works with the Android SDK
         * used by this project and loads:
         *
         * app/src/main/assets/font.ttf
         */
        try {

            typeface =
                    Typeface.createFromAsset(
                            this.context.getAssets(),
                            "font.ttf"
                    );

        } catch (Exception e) {

            typeface =
                    Typeface.create(
                            Typeface.SANS_SERIF,
                            Typeface.NORMAL
                    );
        }
    }

    // ============================================================
    // MAIN STATIC API
    // ============================================================

    /*
     * This is the method required by MainActivity.java.
     *
     * MainActivity can call:
     *
     * VideoGenerator.generate(
     *     MainActivity.this,
     *     verse,
     *     outputFile
     * );
     */
    public static File generate(
            Context context,
            String verseText,
            File outputFile
    ) throws Exception {

        VideoGenerator generator =
                new VideoGenerator(context);

        return generator.generateVideo(
                verseText,
                outputFile
        );
    }

    // ============================================================
    // GENERATE VIDEO
    // ============================================================

    private File generateVideo(
            String verseText,
            File outputFile
    ) throws Exception {

        if (verseText == null) {
            verseText = "";
        }

        verseText =
                verseText
                        .replace("\r", " ")
                        .replace("\n", " ")
                        .trim();

        if (verseText.isEmpty()) {

            throw new IllegalArgumentException(
                    "Verse text is empty"
            );
        }

        File parent =
                outputFile.getParentFile();

        if (
                parent != null
                        &&
                !parent.exists()
        ) {

            if (
                    !parent.mkdirs()
                            &&
                    !parent.exists()
            ) {

                throw new Exception(
                        "Unable to create output directory"
                );
            }
        }

        if (outputFile.exists()) {
            outputFile.delete();
        }

        // --------------------------------------------------------
        // Create audio cache
        // --------------------------------------------------------

        File audioFile =
                createAudioCache();

        // --------------------------------------------------------
        // Create text layers
        // --------------------------------------------------------

        TextLayer heading =
                createHeadingLayer(
                        "Bible Verse"
                );

        TextLayer verse =
                createVerseLayer(
                        verseText
                );

        // --------------------------------------------------------
        // Encode final MP4
        // --------------------------------------------------------

        encodeVideo(
                heading,
                verse,
                audioFile,
                outputFile
        );

        return outputFile;
    }

    // ============================================================
    // HEADING
    // ============================================================

    private TextLayer createHeadingLayer(
            String text
    ) {

        Paint paint =
                new Paint(
                        Paint.ANTI_ALIAS_FLAG
                                |
                        Paint.SUBPIXEL_TEXT_FLAG
                );

        paint.setTypeface(typeface);
        paint.setTextSize(HEADING_SIZE);
        paint.setColor(Color.YELLOW);
        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.CENTER);

        Paint.FontMetrics metrics =
                paint.getFontMetrics();

        float textWidth =
                paint.measureText(text);

        float bitmapWidth =
                Math.min(
                        TEXT_WRAP_WIDTH,
                        textWidth + 40f
                );

        float bitmapHeight =
                (
                        metrics.bottom
                                -
                        metrics.top
                ) + 40f;

        Bitmap bitmap =
                Bitmap.createBitmap(
                        Math.max(
                                1,
                                (int) Math.ceil(
                                        bitmapWidth
                                )
                        ),
                        Math.max(
                                1,
                                (int) Math.ceil(
                                        bitmapHeight
                                )
                        ),
                        Bitmap.Config.ARGB_8888
                );

        Canvas canvas =
                new Canvas(bitmap);

        canvas.drawColor(
                Color.TRANSPARENT
        );

        float baseline =
                20f - metrics.top;

        canvas.drawText(
                text,
                bitmap.getWidth() / 2f,
                baseline,
                paint
        );

        return new TextLayer(
                bitmap,
                bitmapWidth,
                bitmapHeight
        );
    }

    // ============================================================
    // VERSE
    // ============================================================

    private TextLayer createVerseLayer(
            String text
    ) {

        Paint paint =
                new Paint(
                        Paint.ANTI_ALIAS_FLAG
                                |
                        Paint.SUBPIXEL_TEXT_FLAG
                );

        paint.setTypeface(typeface);
        paint.setColor(Color.WHITE);
        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.CENTER);

        float selectedSize =
                MIN_VERSE_SIZE;

        List<String> selectedLines =
                null;

        float selectedLineHeight =
                0f;

        float selectedTotalHeight =
                0f;

        /*
         * The verse must remain below the heading
         * and above the 200 px bottom safe area.
         */
        float maximumAllowedHeight =
                SAFE_BOTTOM
                        -
                SAFE_TOP
                        -
                HEADING_SIZE
                        -
                HEADING_GAP
                        -
                80f;

        // --------------------------------------------------------
        // Automatically shrink long verses
        // --------------------------------------------------------

        for (
                float size = MAX_VERSE_SIZE;
                size >= MIN_VERSE_SIZE;
                size -= 2f
        ) {

            paint.setTextSize(size);

            List<String> lines =
                    wrapText(
                            paint,
                            text,
                            TEXT_WRAP_WIDTH
                    );

            Paint.FontMetrics metrics =
                    paint.getFontMetrics();

            float lineHeight =
                    (
                            metrics.bottom
                                    -
                            metrics.top
                    )
                            *
                    LINE_SPACING_MULTIPLIER;

            float totalHeight =
                    lineHeight *
                    lines.size();

            if (
                    totalHeight
                            <=
                    maximumAllowedHeight
            ) {

                selectedSize =
                        size;

                selectedLines =
                        lines;

                selectedLineHeight =
                        lineHeight;

                selectedTotalHeight =
                        totalHeight;

                break;
            }
        }

        // --------------------------------------------------------
        // Minimum size fallback
        // --------------------------------------------------------

        if (selectedLines == null) {

            paint.setTextSize(
                    MIN_VERSE_SIZE
            );

            selectedLines =
                    wrapText(
                            paint,
                            text,
                            TEXT_WRAP_WIDTH
                    );

            Paint.FontMetrics metrics =
                    paint.getFontMetrics();

            selectedLineHeight =
                    (
                            metrics.bottom
                                    -
                            metrics.top
                    )
                            *
                    LINE_SPACING_MULTIPLIER;

            selectedTotalHeight =
                    selectedLineHeight
                            *
                    selectedLines.size();

            selectedSize =
                    MIN_VERSE_SIZE;
        }

        paint.setTextSize(
                selectedSize
        );

        Paint.FontMetrics metrics =
                paint.getFontMetrics();

        float bitmapHeight =
                selectedTotalHeight
                        + 30f;

        Bitmap bitmap =
                Bitmap.createBitmap(
                        (int) TEXT_WRAP_WIDTH,
                        Math.max(
                                1,
                                (int) Math.ceil(
                                        bitmapHeight
                                )
                        ),
                        Bitmap.Config.ARGB_8888
                );

        Canvas canvas =
                new Canvas(bitmap);

        canvas.drawColor(
                Color.TRANSPARENT
        );

        /*
         * Center the complete group of lines vertically
         * inside the bitmap.
         */
        float actualTextHeight =
                metrics.bottom
                        -
                metrics.top;

        float firstBaseline =
                15f
                        -
                metrics.top
                        +
                (
                        selectedLineHeight
                                -
                        actualTextHeight
                ) / 2f;

        for (
                int i = 0;
                i < selectedLines.size();
                i++
        ) {

            float baseline =
                    firstBaseline
                            +
                    i * selectedLineHeight;

            canvas.drawText(
                    selectedLines.get(i),
                    bitmap.getWidth() / 2f,
                    baseline,
                    paint
            );
        }

        return new TextLayer(
                bitmap,
                TEXT_WRAP_WIDTH,
                bitmapHeight
        );
    }

    // ============================================================
    // TEXT WRAPPING
    // ============================================================

    private List<String> wrapText(
            Paint paint,
            String text,
            float maxWidth
    ) {

        List<String> result =
                new ArrayList<>();

        String cleaned =
                text
                        .replace("\r", " ")
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

                if (
                        paint.measureText(word)
                                <=
                        maxWidth
                ) {

                    current.append(
                            word
                    );

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
                    current.toString()
                            +
                    " "
                            +
                    word;

            if (
                    paint.measureText(candidate)
                            <=
                    maxWidth
            ) {

                current
                        .append(" ")
                        .append(word);

            } else {

                result.add(
                        current.toString()
                );

                current.setLength(0);

                if (
                        paint.measureText(word)
                                <=
                        maxWidth
                ) {

                    current.append(
                            word
                    );

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

    private void splitLongWord(
            Paint paint,
            String word,
            float maxWidth,
            List<String> output
    ) {

        StringBuilder part =
                new StringBuilder();

        for (
                int i = 0;
                i < word.length();
                i++
        ) {

            char c =
                    word.charAt(i);

            String candidate =
                    part.toString()
                            +
                    c;

            if (
                    part.length() > 0
                            &&
                    paint.measureText(
                            candidate
                    ) > maxWidth
            ) {

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
    // VIDEO ENCODER
    // ============================================================

    private void encodeVideo(
            TextLayer heading,
            TextLayer verse,
            File audioFile,
            File outputFile
    ) throws Exception {

        MediaCodec encoder =
                MediaCodec.createEncoderByType(
                        "video/avc"
                );

        MediaFormat videoFormat =
                MediaFormat.createVideoFormat(
                        "video/avc",
                        WIDTH,
                        HEIGHT
                );

        videoFormat.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities
                        .COLOR_FormatSurface
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

        encoder.configure(
                videoFormat,
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE
        );

        Surface inputSurface =
                encoder.createInputSurface();

        encoder.start();

        // --------------------------------------------------------
        // EGL
        // --------------------------------------------------------

        EGLDisplay display =
                EGL14.eglGetDisplay(
                        EGL14.EGL_DEFAULT_DISPLAY
                );

        if (
                display
                        ==
                EGL14.EGL_NO_DISPLAY
        ) {

            throw new Exception(
                    "Unable to obtain EGL display"
            );
        }

        int[] version =
                new int[2];

        if (
                !EGL14.eglInitialize(
                        display,
                        version,
                        0,
                        version,
                        1
                )
        ) {

            throw new Exception(
                    "Unable to initialize EGL"
            );
        }

        int[] configAttributes = {
                EGL14.EGL_RED_SIZE,
                8,

                EGL14.EGL_GREEN_SIZE,
                8,

                EGL14.EGL_BLUE_SIZE,
                8,

                EGL14.EGL_ALPHA_SIZE,
                8,

                EGL14.EGL_RENDERABLE_TYPE,
                EGL14.EGL_OPENGL_ES2_BIT,

                EGL14.EGL_NONE
        };

        EGLConfig[] configs =
                new EGLConfig[1];

        int[] numConfigs =
                new int[1];

        if (
                !EGL14.eglChooseConfig(
                        display,
                        configAttributes,
                        0,
                        configs,
                        0,
                        1,
                        numConfigs,
                        0
                )
        ) {

            throw new Exception(
                    "Unable to choose EGL config"
            );
        }

        EGLConfig config =
                configs[0];

        int[] contextAttributes = {
                EGL14.EGL_CONTEXT_CLIENT_VERSION,
                2,
                EGL14.EGL_NONE
        };

        EGLContext eglContext =
                EGL14.eglCreateContext(
                        display,
                        config,
                        EGL14.EGL_NO_CONTEXT,
                        contextAttributes,
                        0
                );

        if (
                eglContext
                        ==
                EGL14.EGL_NO_CONTEXT
        ) {

            throw new Exception(
                    "Unable to create EGL context"
            );
        }

        int[] surfaceAttributes = {
                EGL14.EGL_NONE
        };

        EGLSurface eglSurface =
                EGL14.eglCreateWindowSurface(
                        display,
                        config,
                        inputSurface,
                        surfaceAttributes,
                        0
                );

        if (
                eglSurface
                        ==
                EGL14.EGL_NO_SURFACE
        ) {

            throw new Exception(
                    "Unable to create EGL window surface"
            );
        }

        if (
                !EGL14.eglMakeCurrent(
                        display,
                        eglSurface,
                        eglSurface,
                        eglContext
                )
        ) {

            throw new Exception(
                    "Unable to make EGL current"
            );
        }

        // --------------------------------------------------------
        // GL program
        // --------------------------------------------------------

        int program =
                createProgram();

        int positionHandle =
                GLES20.glGetAttribLocation(
                        program,
                        "aPosition"
                );

        int texCoordHandle =
                GLES20.glGetAttribLocation(
                        program,
                        "aTexCoord"
                );

        int textureHandle =
                GLES20.glGetUniformLocation(
                        program,
                        "uTexture"
                );

        int alphaHandle =
                GLES20.glGetUniformLocation(
                        program,
                        "uAlpha"
                );

        FloatBuffer textureCoordinates =
                createFloatBuffer(
                        TEX_COORDS
                );

        int headingTexture =
                createTexture(
                        heading.bitmap
                );

        int verseTexture =
                createTexture(
                        verse.bitmap
                );

        // --------------------------------------------------------
        // Audio track format
        // --------------------------------------------------------

        MediaFormat audioFormat =
                getAudioFormat(
                        audioFile
                );

        // --------------------------------------------------------
        // Muxer
        // --------------------------------------------------------

        MediaMuxer muxer =
                new MediaMuxer(
                        outputFile.getAbsolutePath(),
                        MediaMuxer.OutputFormat
                                .MUXER_OUTPUT_MPEG_4
                );

        int videoTrack = -1;
        int audioTrack = -1;

        boolean muxerStarted = false;

        MediaCodec.BufferInfo bufferInfo =
                new MediaCodec.BufferInfo();

        try {

            // ----------------------------------------------------
            // Render all frames
            // ----------------------------------------------------

            for (
                    int frame = 0;
                    frame < TOTAL_FRAMES;
                    frame++
            ) {

                long presentationTimeUs =
                        frame
                                *
                        1_000_000L
                                /
                        FPS;

                drawFrame(
                        program,
                        positionHandle,
                        texCoordHandle,
                        textureHandle,
                        alphaHandle,
                        textureCoordinates,
                        headingTexture,
                        verseTexture,
                        heading,
                        verse,
                        presentationTimeUs
                );

                /*
                 * Android EGL expects nanoseconds here.
                 */
                EGLExt
                        .eglPresentationTimeANDROID(
                                display,
                                eglSurface,
                                presentationTimeUs
                                        *
                                1000L
                        );

                if (
                        !EGL14.eglSwapBuffers(
                                display,
                                eglSurface
                        )
                ) {

                    throw new Exception(
                            "eglSwapBuffers failed"
                    );
                }

                // -----------------------------------------------
                // Drain available encoder output
                // -----------------------------------------------

                drainEncoder(
                        encoder,
                        muxer,
                        bufferInfo,
                        audioFormat,
                        new TrackState(
                                videoTrack,
                                audioTrack,
                                muxerStarted
                        )
                );

                /*
                 * TrackState is mutable through the returned
                 * state below.
                 */
                TrackState state =
                        drainEncoderWithState(
                                encoder,
                                muxer,
                                bufferInfo,
                                audioFormat,
                                videoTrack,
                                audioTrack,
                                muxerStarted
                        );

                videoTrack =
                        state.videoTrack;

                audioTrack =
                        state.audioTrack;

                muxerStarted =
                        state.muxerStarted;
            }

            // ----------------------------------------------------
            // End video input
            // ----------------------------------------------------

            encoder.signalEndOfInputStream();

            boolean endOfStream =
                    false;

            while (!endOfStream) {

                int outputIndex =
                        encoder.dequeueOutputBuffer(
                                bufferInfo,
                                10_000
                        );

                if (
                        outputIndex
                                ==
                        MediaCodec.INFO_TRY_AGAIN_LATER
                ) {

                    continue;
                }

                if (
                        outputIndex
                                ==
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED
                ) {

                    if (videoTrack < 0) {

                        videoTrack =
                                muxer.addTrack(
                                        encoder
                                                .getOutputFormat()
                                );
                    }

                    if (audioTrack < 0) {

                        audioTrack =
                                muxer.addTrack(
                                        audioFormat
                        );
                    }

                    if (!muxerStarted) {

                        muxer.start();

                        muxerStarted = true;
                    }

                    continue;
                }

                if (outputIndex >= 0) {

                    ByteBuffer outputBuffer =
                            encoder.getOutputBuffer(
                                    outputIndex
                            );

                    if (
                            outputBuffer != null
                                    &&
                            bufferInfo.size > 0
                                    &&
                            muxerStarted
                    ) {

                        outputBuffer.position(
                                bufferInfo.offset
                        );

                        outputBuffer.limit(
                                bufferInfo.offset
                                        +
                                bufferInfo.size
                        );

                        muxer.writeSampleData(
                                videoTrack,
                                outputBuffer,
                                bufferInfo
                        );
                    }

                    endOfStream =
                            (
                                    bufferInfo.flags
                                            &
                                    MediaCodec
                                            .BUFFER_FLAG_END_OF_STREAM
                            ) != 0;

                    encoder.releaseOutputBuffer(
                            outputIndex,
                            false
                    );
                }
            }

            // ----------------------------------------------------
            // Make sure muxer has started
            // ----------------------------------------------------

            if (!muxerStarted) {

                /*
                 * Normally MediaCodec always produces
                 * INFO_OUTPUT_FORMAT_CHANGED.
                 */
                throw new Exception(
                        "H.264 encoder produced no video track"
                );
            }

            // ----------------------------------------------------
            // Write AAC
            // ----------------------------------------------------

            writeAudioTrack(
                    muxer,
                    audioTrack,
                    audioFile
            );

        } finally {

            try {
                if (muxerStarted) {
                    muxer.stop();
                }
            } catch (Exception ignored) {
            }

            try {
                muxer.release();
            } catch (Exception ignored) {
            }

            try {
                encoder.stop();
            } catch (Exception ignored) {
            }

            try {
                encoder.release();
            } catch (Exception ignored) {
            }

            try {
                GLES20.glDeleteTextures(
                        2,
                        new int[]{
                                headingTexture,
                                verseTexture
                        },
                        0
                );
            } catch (Exception ignored) {
            }

            try {
                GLES20.glDeleteProgram(
                        program
                );
            } catch (Exception ignored) {
            }

            try {
                EGL14.eglMakeCurrent(
                        display,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_CONTEXT
                );
            } catch (Exception ignored) {
            }

            try {
                EGL14.eglDestroySurface(
                        display,
                        eglSurface
                );
            } catch (Exception ignored) {
            }

            try {
                EGL14.eglDestroyContext(
                        display,
                        eglContext
                );
            } catch (Exception ignored) {
            }

            try {
                EGL14.eglTerminate(
                        display
                );
            } catch (Exception ignored) {
            }

            try {
                inputSurface.release();
            } catch (Exception ignored) {
            }
        }
    }

    // ============================================================
    // DRAIN ENCODER
    // ============================================================

    private void drainEncoder(
            MediaCodec encoder,
            MediaMuxer muxer,
            MediaCodec.BufferInfo bufferInfo,
            MediaFormat audioFormat,
            TrackState state
    ) {
        // Intentionally lightweight.
        // The stateful drain is performed below.
    }

    private TrackState drainEncoderWithState(
            MediaCodec encoder,
            MediaMuxer muxer,
            MediaCodec.BufferInfo bufferInfo,
            MediaFormat audioFormat,
            int videoTrack,
            int audioTrack,
            boolean muxerStarted
    ) throws Exception {

        TrackState state =
                new TrackState(
                        videoTrack,
                        audioTrack,
                        muxerStarted
                );

        while (true) {

            int outputIndex =
                    encoder.dequeueOutputBuffer(
                            bufferInfo,
                            0
                    );

            if (
                    outputIndex
                            ==
                    MediaCodec.INFO_TRY_AGAIN_LATER
            ) {

                break;
            }

            if (
                    outputIndex
                            ==
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED
            ) {

                if (state.videoTrack < 0) {

                    state.videoTrack =
                            muxer.addTrack(
                                    encoder
                                            .getOutputFormat()
                            );
                }

                if (state.audioTrack < 0) {

                    state.audioTrack =
                            muxer.addTrack(
                                    audioFormat
                            );
                }

                if (!state.muxerStarted) {

                    muxer.start();

                    state.muxerStarted =
                            true;
                }

                continue;
            }

            if (outputIndex >= 0) {

                ByteBuffer outputBuffer =
                        encoder.getOutputBuffer(
                                outputIndex
                        );

                if (
                        outputBuffer != null
                                &&
                        bufferInfo.size > 0
                                &&
                        state.muxerStarted
                ) {

                    outputBuffer.position(
                            bufferInfo.offset
                    );

                    outputBuffer.limit(
                            bufferInfo.offset
                                    +
                            bufferInfo.size
                    );

                    muxer.writeSampleData(
                            state.videoTrack,
                            outputBuffer,
                            bufferInfo
                    );
                }

                encoder.releaseOutputBuffer(
                        outputIndex,
                        false
                );
            }
        }

        return state;
    }

    // ============================================================
    // DRAW FRAME
    // ============================================================

    private void drawFrame(
            int program,
            int positionHandle,
            int texCoordHandle,
            int textureHandle,
            int alphaHandle,
            FloatBuffer textureCoordinates,
            int headingTexture,
            int verseTexture,
            TextLayer heading,
            TextLayer verse,
            long timeUs
    ) {

        GLES20.glViewport(
                0,
                0,
                WIDTH,
                HEIGHT
        );

        GLES20.glClearColor(
                0f,
                0f,
                0f,
                1f
        );

        GLES20.glClear(
                GLES20.GL_COLOR_BUFFER_BIT
        );

        GLES20.glEnable(
                GLES20.GL_BLEND
        );

        GLES20.glBlendFunc(
                GLES20.GL_SRC_ALPHA,
                GLES20.GL_ONE_MINUS_SRC_ALPHA
        );

        GLES20.glUseProgram(
                program
        );

        // --------------------------------------------------------
        // Heading
        // --------------------------------------------------------

        float headingAlpha =
                getHeadingAlpha(
                        timeUs
                );

        float headingScale =
                getHeadingScale(
                        timeUs
                );

        float headingCenterY =
                getHeadingCenterY(
                        timeUs
                );

        drawLayer(
                program,
                positionHandle,
                texCoordHandle,
                textureHandle,
                alphaHandle,
                textureCoordinates,
                headingTexture,
                heading,
                WIDTH / 2f,
                headingCenterY,
                headingScale,
                headingAlpha
        );

        // --------------------------------------------------------
        // Verse
        // --------------------------------------------------------

        float verseAlpha =
                getVerseAlpha(
                        timeUs
                );

        float verseScale =
                getVerseScale(
                        timeUs
                );

        float verseCenterY =
                getVerseCenterY(
                        heading,
                        verse,
                        timeUs
                );

        drawLayer(
                program,
                positionHandle,
                texCoordHandle,
                textureHandle,
                alphaHandle,
                textureCoordinates,
                verseTexture,
                verse,
                WIDTH / 2f,
                verseCenterY,
                verseScale,
                verseAlpha
        );

        GLES20.glDisable(
                GLES20.GL_BLEND
        );
    }

    // ============================================================
    // DRAW TEXTURE
    // ============================================================

    private void drawLayer(
            int program,
            int positionHandle,
            int texCoordHandle,
            int textureHandle,
            int alphaHandle,
            FloatBuffer textureCoordinates,
            int texture,
            TextLayer layer,
            float centerX,
            float centerY,
            float scale,
            float alpha
    ) {

        if (alpha <= 0f) {
            return;
        }

        float halfWidth =
                (
                        layer.width
                                *
                        scale
                )
                        /
                WIDTH;

        float halfHeight =
                (
                        layer.height
                                *
                        scale
                )
                        /
                HEIGHT;

        float ndcCenterX =
                (
                        centerX
                                /
                        WIDTH
                )
                        *
                2f
                        -
                1f;

        float ndcCenterY =
                1f
                        -
                (
                        centerY
                                /
                        HEIGHT
                )
                        *
                2f;

        float[] vertices = {
                ndcCenterX - halfWidth,
                ndcCenterY - halfHeight,

                ndcCenterX + halfWidth,
                ndcCenterY - halfHeight,

                ndcCenterX - halfWidth,
                ndcCenterY + halfHeight,

                ndcCenterX + halfWidth,
                ndcCenterY + halfHeight
        };

        FloatBuffer vertexBuffer =
                createFloatBuffer(
                        vertices
                );

        vertexBuffer.position(0);

        GLES20.glEnableVertexAttribArray(
                positionHandle
        );

        GLES20.glVertexAttribPointer(
                positionHandle,
                2,
                GLES20.GL_FLOAT,
                false,
                0,
                vertexBuffer
        );

        textureCoordinates.position(0);

        GLES20.glEnableVertexAttribArray(
                texCoordHandle
        );

        GLES20.glVertexAttribPointer(
                texCoordHandle,
                2,
                GLES20.GL_FLOAT,
                false,
                0,
                textureCoordinates
        );

        GLES20.glActiveTexture(
                GLES20.GL_TEXTURE0
        );

        GLES20.glBindTexture(
                GLES20.GL_TEXTURE_2D,
                texture
        );

        GLES20.glUniform1i(
                textureHandle,
                0
        );

        GLES20.glUniform1f(
                alphaHandle,
                alpha
        );

        GLES20.glDrawArrays(
                GLES20.GL_TRIANGLE_STRIP,
                0,
                4
        );

        GLES20.glDisableVertexAttribArray(
                positionHandle
        );

        GLES20.glDisableVertexAttribArray(
                texCoordHandle
        );

        GLES20.glBindTexture(
                GLES20.GL_TEXTURE_2D,
                0
        );
    }

    // ============================================================
    // HEADING POSITION
    // ============================================================

    private float getHeadingCenterY(
            long timeUs
    ) {

        float headingHeight =
                HEADING_SIZE + 20f;

        float baseCenter =
                SAFE_TOP
                        +
                headingHeight / 2f;

        float progress =
                Math.min(
                        1f,
                        timeUs
                                /
                        (float)
                                HEADING_ANIMATION_US
                );

        float eased =
                1f
                        -
                (float) Math.pow(
                        1f - progress,
                        3f
                );

        return baseCenter
                -
                ENTRANCE_SHIFT
                        *
                (1f - eased);
    }

    // ============================================================
    // HEADING ALPHA
    // ============================================================

    private float getHeadingAlpha(
            long timeUs
    ) {

        float progress =
                timeUs
                        /
                (float)
                        HEADING_ANIMATION_US;

        return Math.max(
                0f,
                Math.min(
                        1f,
                        progress
                )
        );
    }

    // ============================================================
    // HEADING SCALE
    // ============================================================

    private float getHeadingScale(
            long timeUs
    ) {

        float progress =
                Math.min(
                        1f,
                        timeUs
                                /
                        (float)
                                HEADING_ANIMATION_US
                );

        return 0.96f
                +
                0.04f * progress;
    }

    // ============================================================
    // VERSE ALPHA
    // ============================================================

    private float getVerseAlpha(
            long timeUs
    ) {

        if (
                timeUs
                        <=
                HEADING_ANIMATION_US
        ) {

            return 0f;
        }

        float progress =
                (
                        timeUs
                                -
                        HEADING_ANIMATION_US
                )
                        /
                (float)
                        VERSE_ANIMATION_US;

        return Math.max(
                0f,
                Math.min(
                        1f,
                        progress
                )
        );
    }

    // ============================================================
    // VERSE SCALE
    // ============================================================

    private float getVerseScale(
            long timeUs
    ) {

        float breathing =
                (float) Math.sin(
                        (
                                timeUs
                                        /
                                1_000_000.0
                        )
                                *
                        Math.PI
                                *
                        0.5
                );

        return 1f
                +
                breathing
                        *
                BREATHING_SCALE;
    }

    // ============================================================
    // VERSE POSITION
    // ============================================================

    private float getVerseCenterY(
            TextLayer heading,
            TextLayer verse,
            long timeUs
    ) {

        float headingCenter =
                getHeadingCenterY(
                        timeUs
                );

        float headingBottom =
                headingCenter
                        +
                (
                        heading.height
                                /
                        2f
                );

        float verseTop =
                headingBottom
                        +
                HEADING_GAP;

        float desiredCenter =
                verseTop
                        +
                verse.height / 2f;

        float minimumCenter =
                SAFE_TOP
                        +
                verse.height / 2f;

        float maximumCenter =
                SAFE_BOTTOM
                        -
                verse.height / 2f;

        float center =
                Math.max(
                        minimumCenter,
                        Math.min(
                                maximumCenter,
                                desiredCenter
                        )
                );

        /*
         * Verse entrance animation.
         */
        if (
                timeUs
                        <
                HEADING_ANIMATION_US
                        +
                VERSE_ANIMATION_US
        ) {

            float start =
                    HEADING_ANIMATION_US;

            float progress =
                    (
                            timeUs
                                    -
                            start
                    )
                            /
                    (float)
                            VERSE_ANIMATION_US;

            progress =
                    Math.max(
                            0f,
                            Math.min(
                                    1f,
                                    progress
                            )
                    );

            float eased =
                    1f
                            -
                    (float) Math.pow(
                            1f - progress,
                            3f
                    );

            center +=
                    ENTRANCE_SHIFT
                            *
                    (1f - eased);
        }

        return Math.max(
                minimumCenter,
                Math.min(
                        maximumCenter,
                        center
                )
        );
    }

    // ============================================================
    // CREATE OPENGL PROGRAM
    // ============================================================

    private int createProgram()
            throws Exception {

        int vertexShader =
                compileShader(
                        GLES20.GL_VERTEX_SHADER,
                        VERTEX_SHADER
                );

        int fragmentShader =
                compileShader(
                        GLES20.GL_FRAGMENT_SHADER,
                        FRAGMENT_SHADER
                );

        int program =
                GLES20.glCreateProgram();

        GLES20.glAttachShader(
                program,
                vertexShader
        );

        GLES20.glAttachShader(
                program,
                fragmentShader
        );

        GLES20.glLinkProgram(
                program
        );

        int[] status =
                new int[1];

        GLES20.glGetProgramiv(
                program,
                GLES20.GL_LINK_STATUS,
                status,
                0
        );

        if (
                status[0]
                        !=
                GLES20.GL_TRUE
        ) {

            String error =
                    GLES20.glGetProgramInfoLog(
                            program
                    );

            GLES20.glDeleteProgram(
                    program
            );

            throw new Exception(
                    "OpenGL program link failed: "
                            +
                    error
            );
        }

        GLES20.glDeleteShader(
                vertexShader
        );

        GLES20.glDeleteShader(
                fragmentShader
        );

        return program;
    }

    // ============================================================
    // COMPILE SHADER
    // ============================================================

    private int compileShader(
            int type,
            String source
    ) throws Exception {

        int shader =
                GLES20.glCreateShader(
                        type
                );

        GLES20.glShaderSource(
                shader,
                source
        );

        GLES20.glCompileShader(
                shader
        );

        int[] status =
                new int[1];

        GLES20.glGetShaderiv(
                shader,
                GLES20.GL_COMPILE_STATUS,
                status,
                0
        );

        if (
                status[0]
                        !=
                GLES20.GL_TRUE
        ) {

            String error =
                    GLES20.glGetShaderInfoLog(
                            shader
                    );

            GLES20.glDeleteShader(
                    shader
            );

            throw new Exception(
                    "OpenGL shader compilation failed: "
                            +
                    error
            );
        }

        return shader;
    }

    // ============================================================
    // CREATE TEXTURE
    // ============================================================

    private int createTexture(
            Bitmap bitmap
    ) {

        int[] textures =
                new int[1];

        GLES20.glGenTextures(
                1,
                textures,
                0
        );

        int texture =
                textures[0];

        GLES20.glBindTexture(
                GLES20.GL_TEXTURE_2D,
                texture
        );

        GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_LINEAR
        );

        GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR
        );

        GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE
        );

        GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE
        );

        android.opengl.GLUtils.texImage2D(
                GLES20.GL_TEXTURE_2D,
                0,
                bitmap,
                0
        );

        GLES20.glBindTexture(
                GLES20.GL_TEXTURE_2D,
                0
        );

        return texture;
    }

    // ============================================================
    // FLOAT BUFFER
    // ============================================================

    private FloatBuffer createFloatBuffer(
            float[] values
    ) {

        ByteBuffer buffer =
                ByteBuffer.allocateDirect(
                        values.length * 4
                );

        buffer.order(
                ByteOrder.nativeOrder()
        );

        FloatBuffer floatBuffer =
                buffer.asFloatBuffer();

        floatBuffer.put(values);

        floatBuffer.position(0);

        return floatBuffer;
    }

    // ============================================================
    // AUDIO CACHE
    // ============================================================

    private File createAudioCache()
            throws Exception {

        File audioDirectory =
                new File(
                        context.getCacheDir(),
                        "bible_audio"
                );

        if (
                !audioDirectory.exists()
                        &&
                !audioDirectory.mkdirs()
        ) {

            throw new Exception(
                    "Unable to create audio cache directory"
            );
        }

        File cachedAudio =
                new File(
                        audioDirectory,
                        AUDIO_CACHE_NAME
                );

        if (
                cachedAudio.exists()
                        &&
                cachedAudio.length() > 0
        ) {

            return cachedAudio;
        }

        File source =
                new File(
                        audioDirectory,
                        "bg_source.mp3"
                );

        copyAsset(
                "bg.mp3",
                source
        );

        createEightSecondAac(
                source,
                cachedAudio
        );

        return cachedAudio;
    }

    // ============================================================
    // COPY ASSET
    // ============================================================

    private void copyAsset(
            String assetName,
            File destination
    ) throws Exception {

        try (
                InputStream input =
                        context.getAssets()
                                .open(assetName);

                FileOutputStream output =
                        new FileOutputStream(
                                destination
                        )
        ) {

            byte[] buffer =
                    new byte[64 * 1024];

            int count;

            while (
                    (count = input.read(buffer))
                            !=
                    -1
            ) {

                output.write(
                        buffer,
                        0,
                        count
                );
            }

            output.flush();
        }
    }

    // ============================================================
    // AUDIO FORMAT
    // ============================================================

    private MediaFormat getAudioFormat(
            File audioFile
    ) throws Exception {

        MediaExtractor extractor =
                new MediaExtractor();

        try {

            extractor.setDataSource(
                    audioFile.getAbsolutePath()
            );

            for (
                    int i = 0;
                    i < extractor.getTrackCount();
                    i++
            ) {

                MediaFormat format =
                        extractor.getTrackFormat(i);

                String mime =
                        format.getString(
                                MediaFormat.KEY_MIME
                        );

                if (
                        mime != null
                                &&
                        mime.startsWith("audio/")
                ) {

                    return format;
                }
            }

        } finally {

            extractor.release();
        }

        throw new Exception(
                "AAC audio track not found"
        );
    }

    // ============================================================
    // CREATE EXACT 8 SECOND AAC
    // ============================================================

    private void createEightSecondAac(
            File sourceMp3,
            File outputM4a
    ) throws Exception {

        MediaExtractor extractor =
                new MediaExtractor();

        extractor.setDataSource(
                sourceMp3.getAbsolutePath()
        );

        int audioTrack =
                findAudioTrack(
                        extractor
                );

        if (audioTrack < 0) {

            extractor.release();

            throw new Exception(
                    "bg.mp3 contains no audio track"
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

        extractor.selectTrack(
                audioTrack
        );

        MediaCodec decoder =
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

        MediaCodec encoder =
                MediaCodec.createEncoderByType(
                        "audio/mp4a-latm"
                );

        MediaFormat encoderFormat =
                MediaFormat.createAudioFormat(
                        "audio/mp4a-latm",
                        AAC_SAMPLE_RATE,
                        AAC_CHANNELS
                );

        encoderFormat.setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel
                        .AACObjectLC
        );

        encoderFormat.setInteger(
                MediaFormat.KEY_BIT_RATE,
                AAC_BITRATE
        );

        encoderFormat.setInteger(
                MediaFormat.KEY_MAX_INPUT_SIZE,
                16384
        );

        encoder.configure(
                encoderFormat,
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE
        );

        encoder.start();

        ByteArrayOutputStream pcm =
                new ByteArrayOutputStream();

        MediaCodec.BufferInfo decoderInfo =
                new MediaCodec.BufferInfo();

        boolean decoderDone =
                false;

        while (!decoderDone) {

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

                    inputBuffer.clear();

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
                                MediaCodec
                                        .BUFFER_FLAG_END_OF_STREAM
                        );

                    } else {

                        long pts =
                                extractor.getSampleTime();

                        decoder.queueInputBuffer(
                                inputIndex,
                                0,
                                sampleSize,
                                pts,
                                0
                        );

                        extractor.advance();
                    }
                }
            }

            int outputIndex =
                    decoder.dequeueOutputBuffer(
                            decoderInfo,
                            10_000
                    );

            if (
                    outputIndex
                            ==
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED
            ) {

                continue;
            }

            if (outputIndex >= 0) {

                ByteBuffer outputBuffer =
                        decoder.getOutputBuffer(
                                outputIndex
                        );

                if (
                        outputBuffer != null
                                &&
                        decoderInfo.size > 0
                ) {

                    outputBuffer.position(
                            decoderInfo.offset
                    );

                    outputBuffer.limit(
                            decoderInfo.offset
                                    +
                            decoderInfo.size
                    );

                    byte[] data =
                            new byte[
                                    decoderInfo.size
                            ];

                    outputBuffer.get(
                            data
                    );

                    pcm.write(
                            data
                    );
                }

                boolean eos =
                        (
                                decoderInfo.flags
                                        &
                                MediaCodec
                                        .BUFFER_FLAG_END_OF_STREAM
                        ) != 0;

                decoder.releaseOutputBuffer(
                        outputIndex,
                        false
                );

                if (eos) {
                    decoderDone = true;
                }
            }
        }

        decoder.stop();
        decoder.release();

        extractor.release();

        byte[] sourcePcm =
                pcm.toByteArray();

        if (sourcePcm.length == 0) {

            encoder.stop();
            encoder.release();

            throw new Exception(
                    "Unable to decode bg.mp3"
            );
        }

        /*
         * 16-bit PCM:
         *
         * 44100 samples/sec
         * 2 channels
         * 2 bytes/sample
         */
        int bytesPerSecond =
                AAC_SAMPLE_RATE
                        *
                AAC_CHANNELS
                        *
                2;

        int targetBytes =
                bytesPerSecond
                        *
                DURATION_SECONDS;

        /*
         * Loop or truncate source audio so it is
         * exactly 8 seconds.
         */
        byte[] exactPcm =
                new byte[targetBytes];

        for (
                int i = 0;
                i < targetBytes;
                i++
        ) {

            exactPcm[i] =
                    sourcePcm[
                            i % sourcePcm.length
                    ];
        }

        MediaMuxer muxer =
                new MediaMuxer(
                        outputM4a.getAbsolutePath(),
                        MediaMuxer.OutputFormat
                                .MUXER_OUTPUT_MPEG_4
                );

        int audioTrackOutput = -1;

        boolean muxerStarted =
                false;

        MediaCodec.BufferInfo encoderInfo =
                new MediaCodec.BufferInfo();

        int pcmOffset = 0;

        long ptsUs = 0;

        boolean inputDone = false;
        boolean outputDone = false;

        try {

            while (!outputDone) {

                // ----------------------------------------------
                // Encoder input
                // ----------------------------------------------

                if (!inputDone) {

                    int inputIndex =
                            encoder.dequeueInputBuffer(
                                    10_000
                            );

                    if (inputIndex >= 0) {

                        ByteBuffer inputBuffer =
                                encoder.getInputBuffer(
                                        inputIndex
                                );

                        if (inputBuffer != null) {

                            inputBuffer.clear();

                            int remaining =
                                    targetBytes
                                            -
                                    pcmOffset;

                            if (remaining <= 0) {

                                encoder.queueInputBuffer(
                                        inputIndex,
                                        0,
                                        0,
                                        ptsUs,
                                        MediaCodec
                                                .BUFFER_FLAG_END_OF_STREAM
                                );

                                inputDone = true;

                            } else {

                                int count =
                                        Math.min(
                                                inputBuffer.remaining(),
                                                remaining
                                        );

                                /*
                                 * Keep complete PCM frames.
                                 */
                                int frameSize =
                                        AAC_CHANNELS * 2;

                                count =
                                        count
                                                -
                                        (
                                                count
                                                        %
                                                frameSize
                                        );

                                if (count <= 0) {
                                    count = frameSize;
                                }

                                count =
                                        Math.min(
                                                count,
                                                remaining
                                        );

                                inputBuffer.put(
                                        exactPcm,
                                        pcmOffset,
                                        count
                                );

                                pcmOffset += count;

                                long durationUs =
                                        (
                                                count
                                                        *
                                                1_000_000L
                                        )
                                                /
                                        bytesPerSecond;

                                encoder.queueInputBuffer(
                                        inputIndex,
                                        0,
                                        count,
                                        ptsUs,
                                        0
                                );

                                ptsUs +=
                                        durationUs;
                            }
                        }
                    }
                }

                // ----------------------------------------------
                // Encoder output
                // ----------------------------------------------

                int outputIndex =
                        encoder.dequeueOutputBuffer(
                                encoderInfo,
                                10_000
                        );

                if (
                        outputIndex
                                ==
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED
                ) {

                    MediaFormat outputFormat =
                            encoder.getOutputFormat();

                    audioTrackOutput =
                            muxer.addTrack(
                                    outputFormat
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

                    if (
                            outputBuffer != null
                                    &&
                            encoderInfo.size > 0
                                    &&
                            muxerStarted
                    ) {

                        outputBuffer.position(
                                encoderInfo.offset
                        );

                        outputBuffer.limit(
                                encoderInfo.offset
                                        +
                                encoderInfo.size
                        );

                        muxer.writeSampleData(
                                audioTrackOutput,
                                outputBuffer,
                                encoderInfo
                        );
                    }

                    boolean eos =
                            (
                                    encoderInfo.flags
                                            &
                                    MediaCodec
                                            .BUFFER_FLAG_END_OF_STREAM
                            ) != 0;

                    encoder.releaseOutputBuffer(
                            outputIndex,
                            false
                    );

                    if (eos) {
                        outputDone = true;
                    }
                }
            }

        } finally {

            try {

                if (muxerStarted) {
                    muxer.stop();
                }

            } catch (Exception ignored) {
            }

            try {
                muxer.release();
            } catch (Exception ignored) {
            }

            try {
                encoder.stop();
            } catch (Exception ignored) {
            }

            try {
                encoder.release();
            } catch (Exception ignored) {
            }
        }
    }

    // ============================================================
    // FIND AUDIO TRACK
    // ============================================================

    private int findAudioTrack(
            MediaExtractor extractor
    ) {

        for (
                int i = 0;
                i < extractor.getTrackCount();
                i++
        ) {

            MediaFormat format =
                    extractor.getTrackFormat(i);

            String mime =
                    format.getString(
                            MediaFormat.KEY_MIME
                    );

            if (
                    mime != null
                            &&
                    mime.startsWith("audio/")
            ) {

                return i;
            }
        }

        return -1;
    }

    // ============================================================
    // WRITE AUDIO INTO FINAL MP4
    // ============================================================

    private void writeAudioTrack(
            MediaMuxer muxer,
            int audioTrack,
            File audioFile
    ) throws Exception {

        if (audioTrack < 0) {

            throw new Exception(
                    "Invalid audio track"
            );
        }

        MediaExtractor extractor =
                new MediaExtractor();

        extractor.setDataSource(
                audioFile.getAbsolutePath()
        );

        int sourceTrack =
                findAudioTrack(
                        extractor
                );

        if (sourceTrack < 0) {

            extractor.release();

            throw new Exception(
                    "Audio track missing"
            );
        }

        extractor.selectTrack(
                sourceTrack
        );

        ByteBuffer buffer =
                ByteBuffer.allocateDirect(
                        64 * 1024
                );

        MediaCodec.BufferInfo info =
                new MediaCodec.BufferInfo();

        try {

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

                info.offset = 0;
                info.size = size;
                info.presentationTimeUs =
                        pts;
                info.flags =
                        extractor.getSampleFlags();

                buffer.position(0);
                buffer.limit(size);

                muxer.writeSampleData(
                        audioTrack,
                        buffer,
                        info
                );

                extractor.advance();
            }

        } finally {

            extractor.release();
        }
    }

    // ============================================================
    // TEXT LAYER
    // ============================================================

    private static final class TextLayer {

        final Bitmap bitmap;

        final float width;
        final float height;

        TextLayer(
                Bitmap bitmap,
                float width,
                float height
        ) {

            this.bitmap = bitmap;
            this.width = width;
            this.height = height;
        }
    }

    // ============================================================
    // TRACK STATE
    // ============================================================

    private static final class TrackState {

        int videoTrack;
        int audioTrack;
        boolean muxerStarted;

        TrackState(
                int videoTrack,
                int audioTrack,
                boolean muxerStarted
        ) {

            this.videoTrack =
                    videoTrack;

            this.audioTrack =
                    audioTrack;

            this.muxerStarted =
                    muxerStarted;
        }
    }
}
