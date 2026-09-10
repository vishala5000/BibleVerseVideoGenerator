package com.bibledailyshine.videogenerator;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
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

    private static final int WIDTH = 1080;
    private static final int HEIGHT = 1920;

    private static final int FPS = 30;
    private static final int TOTAL_FRAMES = FPS * 8;

    private static final long VIDEO_DURATION_US = 8_000_000L;

    private static final int VIDEO_BITRATE = 5_000_000;

    private static final float SAFE_TOP = 200f;
    private static final float SAFE_BOTTOM = HEIGHT - 200f;

    private static final float TEXT_WRAP_WIDTH = 680f;
    private static final float TEXT_LEFT =
            (WIDTH - TEXT_WRAP_WIDTH) / 2f;

    private static final float HEADING_SIZE = 100f;
    private static final float HEADING_GAP = 65f;

    private static final float MAX_VERSE_SIZE = 76f;
    private static final float MIN_VERSE_SIZE = 26f;

    private static final float LINE_SPACING_MULTIPLIER = 1.05f;

    private static final long HEADING_ANIMATION_US = 450_000L;
    private static final long VERSE_ANIMATION_US = 650_000L;

    private static final float ENTRANCE_SHIFT = 18f;
    private static final float BREATHING_SCALE = 0.008f;

    private static final int AAC_SAMPLE_RATE = 44100;
    private static final int AAC_CHANNELS = 2;
    private static final int AAC_BITRATE = 192000;

    private static final String AUDIO_CACHE_NAME =
            "bible_daily_shine_bg_v4.m4a";

    private final Context context;

    private Typeface typeface;

    private static final float[] FULLSCREEN_VERTICES = {
            -1f, -1f,
             1f, -1f,
            -1f,  1f,
             1f,  1f
    };

    private static final float[] FULLSCREEN_TEX_COORDS = {
            0f, 1f,
            1f, 1f,
            0f, 0f,
            1f, 0f
    };

    private static final String VERTEX_SHADER =
            "attribute vec4 aPosition;" +
            "attribute vec2 aTexCoord;" +
            "varying vec2 vTexCoord;" +
            "void main() {" +
            "  gl_Position = aPosition;" +
            "  vTexCoord = aTexCoord;" +
            "}";

    private static final String FRAGMENT_SHADER =
            "precision mediump float;" +
            "uniform sampler2D uTexture;" +
            "varying vec2 vTexCoord;" +
            "void main() {" +
            "  gl_FragColor = texture2D(uTexture, vTexCoord);" +
            "}";

    public VideoGenerator(Context context) {
        this.context = context.getApplicationContext();

        /*
         * IMPORTANT:
         *
         * Do not use Typeface.createFromStream().
         * That method is not available in the Android SDK/API
         * combination used by this project.
         */
        try {
            typeface = Typeface.createFromAsset(
                    context.getAssets(),
                    "font.ttf"
            );
        } catch (Exception e) {
            typeface = Typeface.create(
                    Typeface.SANS_SERIF,
                    Typeface.NORMAL
            );
        }
    }

    public File generateVideo(
            String verseText,
            File outputFile
    ) throws Exception {

        if (verseText == null) {
            verseText = "";
        }

        verseText = verseText
                .replace("\r", " ")
                .replace("\n", " ")
                .trim();

        if (verseText.isEmpty()) {
            throw new IllegalArgumentException(
                    "Verse text is empty"
            );
        }

        File parent = outputFile.getParentFile();

        if (parent != null && !parent.exists()) {
            if (!parent.mkdirs() && !parent.exists()) {
                throw new Exception(
                        "Unable to create output directory"
                );
            }
        }

        File audioCache = createAudioCache();

        TextLayer heading = createHeadingLayer(
                "Bible Verse"
        );

        TextLayer verse = createVerseLayer(
                verseText
        );

        encodeVideo(
                heading,
                verse,
                audioCache,
                outputFile
        );

        return outputFile;
    }

    private TextLayer createHeadingLayer(
            String text
    ) {

        Paint paint = new Paint(
                Paint.ANTI_ALIAS_FLAG |
                Paint.SUBPIXEL_TEXT_FLAG
        );

        paint.setTypeface(typeface);
        paint.setTextSize(HEADING_SIZE);
        paint.setColor(Color.YELLOW);
        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.CENTER);

        Paint.FontMetrics fm = paint.getFontMetrics();

        float width = Math.min(
                TEXT_WRAP_WIDTH,
                paint.measureText(text) + 40f
        );

        float height =
                (fm.bottom - fm.top) + 40f;

        Bitmap bitmap = Bitmap.createBitmap(
                Math.max(1, (int) Math.ceil(width)),
                Math.max(1, (int) Math.ceil(height)),
                Bitmap.Config.ARGB_8888
        );

        Canvas canvas = new Canvas(bitmap);

        canvas.drawColor(Color.TRANSPARENT);

        float baseline =
                20f - fm.top;

        canvas.drawText(
                text,
                bitmap.getWidth() / 2f,
                baseline,
                paint
        );

        return new TextLayer(
                bitmap,
                paint.measureText(text),
                fm.bottom - fm.top,
                width,
                height
        );
    }

    private TextLayer createVerseLayer(
            String text
    ) {

        Paint paint = new Paint(
                Paint.ANTI_ALIAS_FLAG |
                Paint.SUBPIXEL_TEXT_FLAG
        );

        paint.setTypeface(typeface);
        paint.setColor(Color.WHITE);
        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.CENTER);

        float selectedSize = MIN_VERSE_SIZE;
        List<String> selectedLines = null;
        float selectedLineHeight = 0f;
        float selectedTotalHeight = 0f;

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

            Paint.FontMetrics fm =
                    paint.getFontMetrics();

            float lineHeight =
                    (fm.bottom - fm.top)
                            * LINE_SPACING_MULTIPLIER;

            float totalHeight =
                    lineHeight * lines.size();

            /*
             * Keep the verse comfortably inside the
             * vertical safe area.
             */
            float maximumAllowedHeight =
                    SAFE_BOTTOM -
                    SAFE_TOP -
                    HEADING_SIZE -
                    HEADING_GAP -
                    100f;

            if (totalHeight <= maximumAllowedHeight) {

                selectedSize = size;
                selectedLines = lines;
                selectedLineHeight = lineHeight;
                selectedTotalHeight = totalHeight;

                break;
            }
        }

        if (selectedLines == null) {

            paint.setTextSize(MIN_VERSE_SIZE);

            selectedLines =
                    wrapText(
                            paint,
                            text,
                            TEXT_WRAP_WIDTH
                    );

            Paint.FontMetrics fm =
                    paint.getFontMetrics();

            selectedLineHeight =
                    (fm.bottom - fm.top)
                            * LINE_SPACING_MULTIPLIER;

            selectedTotalHeight =
                    selectedLineHeight *
                    selectedLines.size();

            selectedSize = MIN_VERSE_SIZE;
        }

        paint.setTextSize(selectedSize);

        Paint.FontMetrics fm =
                paint.getFontMetrics();

        float bitmapHeight =
                selectedTotalHeight + 30f;

        Bitmap bitmap = Bitmap.createBitmap(
                (int) TEXT_WRAP_WIDTH,
                Math.max(
                        1,
                        (int) Math.ceil(bitmapHeight)
                ),
                Bitmap.Config.ARGB_8888
        );

        Canvas canvas = new Canvas(bitmap);

        canvas.drawColor(Color.TRANSPARENT);

        float firstBaseline =
                15f
                        - fm.top
                        + (
                        selectedLineHeight
                                -
                                (fm.bottom - fm.top)
                ) / 2f;

        for (int i = 0; i < selectedLines.size(); i++) {

            float baseline =
                    firstBaseline
                            + i * selectedLineHeight;

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
                selectedTotalHeight,
                TEXT_WRAP_WIDTH,
                bitmapHeight
        );
    }

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
                                <= maxWidth
                ) {

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
                    current
                            .toString()
                            + " "
                            + word;

            if (
                    paint.measureText(candidate)
                            <= maxWidth
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
                                <= maxWidth
                ) {

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

    private void splitLongWord(
            Paint paint,
            String word,
            float maxWidth,
            List<String> output
    ) {

        StringBuilder part =
                new StringBuilder();

        for (int i = 0; i < word.length(); i++) {

            char c = word.charAt(i);

            String candidate =
                    part.toString() + c;

            if (
                    part.length() > 0
                            &&
                    paint.measureText(candidate)
                            > maxWidth
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

    private void encodeVideo(
            TextLayer heading,
            TextLayer verse,
            File audioCache,
            File outputFile
    ) throws Exception {

        MediaCodec videoEncoder =
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

        videoEncoder.configure(
                videoFormat,
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE
        );

        Surface inputSurface =
                videoEncoder.createInputSurface();

        videoEncoder.start();

        EGLDisplay eglDisplay =
                EGL14.eglGetDisplay(
                        EGL14.EGL_DEFAULT_DISPLAY
                );

        if (
                eglDisplay
                        == EGL14.EGL_NO_DISPLAY
        ) {
            throw new Exception(
                    "Unable to get EGL display"
            );
        }

        int[] version = new int[2];

        if (
                !EGL14.eglInitialize(
                        eglDisplay,
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
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
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
                        eglDisplay,
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

        EGLConfig eglConfig =
                configs[0];

        int[] contextAttributes = {
                EGL14.EGL_CONTEXT_CLIENT_VERSION,
                2,
                EGL14.EGL_NONE
        };

        EGLContext eglContext =
                EGL14.eglCreateContext(
                        eglDisplay,
                        eglConfig,
                        EGL14.EGL_NO_CONTEXT,
                        contextAttributes,
                        0
                );

        if (
                eglContext
                        == EGL14.EGL_NO_CONTEXT
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
                        eglDisplay,
                        eglConfig,
                        inputSurface,
                        surfaceAttributes,
                        0
                );

        if (
                eglSurface
                        == EGL14.EGL_NO_SURFACE
        ) {

            throw new Exception(
                    "Unable to create EGL surface"
            );
        }

        if (
                !EGL14.eglMakeCurrent(
                        eglDisplay,
                        eglSurface,
                        eglSurface,
                        eglContext
                )
        ) {

            throw new Exception(
                    "Unable to make EGL context current"
            );
        }

        int program =
                createProgram(
                        VERTEX_SHADER,
                        FRAGMENT_SHADER
                );

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

        FloatBuffer vertexBuffer =
                createFloatBuffer(
                        FULLSCREEN_VERTICES
                );

        FloatBuffer texBuffer =
                createFloatBuffer(
                        FULLSCREEN_TEX_COORDS
                );

        int headingTexture =
                createTexture(
                        heading.bitmap
                );

        int verseTexture =
                createTexture(
                        verse.bitmap
                );

        MediaMuxer muxer =
                new MediaMuxer(
                        outputFile.getAbsolutePath(),
                        MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                );

        boolean muxerStarted = false;
        int videoTrack = -1;
        int audioTrack = -1;

        MediaCodec.BufferInfo bufferInfo =
                new MediaCodec.BufferInfo();

        videoEncoder.signalEndOfInputStream();

        /*
         * We need to render all frames before ending the
         * EGL surface. The encoder is drained after each
         * rendered frame so output buffers do not pile up.
         */
        videoEncoder.stop();

        /*
         * Restarting here is not allowed, so the actual
         * rendering/draining sequence is handled by the
         * dedicated encoding method below.
         *
         * Release current codec and rebuild cleanly.
         */
        try {
            videoEncoder.release();
        } catch (Exception ignored) {
        }

        try {
            GLES20.glDeleteTextures(
                    1,
                    new int[]{headingTexture},
                    0
            );

            GLES20.glDeleteTextures(
                    1,
                    new int[]{verseTexture},
                    0
            );
        } catch (Exception ignored) {
        }

        EGL14.eglMakeCurrent(
                eglDisplay,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT
        );

        EGL14.eglDestroySurface(
                eglDisplay,
                eglSurface
        );

        EGL14.eglDestroyContext(
                eglDisplay,
                eglContext
        );

        EGL14.eglTerminate(
                eglDisplay
        );

        inputSurface.release();

        muxer.release();

        /*
         * The real encoder pass is performed here.
         */
        encodeVideoPass(
                heading,
                verse,
                audioCache,
                outputFile
        );
    }

    private void encodeVideoPass(
            TextLayer heading,
            TextLayer verse,
            File audioCache,
            File outputFile
    ) throws Exception {

        MediaCodec videoEncoder =
                MediaCodec.createEncoderByType(
                        "video/avc"
                );

        MediaFormat format =
                MediaFormat.createVideoFormat(
                        "video/avc",
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
                1
        );

        videoEncoder.configure(
                format,
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE
        );

        Surface inputSurface =
                videoEncoder.createInputSurface();

        videoEncoder.start();

        EGLDisplay eglDisplay =
                EGL14.eglGetDisplay(
                        EGL14.EGL_DEFAULT_DISPLAY
                );

        int[] version = new int[2];

        if (
                !EGL14.eglInitialize(
                        eglDisplay,
                        version,
                        0,
                        version,
                        1
                )
        ) {

            throw new Exception(
                    "EGL initialization failed"
            );
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

        EGLConfig[] configs =
                new EGLConfig[1];

        int[] numConfigs =
                new int[1];

        EGL14.eglChooseConfig(
                eglDisplay,
                configAttributes,
                0,
                configs,
                0,
                1,
                numConfigs,
                0
        );

        EGLConfig eglConfig =
                configs[0];

        int[] contextAttributes = {
                EGL14.EGL_CONTEXT_CLIENT_VERSION,
                2,
                EGL14.EGL_NONE
        };

        EGLContext eglContext =
                EGL14.eglCreateContext(
                        eglDisplay,
                        eglConfig,
                        EGL14.EGL_NO_CONTEXT,
                        contextAttributes,
                        0
                );

        int[] surfaceAttributes = {
                EGL14.EGL_NONE
        };

        EGLSurface eglSurface =
                EGL14.eglCreateWindowSurface(
                        eglDisplay,
                        eglConfig,
                        inputSurface,
                        surfaceAttributes,
                        0
                );

        EGL14.eglMakeCurrent(
                eglDisplay,
                eglSurface,
                eglSurface,
                eglContext
        );

        int program =
                createProgram(
                        VERTEX_SHADER,
                        FRAGMENT_SHADER
                );

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

        FloatBuffer vertexBuffer =
                createFloatBuffer(
                        FULLSCREEN_VERTICES
                );

        FloatBuffer texBuffer =
                createFloatBuffer(
                        FULLSCREEN_TEX_COORDS
                );

        int headingTexture =
                createTexture(
                        heading.bitmap
                );

        int verseTexture =
                createTexture(
                        verse.bitmap
                );

        MediaMuxer muxer =
                new MediaMuxer(
                        outputFile.getAbsolutePath(),
                        MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                );

        int videoTrack = -1;
        int audioTrack = -1;

        boolean muxerStarted = false;

        MediaCodec.BufferInfo bufferInfo =
                new MediaCodec.BufferInfo();

        for (int frame = 0; frame < TOTAL_FRAMES; frame++) {

            long presentationTimeUs =
                    frame * 1_000_000L / FPS;

            drawFrame(
                    program,
                    positionHandle,
                    texCoordHandle,
                    textureHandle,
                    vertexBuffer,
                    texBuffer,
                    headingTexture,
                    verseTexture,
                    heading,
                    verse,
                    presentationTimeUs
            );

            EGLExt.eglPresentationTimeANDROID(
                    eglDisplay,
                    eglSurface,
                    presentationTimeUs * 1000L
            );

            EGL14.eglSwapBuffers(
                    eglDisplay,
                    eglSurface
            );

            while (true) {

                int outputIndex =
                        videoEncoder.dequeueOutputBuffer(
                                bufferInfo,
                                0
                        );

                if (
                        outputIndex
                                == MediaCodec.INFO_TRY_AGAIN_LATER
                ) {
                    break;
                }

                if (
                        outputIndex
                                == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED
                ) {

                    MediaFormat outputFormat =
                            videoEncoder.getOutputFormat();

                    videoTrack =
                            muxer.addTrack(
                                    outputFormat
                            );

                    MediaExtractor audioExtractor =
                            new MediaExtractor();

                    audioExtractor.setDataSource(
                            audioCache.getAbsolutePath()
                    );

                    int audioIndex = -1;

                    for (
                            int i = 0;
                            i < audioExtractor.getTrackCount();
                            i++
                    ) {

                        MediaFormat af =
                                audioExtractor.getTrackFormat(i);

                        String mime =
                                af.getString(
                                        MediaFormat.KEY_MIME
                                );

                        if (
                                mime != null
                                        &&
                                mime.startsWith("audio/")
                        ) {

                            audioIndex = i;
                            break;
                        }
                    }

                    if (audioIndex < 0) {
                        audioExtractor.release();
                        throw new Exception(
                                "AAC audio track missing"
                        );
                    }

                    MediaFormat audioFormat =
                            audioExtractor.getTrackFormat(
                                    audioIndex
                            );

                    audioTrack =
                            muxer.addTrack(
                                    audioFormat
                            );

                    audioExtractor.release();

                    muxer.start();

                    muxerStarted = true;

                    continue;
                }

                if (outputIndex >= 0) {

                    ByteBuffer encodedData =
                            videoEncoder.getOutputBuffer(
                                    outputIndex
                            );

                    if (
                            encodedData == null
                    ) {
                        throw new RuntimeException(
                                "Encoder output buffer is null"
                        );
                    }

                    if (
                            (bufferInfo.flags
                                    &
                                    MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
                                    != 0
                    ) {
                        bufferInfo.size = 0;
                    }

                    if (
                            bufferInfo.size > 0
                                    &&
                            muxerStarted
                    ) {

                        encodedData.position(
                                bufferInfo.offset
                        );

                        encodedData.limit(
                                bufferInfo.offset
                                        + bufferInfo.size
                        );

                        muxer.writeSampleData(
                                videoTrack,
                                encodedData,
                                bufferInfo
                        );
                    }

                    videoEncoder.releaseOutputBuffer(
                            outputIndex,
                            false
                    );

                    if (
                            (bufferInfo.flags
                                    &
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    != 0
                    ) {
                        break;
                    }
                }
            }
        }

        videoEncoder.signalEndOfInputStream();

        boolean videoDone = false;

        while (!videoDone) {

            int outputIndex =
                    videoEncoder.dequeueOutputBuffer(
                            bufferInfo,
                            10_000
                    );

            if (
                    outputIndex
                            == MediaCodec.INFO_TRY_AGAIN_LATER
            ) {
                continue;
            }

            if (
                    outputIndex
                            == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED
            ) {

                if (videoTrack < 0) {

                    MediaFormat outputFormat =
                            videoEncoder.getOutputFormat();

                    videoTrack =
                            muxer.addTrack(
                                    outputFormat
                            );

                    if (audioTrack < 0) {

                        MediaExtractor extractor =
                                new MediaExtractor();

                        extractor.setDataSource(
                                audioCache.getAbsolutePath()
                        );

                        int audioIndex = -1;

                        for (
                                int i = 0;
                                i < extractor.getTrackCount();
                                i++
                        ) {

                            MediaFormat af =
                                    extractor.getTrackFormat(i);

                            String mime =
                                    af.getString(
                                            MediaFormat.KEY_MIME
                                    );

                            if (
                                    mime != null
                                            &&
                                    mime.startsWith(
                                            "audio/"
                                    )
                            ) {

                                audioIndex = i;
                                break;
                            }
                        }

                        if (audioIndex >= 0) {

                            audioTrack =
                                    muxer.addTrack(
                                            extractor
                                                    .getTrackFormat(
                                                            audioIndex
                                                    )
                                    );
                        }

                        extractor.release();
                    }

                    muxer.start();
                    muxerStarted = true;
                }

                continue;
            }

            if (outputIndex >= 0) {

                ByteBuffer encodedData =
                        videoEncoder.getOutputBuffer(
                                outputIndex
                        );

                if (
                        encodedData != null
                                &&
                        bufferInfo.size > 0
                                &&
                        muxerStarted
                ) {

                    encodedData.position(
                            bufferInfo.offset
                    );

                    encodedData.limit(
                            bufferInfo.offset
                                    + bufferInfo.size
                    );

                    muxer.writeSampleData(
                            videoTrack,
                            encodedData,
                            bufferInfo
                    );
                }

                boolean eos =
                        (
                                bufferInfo.flags
                                        &
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        ) != 0;

                videoEncoder.releaseOutputBuffer(
                        outputIndex,
                        false
                );

                if (eos) {
                    videoDone = true;
                }
            }
        }

        /*
         * Write the cached AAC track.
         */
        if (
                muxerStarted
                        &&
                audioTrack >= 0
        ) {

            writeAudioTrack(
                    muxer,
                    audioTrack,
                    audioCache
            );
        }

        try {
            muxer.stop();
        } catch (Exception ignored) {
        }

        muxer.release();

        videoEncoder.stop();
        videoEncoder.release();

        try {
            GLES20.glDeleteTextures(
                    1,
                    new int[]{headingTexture},
                    0
            );

            GLES20.glDeleteTextures(
                    1,
                    new int[]{verseTexture},
                    0
            );
        } catch (Exception ignored) {
        }

        EGL14.eglMakeCurrent(
                eglDisplay,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT
        );

        EGL14.eglDestroySurface(
                eglDisplay,
                eglSurface
        );

        EGL14.eglDestroyContext(
                eglDisplay,
                eglContext
        );

        EGL14.eglTerminate(
                eglDisplay
        );

        inputSurface.release();
    }

    private void drawFrame(
            int program,
            int positionHandle,
            int texCoordHandle,
            int textureHandle,
            FloatBuffer vertexBuffer,
            FloatBuffer texBuffer,
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

        drawTexture(
                program,
                positionHandle,
                texCoordHandle,
                textureHandle,
                vertexBuffer,
                texBuffer,
                headingTexture,
                heading,
                getHeadingY(timeUs),
                getHeadingAlpha(timeUs),
                getHeadingScale(timeUs)
        );

        float verseY =
                getVerseCenterY(
                        heading,
                        verse,
                        timeUs
                );

        drawTexture(
                program,
                positionHandle,
                texCoordHandle,
                textureHandle,
                vertexBuffer,
                texBuffer,
                verseTexture,
                verse,
                verseY,
                getVerseAlpha(timeUs),
                getVerseScale(timeUs)
        );

        GLES20.glDisable(
                GLES20.GL_BLEND
        );
    }

    private float getHeadingY(
            long timeUs
    ) {

        float progress =
                Math.min(
                        1f,
                        timeUs /
                                (float) HEADING_ANIMATION_US
                );

        float eased =
                1f -
                        (float) Math.pow(
                                1f - progress,
                                3f
                        );

        float base =
                SAFE_TOP
                        +
                headingHeight();

        return base
                -
                ENTRANCE_SHIFT
                        * (1f - eased);
    }

    private float headingHeight() {
        return HEADING_SIZE + 20f;
    }

    private float getHeadingAlpha(
            long timeUs
    ) {

        float progress =
                Math.min(
                        1f,
                        timeUs /
                                (float) HEADING_ANIMATION_US
                );

        return progress;
    }

    private float getHeadingScale(
            long timeUs
    ) {

        float progress =
                Math.min(
                        1f,
                        timeUs /
                                (float) HEADING_ANIMATION_US
                );

        return 0.96f
                +
                0.04f * progress;
    }

    private float getVerseAlpha(
            long timeUs
    ) {

        if (
                timeUs <= HEADING_ANIMATION_US
        ) {
            return 0f;
        }

        float progress =
                Math.min(
                        1f,
                        (
                                timeUs
                                        -
                                HEADING_ANIMATION_US
                        )
                                /
                                (float)
                                        VERSE_ANIMATION_US
                );

        return progress;
    }

    private float getVerseScale(
            long timeUs
    ) {

        float breathing =
                (float) Math.sin(
                        timeUs /
                                1_000_000.0 *
                                Math.PI *
                                0.5
                );

        return 1f
                +
                breathing
                        * BREATHING_SCALE;
    }

    private float getVerseCenterY(
            TextLayer heading,
            TextLayer verse,
            long timeUs
    ) {

        float headingTop =
                SAFE_TOP;

        float headingBottom =
                headingTop
                        +
                        headingHeight();

        float desiredTop =
                headingBottom
                        +
                        HEADING_GAP;

        float desiredCenter =
                desiredTop
                        +
                        verse.height
                        / 2f;

        float halfHeight =
                verse.height / 2f;

        float minimumCenter =
                SAFE_TOP
                        +
                        halfHeight;

        float maximumCenter =
                SAFE_BOTTOM
                        -
                        halfHeight;

        float center =
                Math.max(
                        minimumCenter,
                        Math.min(
                                maximumCenter,
                                desiredCenter
                        )
                );

        if (
                timeUs
                        <
                        HEADING_ANIMATION_US
                                +
                        VERSE_ANIMATION_US
        ) {

            float progress =
                    Math.max(
                            0f,
                            timeUs
                                    -
                            HEADING_ANIMATION_US
                    )
                            /
                            (float)
                                    VERSE_ANIMATION_US;

            progress =
                    Math.min(
                            1f,
                            progress
                    );

            float eased =
                    1f -
                            (float) Math.pow(
                                    1f - progress,
                                    3f
                            );

            center +=
                    ENTRANCE_SHIFT
                            * (1f - eased);
        }

        return Math.max(
                minimumCenter,
                Math.min(
                        maximumCenter,
                        center
                )
        );
    }

    private void drawTexture(
            int program,
            int positionHandle,
            int texCoordHandle,
            int textureHandle,
            FloatBuffer vertexBuffer,
            FloatBuffer texBuffer,
            int texture,
            TextLayer layer,
            float centerY,
            float alpha,
            float scale
    ) {

        if (alpha <= 0f) {
            return;
        }

        GLES20.glUseProgram(
                program
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

        texBuffer.position(0);

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
                texture
        );

        GLES20.glUniform1i(
                textureHandle,
                0
        );

        /*
         * The bitmap is drawn full-screen by the shader,
         * so the bitmap itself must be positioned by changing
         * the vertex coordinates.
         *
         * We generate scaled NDC coordinates for this layer.
         */
        float halfWidth =
                layer.width
                        * scale
                        /
                        WIDTH;

        float halfHeight =
                layer.height
                        * scale
                        /
                        HEIGHT;

        float centerX =
                0f;

        float ndcCenterY =
                1f
                        -
                        2f *
                        centerY
                        /
                        HEIGHT;

        float[] vertices = {
                centerX - halfWidth,
                ndcCenterY - halfHeight,

                centerX + halfWidth,
                ndcCenterY - halfHeight,

                centerX - halfWidth,
                ndcCenterY + halfHeight,

                centerX + halfWidth,
                ndcCenterY + halfHeight
        };

        FloatBuffer layerVertices =
                createFloatBuffer(
                        vertices
                );

        GLES20.glVertexAttribPointer(
                positionHandle,
                2,
                GLES20.GL_FLOAT,
                false,
                0,
                layerVertices
        );

        /*
         * Alpha is applied through the texture itself.
         * The bitmap already contains transparent background.
         */
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

    private int createTexture(
            Bitmap bitmap
    ) {

        int[] textures = new int[1];

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

    private int createProgram(
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

        int[] linkStatus =
                new int[1];

        GLES20.glGetProgramiv(
                program,
                GLES20.GL_LINK_STATUS,
                linkStatus,
                0
        );

        if (
                linkStatus[0]
                        != GLES20.GL_TRUE
        ) {

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

        GLES20.glDeleteShader(
                vertexShader
        );

        GLES20.glDeleteShader(
                fragmentShader
        );

        return program;
    }

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
                        != GLES20.GL_TRUE
        ) {

            String error =
                    GLES20.glGetShaderInfoLog(
                            shader
                    );

            GLES20.glDeleteShader(
                    shader
            );

            throw new Exception(
                    "GL shader compilation failed: "
                            + error
            );
        }

        return shader;
    }

    private FloatBuffer createFloatBuffer(
            float[] values
    ) {

        ByteBuffer bb =
                ByteBuffer.allocateDirect(
                        values.length * 4
                );

        bb.order(
                ByteOrder.nativeOrder()
        );

        FloatBuffer buffer =
                bb.asFloatBuffer();

        buffer.put(values);

        buffer.position(0);

        return buffer;
    }

    private File createAudioCache()
            throws Exception {

        File cacheDir =
                new File(
                        context.getCacheDir(),
                        "audio"
                );

        if (
                !cacheDir.exists()
                        &&
                !cacheDir.mkdirs()
        ) {
            throw new Exception(
                    "Unable to create audio cache"
            );
        }

        File cacheFile =
                new File(
                        cacheDir,
                        AUDIO_CACHE_NAME
                );

        if (
                cacheFile.exists()
                        &&
                cacheFile.length() > 0
        ) {
            return cacheFile;
        }

        File source =
                new File(
                        cacheDir,
                        "bg_source.mp3"
                );

        copyAsset(
                "bg.mp3",
                source
        );

        createEightSecondAac(
                source,
                cacheFile
        );

        return cacheFile;
    }

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

            int read;

            while (
                    (read = input.read(buffer))
                            != -1
            ) {

                output.write(
                        buffer,
                        0,
                        read
                );
            }

            output.flush();
        }
    }

    private void createEightSecondAac(
            File sourceMp3,
            File outputM4a
    ) throws Exception {

        MediaExtractor extractor =
                new MediaExtractor();

        extractor.setDataSource(
                sourceMp3.getAbsolutePath()
        );

        int audioTrack = -1;

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

                audioTrack = i;
                break;
            }
        }

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

        extractor.selectTrack(
                audioTrack
        );

        String sourceMime =
                sourceFormat.getString(
                        MediaFormat.KEY_MIME
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

        MediaCodec encoder =
                MediaCodec.createEncoderByType(
                        "audio/mp4a-latm"
                );

        encoder.configure(
                encoderFormat,
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE
        );

        encoder.start();

        MediaMuxer muxer =
                new MediaMuxer(
                        outputM4a.getAbsolutePath(),
                        MediaMuxer.OutputFormat
                                .MUXER_OUTPUT_MPEG_4
                );

        int outputTrack = -1;
        boolean muxerStarted = false;

        MediaCodec.BufferInfo decoderInfo =
                new MediaCodec.BufferInfo();

        MediaCodec.BufferInfo encoderInfo =
                new MediaCodec.BufferInfo();

        ByteArrayOutputStream pcm =
                new ByteArrayOutputStream();

        boolean decoderDone = false;

        while (!decoderDone) {

            int inputIndex =
                    decoder.dequeueInputBuffer(
                            10_000
                    );

            if (inputIndex >= 0) {

                ByteBuffer input =
                        decoder.getInputBuffer(
                                inputIndex
                        );

                if (input != null) {

                    input.clear();

                    int sampleSize =
                            extractor.readSampleData(
                                    input,
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
                            == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED
            ) {
                continue;
            }

            if (outputIndex >= 0) {

                ByteBuffer output =
                        decoder.getOutputBuffer(
                                outputIndex
                        );

                if (
                        output != null
                                &&
                        decoderInfo.size > 0
                ) {

                    output.position(
                            decoderInfo.offset
                    );

                    output.limit(
                            decoderInfo.offset
                                    + decoderInfo.size
                    );

                    byte[] data =
                            new byte[
                                    decoderInfo.size
                            ];

                    output.get(data);

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

        byte[] pcmData =
                pcm.toByteArray();

        int bytesPerSecond =
                AAC_SAMPLE_RATE
                        *
                AAC_CHANNELS
                        *
                2;

        int targetBytes =
                bytesPerSecond * 8;

        byte[] exactPcm =
                new byte[targetBytes];

        if (pcmData.length > 0) {

            for (
                    int i = 0;
                    i < targetBytes;
                    i++
            ) {

                exactPcm[i] =
                        pcmData[
                                i % pcmData.length
                        ];
            }
        }

        int pcmOffset = 0;

        long presentationTimeUs = 0;

        boolean encoderInputDone = false;
        boolean encoderOutputDone = false;

        while (!encoderOutputDone) {

            if (!encoderInputDone) {

                int inputIndex =
                        encoder.dequeueInputBuffer(
                                10_000
                        );

                if (inputIndex >= 0) {

                    ByteBuffer input =
                            encoder.getInputBuffer(
                                    inputIndex
                            );

                    if (input != null) {

                        input.clear();

                        int remaining =
                                targetBytes
                                        -
                                pcmOffset;

                        if (remaining <= 0) {

                            encoder.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    0,
                                    presentationTimeUs,
                                    MediaCodec
                                            .BUFFER_FLAG_END_OF_STREAM
                            );

                            encoderInputDone = true;

                        } else {

                            int count =
                                    Math.min(
                                            input.remaining(),
                                            remaining
                                    );

                            input.put(
                                    exactPcm,
                                    pcmOffset,
                                    count
                            );

                            pcmOffset += count;

                            long durationUs =
                                    count
                                            *
                                    1_000_000L
                                            /
                                    bytesPerSecond;

                            encoder.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    count,
                                    presentationTimeUs,
                                    0
                            );

                            presentationTimeUs +=
                                    durationUs;
                        }
                    }
                }
            }

            int outputIndex =
                    encoder.dequeueOutputBuffer(
                            encoderInfo,
                            10_000
                    );

            if (
                    outputIndex
                            == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED
            ) {

                MediaFormat outputFormat =
                        encoder.getOutputFormat();

                outputTrack =
                        muxer.addTrack(
                                outputFormat
                        );

                muxer.start();

                muxerStarted = true;

                continue;
            }

            if (outputIndex >= 0) {

                ByteBuffer output =
                        encoder.getOutputBuffer(
                                outputIndex
                        );

                if (
                        output != null
                                &&
                        encoderInfo.size > 0
                                &&
                        muxerStarted
                ) {

                    output.position(
                            encoderInfo.offset
                    );

                    output.limit(
                            encoderInfo.offset
                                    + encoderInfo.size
                    );

                    muxer.writeSampleData(
                            outputTrack,
                            output,
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
                    encoderOutputDone = true;
                }
            }
        }

        try {
            if (muxerStarted) {
                muxer.stop();
            }
        } catch (Exception ignored) {
        }

        muxer.release();

        encoder.stop();
        encoder.release();
    }

    private void writeAudioTrack(
            MediaMuxer muxer,
            int audioTrack,
            File audioFile
    ) throws Exception {

        MediaExtractor extractor =
                new MediaExtractor();

        extractor.setDataSource(
                audioFile.getAbsolutePath()
        );

        int trackIndex = -1;

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

                trackIndex = i;
                break;
            }
        }

        if (trackIndex < 0) {
            extractor.release();

            throw new Exception(
                    "Audio track not found"
            );
        }

        extractor.selectTrack(
                trackIndex
        );

        ByteBuffer buffer =
                ByteBuffer.allocateDirect(
                        64 * 1024
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

            info.offset = 0;
            info.size = size;
            info.presentationTimeUs =
                    extractor.getSampleTime();

            int flags =
                    extractor.getSampleFlags();

            info.flags = flags;

            buffer.position(0);
            buffer.limit(size);

            muxer.writeSampleData(
                    audioTrack,
                    buffer,
                    info
            );

            extractor.advance();
        }

        extractor.release();
    }

    private static final class TextLayer {

        final Bitmap bitmap;

        final float width;
        final float height;

        final float logicalWidth;
        final float logicalHeight;

        TextLayer(
                Bitmap bitmap,
                float width,
                float height,
                float logicalWidth,
                float logicalHeight
        ) {

            this.bitmap = bitmap;
            this.width = width;
            this.height = height;
            this.logicalWidth = logicalWidth;
            this.logicalHeight = logicalHeight;
        }
    }
}
