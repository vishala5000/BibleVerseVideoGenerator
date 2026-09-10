package com.bibledailyshine.videogenerator;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaExtractor;
import android.media.MediaCodec.BufferInfo;
import android.net.Uri;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.EGLExt;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.view.Surface;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

public final class VideoGenerator {

    private static final String TAG = "BibleVideoGenerator";

    // ------------------------------------------------------------
    // VIDEO SETTINGS
    // ------------------------------------------------------------

    private static final int WIDTH = 1080;
    private static final int HEIGHT = 1920;

    private static final int FPS = 30;
    private static final int TOTAL_FRAMES = 8 * FPS;

    private static final long VIDEO_DURATION_US = 8_000_000L;

    private static final int VIDEO_BITRATE = 5_000_000;

    private static final String MIME_VIDEO = "video/avc";
    private static final String MIME_AUDIO = "audio/mp4a-latm";

    // ------------------------------------------------------------
    // SAFE AREA
    // ------------------------------------------------------------

    /*
     * Absolutely no text is allowed above 200 px
     * or below 1720 px.
     */
    private static final float SAFE_TOP = 200f;
    private static final float SAFE_BOTTOM = 1720f;

    /*
     * Exact requested text wrapping width.
     */
    private static final float TEXT_WRAP_WIDTH = 680f;

    /*
     * This gives the text 200 px left/right from the
     * 1080 px video edges.
     */
    private static final float TEXT_LEFT =
            (WIDTH - TEXT_WRAP_WIDTH) / 2f;

    // ------------------------------------------------------------
    // TYPOGRAPHY
    // ------------------------------------------------------------

    private static final float HEADING_SIZE = 100f;

    private static final float HEADING_GAP = 65f;

    /*
     * Much tighter than the old 1.25 spacing.
     */
    private static final float LINE_SPACING_MULTIPLIER = 1.05f;

    private static final float MIN_VERSE_SIZE = 26f;
    private static final float MAX_VERSE_SIZE = 76f;

    /*
     * Extra internal vertical safety.
     */
    private static final float TEXT_INTERNAL_MARGIN = 24f;

    // ------------------------------------------------------------
    // ANIMATION
    // ------------------------------------------------------------

    private static final long HEADING_ANIMATION_US = 450_000L;
    private static final long VERSE_ANIMATION_US = 650_000L;

    /*
     * Maximum movement of text.
     *
     * This is deliberately small so the animation can NEVER
     * push text outside the 200 px safe boundaries.
     */
    private static final float MAX_ENTRANCE_SHIFT = 18f;

    /*
     * Very subtle breathing effect.
     */
    private static final float MAX_BREATH_SCALE = 0.008f;

    // ------------------------------------------------------------
    // AUDIO
    // ------------------------------------------------------------

    private static final int AUDIO_BITRATE = 192_000;

    /*
     * Versioned cache name so an older generated audio cache
     * does not accidentally get reused after changing bg.mp3.
     */
    private static final String AUDIO_CACHE_NAME =
            "bible_daily_shine_bg_v4.m4a";

    // ------------------------------------------------------------
    // SHADERS
    // ------------------------------------------------------------

    private static final String VERTEX_SHADER =
            "attribute vec4 aPosition;\n" +
            "attribute vec2 aTexCoord;\n" +
            "uniform mat4 uMatrix;\n" +
            "varying vec2 vTexCoord;\n" +
            "void main() {\n" +
            "    gl_Position = uMatrix * aPosition;\n" +
            "    vTexCoord = aTexCoord;\n" +
            "}\n";

    private static final String FRAGMENT_SHADER =
            "precision mediump float;\n" +
            "uniform sampler2D uTexture;\n" +
            "uniform float uAlpha;\n" +
            "varying vec2 vTexCoord;\n" +
            "void main() {\n" +
            "    vec4 c = texture2D(uTexture, vTexCoord);\n" +
            "    gl_FragColor = vec4(c.rgb, c.a * uAlpha);\n" +
            "}\n";

    private VideoGenerator() {
    }

    // ============================================================
    // PUBLIC API
    // ============================================================

    public static File generate(
            Context context,
            String verse,
            File outputFile
    ) throws Exception {

        if (verse == null) {
            throw new IllegalArgumentException("Verse is null");
        }

        verse = verse.trim();

        if (verse.isEmpty()) {
            throw new IllegalArgumentException("Verse is empty");
        }

        File parent = outputFile.getParentFile();

        if (parent != null && !parent.exists()) {
            if (!parent.mkdirs() && !parent.exists()) {
                throw new Exception(
                        "Unable to create output directory: "
                                + parent.getAbsolutePath()
                );
            }
        }

        File audioCache = new File(
                context.getCacheDir(),
                AUDIO_CACHE_NAME
        );

        if (!audioCache.exists() || audioCache.length() < 1024) {
            createAudioCache(context, audioCache);
        }

        Bitmap headingBitmap = null;
        Bitmap verseBitmap = null;

        MediaCodec videoEncoder = null;
        MediaMuxer muxer = null;

        EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
        EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
        EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;

        Surface inputSurface = null;

        int program = 0;
        int headingTexture = 0;
        int verseTexture = 0;

        int videoTrack = -1;
        int audioTrack = -1;

        boolean muxerStarted = false;

        try {

            // ----------------------------------------------------
            // CREATE TEXT BITMAPS
            // ----------------------------------------------------

            TextLayers layers = createTextLayers(
                    context,
                    verse
            );

            headingBitmap = layers.heading;
            verseBitmap = layers.verse;

            // ----------------------------------------------------
            // CREATE H264 ENCODER
            // ----------------------------------------------------

            MediaFormat videoFormat = MediaFormat.createVideoFormat(
                    MIME_VIDEO,
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

            videoEncoder = MediaCodec.createEncoderByType(
                    MIME_VIDEO
            );

            videoEncoder.configure(
                    videoFormat,
                    null,
                    null,
                    MediaCodec.CONFIGURE_FLAG_ENCODE
            );

            inputSurface = videoEncoder.createInputSurface();

            videoEncoder.start();

            // ----------------------------------------------------
            // EGL
            // ----------------------------------------------------

            eglDisplay = EGL14.eglGetDisplay(
                    EGL14.EGL_DEFAULT_DISPLAY
            );

            if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
                throw new Exception("Unable to get EGL display");
            }

            int[] version = new int[2];

            if (!EGL14.eglInitialize(
                    eglDisplay,
                    version,
                    0,
                    version,
                    1
            )) {
                throw new Exception("Unable to initialize EGL");
            }

            int[] configAttributes = {
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE,
                    EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_NONE
            };

            EGLConfig[] configs = new EGLConfig[1];
            int[] numConfigs = new int[1];

            if (!EGL14.eglChooseConfig(
                    eglDisplay,
                    configAttributes,
                    0,
                    configs,
                    0,
                    1,
                    numConfigs,
                    0
            )) {
                throw new Exception("Unable to choose EGL config");
            }

            EGLConfig eglConfig = configs[0];

            int[] contextAttributes = {
                    EGL14.EGL_CONTEXT_CLIENT_VERSION,
                    2,
                    EGL14.EGL_NONE
            };

            eglContext = EGL14.eglCreateContext(
                    eglDisplay,
                    eglConfig,
                    EGL14.EGL_NO_CONTEXT,
                    contextAttributes,
                    0
            );

            if (eglContext == EGL14.EGL_NO_CONTEXT) {
                throw new Exception("Unable to create EGL context");
            }

            int[] surfaceAttributes = {
                    EGL14.EGL_NONE
            };

            eglSurface = EGL14.eglCreateWindowSurface(
                    eglDisplay,
                    eglConfig,
                    inputSurface,
                    surfaceAttributes,
                    0
            );

            if (eglSurface == EGL14.EGL_NO_SURFACE) {
                throw new Exception("Unable to create EGL window surface");
            }

            if (!EGL14.eglMakeCurrent(
                    eglDisplay,
                    eglSurface,
                    eglSurface,
                    eglContext
            )) {
                throw new Exception("Unable to make EGL current");
            }

            // ----------------------------------------------------
            // OPENGL
            // ----------------------------------------------------

            program = createProgram(
                    VERTEX_SHADER,
                    FRAGMENT_SHADER
            );

            if (program == 0) {
                throw new Exception("Unable to create GL program");
            }

            headingTexture = createTexture(headingBitmap);
            verseTexture = createTexture(verseBitmap);

            GLES20.glViewport(
                    0,
                    0,
                    WIDTH,
                    HEIGHT
            );

            GLES20.glDisable(GLES20.GL_DEPTH_TEST);

            GLES20.glEnable(GLES20.GL_BLEND);

            GLES20.glBlendFunc(
                    GLES20.GL_SRC_ALPHA,
                    GLES20.GL_ONE_MINUS_SRC_ALPHA
            );

            GLES20.glUseProgram(program);

            // ----------------------------------------------------
            // CREATE MUXER
            // ----------------------------------------------------

            muxer = new MediaMuxer(
                    outputFile.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            );

            // ----------------------------------------------------
            // ENCODE VIDEO
            // ----------------------------------------------------

            BufferInfo videoBufferInfo = new BufferInfo();

            boolean videoEncoderDone = false;

            long frameDurationUs =
                    1_000_000L / FPS;

            for (int frame = 0;
                 frame < TOTAL_FRAMES;
                 frame++) {

                long presentationTimeUs =
                        frame * frameDurationUs;

                drawFrame(
                        program,
                        headingTexture,
                        verseTexture,
                        layers,
                        presentationTimeUs
                );

                EGLExt.eglPresentationTimeANDROID(
                        eglDisplay,
                        eglSurface,
                        presentationTimeUs * 1000L
                );

                if (!EGL14.eglSwapBuffers(
                        eglDisplay,
                        eglSurface
                )) {
                    throw new Exception(
                            "eglSwapBuffers failed at frame "
                                    + frame
                    );
                }

                // Drain encoder after every frame.
                while (true) {

                    int outputIndex =
                            videoEncoder.dequeueOutputBuffer(
                                    videoBufferInfo,
                                    10_000
                            );

                    if (outputIndex ==
                            MediaCodec.INFO_TRY_AGAIN_LATER) {

                        break;

                    } else if (outputIndex ==
                            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {

                        if (muxerStarted) {
                            throw new Exception(
                                    "Video output format changed twice"
                            );
                        }

                        MediaFormat outputFormat =
                                videoEncoder.getOutputFormat();

                        videoTrack = muxer.addTrack(
                                outputFormat
                        );

                        // Audio track is added here too so muxer
                        // can start once both tracks are known.
                        audioTrack = addAudioTrack(
                                muxer,
                                audioCache
                        );

                        muxer.start();
                        muxerStarted = true;

                    } else if (outputIndex >= 0) {

                        ByteBuffer encodedData =
                                videoEncoder.getOutputBuffer(
                                        outputIndex
                                );

                        if (encodedData == null) {
                            throw new Exception(
                                    "Video encoder returned null buffer"
                            );
                        }

                        if ((videoBufferInfo.flags &
                                MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {

                            videoBufferInfo.size = 0;
                        }

                        if (videoBufferInfo.size > 0) {

                            if (!muxerStarted) {
                                throw new Exception(
                                        "Muxer not started before video sample"
                                );
                            }

                            encodedData.position(
                                    videoBufferInfo.offset
                            );

                            encodedData.limit(
                                    videoBufferInfo.offset
                                            + videoBufferInfo.size
                            );

                            muxer.writeSampleData(
                                    videoTrack,
                                    encodedData,
                                    videoBufferInfo
                            );
                        }

                        videoEncoder.releaseOutputBuffer(
                                outputIndex,
                                false
                        );

                        if ((videoBufferInfo.flags &
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {

                            videoEncoderDone = true;
                            break;
                        }
                    }
                }
            }

            // ----------------------------------------------------
            // SIGNAL END OF VIDEO INPUT
            // ----------------------------------------------------

            videoEncoder.signalEndOfInputStream();

            // ----------------------------------------------------
            // DRAIN FINAL VIDEO DATA
            // ----------------------------------------------------

            while (!videoEncoderDone) {

                int outputIndex =
                        videoEncoder.dequeueOutputBuffer(
                                videoBufferInfo,
                                20_000
                        );

                if (outputIndex ==
                        MediaCodec.INFO_TRY_AGAIN_LATER) {

                    continue;

                } else if (outputIndex ==
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {

                    if (muxerStarted) {
                        throw new Exception(
                                "Video output format changed after muxer start"
                        );
                    }

                    MediaFormat outputFormat =
                            videoEncoder.getOutputFormat();

                    videoTrack = muxer.addTrack(
                            outputFormat
                    );

                    audioTrack = addAudioTrack(
                            muxer,
                            audioCache
                    );

                    muxer.start();
                    muxerStarted = true;

                } else if (outputIndex >= 0) {

                    ByteBuffer encodedData =
                            videoEncoder.getOutputBuffer(
                                    outputIndex
                            );

                    if (encodedData == null) {
                        throw new Exception(
                                "Null final video output buffer"
                        );
                    }

                    if ((videoBufferInfo.flags &
                            MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {

                        videoBufferInfo.size = 0;
                    }

                    if (videoBufferInfo.size > 0) {

                        encodedData.position(
                                videoBufferInfo.offset
                        );

                        encodedData.limit(
                                videoBufferInfo.offset
                                        + videoBufferInfo.size
                        );

                        muxer.writeSampleData(
                                videoTrack,
                                encodedData,
                                videoBufferInfo
                        );
                    }

                    videoEncoder.releaseOutputBuffer(
                            outputIndex,
                            false
                    );

                    if ((videoBufferInfo.flags &
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {

                        videoEncoderDone = true;
                    }
                }
            }

            // ----------------------------------------------------
            // WRITE AUDIO
            // ----------------------------------------------------

            if (!muxerStarted) {
                throw new Exception(
                        "H.264 encoder produced no video samples"
                );
            }

            writeAudioSamples(
                    audioCache,
                    muxer,
                    audioTrack
            );

            // ----------------------------------------------------
            // FINISH
            // ----------------------------------------------------

            muxer.stop();
            muxerStarted = false;

            return outputFile;

        } finally {

            // ----------------------------------------------------
            // GL CLEANUP
            // ----------------------------------------------------

            if (headingTexture != 0) {
                int[] textures = {headingTexture};
                GLES20.glDeleteTextures(
                        1,
                        textures,
                        0
                );
            }

            if (verseTexture != 0) {
                int[] textures = {verseTexture};
                GLES20.glDeleteTextures(
                        1,
                        textures,
                        0
                );
            }

            if (program != 0) {
                GLES20.glDeleteProgram(program);
            }

            // ----------------------------------------------------
            // EGL CLEANUP
            // ----------------------------------------------------

            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {

                EGL14.eglMakeCurrent(
                        eglDisplay,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_CONTEXT
                );

                if (eglSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(
                            eglDisplay,
                            eglSurface
                    );
                }

                if (eglContext != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(
                            eglDisplay,
                            eglContext
                    );
                }

                EGL14.eglTerminate(eglDisplay);
            }

            if (inputSurface != null) {
                inputSurface.release();
            }

            if (videoEncoder != null) {
                try {
                    videoEncoder.stop();
                } catch (Exception ignored) {
                }

                try {
                    videoEncoder.release();
                } catch (Exception ignored) {
                }
            }

            if (muxer != null && muxerStarted) {
                try {
                    muxer.stop();
                } catch (Exception ignored) {
                }
            }

            if (muxer != null) {
                try {
                    muxer.release();
                } catch (Exception ignored) {
                }
            }

            if (headingBitmap != null) {
                headingBitmap.recycle();
            }

            if (verseBitmap != null) {
                verseBitmap.recycle();
            }
        }
    }

    // ============================================================
    // TEXT LAYERS
    // ============================================================

    private static final class TextLayers {

        Bitmap heading;
        Bitmap verse;

        float headingTop;
        float headingHeight;

        float verseTop;
        float verseHeight;
    }

    private static TextLayers createTextLayers(
            Context context,
            String verseText
    ) throws Exception {

        Typeface typeface;

        try {
            InputStream inputStream =
                    context.getAssets().open("font.ttf");

            typeface = Typeface.createFromStream(
                    inputStream
            );

            inputStream.close();

        } catch (Exception e) {

            typeface = Typeface.create(
                    Typeface.SANS_SERIF,
                    Typeface.NORMAL
            );
        }

        TextLayers result = new TextLayers();

        // --------------------------------------------------------
        // HEADING
        // --------------------------------------------------------

        Paint headingPaint = new Paint(
                Paint.ANTI_ALIAS_FLAG |
                        Paint.SUBPIXEL_TEXT_FLAG
        );

        headingPaint.setTypeface(typeface);
        headingPaint.setTextSize(HEADING_SIZE);
        headingPaint.setColor(Color.YELLOW);
        headingPaint.setTextAlign(Paint.Align.CENTER);
        headingPaint.setStyle(Paint.Style.FILL);

        Paint.FontMetrics headingFM =
                headingPaint.getFontMetrics();

        float headingActualHeight =
                headingFM.bottom - headingFM.top;

        float headingBitmapPadding = 20f;

        int headingWidth = WIDTH;
        int headingHeight =
                (int) Math.ceil(
                        headingActualHeight
                                + headingBitmapPadding * 2
                );

        Bitmap headingBitmap =
                Bitmap.createBitmap(
                        headingWidth,
                        headingHeight,
                        Bitmap.Config.ARGB_8888
                );

        Canvas headingCanvas =
                new Canvas(headingBitmap);

        headingCanvas.drawColor(
                Color.TRANSPARENT,
                android.graphics.PorterDuff.Mode.CLEAR
        );

        float headingBaseline =
                headingBitmapPadding
                        - headingFM.top;

        headingCanvas.drawText(
                "Bible Verse",
                WIDTH / 2f,
                headingBaseline,
                headingPaint
        );

        result.heading = headingBitmap;

        /*
         * Actual heading text begins safely below 200 px.
         */
        result.headingTop =
                SAFE_TOP + TEXT_INTERNAL_MARGIN;

        result.headingHeight =
                headingActualHeight;

        // --------------------------------------------------------
        // VERSE LAYOUT
        // --------------------------------------------------------

        Paint versePaint = new Paint(
                Paint.ANTI_ALIAS_FLAG |
                        Paint.SUBPIXEL_TEXT_FLAG
        );

        versePaint.setTypeface(typeface);
        versePaint.setColor(Color.WHITE);
        versePaint.setTextAlign(Paint.Align.CENTER);
        versePaint.setStyle(Paint.Style.FILL);

        float verseStart =
                result.headingTop
                        + result.headingHeight
                        + HEADING_GAP;

        float verseEnd =
                SAFE_BOTTOM
                        - TEXT_INTERNAL_MARGIN;

        float availableHeight =
                verseEnd - verseStart;

        VerseLayout layout =
                findBestVerseLayout(
                        versePaint,
                        verseText,
                        TEXT_WRAP_WIDTH,
                        availableHeight
                );

        versePaint.setTextSize(
                layout.textSize
        );

        versePaint.setColor(Color.WHITE);

        Paint.FontMetrics verseFM =
                versePaint.getFontMetrics();

        float lineHeight =
                layout.lineHeight;

        float actualTextHeight =
                lineHeight * layout.lines.size();

        float versePadding = 30f;

        int verseBitmapHeight =
                (int) Math.ceil(
                        actualTextHeight
                                + versePadding * 2
                );

        Bitmap verseBitmap =
                Bitmap.createBitmap(
                        (int) TEXT_WRAP_WIDTH,
                        verseBitmapHeight,
                        Bitmap.Config.ARGB_8888
                );

        Canvas verseCanvas =
                new Canvas(verseBitmap);

        verseCanvas.drawColor(
                Color.TRANSPARENT,
                android.graphics.PorterDuff.Mode.CLEAR
        );

        /*
         * Draw each line using tight line spacing.
         */
        float firstBaseline =
                versePadding
                        - verseFM.top;

        for (int i = 0;
             i < layout.lines.size();
             i++) {

            String line =
                    layout.lines.get(i);

            float baseline =
                    firstBaseline
                            + i * lineHeight;

            verseCanvas.drawText(
                    line,
                    TEXT_WRAP_WIDTH / 2f,
                    baseline,
                    versePaint
            );
        }

        result.verse = verseBitmap;

        /*
         * Center verse vertically in its own safe region.
         */
        result.verseTop =
                verseStart
                        + (
                        availableHeight
                                - actualTextHeight
                ) / 2f;

        result.verseHeight =
                actualTextHeight;

        /*
         * Final defensive validation.
         */
        if (result.headingTop < SAFE_TOP) {
            throw new Exception(
                    "Heading violates top safe area"
            );
        }

        if (result.headingTop
                + result.headingHeight
                >= result.verseTop) {

            throw new Exception(
                    "Heading and verse overlap"
            );
        }

        if (result.verseTop < SAFE_TOP) {
            throw new Exception(
                    "Verse violates top safe area"
            );
        }

        if (result.verseTop
                + result.verseHeight
                > SAFE_BOTTOM) {

            throw new Exception(
                    "Verse violates bottom safe area"
            );
        }

        return result;
    }

    // ============================================================
    // VERSE LAYOUT
    // ============================================================

    private static final class VerseLayout {

        List<String> lines;
        float textSize;
        float lineHeight;
    }

    private static VerseLayout findBestVerseLayout(
            Paint paint,
            String text,
            float maxWidth,
            float maxHeight
    ) throws Exception {

        String cleaned =
                text.replace("\r", " ")
                        .replace("\n", " ")
                        .trim();

        if (cleaned.isEmpty()) {
            cleaned = " ";
        }

        for (float size = MAX_VERSE_SIZE;
             size >= MIN_VERSE_SIZE;
             size -= 1f) {

            paint.setTextSize(size);

            List<String> lines =
                    wrapText(
                            paint,
                            cleaned,
                            maxWidth
                    );

            Paint.FontMetrics fm =
                    paint.getFontMetrics();

            float naturalHeight =
                    fm.bottom - fm.top;

            /*
             * Tight line spacing.
             */
            float lineHeight =
                    naturalHeight
                            * LINE_SPACING_MULTIPLIER;

            float totalHeight =
                    lineHeight
                            * lines.size();

            if (totalHeight <= maxHeight) {

                VerseLayout result =
                        new VerseLayout();

                result.lines = lines;
                result.textSize = size;
                result.lineHeight = lineHeight;

                return result;
            }
        }

        /*
         * Even minimum font did not fit.
         *
         * We still return the minimum size, but because the
         * available region is large, normal Bible verses should
         * comfortably fit.
         */
        paint.setTextSize(MIN_VERSE_SIZE);

        List<String> lines =
                wrapText(
                        paint,
                        cleaned,
                        maxWidth
                );

        Paint.FontMetrics fm =
                paint.getFontMetrics();

        VerseLayout result =
                new VerseLayout();

        result.lines = lines;
        result.textSize = MIN_VERSE_SIZE;

        result.lineHeight =
                (fm.bottom - fm.top)
                        * LINE_SPACING_MULTIPLIER;

        return result;
    }

    private static List<String> wrapText(
            Paint paint,
            String text,
            float maxWidth
    ) {

        List<String> result =
                new ArrayList<>();

        String[] words =
                text.split("\\s+");

        StringBuilder current =
                new StringBuilder();

        for (String word : words) {

            if (word.isEmpty()) {
                continue;
            }

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
                    current.toString()
                            + " "
                            + word;

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

        if (result.isEmpty()) {
            result.add("");
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

            if (part.length() > 0
                    && paint.measureText(candidate)
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
    // DRAW FRAME
    // ============================================================

    private static void drawFrame(
            int program,
            int headingTexture,
            int verseTexture,
            TextLayers layers,
            long timeUs
    ) {

        /*
         * Completely black background.
         */
        GLES20.glClearColor(
                0f,
                0f,
                0f,
                1f
        );

        GLES20.glClear(
                GLES20.GL_COLOR_BUFFER_BIT
        );

        GLES20.glUseProgram(program);

        // --------------------------------------------------------
        // HEADING ANIMATION
        // --------------------------------------------------------

        float headingProgress =
                clamp01(
                        timeUs /
                                (float) HEADING_ANIMATION_US
                );

        float headingEase =
                easeOutCubic(
                        headingProgress
                );

        float headingAlpha =
                smoothstep(
                        0f,
                        1f,
                        headingProgress
                );

        /*
         * Starts slightly lower and rises into position.
         */
        float headingShift =
                (1f - headingEase)
                        * MAX_ENTRANCE_SHIFT;

        /*
         * Small scale entrance.
         */
        float headingScale =
                0.985f
                        + 0.015f * headingEase;

        /*
         * Keep animation inside safe area.
         */
        drawTexture(
                program,
                headingTexture,
                WIDTH,
                layers.heading.getHeight(),
                WIDTH / 2f,
                layers.headingTop
                        + layers.heading.getHeight() / 2f
                        + headingShift,
                headingScale,
                headingAlpha
        );

        // --------------------------------------------------------
        // VERSE ANIMATION
        // --------------------------------------------------------

        float verseProgress =
                clamp01(
                        timeUs /
                                (float) VERSE_ANIMATION_US
                );

        float verseEase =
                easeOutCubic(
                        verseProgress
                );

        float verseAlpha =
                smoothstep(
                        0f,
                        1f,
                        verseProgress
                );

        float verseShift =
                (1f - verseEase)
                        * MAX_ENTRANCE_SHIFT;

        /*
         * Gentle scale entrance.
         */
        float verseScale =
                0.985f
                        + 0.015f * verseEase;

        // --------------------------------------------------------
        // GENTLE BREATHING
        // --------------------------------------------------------

        if (timeUs > VERSE_ANIMATION_US) {

            double seconds =
                    timeUs / 1_000_000.0;

            float breathing =
                    (float)
                            Math.sin(
                                    seconds
                                            * Math.PI
                                            * 0.55
                            );

            float breathScale =
                    1f
                            + breathing
                            * MAX_BREATH_SCALE;

            /*
             * Never exceed 1.01.
             */
            verseScale =
                    Math.min(
                            1.01f,
                            Math.max(
                                    0.985f,
                                    verseScale
                                            * breathScale
                            )
                    );
        }

        /*
         * Additional defensive clamping.
         */
        float verseCenterY =
                layers.verseTop
                        + layers.verseHeight / 2f;

        float halfHeight =
                layers.verseHeight
                        * verseScale
                        / 2f;

        float minimumCenter =
                SAFE_TOP
                        + TEXT_INTERNAL_MARGIN
                        + halfHeight;

        float maximumCenter =
                SAFE_BOTTOM
                        - TEXT_INTERNAL_MARGIN
                        - halfHeight;

        verseCenterY =
                Math.max(
                        minimumCenter,
                        Math.min(
                                maximumCenter,
                                verseCenterY
                                        + verseShift
                        )
                );

        drawTexture(
                program,
                verseTexture,
                layers.verse.getWidth(),
                layers.verse.getHeight(),
                WIDTH / 2f,
                verseCenterY,
                verseScale,
                verseAlpha
        );
    }

    // ============================================================
    // OPENGL TEXTURE
    // ============================================================

    private static int createTexture(
            Bitmap bitmap
    ) {

        int[] textureIds = new int[1];

        GLES20.glGenTextures(
                1,
                textureIds,
                0
        );

        int textureId =
                textureIds[0];

        GLES20.glBindTexture(
                GLES20.GL_TEXTURE_2D,
                textureId
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

        GLUtils.texImage2D(
                GLES20.GL_TEXTURE_2D,
                0,
                bitmap,
                0
        );

        GLES20.glBindTexture(
                GLES20.GL_TEXTURE_2D,
                0
        );

        return textureId;
    }

    // ============================================================
    // DRAW TEXTURE
    // ============================================================

    private static void drawTexture(
            int program,
            int textureId,
            float textureWidth,
            float textureHeight,
            float centerX,
            float centerY,
            float scale,
            float alpha
    ) {

        /*
         * Convert pixel dimensions into OpenGL NDC.
         */
        float halfWidth =
                (textureWidth * scale)
                        / WIDTH;

        float halfHeight =
                (textureHeight * scale)
                        / HEIGHT;

        float ndcCenterX =
                (centerX / WIDTH) * 2f - 1f;

        /*
         * Android bitmap/OpenGL coordinate system is flipped
         * vertically relative to the normal screen coordinate.
         */
        float ndcCenterY =
                1f
                        - (centerY / HEIGHT) * 2f;

        float left =
                ndcCenterX - halfWidth;

        float right =
                ndcCenterX + halfWidth;

        float top =
                ndcCenterY + halfHeight;

        float bottom =
                ndcCenterY - halfHeight;

        float[] vertices = {
                left,  bottom, 0f,
                right, bottom, 0f,
                left,  top,    0f,
                right, top,    0f
        };

        float[] texCoords = {
                0f, 1f,
                1f, 1f,
                0f, 0f,
                1f, 0f
        };

        FloatBuffer vertexBuffer =
                ByteBuffer.allocateDirect(
                                vertices.length * 4
                        )
                        .order(
                                ByteOrder.nativeOrder()
                        )
                        .asFloatBuffer();

        vertexBuffer.put(vertices);
        vertexBuffer.position(0);

        FloatBuffer texBuffer =
                ByteBuffer.allocateDirect(
                                texCoords.length * 4
                        )
                        .order(
                                ByteOrder.nativeOrder()
                        )
                        .asFloatBuffer();

        texBuffer.put(texCoords);
        texBuffer.position(0);

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

        int matrixHandle =
                GLES20.glGetUniformLocation(
                        program,
                        "uMatrix"
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

        float[] identity = {
                1f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f,
                0f, 0f, 1f, 0f,
                0f, 0f, 0f, 1f
        };

        GLES20.glUniformMatrix4fv(
                matrixHandle,
                1,
                false,
                identity,
                0
        );

        GLES20.glEnableVertexAttribArray(
                positionHandle
        );

        GLES20.glVertexAttribPointer(
                positionHandle,
                3,
                GLES20.GL_FLOAT,
                false,
                0,
                vertexBuffer
        );

        GLES20.glEnableVertexAttribArray(
                texCoordHandle
        );

        GLES20.glVertexAttribPointer(
                texCoordHandle,
                2,
                GLES20.GL_FLOAT,
                false,
                0,
                texBuffer
        );

        GLES20.glActiveTexture(
                GLES20.GL_TEXTURE0
        );

        GLES20.glBindTexture(
                GLES20.GL_TEXTURE_2D,
                textureId
        );

        GLES20.glUniform1i(
                textureHandle,
                0
        );

        GLES20.glUniform1f(
                alphaHandle,
                Math.max(
                        0f,
                        Math.min(
                                1f,
                                alpha
                        )
                )
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
    // GL PROGRAM
    // ============================================================

    private static int createProgram(
            String vertexShaderSource,
            String fragmentShaderSource
    ) throws Exception {

        int vertexShader =
                compileShader(
                        GLES20.GL_VERTEX_SHADER,
                        vertexShaderSource
                );

        int fragmentShader =
                compileShader(
                        GLES20.GL_FRAGMENT_SHADER,
                        fragmentShaderSource
                );

        int program =
                GLES20.glCreateProgram();

        if (program == 0) {
            throw new Exception(
                    "glCreateProgram failed"
            );
        }

        GLES20.glAttachShader(
                program,
                vertexShader
        );

        GLES20.glAttachShader(
                program,
                fragmentShader
        );

        GLES20.glLinkProgram(program);

        int[] linkStatus = new int[1];

        GLES20.glGetProgramiv(
                program,
                GLES20.GL_LINK_STATUS,
                linkStatus,
                0
        );

        GLES20.glDeleteShader(
                vertexShader
        );

        GLES20.glDeleteShader(
                fragmentShader
        );

        if (linkStatus[0] == 0) {

            String error =
                    GLES20.glGetProgramInfoLog(
                            program
                    );

            GLES20.glDeleteProgram(
                    program
            );

            throw new Exception(
                    "GL program link failed: "
                            + error
            );
        }

        return program;
    }

    private static int compileShader(
            int type,
            String source
    ) throws Exception {

        int shader =
                GLES20.glCreateShader(type);

        if (shader == 0) {
            throw new Exception(
                    "glCreateShader failed"
            );
        }

        GLES20.glShaderSource(
                shader,
                source
        );

        GLES20.glCompileShader(shader);

        int[] compiled = new int[1];

        GLES20.glGetShaderiv(
                shader,
                GLES20.GL_COMPILE_STATUS,
                compiled,
                0
        );

        if (compiled[0] == 0) {

            String error =
                    GLES20.glGetShaderInfoLog(
                            shader
                    );

            GLES20.glDeleteShader(
                    shader
            );

            throw new Exception(
                    "Shader compile failed: "
                            + error
            );
        }

        return shader;
    }

    // ============================================================
    // AUDIO TRACK
    // ============================================================

    private static int addAudioTrack(
            MediaMuxer muxer,
            File audioFile
    ) throws Exception {

        MediaExtractor extractor =
                new MediaExtractor();

        extractor.setDataSource(
                audioFile.getAbsolutePath()
        );

        int audioTrackIndex = -1;

        for (int i = 0;
             i < extractor.getTrackCount();
             i++) {

            MediaFormat format =
                    extractor.getTrackFormat(i);

            String mime =
                    format.getString(
                            MediaFormat.KEY_MIME
                    );

            if (mime != null
                    && mime.startsWith("audio/")) {

                audioTrackIndex =
                        muxer.addTrack(format);

                break;
            }
        }

        extractor.release();

        if (audioTrackIndex < 0) {
            throw new Exception(
                    "No audio track found in cache"
            );
        }

        return audioTrackIndex;
    }

    // ============================================================
    // WRITE AUDIO
    // ============================================================

    private static void writeAudioSamples(
            File audioFile,
            MediaMuxer muxer,
            int audioTrack
    ) throws Exception {

        MediaExtractor extractor =
                new MediaExtractor();

        extractor.setDataSource(
                audioFile.getAbsolutePath()
        );

        int selectedTrack = -1;

        for (int i = 0;
             i < extractor.getTrackCount();
             i++) {

            MediaFormat format =
                    extractor.getTrackFormat(i);

            String mime =
                    format.getString(
                            MediaFormat.KEY_MIME
                    );

            if (mime != null
                    && mime.startsWith("audio/")) {

                selectedTrack = i;
                break;
            }
        }

        if (selectedTrack < 0) {
            extractor.release();

            throw new Exception(
                    "No audio track available"
            );
        }

        extractor.selectTrack(
                selectedTrack
        );

        ByteBuffer buffer =
                ByteBuffer.allocateDirect(
                        256 * 1024
                );

        BufferInfo info =
                new BufferInfo();

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

            long presentationTimeUs =
                    extractor.getSampleTime();

            if (presentationTimeUs < 0) {
                break;
            }

            /*
             * Only the first 8 seconds are required.
             */
            if (presentationTimeUs
                    >= VIDEO_DURATION_US) {

                break;
            }

            info.offset = 0;
            info.size = size;
            info.presentationTimeUs =
                    presentationTimeUs;

            int sampleFlags =
                    extractor.getSampleFlags();

            info.flags = sampleFlags;

            muxer.writeSampleData(
                    audioTrack,
                    buffer,
                    info
            );

            extractor.advance();
        }

        extractor.release();
    }

    // ============================================================
    // CREATE AUDIO CACHE
    // ============================================================

    private static void createAudioCache(
            Context context,
            File output
    ) throws Exception {

        File tempPcm =
                new File(
                        context.getCacheDir(),
                        "bible_bg_temp.pcm"
                );

        File tempAac =
                new File(
                        context.getCacheDir(),
                        "bible_bg_temp.m4a"
                );

        if (tempPcm.exists()) {
            tempPcm.delete();
        }

        if (tempAac.exists()) {
            tempAac.delete();
        }

        // --------------------------------------------------------
        // LOAD MP3
        // --------------------------------------------------------

        File rawAudio =
                new File(
                        context.getCacheDir(),
                        "bg_source.mp3"
                );

        copyAsset(
                context,
                "bg.mp3",
                rawAudio
        );

        // --------------------------------------------------------
        // DECODE MP3 → PCM
        // --------------------------------------------------------

        MediaExtractor extractor =
                new MediaExtractor();

        extractor.setDataSource(
                rawAudio.getAbsolutePath()
        );

        int audioTrack = -1;
        MediaFormat inputFormat = null;

        for (int i = 0;
             i < extractor.getTrackCount();
             i++) {

            MediaFormat format =
                    extractor.getTrackFormat(i);

            String mime =
                    format.getString(
                            MediaFormat.KEY_MIME
                    );

            if (mime != null
                    && mime.startsWith("audio/")) {

                audioTrack = i;
                inputFormat = format;
                break;
            }
        }

        if (audioTrack < 0) {

            extractor.release();

            throw new Exception(
                    "bg.mp3 contains no audio track"
            );
        }

        extractor.selectTrack(
                audioTrack
        );

        String inputMime =
                inputFormat.getString(
                        MediaFormat.KEY_MIME
                );

        MediaCodec decoder =
                MediaCodec.createDecoderByType(
                        inputMime
                );

        decoder.configure(
                inputFormat,
                null,
                null,
                0
        );

        decoder.start();

        ByteArrayOutputStream pcm =
                new ByteArrayOutputStream();

        MediaCodec.BufferInfo bufferInfo =
                new MediaCodec.BufferInfo();

        boolean inputDone = false;
        boolean outputDone = false;

        long firstPtsUs = -1;

        int sampleRate =
                inputFormat.containsKey(
                        MediaFormat.KEY_SAMPLE_RATE
                )
                        ? inputFormat.getInteger(
                        MediaFormat.KEY_SAMPLE_RATE
                )
                        : 44100;

        int channelCount =
                inputFormat.containsKey(
                        MediaFormat.KEY_CHANNEL_COUNT
                )
                        ? inputFormat.getInteger(
                        MediaFormat.KEY_CHANNEL_COUNT
                )
                        : 2;

        while (!outputDone) {

            if (!inputDone) {

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

                        int size =
                                extractor.readSampleData(
                                        inputBuffer,
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
                                    extractor.getSampleTime();

                            if (firstPtsUs < 0) {
                                firstPtsUs = pts;
                            }

                            decoder.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    size,
                                    Math.max(
                                            0,
                                            pts - firstPtsUs
                                    ),
                                    extractor.getSampleFlags()
                            );

                            extractor.advance();
                        }
                    }
                }
            }

            int outputIndex =
                    decoder.dequeueOutputBuffer(
                            bufferInfo,
                            10_000
                    );

            if (outputIndex ==
                    MediaCodec.INFO_TRY_AGAIN_LATER) {

                continue;

            } else if (outputIndex ==
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {

                MediaFormat newFormat =
                        decoder.getOutputFormat();

                if (newFormat.containsKey(
                        MediaFormat.KEY_SAMPLE_RATE
                )) {
                    sampleRate =
                            newFormat.getInteger(
                                    MediaFormat.KEY_SAMPLE_RATE
                            );
                }

                if (newFormat.containsKey(
                        MediaFormat.KEY_CHANNEL_COUNT
                )) {
                    channelCount =
                            newFormat.getInteger(
                                    MediaFormat.KEY_CHANNEL_COUNT
                            );
                }

            } else if (outputIndex >= 0) {

                ByteBuffer outputBuffer =
                        decoder.getOutputBuffer(
                                outputIndex
                        );

                if (outputBuffer != null
                        && bufferInfo.size > 0) {

                    outputBuffer.position(
                            bufferInfo.offset
                    );

                    outputBuffer.limit(
                            bufferInfo.offset
                                    + bufferInfo.size
                    );

                    byte[] bytes =
                            new byte[bufferInfo.size];

                    outputBuffer.get(bytes);

                    pcm.write(
                            bytes,
                            0,
                            bytes.length
                    );
                }

                boolean end =
                        (bufferInfo.flags &
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                != 0;

                decoder.releaseOutputBuffer(
                        outputIndex,
                        false
                );

                if (end) {
                    outputDone = true;
                }
            }
        }

        decoder.stop();
        decoder.release();
        extractor.release();

        // --------------------------------------------------------
        // CREATE EXACTLY 8 SECONDS PCM
        // --------------------------------------------------------

        byte[] sourcePcm =
                pcm.toByteArray();

        int bytesPerSample = 2;

        int bytesPerSecond =
                sampleRate
                        * channelCount
                        * bytesPerSample;

        int targetBytes =
                bytesPerSecond
                        * 8;

        if (sourcePcm.length == 0) {
            throw new Exception(
                    "Decoded bg.mp3 produced no PCM"
            );
        }

        byte[] finalPcm =
                new byte[targetBytes];

        int position = 0;

        while (position < targetBytes) {

            int copy =
                    Math.min(
                            sourcePcm.length,
                            targetBytes - position
                    );

            System.arraycopy(
                    sourcePcm,
                    0,
                    finalPcm,
                    position,
                    copy
            );

            position += copy;
        }

        try (FileOutputStream fos =
                     new FileOutputStream(
                             tempPcm
                     )) {

            fos.write(finalPcm);
        }

        // --------------------------------------------------------
        // PCM → AAC
        // --------------------------------------------------------

        MediaFormat aacFormat =
                MediaFormat.createAudioFormat(
                        MIME_AUDIO,
                        sampleRate,
                        channelCount
                );

        aacFormat.setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel
                        .AACObjectLC
        );

        aacFormat.setInteger(
                MediaFormat.KEY_BIT_RATE,
                AUDIO_BITRATE
        );

        aacFormat.setInteger(
                MediaFormat.KEY_MAX_INPUT_SIZE,
                64 * 1024
        );

        MediaCodec encoder =
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

        MediaMuxer audioMuxer =
                new MediaMuxer(
                        tempAac.getAbsolutePath(),
                        MediaMuxer.OutputFormat
                                .MUXER_OUTPUT_MPEG_4
                );

        int trackIndex = -1;
        boolean muxerStarted = false;
        boolean encoderDone = false;

        MediaCodec.BufferInfo encoderInfo =
                new MediaCodec.BufferInfo();

        int pcmOffset = 0;

        long ptsUs = 0;

        long bytesPerFrame =
                channelCount
                        * bytesPerSample;

        int samplesPerInput =
                1024;

        int inputBytes =
                (int)
                        (samplesPerInput
                                * bytesPerFrame);

        while (!encoderDone) {

            int inputIndex =
                    encoder.dequeueInputBuffer(
                            10_000
                    );

            if (inputIndex >= 0
                    && pcmOffset < finalPcm.length) {

                ByteBuffer inputBuffer =
                        encoder.getInputBuffer(
                                inputIndex
                        );

                if (inputBuffer != null) {

                    inputBuffer.clear();

                    int count =
                            Math.min(
                                    inputBytes,
                                    finalPcm.length
                                            - pcmOffset
                            );

                    inputBuffer.put(
                            finalPcm,
                            pcmOffset,
                            count
                    );

                    long samples =
                            count
                                    / bytesPerFrame;

                    encoder.queueInputBuffer(
                            inputIndex,
                            0,
                            count,
                            ptsUs,
                            0
                    );

                    pcmOffset += count;

                    ptsUs +=
                            samples
                                    * 1_000_000L
                                    / sampleRate;
                }

            } else if (inputIndex >= 0
                    && pcmOffset >= finalPcm.length) {

                encoder.queueInputBuffer(
                        inputIndex,
                        0,
                        0,
                        ptsUs,
                        MediaCodec
                                .BUFFER_FLAG_END_OF_STREAM
                );

                pcmOffset = finalPcm.length + 1;
            }

            while (true) {

                int outputIndex =
                        encoder.dequeueOutputBuffer(
                                encoderInfo,
                                10_000
                        );

                if (outputIndex ==
                        MediaCodec.INFO_TRY_AGAIN_LATER) {

                    break;

                } else if (outputIndex ==
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {

                    if (muxerStarted) {
                        throw new Exception(
                                "AAC format changed twice"
                        );
                    }

                    MediaFormat outputFormat =
                            encoder.getOutputFormat();

                    trackIndex =
                            audioMuxer.addTrack(
                                    outputFormat
                            );

                    audioMuxer.start();

                    muxerStarted = true;

                } else if (outputIndex >= 0) {

                    ByteBuffer outputBuffer =
                            encoder.getOutputBuffer(
                                    outputIndex
                            );

                    if (outputBuffer == null) {
                        throw new Exception(
                                "AAC output buffer null"
                        );
                    }

                    if ((encoderInfo.flags &
                            MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
                            != 0) {

                        encoderInfo.size = 0;
                    }

                    if (encoderInfo.size > 0
                            && muxerStarted) {

                        outputBuffer.position(
                                encoderInfo.offset
                        );

                        outputBuffer.limit(
                                encoderInfo.offset
                                        + encoderInfo.size
                        );

                        audioMuxer.writeSampleData(
                                trackIndex,
                                outputBuffer,
                                encoderInfo
                        );
                    }

                    encoder.releaseOutputBuffer(
                            outputIndex,
                            false
                    );

                    if ((encoderInfo.flags &
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            != 0) {

                        encoderDone = true;
                        break;
                    }
                }
            }
        }

        if (muxerStarted) {
            audioMuxer.stop();
        }

        audioMuxer.release();

        encoder.stop();
        encoder.release();

        // --------------------------------------------------------
        // MOVE CACHE
        // --------------------------------------------------------

        if (output.exists()) {
            output.delete();
        }

        copyFile(
                tempAac,
                output
        );

        tempAac.delete();
        tempPcm.delete();
        rawAudio.delete();
    }

    // ============================================================
    // ASSET COPY
    // ============================================================

    private static void copyAsset(
            Context context,
            String assetName,
            File destination
    ) throws Exception {

        try (InputStream input =
                     context.getAssets()
                             .open(assetName);

             FileOutputStream output =
                     new FileOutputStream(
                             destination
                     )) {

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
    }

    // ============================================================
    // FILE COPY
    // ============================================================

    private static void copyFile(
            File source,
            File destination
    ) throws Exception {

        try (FileInputStream input =
                     new FileInputStream(source);

             FileOutputStream output =
                     new FileOutputStream(
                             destination
                     )) {

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
    }

    // ============================================================
    // ANIMATION HELPERS
    // ============================================================

    private static float clamp01(
            float value
    ) {

        if (value < 0f) {
            return 0f;
        }

        if (value > 1f) {
            return 1f;
        }

        return value;
    }

    private static float easeOutCubic(
            float value
    ) {

        value = clamp01(value);

        float inverse =
                1f - value;

        return 1f
                - inverse
                * inverse
                * inverse;
    }

    private static float smoothstep(
            float edge0,
            float edge1,
            float value
    ) {

        if (edge0 == edge1) {
            return value < edge0
                    ? 0f
                    : 1f;
        }

        float t =
                (value - edge0)
                        / (edge1 - edge0);

        t = clamp01(t);

        return t * t
                * (3f - 2f * t);
    }
}
