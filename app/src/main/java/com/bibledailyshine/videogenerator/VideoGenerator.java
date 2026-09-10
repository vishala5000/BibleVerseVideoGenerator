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
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.view.Surface;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

public final class VideoGenerator {

    private VideoGenerator() {
    }

    // ============================================================
    // VIDEO SETTINGS
    // ============================================================

    private static final int WIDTH = 1080;
    private static final int HEIGHT = 1920;

    private static final int FPS = 30;

    /*
     * Exactly 8 seconds at 30 FPS.
     */
    private static final int TOTAL_FRAMES = 240;

    private static final long FRAME_TIME_US = 33_333L;

    private static final long VIDEO_DURATION_US = 8_000_000L;

    /*
     * 5 Mbps.
     *
     * This is intentionally moderate for faster encoding on
     * lower-end Android devices.
     */
    private static final int VIDEO_BITRATE = 5_000_000;

    private static final String VIDEO_MIME = "video/avc";

    private static final String AUDIO_MIME = "audio/mp4a-latm";

    private static final String HEADING = "Bible Verse";

    private static final int SAFE_TOP = 200;

    private static final int SAFE_BOTTOM = 200;

    private static final int SIDE_MARGIN = 100;

    private static final int HEADING_SIZE = 100;

    private static final float HEADING_GAP = 70f;

    private static final int HEADING_COLOR = Color.YELLOW;

    private static final int VERSE_COLOR = Color.WHITE;

    /*
     * Increment this if bg.mp3 is changed in a new APK.
     */
    private static final String AUDIO_CACHE_NAME =
            "bible_daily_shine_bg_v3.m4a";

    private static final int EGL_RECORDABLE_ANDROID = 0x3142;

    // ============================================================
    // OPENGL SHADERS
    // ============================================================

    private static final String VERTEX_SHADER =
            "attribute vec4 aPosition;\n" +
            "attribute vec2 aTexCoord;\n" +
            "varying vec2 vTexCoord;\n" +
            "void main() {\n" +
            "    gl_Position = aPosition;\n" +
            "    vTexCoord = aTexCoord;\n" +
            "}\n";

    private static final String FRAGMENT_SHADER =
            "precision mediump float;\n" +
            "uniform sampler2D uTexture;\n" +
            "varying vec2 vTexCoord;\n" +
            "void main() {\n" +
            "    gl_FragColor = texture2D(uTexture, vTexCoord);\n" +
            "}\n";

    /*
     * Full-screen quad.
     */
    private static final float[] VERTICES = {
            -1f, -1f,
             1f, -1f,
            -1f,  1f,
             1f,  1f
    };

    /*
     * Bitmap coordinates are top-left based.
     * OpenGL texture coordinates are bottom-left based.
     *
     * This mapping keeps the generated text upright.
     */
    private static final float[] TEX_COORDS = {
            0f, 1f,
            1f, 1f,
            0f, 0f,
            1f, 0f
    };

    // ============================================================
    // PUBLIC API
    // ============================================================

    /*
     * MainActivity can continue calling:
     *
     * VideoGenerator.generate(
     *     context,
     *     verse,
     *     outputFile
     * );
     */
    public static void generate(
            Context context,
            String verse,
            File outputFile
    ) throws Exception {

        if (context == null) {
            throw new IllegalArgumentException(
                    "Context is null"
            );
        }

        if (verse == null ||
                verse.trim().isEmpty()) {

            throw new IllegalArgumentException(
                    "Verse is empty"
            );
        }

        if (outputFile == null) {
            throw new IllegalArgumentException(
                    "Output file is null"
            );
        }

        File parent =
                outputFile.getParentFile();

        if (parent != null &&
                !parent.exists()) {

            if (!parent.mkdirs() &&
                    !parent.exists()) {

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
         * Create/cache AAC once.
         *
         * Bulk videos reuse this same AAC file.
         */
        File audioFile =
                getCachedAudio(context);

        generateVideo(
                context,
                verse.trim(),
                audioFile,
                outputFile
        );
    }

    // ============================================================
    // MAIN VIDEO GENERATION
    // ============================================================

    private static void generateVideo(
            Context context,
            String verse,
            File audioFile,
            File outputFile
    ) throws Exception {

        Bitmap bitmap = null;

        MediaCodec encoder = null;

        Surface encoderSurface = null;

        EGLDisplay eglDisplay =
                EGL14.EGL_NO_DISPLAY;

        EGLContext eglContext =
                EGL14.EGL_NO_CONTEXT;

        EGLSurface eglSurface =
                EGL14.EGL_NO_SURFACE;

        EGLConfig eglConfig = null;

        MediaExtractor audioExtractor = null;

        MediaMuxer muxer = null;

        boolean muxerStarted = false;

        int videoTrack = -1;

        int audioTrack = -1;

        boolean videoFormatReceived = false;

        boolean videoSampleWritten = false;

        try {

            // ====================================================
            // CREATE FINAL TEXT BITMAP ONCE
            // ====================================================

            bitmap =
                    createTextBitmap(
                            context,
                            verse
                    );

            // ====================================================
            // CREATE H.264 ENCODER
            // ====================================================

            MediaFormat videoFormat =
                    MediaFormat.createVideoFormat(
                            VIDEO_MIME,
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

            encoder =
                    MediaCodec.createEncoderByType(
                            VIDEO_MIME
                    );

            encoder.configure(
                    videoFormat,
                    null,
                    null,
                    MediaCodec.CONFIGURE_FLAG_ENCODE
            );

            /*
             * IMPORTANT:
             *
             * This Surface must be rendered through EGL/OpenGL ES.
             */
            encoderSurface =
                    encoder.createInputSurface();

            encoder.start();

            // ====================================================
            // CREATE EGL
            // ====================================================

            EglObjects egl =
                    createEgl(
                            encoderSurface
                    );

            eglDisplay = egl.display;

            eglContext = egl.context;

            eglSurface = egl.surface;

            eglConfig = egl.config;

            // ====================================================
            // OPEN AUDIO
            // ====================================================

            audioExtractor =
                    new MediaExtractor();

            audioExtractor.setDataSource(
                    audioFile.getAbsolutePath()
            );

            int audioSourceTrack =
                    findAudioTrack(
                            audioExtractor
                    );

            if (audioSourceTrack < 0) {

                throw new IOException(
                        "Audio track missing from cached AAC"
                );
            }

            audioExtractor.selectTrack(
                    audioSourceTrack
            );

            MediaFormat audioFormat =
                    audioExtractor.getTrackFormat(
                            audioSourceTrack
                    );

            // ====================================================
            // CREATE FINAL MP4 MUXER
            // ====================================================

            muxer =
                    new MediaMuxer(
                            outputFile.getAbsolutePath(),
                            MediaMuxer.OutputFormat
                                    .MUXER_OUTPUT_MPEG_4
                    );

            // ====================================================
            // OPEN GL TEXTURE
            // ====================================================

            GlRenderer renderer =
                    new GlRenderer();

            renderer.initialize(
                    bitmap
            );

            // ====================================================
            // RENDER 240 FRAMES
            // ====================================================

            for (int frame = 0;
                 frame < TOTAL_FRAMES;
                 frame++) {

                long presentationTimeUs =
                        frame * FRAME_TIME_US;

                /*
                 * Draw bitmap to the MediaCodec input surface.
                 */
                renderer.draw(
                        WIDTH,
                        HEIGHT
                );

                /*
                 * Explicit frame timestamp.
                 */
                EGLExt.eglPresentationTimeANDROID(
                        eglDisplay,
                        eglSurface,
                        presentationTimeUs * 1000L
                );

                if (!EGL14.eglSwapBuffers(
                        eglDisplay,
                        eglSurface
                )) {

                    throw new IOException(
                            "EGL swapBuffers failed at frame " +
                                    frame
                    );
                }

                /*
                 * Drain encoder.
                 *
                 * The first INFO_OUTPUT_FORMAT_CHANGED gives
                 * us the real H.264 output format.
                 */
                while (true) {

                    MediaCodec.BufferInfo info =
                            new MediaCodec.BufferInfo();

                    int outputIndex =
                            encoder.dequeueOutputBuffer(
                                    info,
                                    frame == 0
                                            ? 10_000
                                            : 0
                            );

                    if (outputIndex ==
                            MediaCodec.INFO_TRY_AGAIN_LATER) {

                        break;
                    }

                    if (outputIndex ==
                            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {

                        if (videoFormatReceived) {

                            throw new IOException(
                                    "H.264 output format changed twice"
                            );
                        }

                        MediaFormat actualVideoFormat =
                                encoder.getOutputFormat();

                        videoTrack =
                                muxer.addTrack(
                                        actualVideoFormat
                                );

                        /*
                         * Add BOTH tracks before starting muxer.
                         */
                        audioTrack =
                                muxer.addTrack(
                                        audioFormat
                                );

                        muxer.start();

                        muxerStarted = true;

                        videoFormatReceived = true;

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

                            /*
                             * Ignore codec configuration data.
                             */
                            if ((info.flags &
                                    MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
                                    == 0) {

                                outputBuffer.position(
                                        info.offset
                                );

                                outputBuffer.limit(
                                        info.offset +
                                                info.size
                                );

                                /*
                                 * Never write beyond 8 seconds.
                                 */
                                if (info.presentationTimeUs
                                        < VIDEO_DURATION_US) {

                                    muxer.writeSampleData(
                                            videoTrack,
                                            outputBuffer,
                                            info
                                    );

                                    videoSampleWritten =
                                            true;
                                }
                            }
                        }

                        encoder.releaseOutputBuffer(
                                outputIndex,
                                false
                        );

                    } else {

                        /*
                         * Other INFO_* values.
                         */
                        break;
                    }
                }
            }

            // ====================================================
            // END OF VIDEO INPUT
            // ====================================================

            encoder.signalEndOfInputStream();

            // ====================================================
            // DRAIN FINAL H.264 OUTPUT
            // ====================================================

            boolean videoEOS = false;

            long drainStart =
                    System.currentTimeMillis();

            while (!videoEOS) {

                MediaCodec.BufferInfo info =
                        new MediaCodec.BufferInfo();

                int outputIndex =
                        encoder.dequeueOutputBuffer(
                                info,
                                20_000
                        );

                if (outputIndex ==
                        MediaCodec.INFO_TRY_AGAIN_LATER) {

                    if (System.currentTimeMillis()
                            - drainStart > 20_000) {

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

                        videoTrack =
                                muxer.addTrack(
                                        actualVideoFormat
                                );

                        audioTrack =
                                muxer.addTrack(
                                        audioFormat
                                );

                        muxer.start();

                        muxerStarted = true;

                        videoFormatReceived = true;
                    }

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
                                MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
                                == 0) {

                            outputBuffer.position(
                                    info.offset
                            );

                            outputBuffer.limit(
                                    info.offset +
                                            info.size
                            );

                            if (info.presentationTimeUs
                                    < VIDEO_DURATION_US) {

                                muxer.writeSampleData(
                                        videoTrack,
                                        outputBuffer,
                                        info
                                );

                                videoSampleWritten =
                                        true;
                            }
                        }
                    }

                    if ((info.flags &
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            != 0) {

                        videoEOS = true;
                    }

                    encoder.releaseOutputBuffer(
                            outputIndex,
                            false
                    );

                } else {

                    /*
                     * Keep waiting for EOS.
                     */
                }
            }

            // ====================================================
            // VALIDATE VIDEO
            // ====================================================

            if (!videoFormatReceived) {

                throw new IOException(
                        "H.264 encoder did not produce an output format"
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

            // ====================================================
            // WRITE AAC
            // ====================================================

            writeAudioSamples(
                    audioExtractor,
                    audioTrack,
                    muxer
            );

            // ====================================================
            // CLEAN EGL GL RESOURCES
            // ====================================================

            renderer.release();

        } finally {

            // ====================================================
            // EGL CLEANUP
            // ====================================================

            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {

                if (eglSurface !=
                        EGL14.EGL_NO_SURFACE) {

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
                }

                if (eglContext !=
                        EGL14.EGL_NO_CONTEXT) {

                    EGL14.eglDestroyContext(
                            eglDisplay,
                            eglContext
                    );
                }

                EGL14.eglReleaseThread();

                EGL14.eglTerminate(
                        eglDisplay
                );
            }

            // ====================================================
            // ENCODER
            // ====================================================

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

            // ====================================================
            // SURFACE
            // ====================================================

            if (encoderSurface != null) {

                try {
                    encoderSurface.release();
                } catch (Exception ignored) {
                }
            }

            // ====================================================
            // AUDIO EXTRACTOR
            // ====================================================

            if (audioExtractor != null) {

                try {
                    audioExtractor.release();
                } catch (Exception ignored) {
                }
            }

            // ====================================================
            // MUXER
            // ====================================================

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

            // ====================================================
            // BITMAP
            // ====================================================

            if (bitmap != null &&
                    !bitmap.isRecycled()) {

                bitmap.recycle();
            }
        }

        // ========================================================
        // FINAL FILE CHECK
        // ========================================================

        if (!outputFile.exists()) {

            throw new IOException(
                    "MP4 was not created: " +
                            outputFile.getAbsolutePath()
            );
        }

        if (outputFile.length() < 10_000) {

            throw new IOException(
                    "Generated MP4 is too small: " +
                            outputFile.length() +
                            " bytes"
            );
        }
    }

    // ============================================================
    // EGL CREATION
    // ============================================================

    private static EglObjects createEgl(
            Surface surface
    ) throws Exception {

        EGLDisplay display =
                EGL14.eglGetDisplay(
                        EGL14.EGL_DEFAULT_DISPLAY
                );

        if (display ==
                EGL14.EGL_NO_DISPLAY) {

            throw new IOException(
                    "Unable to obtain EGL display"
            );
        }

        int[] version = new int[2];

        if (!EGL14.eglInitialize(
                display,
                version,
                0,
                version,
                1
        )) {

            throw new IOException(
                    "Unable to initialize EGL"
            );
        }

        /*
         * RGBA8888 + window surface + recordable.
         */
        int[] configAttributes = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,

                EGL14.EGL_RENDERABLE_TYPE,
                EGL14.EGL_OPENGL_ES2_BIT,

                EGL14.EGL_SURFACE_TYPE,
                EGL14.EGL_WINDOW_BIT,

                EGL_RECORDABLE_ANDROID,
                1,

                EGL14.EGL_NONE
        };

        EGLConfig[] configs =
                new EGLConfig[1];

        int[] numConfigs = new int[1];

        if (!EGL14.eglChooseConfig(
                display,
                configAttributes,
                0,
                configs,
                0,
                1,
                numConfigs,
                0
        )) {

            throw new IOException(
                    "eglChooseConfig failed"
            );
        }

        if (numConfigs[0] <= 0 ||
                configs[0] == null) {

            throw new IOException(
                    "No suitable EGL configuration"
            );
        }

        EGLConfig config =
                configs[0];

        int[] contextAttributes = {
                EGL14.EGL_CONTEXT_CLIENT_VERSION,
                2,
                EGL14.EGL_NONE
        };

        EGLContext context =
                EGL14.eglCreateContext(
                        display,
                        config,
                        EGL14.EGL_NO_CONTEXT,
                        contextAttributes,
                        0
                );

        if (context ==
                EGL14.EGL_NO_CONTEXT) {

            throw new IOException(
                    "eglCreateContext failed: " +
                            EGL14.eglGetError()
            );
        }

        int[] surfaceAttributes = {
                EGL14.EGL_NONE
        };

        EGLSurface eglSurface =
                EGL14.eglCreateWindowSurface(
                        display,
                        config,
                        surface,
                        surfaceAttributes,
                        0
                );

        if (eglSurface ==
                EGL14.EGL_NO_SURFACE) {

            EGL14.eglDestroyContext(
                    display,
                    context
            );

            throw new IOException(
                    "eglCreateWindowSurface failed: " +
                            EGL14.eglGetError()
            );
        }

        if (!EGL14.eglMakeCurrent(
                display,
                eglSurface,
                eglSurface,
                context
        )) {

            EGL14.eglDestroySurface(
                    display,
                    eglSurface
            );

            EGL14.eglDestroyContext(
                    display,
                    context
            );

            throw new IOException(
                    "eglMakeCurrent failed: " +
                            EGL14.eglGetError()
            );
        }

        return new EglObjects(
                display,
                context,
                eglSurface,
                config
        );
    }

    // ============================================================
    // OPENGL RENDERER
    // ============================================================

    private static final class GlRenderer {

        private int program;

        private int textureId;

        private int positionLocation;

        private int texCoordLocation;

        private int textureLocation;

        private FloatBuffer vertexBuffer;

        private FloatBuffer texBuffer;

        void initialize(
                Bitmap bitmap
        ) throws Exception {

            vertexBuffer =
                    ByteBuffer
                            .allocateDirect(
                                    VERTICES.length * 4
                            )
                            .order(
                                    ByteOrder.nativeOrder()
                            )
                            .asFloatBuffer();

            vertexBuffer.put(
                    VERTICES
            );

            vertexBuffer.position(0);

            texBuffer =
                    ByteBuffer
                            .allocateDirect(
                                    TEX_COORDS.length * 4
                            )
                            .order(
                                    ByteOrder.nativeOrder()
                            )
                            .asFloatBuffer();

            texBuffer.put(
                    TEX_COORDS
            );

            texBuffer.position(0);

            int vertexShader =
                    loadShader(
                            GLES20.GL_VERTEX_SHADER,
                            VERTEX_SHADER
                    );

            int fragmentShader =
                    loadShader(
                            GLES20.GL_FRAGMENT_SHADER,
                            FRAGMENT_SHADER
                    );

            program =
                    GLES20.glCreateProgram();

            checkGl(
                    "glCreateProgram"
            );

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

            if (linkStatus[0] == 0) {

                String log =
                        GLES20.glGetProgramInfoLog(
                                program
                        );

                GLES20.glDeleteProgram(
                        program
                );

                throw new IOException(
                        "OpenGL program link failed: " +
                                log
                );
            }

            GLES20.glDeleteShader(
                    vertexShader
            );

            GLES20.glDeleteShader(
                    fragmentShader
            );

            positionLocation =
                    GLES20.glGetAttribLocation(
                            program,
                            "aPosition"
                    );

            texCoordLocation =
                    GLES20.glGetAttribLocation(
                            program,
                            "aTexCoord"
                    );

            textureLocation =
                    GLES20.glGetUniformLocation(
                            program,
                            "uTexture"
                    );

            int[] textures =
                    new int[1];

            GLES20.glGenTextures(
                    1,
                    textures,
                    0
            );

            textureId =
                    textures[0];

            if (textureId == 0) {

                throw new IOException(
                        "Could not create OpenGL texture"
                );
            }

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

            checkGl(
                    "texture upload"
            );
        }

        void draw(
                int width,
                int height
        ) {

            GLES20.glViewport(
                    0,
                    0,
                    width,
                    height
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

            GLES20.glUseProgram(
                    program
            );

            vertexBuffer.position(0);

            GLES20.glEnableVertexAttribArray(
                    positionLocation
            );

            GLES20.glVertexAttribPointer(
                    positionLocation,
                    2,
                    GLES20.GL_FLOAT,
                    false,
                    0,
                    vertexBuffer
            );

            texBuffer.position(0);

            GLES20.glEnableVertexAttribArray(
                    texCoordLocation
            );

            GLES20.glVertexAttribPointer(
                    texCoordLocation,
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
                    textureLocation,
                    0
            );

            GLES20.glDrawArrays(
                    GLES20.GL_TRIANGLE_STRIP,
                    0,
                    4
            );

            GLES20.glBindTexture(
                    GLES20.GL_TEXTURE_2D,
                    0
            );

            GLES20.glDisableVertexAttribArray(
                    positionLocation
            );

            GLES20.glDisableVertexAttribArray(
                    texCoordLocation
            );

            checkGl(
                    "draw"
            );
        }

        void release() {

            if (textureId != 0) {

                GLES20.glDeleteTextures(
                        1,
                        new int[]{textureId},
                        0
                );

                textureId = 0;
            }

            if (program != 0) {

                GLES20.glDeleteProgram(
                        program
                );

                program = 0;
            }
        }

        private static int loadShader(
                int type,
                String source
        ) throws Exception {

            int shader =
                    GLES20.glCreateShader(
                            type
                    );

            if (shader == 0) {

                throw new IOException(
                        "glCreateShader failed"
                );
            }

            GLES20.glShaderSource(
                    shader,
                    source
            );

            GLES20.glCompileShader(
                    shader
            );

            int[] compiled =
                    new int[1];

            GLES20.glGetShaderiv(
                    shader,
                    GLES20.GL_COMPILE_STATUS,
                    compiled,
                    0
            );

            if (compiled[0] == 0) {

                String log =
                        GLES20.glGetShaderInfoLog(
                                shader
                        );

                GLES20.glDeleteShader(
                        shader
                );

                throw new IOException(
                        "OpenGL shader compile failed: " +
                                log
                );
            }

            return shader;
        }

        private static void checkGl(
                String operation
        ) throws RuntimeException {

            int error =
                    GLES20.glGetError();

            if (error != GLES20.GL_NO_ERROR) {

                throw new RuntimeException(
                        "OpenGL error during " +
                                operation +
                                ": 0x" +
                                Integer.toHexString(error)
                );
            }
        }
    }

    // ============================================================
    // EGL HOLDER
    // ============================================================

    private static final class EglObjects {

        final EGLDisplay display;

        final EGLContext context;

        final EGLSurface surface;

        final EGLConfig config;

        EglObjects(
                EGLDisplay display,
                EGLContext context,
                EGLSurface surface,
                EGLConfig config
        ) {

            this.display = display;

            this.context = context;

            this.surface = surface;

            this.config = config;
        }
    }

    // ============================================================
    // CREATE TEXT BITMAP
    // ============================================================

    private static Bitmap createTextBitmap(
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

        canvas.drawColor(
                Color.BLACK
        );

        Typeface typeface;

        try {

            typeface =
                    Typeface.createFromAsset(
                            context.getAssets(),
                            "font.ttf"
                    );

        } catch (Exception e) {

            /*
             * Fallback if font.ttf is missing.
             */
            typeface =
                    Typeface.create(
                            Typeface.DEFAULT,
                            Typeface.NORMAL
                    );
        }

        // ========================================================
        // HEADING
        // ========================================================

        Paint headingPaint =
                new Paint(
                        Paint.ANTI_ALIAS_FLAG |
                                Paint.SUBPIXEL_TEXT_FLAG
                );

        headingPaint.setTypeface(
                typeface
        );

        headingPaint.setColor(
                HEADING_COLOR
        );

        headingPaint.setTextAlign(
                Paint.Align.CENTER
        );

        headingPaint.setTextSize(
                HEADING_SIZE
        );

        Paint.FontMetrics headingMetrics =
                headingPaint.getFontMetrics();

        float headingBaseline =
                SAFE_TOP -
                        headingMetrics.top;

        canvas.drawText(
                HEADING,
                WIDTH / 2f,
                headingBaseline,
                headingPaint
        );

        // ========================================================
        // VERSE
        // ========================================================

        Paint versePaint =
                new Paint(
                        Paint.ANTI_ALIAS_FLAG |
                                Paint.SUBPIXEL_TEXT_FLAG
                );

        versePaint.setTypeface(
                typeface
        );

        versePaint.setColor(
                VERSE_COLOR
        );

        versePaint.setTextAlign(
                Paint.Align.CENTER
        );

        float maxWidth =
                WIDTH -
                        (SIDE_MARGIN * 2);

        float verseTop =
                headingBaseline +
                        headingMetrics.bottom +
                        HEADING_GAP;

        float maxVerseHeight =
                HEIGHT -
                        SAFE_BOTTOM -
                        verseTop -
                        30f;

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

        versePaint.setTextSize(
                layout.textSize
        );

        float firstBaseline =
                verseTop +
                        ((maxVerseHeight -
                                layout.totalHeight) /
                                2f) -
                        layout.fontTop;

        /*
         * Make absolutely sure bottom safe area isn't violated.
         */
        float lastBaseline =
                firstBaseline +
                        (layout.lines.size() - 1) *
                                layout.lineHeight;

        float lowest =
                lastBaseline +
                        layout.fontBottom;

        float allowedBottom =
                HEIGHT -
                        SAFE_BOTTOM;

        if (lowest > allowedBottom) {

            firstBaseline -=
                    lowest -
                            allowedBottom;
        }

        /*
         * Also ensure verse isn't pushed above its start.
         */
        float highest =
                firstBaseline +
                        layout.fontTop;

        if (highest < verseTop) {

            firstBaseline +=
                    verseTop -
                            highest;
        }

        for (int i = 0;
             i < layout.lines.size();
             i++) {

            float baseline =
                    firstBaseline +
                            i *
                                    layout.lineHeight;

            canvas.drawText(
                    layout.lines.get(i),
                    WIDTH / 2f,
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

        float size = 72f;

        while (size >= 24f) {

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
                            metrics.top) *
                            1.25f;

            float totalHeight =
                    lineHeight *
                            lines.size();

            if (totalHeight <=
                    maxHeight) {

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

        paint.setTextSize(
                24f
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
                text.replace(
                                "\r",
                                " "
                        )
                        .replace(
                                "\n",
                                " "
                        )
                        .trim();

        if (cleaned.isEmpty()) {

            result.add("");

            return result;
        }

        String[] words =
                cleaned.split(
                        "\\s+"
                );

        StringBuilder current =
                new StringBuilder();

        for (String word : words) {

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

                current.append(
                                " "
                        )
                        .append(
                                word
                        );

            } else {

                result.add(
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

            char c =
                    word.charAt(i);

            String candidate =
                    part.toString() +
                            c;

            if (part.length() > 0 &&
                    paint.measureText(
                            candidate
                    ) > maxWidth) {

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

        encodeBackgroundMusic(
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
    // MP3 -> 8 SECOND AAC/M4A
    // ============================================================

    private static void encodeBackgroundMusic(
            Context context,
            File output
    ) throws Exception {

        File source =
                copyAssetToCache(
                        context,
                        "bg.mp3",
                        "bible_bg_source.mp3"
                );

        MediaExtractor extractor = null;

        MediaCodec decoder = null;

        MediaCodec encoder = null;

        MediaMuxer muxer = null;

        boolean muxerStarted = false;

        try {

            extractor =
                    new MediaExtractor();

            extractor.setDataSource(
                    source.getAbsolutePath()
            );

            int sourceTrack =
                    findAudioTrack(
                            extractor
                    );

            if (sourceTrack < 0) {

                throw new IOException(
                        "No audio track found in bg.mp3"
                );
            }

            extractor.selectTrack(
                    sourceTrack
            );

            MediaFormat sourceFormat =
                    extractor.getTrackFormat(
                            sourceTrack
                    );

            String mime =
                    sourceFormat.getString(
                            MediaFormat.KEY_MIME
                    );

            if (mime == null ||
                    !mime.startsWith("audio/")) {

                throw new IOException(
                        "bg.mp3 audio MIME is invalid"
                );
            }

            int sampleRate =
                    getInteger(
                            sourceFormat,
                            MediaFormat.KEY_SAMPLE_RATE,
                            44100
                    );

            int channels =
                    getInteger(
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

            // ====================================================
            // DECODER
            // ====================================================

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

            long targetBytesLong =
                    (long) sampleRate *
                            8L *
                            channels *
                            2L;

            if (targetBytesLong >
                    Integer.MAX_VALUE) {

                throw new IOException(
                        "PCM buffer too large"
                );
            }

            int targetBytes =
                    (int) targetBytesLong;

            ByteArrayOutputStream pcm =
                    new ByteArrayOutputStream(
                            targetBytes
                    );

            boolean decoderInputEOS =
                    false;

            boolean decoderOutputEOS =
                    false;

            byte[] temp =
                    new byte[64 * 1024];

            while (!decoderOutputEOS &&
                    pcm.size() < targetBytes) {

                if (!decoderInputEOS) {

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

                                decoderInputEOS =
                                        true;

                            } else {

                                long pts =
                                        extractor
                                                .getSampleTime();

                                decoder.queueInputBuffer(
                                        inputIndex,
                                        0,
                                        size,
                                        pts,
                                        0
                                );

                                extractor.advance();
                            }
                        }
                    }
                }

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
                            getInteger(
                                    decodedFormat,
                                    MediaFormat.KEY_SAMPLE_RATE,
                                    sampleRate
                            );

                    channels =
                            getInteger(
                                    decodedFormat,
                                    MediaFormat.KEY_CHANNEL_COUNT,
                                    channels
                            );

                    targetBytesLong =
                            (long) sampleRate *
                                    8L *
                                    channels *
                                    2L;

                    if (targetBytesLong >
                            Integer.MAX_VALUE) {

                        throw new IOException(
                                "Decoded PCM buffer too large"
                        );
                    }

                    targetBytes =
                            (int) targetBytesLong;

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

                        int wanted =
                                Math.min(
                                        outputBuffer.remaining(),
                                        targetBytes -
                                                pcm.size()
                                );

                        while (wanted > 0) {

                            int count =
                                    Math.min(
                                            wanted,
                                            temp.length
                                    );

                            outputBuffer.get(
                                    temp,
                                    0,
                                    count
                            );

                            pcm.write(
                                    temp,
                                    0,
                                    count
                            );

                            wanted -= count;
                        }
                    }

                    if ((info.flags &
                            MediaCodec
                                    .BUFFER_FLAG_END_OF_STREAM)
                            != 0) {

                        decoderOutputEOS =
                                true;
                    }

                    decoder.releaseOutputBuffer(
                            outputIndex,
                            false
                    );
                }
            }

            byte[] sourcePcm =
                    pcm.toByteArray();

            if (sourcePcm.length == 0) {

                throw new IOException(
                        "bg.mp3 decoded to zero PCM bytes"
                );
            }

            /*
             * Create exactly 8 seconds.
             *
             * If the MP3 is shorter, loop it.
             */
            targetBytes =
                    (int) (
                            (long) sampleRate *
                                    8L *
                                    channels *
                                    2L
                    );

            byte[] finalPcm =
                    new byte[targetBytes];

            if (sourcePcm.length >= targetBytes) {

                System.arraycopy(
                        sourcePcm,
                        0,
                        finalPcm,
                        0,
                        targetBytes
                );

            } else {

                int position = 0;

                while (position <
                        targetBytes) {

                    int count =
                            Math.min(
                                    sourcePcm.length,
                                    targetBytes -
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

            // ====================================================
            // AAC ENCODER
            // ====================================================

            MediaFormat aacFormat =
                    MediaFormat.createAudioFormat(
                            AUDIO_MIME,
                            sampleRate,
                            channels
                    );

            aacFormat.setInteger(
                    MediaFormat.KEY_AAC_PROFILE,
                    2
            );

            aacFormat.setInteger(
                    MediaFormat.KEY_BIT_RATE,
                    128_000
            );

            aacFormat.setInteger(
                    MediaFormat.KEY_MAX_INPUT_SIZE,
                    16_384
            );

            encoder =
                    MediaCodec.createEncoderByType(
                            AUDIO_MIME
                    );

            encoder.configure(
                    aacFormat,
                    null,
                    null,
                    MediaCodec.CONFIGURE_FLAG_ENCODE
            );

            encoder.start();

            // ====================================================
            // AUDIO MUXER
            // ====================================================

            muxer =
                    new MediaMuxer(
                            output.getAbsolutePath(),
                            MediaMuxer.OutputFormat
                                    .MUXER_OUTPUT_MPEG_4
                    );

            int audioTrack = -1;

            int pcmPosition = 0;

            boolean inputEOS = false;

            boolean outputEOS = false;

            long ptsUs = 0;

            int bytesPerFrame =
                    channels * 2;

            while (!outputEOS) {

                if (!inputEOS) {

                    int inputIndex =
                            encoder.dequeueInputBuffer(
                                    10_000
                            );

                    if (inputIndex >= 0) {

                        ByteBuffer input =
                                encoder.getInputBuffer(
                                        inputIndex
                                );

                        if (input == null) {

                            throw new IOException(
                                    "AAC input buffer is null"
                            );
                        }

                        input.clear();

                        int remaining =
                                finalPcm.length -
                                        pcmPosition;

                        if (remaining <= 0) {

                            encoder.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    0,
                                    ptsUs,
                                    MediaCodec
                                            .BUFFER_FLAG_END_OF_STREAM
                            );

                            inputEOS = true;

                        } else {

                            int count =
                                    Math.min(
                                            input.remaining(),
                                            remaining
                                    );

                            /*
                             * Keep complete PCM frames.
                             */
                            count -=
                                    count %
                                            bytesPerFrame;

                            if (count <= 0) {
                                count =
                                        Math.min(
                                                remaining,
                                                input.remaining()
                                        );
                            }

                            input.put(
                                    finalPcm,
                                    pcmPosition,
                                    count
                            );

                            long frames =
                                    count /
                                            bytesPerFrame;

                            long durationUs =
                                    frames *
                                            1_000_000L /
                                            sampleRate;

                            encoder.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    count,
                                    ptsUs,
                                    0
                            );

                            pcmPosition += count;

                            ptsUs += durationUs;
                        }
                    }
                }

                MediaCodec.BufferInfo info =
                        new MediaCodec.BufferInfo();

                int outputIndex =
                        encoder.dequeueOutputBuffer(
                                info,
                                10_000
                        );

                if (outputIndex ==
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {

                    MediaFormat actualFormat =
                            encoder.getOutputFormat();

                    audioTrack =
                            muxer.addTrack(
                                    actualFormat
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
                                MediaCodec
                                        .BUFFER_FLAG_CODEC_CONFIG)
                                == 0) {

                            outputBuffer.position(
                                    info.offset
                            );

                            outputBuffer.limit(
                                    info.offset +
                                            info.size
                            );

                            muxer.writeSampleData(
                                    audioTrack,
                                    outputBuffer,
                                    info
                            );
                        }
                    }

                    if ((info.flags &
                            MediaCodec
                                    .BUFFER_FLAG_END_OF_STREAM)
                            != 0) {

                        outputEOS = true;
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
    // WRITE AUDIO TRACK
    // ============================================================

    private static void writeAudioSamples(
            MediaExtractor extractor,
            int track,
            MediaMuxer muxer
    ) throws Exception {

        ByteBuffer buffer =
                ByteBuffer.allocateDirect(
                        1024 * 1024
                );

        MediaCodec.BufferInfo info =
                new MediaCodec.BufferInfo();

        boolean wroteAudio = false;

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

            if (pts < 0 ||
                    pts >= VIDEO_DURATION_US) {

                break;
            }

            int flags =
                    extractor.getSampleFlags();

            info.set(
                    0,
                    size,
                    pts,
                    flags
            );

            muxer.writeSampleData(
                    track,
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
    // COPY ASSET
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
    // FORMAT INTEGER
    // ============================================================

    private static int getInteger(
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
}
