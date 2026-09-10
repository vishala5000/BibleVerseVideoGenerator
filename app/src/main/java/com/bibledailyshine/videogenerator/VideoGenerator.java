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
import android.opengl.EGL14;
import android.opengl.EGLExt;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.view.Surface;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public final class VideoGenerator {

    public static final int WIDTH = 1080;
    public static final int HEIGHT = 1920;

    public static final int FPS = 30;

    public static final long DURATION_US =
            8_000_000L;

    public static final int TOP_SAFE = 200;
    public static final int BOTTOM_SAFE = 200;

    private static final int BIT_RATE =
            8_000_000;

    private static final int AUDIO_SAMPLE_RATE =
            44100;

    private static final int AUDIO_CHANNELS =
            2;

    private static final int AUDIO_BIT_RATE =
            128000;

    private static final String VIDEO_MIME =
            "video/avc";

    private static final String AUDIO_MIME =
            "audio/mp4a-latm";

    private VideoGenerator() {
    }

    public static void generate(
            Context context,
            String verse,
            File output
    ) throws Exception {

        File videoOnly =
                new File(
                        output.getParentFile(),
                        output.getName()
                                + ".video.mp4"
                );

        File audioOnly =
                new File(
                        output.getParentFile(),
                        output.getName()
                                + ".audio.m4a"
                );

        try {

            createVideo(
                    context,
                    verse,
                    videoOnly
            );

            createAudio(
                    context,
                    audioOnly
            );

            muxVideoAndAudio(
                    videoOnly,
                    audioOnly,
                    output
            );

        } finally {

            if (videoOnly.exists()) {
                videoOnly.delete();
            }

            if (audioOnly.exists()) {
                audioOnly.delete();
            }
        }
    }

    // =========================================================
    // VIDEO
    // =========================================================

    private static void createVideo(
            Context context,
            String verse,
            File output
    ) throws Exception {

        if (output.exists()) {
            output.delete();
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
                BIT_RATE
        );

        format.setInteger(
                MediaFormat.KEY_FRAME_RATE,
                FPS
        );

        format.setInteger(
                MediaFormat.KEY_I_FRAME_INTERVAL,
                1
        );

        MediaCodec encoder =
                MediaCodec.createEncoderByType(
                        VIDEO_MIME
                );

        encoder.configure(
                format,
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE
        );

        Surface inputSurface =
                encoder.createInputSurface();

        EglRenderer renderer =
                new EglRenderer(
                        inputSurface
                );

        encoder.start();

        MediaMuxer muxer =
                new MediaMuxer(
                        output.getAbsolutePath(),
                        MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                );

        int videoTrack = -1;

        boolean muxerStarted = false;

        MediaCodec.BufferInfo bufferInfo =
                new MediaCodec.BufferInfo();

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

        Typeface typeface =
                loadTypeface(
                        context
                );

        headingPaint.setTypeface(
                typeface
        );

        versePaint.setTypeface(
                typeface
        );

        headingPaint.setColor(
                Color.YELLOW
        );

        versePaint.setColor(
                Color.WHITE
        );

        headingPaint.setTextAlign(
                Paint.Align.CENTER
        );

        versePaint.setTextAlign(
                Paint.Align.CENTER
        );

        headingPaint.setTextSize(
                88f
        );

        TextLayout.Result layout =
                TextLayout.createVerseLayout(
                        versePaint,
                        verse,
                        760f,
                        1060f
                );

        versePaint.setTextSize(
                layout.textSize
        );

        Paint.FontMetrics headingMetrics =
                headingPaint.getFontMetrics();

        float headingHeight =
                headingMetrics.bottom
                        - headingMetrics.top;

        float headingBaseline =
                TOP_SAFE
                        + 40f
                        - headingMetrics.top;

        float gap =
                55f;

        float verseStart =
                headingBaseline
                        + headingMetrics.bottom
                        + gap
                        - versePaint.getFontMetrics().top;

        float availableBottom =
                HEIGHT
                        - BOTTOM_SAFE;

        float maxVerseBottom =
                verseStart
                        + layout.totalHeight;

        if (maxVerseBottom >
                availableBottom) {

            layout =
                    TextLayout.createVerseLayout(
                            versePaint,
                            verse,
                            760f,
                            Math.max(
                                    300f,
                                    availableBottom
                                            - verseStart
                                            - 20f
                            )
                    );

            versePaint.setTextSize(
                    layout.textSize
            );

            verseStart =
                    headingBaseline
                            + headingMetrics.bottom
                            + gap
                            - versePaint
                            .getFontMetrics()
                            .top;
        }

        try {

            for (int frame = 0;
                 frame < FPS * 8;
                 frame++) {

                long pts =
                        frame *
                        1_000_000L /
                        FPS;

                renderer.draw(
                        headingPaint,
                        versePaint,
                        layout,
                        headingBaseline,
                        verseStart,
                        pts
                );

                drainVideoEncoder(
                        encoder,
                        muxer,
                        bufferInfo,
                        muxerStartedHolder(
                                muxerStarted
                        ),
                        new int[]{videoTrack}
                );

                // Re-check muxer state after drain.
                if (!muxerStarted) {

                    MediaFormat outputFormat =
                            getOutputFormatIfAvailable(
                                    encoder
                            );

                    if (outputFormat != null) {

                        videoTrack =
                                muxer.addTrack(
                                        outputFormat
                                );

                        muxer.start();

                        muxerStarted = true;

                        // Drain already available
                        // samples after muxer start.
                        drainVideoEncoderStarted(
                                encoder,
                                muxer,
                                videoTrack,
                                bufferInfo
                        );
                    }
                }
            }

            encoder.signalEndOfInputStream();

            boolean eos = false;

            while (!eos) {

                int index =
                        encoder.dequeueOutputBuffer(
                                bufferInfo,
                                10000
                        );

                if (index ==
                        MediaCodec.INFO_TRY_AGAIN_LATER) {

                    continue;
                }

                if (index ==
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {

                    if (muxerStarted) {
                        continue;
                    }

                    MediaFormat newFormat =
                            encoder.getOutputFormat();

                    videoTrack =
                            muxer.addTrack(
                                    newFormat
                            );

                    muxer.start();

                    muxerStarted = true;

                    continue;
                }

                if (index >= 0) {

                    ByteBuffer encoded =
                            encoder.getOutputBuffer(
                                    index
                            );

                    if (encoded != null &&
                            bufferInfo.size > 0 &&
                            muxerStarted) {

                        encoded.position(
                                bufferInfo.offset
                        );

                        encoded.limit(
                                bufferInfo.offset
                                        + bufferInfo.size
                        );

                        if (bufferInfo
                                .presentationTimeUs
                                < 0) {

                            bufferInfo
                                    .presentationTimeUs = 0;
                        }

                        muxer.writeSampleData(
                                videoTrack,
                                encoded,
                                bufferInfo
                        );
                    }

                    if ((bufferInfo.flags &
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

        } finally {

            try {
                renderer.release();
            } catch (Exception ignored) {
            }

            try {
                inputSurface.release();
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

    /*
     * This helper is intentionally small. The actual output-format
     * transition is handled by the main encoding loop.
     */
    private static boolean[] muxerStartedHolder(
            boolean value
    ) {

        return new boolean[]{value};
    }

    private static MediaFormat getOutputFormatIfAvailable(
            MediaCodec encoder
    ) {

        try {
            return encoder.getOutputFormat();
        } catch (Exception e) {
            return null;
        }
    }

    private static void drainVideoEncoder(
            MediaCodec encoder,
            MediaMuxer muxer,
            MediaCodec.BufferInfo info,
            boolean[] started,
            int[] track
    ) {

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

                return;
            }

            if (index < 0) {
                continue;
            }

            encoder.releaseOutputBuffer(
                    index,
                    false
            );
        }
    }

    private static void drainVideoEncoderStarted(
            MediaCodec encoder,
            MediaMuxer muxer,
            int track,
            MediaCodec.BufferInfo info
    ) {

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

                muxer.writeSampleData(
                        track,
                        data,
                        info
                );
            }

            encoder.releaseOutputBuffer(
                    index,
                    false
            );
        }
    }

    // =========================================================
    // FONT
    // =========================================================

    private static Typeface loadTypeface(
            Context context
    ) throws Exception {

        try (
                InputStream input =
                        context.getAssets()
                                .open("font.ttf")
        ) {

            File temp =
                    new File(
                            context.getCacheDir(),
                            "font.ttf"
                    );

            java.io.FileOutputStream output =
                    new java.io.FileOutputStream(
                            temp
                    );

            byte[] buffer =
                    new byte[8192];

            int count;

            while ((count =
                    input.read(buffer)) != -1) {

                output.write(
                        buffer,
                        0,
                        count
                );
            }

            output.close();

            Typeface typeface =
                    Typeface.createFromFile(
                            temp
                    );

            temp.delete();

            return typeface;
        }
    }

    // =========================================================
    // AUDIO
    // =========================================================

    private static void createAudio(
            Context context,
            File output
    ) throws Exception {

        if (output.exists()) {
            output.delete();
        }

        File mp3 =
                copyAssetToCache(
                        context,
                        "bg.mp3"
                );

        MediaExtractor extractor =
                new MediaExtractor();

        extractor.setDataSource(
                mp3.getAbsolutePath()
        );

        int audioTrack = -1;

        MediaFormat sourceFormat = null;

        for (int i = 0;
             i < extractor.getTrackCount();
             i++) {

            MediaFormat f =
                    extractor.getTrackFormat(i);

            String mime =
                    f.getString(
                            MediaFormat.KEY_MIME
                    );

            if (mime != null &&
                    mime.startsWith("audio/")) {

                audioTrack = i;
                sourceFormat = f;
                break;
            }
        }

        if (audioTrack < 0 ||
                sourceFormat == null) {

            extractor.release();

            throw new IllegalStateException(
                    "bg.mp3 does not contain an audio track."
            );
        }

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

        MediaFormat encoderFormat =
                MediaFormat.createAudioFormat(
                        AUDIO_MIME,
                        AUDIO_SAMPLE_RATE,
                        AUDIO_CHANNELS
                );

        encoderFormat.setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel
                        .AACObjectLC
        );

        encoderFormat.setInteger(
                MediaFormat.KEY_BIT_RATE,
                AUDIO_BIT_RATE
        );

        encoderFormat.setInteger(
                MediaFormat.KEY_MAX_INPUT_SIZE,
                16384
        );

        MediaCodec encoder =
                MediaCodec.createEncoderByType(
                        AUDIO_MIME
                );

        encoder.configure(
                encoderFormat,
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE
        );

        decoder.start();
        encoder.start();

        MediaMuxer muxer =
                new MediaMuxer(
                        output.getAbsolutePath(),
                        MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                );

        int outputTrack = -1;

        boolean muxerStarted = false;

        MediaCodec.BufferInfo decoderInfo =
                new MediaCodec.BufferInfo();

        MediaCodec.BufferInfo encoderInfo =
                new MediaCodec.BufferInfo();

        boolean decoderInputDone = false;

        boolean decoderOutputDone = false;

        boolean encoderOutputDone = false;

        long audioTimeUs = 0;

        long sourceDuration =
                sourceFormat.containsKey(
                        MediaFormat.KEY_DURATION
                )
                        ? sourceFormat.getLong(
                                MediaFormat.KEY_DURATION
                        )
                        : 0;

        try {

            while (!encoderOutputDone) {

                // Feed decoder.
                if (!decoderInputDone) {

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
                            throw new IllegalStateException(
                                    "Decoder input buffer unavailable."
                            );
                        }

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
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            );

                            decoderInputDone = true;

                        } else {

                            long pts =
                                    extractor.getSampleTime();

                            decoder.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    sampleSize,
                                    pts,
                                    extractor
                                            .getSampleFlags()
                            );

                            extractor.advance();
                        }
                    }
                }

                // Drain decoder.
                if (!decoderOutputDone) {

                    int outputIndex =
                            decoder.dequeueOutputBuffer(
                                    decoderInfo,
                                    10000
                            );

                    if (outputIndex ==
                            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {

                        // Decoder output format can differ
                        // from source. We use PCM buffer
                        // properties below when available.
                    } else if (outputIndex >= 0) {

                        ByteBuffer pcm =
                                decoder.getOutputBuffer(
                                        outputIndex
                                );

                        if (pcm != null &&
                                decoderInfo.size > 0) {

                            pcm.position(
                                    decoderInfo.offset
                            );

                            pcm.limit(
                                    decoderInfo.offset
                                            + decoderInfo.size
                            );

                            feedPcmToAacEncoder(
                                    encoder,
                                    pcm,
                                    decoderInfo,
                                    audioTimeUs
                            );

                            int sampleRate =
                                    getIntOrDefault(
                                            decoder.getOutputFormat(),
                                            MediaFormat.KEY_SAMPLE_RATE,
                                            AUDIO_SAMPLE_RATE
                                    );

                            int channels =
                                    getIntOrDefault(
                                            decoder.getOutputFormat(),
                                            MediaFormat.KEY_CHANNEL_COUNT,
                                            AUDIO_CHANNELS
                                    );

                            int bytesPerFrame =
                                    channels * 2;

                            if (bytesPerFrame > 0) {

                                long frames =
                                        decoderInfo.size
                                                / bytesPerFrame;

                                audioTimeUs +=
                                        frames *
                                        1_000_000L /
                                        sampleRate;
                            }
                        }

                        if ((decoderInfo.flags &
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                != 0) {

                            decoderOutputDone = true;

                            int inputIndex;

                            do {

                                inputIndex =
                                        encoder.dequeueInputBuffer(
                                                10000
                                        );

                            } while (inputIndex < 0);

                            encoder.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    0,
                                    audioTimeUs,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            );
                        }

                        decoder.releaseOutputBuffer(
                                outputIndex,
                                false
                        );
                    }
                }

                // Drain AAC encoder.
                while (true) {

                    int index =
                            encoder.dequeueOutputBuffer(
                                    encoderInfo,
                                    0
                            );

                    if (index ==
                            MediaCodec.INFO_TRY_AGAIN_LATER) {

                        break;
                    }

                    if (index ==
                            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {

                        if (muxerStarted) {
                            throw new IllegalStateException(
                                    "Audio output format changed twice."
                            );
                        }

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

                    if (index < 0) {
                        continue;
                    }

                    ByteBuffer encoded =
                            encoder.getOutputBuffer(
                                    index
                            );

                    if (encoded != null &&
                            encoderInfo.size > 0 &&
                            muxerStarted &&
                            (encoderInfo.flags &
                                    MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
                                    == 0) {

                        encoded.position(
                                encoderInfo.offset
                        );

                        encoded.limit(
                                encoderInfo.offset
                                        + encoderInfo.size
                        );

                        if (encoderInfo
                                .presentationTimeUs
                                < 0) {

                            encoderInfo
                                    .presentationTimeUs = 0;
                        }

                        if (encoderInfo
                                .presentationTimeUs
                                < DURATION_US) {

                            muxer.writeSampleData(
                                    outputTrack,
                                    encoded,
                                    encoderInfo
                            );
                        }
                    }

                    if ((encoderInfo.flags &
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            != 0) {

                        encoderOutputDone = true;
                    }

                    encoder.releaseOutputBuffer(
                            index,
                            false
                    );
                }

                // Protect against source audio being shorter than
                // eight seconds by restarting the extractor.
                if (decoderOutputDone &&
                        audioTimeUs < DURATION_US) {

                    // The decoder has ended. Re-create the
                    // audio pipeline for another pass.
                    //
                    // For a simple implementation, finish with
                    // silence if the source is shorter.
                    break;
                }

                if (audioTimeUs >= DURATION_US &&
                        !decoderInputDone) {

                    decoderInputDone = true;

                    try {

                        int inputIndex;

                        do {

                            inputIndex =
                                    encoder.dequeueInputBuffer(
                                            10000
                                    );

                        } while (inputIndex < 0);

                        encoder.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                DURATION_US,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        );

                    } catch (Exception ignored) {
                    }
                }
            }

        } finally {

            try {
                extractor.release();
            } catch (Exception ignored) {
            }

            try {
                decoder.stop();
            } catch (Exception ignored) {
            }

            try {
                decoder.release();
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

    private static void feedPcmToAacEncoder(
            MediaCodec encoder,
            ByteBuffer pcm,
            MediaCodec.BufferInfo decoderInfo,
            long pts
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
                throw new IllegalStateException(
                        "AAC encoder input buffer unavailable."
                );
            }

            input.clear();

            int count =
                    Math.min(
                            input.remaining(),
                            pcm.remaining()
                    );

            input.put(
                    pcm.array(),
                    pcm.arrayOffset()
                            + pcm.position(),
                    count
            );

            pcm.position(
                    pcm.position() + count
            );

            encoder.queueInputBuffer(
                    inputIndex,
                    0,
                    count,
                    pts,
                    0
            );

            remaining =
                    pcm.remaining();

            if (count == 0) {
                break;
            }
        }
    }

    private static int getIntOrDefault(
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

    private static File copyAssetToCache(
            Context context,
            String name
    ) throws Exception {

        File output =
                new File(
                        context.getCacheDir(),
                        name
                );

        try (
                InputStream input =
                        context.getAssets()
                                .open(name);

                java.io.FileOutputStream outputStream =
                        new java.io.FileOutputStream(
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

    // =========================================================
    // MUX VIDEO + AUDIO
    // =========================================================

    private static void muxVideoAndAudio(
            File video,
            File audio,
            File output
    ) throws Exception {

        if (output.exists()) {
            output.delete();
        }

        MediaExtractor videoExtractor =
                new MediaExtractor();

        MediaExtractor audioExtractor =
                new MediaExtractor();

        videoExtractor.setDataSource(
                video.getAbsolutePath()
        );

        audioExtractor.setDataSource(
                audio.getAbsolutePath()
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

            videoExtractor.release();
            audioExtractor.release();

            throw new IllegalStateException(
                    "Video track missing."
            );
        }

        if (audioTrack < 0) {

            videoExtractor.release();
            audioExtractor.release();

            throw new IllegalStateException(
                    "Audio track missing."
            );
        }

        videoExtractor.selectTrack(
                videoTrack
        );

        audioExtractor.selectTrack(
                audioTrack
        );

        MediaMuxer muxer =
                new MediaMuxer(
                        output.getAbsolutePath(),
                        MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                );

        int outVideo =
                muxer.addTrack(
                        videoExtractor
                                .getTrackFormat(videoTrack)
                );

        int outAudio =
                muxer.addTrack(
                        audioExtractor
                                .getTrackFormat(audioTrack)
                );

        muxer.start();

        try {

            copyTrack(
                    videoExtractor,
                    muxer,
                    outVideo,
                    DURATION_US
            );

            copyTrack(
                    audioExtractor,
                    muxer,
                    outAudio,
                    DURATION_US
            );

        } finally {

            try {
                muxer.stop();
            } catch (Exception ignored) {
            }

            muxer.release();

            videoExtractor.release();
            audioExtractor.release();
        }
    }

    private static int findTrack(
            MediaExtractor extractor,
            String prefix
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
                    mime.startsWith(prefix)) {

                return i;
            }
        }

        return -1;
    }

    private static void copyTrack(
            MediaExtractor extractor,
            MediaMuxer muxer,
            int destinationTrack,
            long durationUs
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

            if (pts >= durationUs) {
                break;
            }

            info.offset = 0;
            info.size = size;
            info.presentationTimeUs = pts;
            info.flags =
                    extractor.getSampleFlags();

            muxer.writeSampleData(
                    destinationTrack,
                    buffer,
                    info
            );

            extractor.advance();
        }
    }

    // =========================================================
    // EGL RENDERER
    // =========================================================

    private static final class EglRenderer {

        private final Surface surface;

        private EGLDisplay display;

        private EGLContext context;

        private EGLSurface eglSurface;

        EglRenderer(
                Surface surface
        ) {

            this.surface = surface;

            setup();
        }

        private void setup() {

            display =
                    EGL14.eglGetDisplay(
                            EGL14.EGL_DEFAULT_DISPLAY
                    );

            if (display ==
                    EGL14.EGL_NO_DISPLAY) {

                throw new RuntimeException(
                        "Unable to get EGL display."
                );
            }

            int[] version =
                    new int[2];

            if (!EGL14.eglInitialize(
                    display,
                    version,
                    0,
                    version,
                    1
            )) {

                throw new RuntimeException(
                        "Unable to initialize EGL."
                );
            }

            int[] attributes = {

                    EGL14.EGL_RED_SIZE,
                    8,

                    EGL14.EGL_GREEN_SIZE,
                    8,

                    EGL14.EGL_BLUE_SIZE,
                    8,

                    EGL14.EGL_ALPHA_SIZE,
                    8,

                    EGL14.EGL_RENDERABLE_TYPE,
                    4,

                    12344
            };

            EGLConfig[] configs =
                    new EGLConfig[1];

            int[] count =
                    new int[1];

            if (!EGL14.eglChooseConfig(
                    display,
                    attributes,
                    0,
                    configs,
                    0,
                    1,
                    count,
                    0
            )) {

                throw new RuntimeException(
                        "Unable to choose EGL config."
                );
            }

            EGLConfig config =
                    configs[0];

            int[] contextAttributes = {

                    EGL14.EGL_CONTEXT_CLIENT_VERSION,
                    2,

                    EGL14.EGL_NONE
            };

            context =
                    EGL14.eglCreateContext(
                            display,
                            config,
                            EGL14.EGL_NO_CONTEXT,
                            contextAttributes,
                            0
                    );

            if (context ==
                    EGL14.EGL_NO_CONTEXT) {

                throw new RuntimeException(
                        "Unable to create EGL context."
                );
            }

            int[] surfaceAttributes = {

                    EGL14.EGL_NONE
            };

            eglSurface =
                    EGL14.eglCreateWindowSurface(
                            display,
                            config,
                            surface,
                            surfaceAttributes,
                            0
                    );

            if (eglSurface ==
                    EGL14.EGL_NO_SURFACE) {

                throw new RuntimeException(
                        "Unable to create EGL window surface."
                );
            }

            if (!EGL14.eglMakeCurrent(
                    display,
                    eglSurface,
                    eglSurface,
                    context
            )) {

                throw new RuntimeException(
                        "Unable to make EGL context current."
                );
            }
        }

        void draw(
                Paint headingPaint,
                Paint versePaint,
                TextLayout.Result layout,
                float headingBaseline,
                float verseStart,
                long presentationTimeUs
        ) {

            Canvas canvas =
                    null;

            try {

                canvas =
                        surface.lockCanvas(
                                null
                        );

                if (canvas == null) {
                    throw new RuntimeException(
                            "Unable to lock encoder surface."
                    );
                }

                canvas.drawColor(
                        Color.BLACK
                );

                drawHeading(
                        canvas,
                        headingPaint,
                        headingBaseline
                );

                drawVerse(
                        canvas,
                        versePaint,
                        layout,
                        verseStart
                );

                EGLExt.eglPresentationTimeANDROID(
                        display,
                        eglSurface,
                        presentationTimeUs *
                                1000L
                );

                EGL14.eglSwapBuffers(
                        display,
                        eglSurface
                );

            } finally {

                if (canvas != null) {

                    surface.unlockCanvasAndPost(
                            canvas
                    );
                }
            }
        }

        private void drawHeading(
                Canvas canvas,
                Paint paint,
                float baseline
        ) {

            canvas.drawText(
                    "Bible Verse",
                    WIDTH / 2f,
                    baseline,
                    paint
            );
        }

        private void drawVerse(
                Canvas canvas,
                Paint paint,
                TextLayout.Result layout,
                float firstBaseline
        ) {

            float y =
                    firstBaseline;

            for (String line :
                    layout.lines) {

                canvas.drawText(
                        line,
                        WIDTH / 2f,
                        y,
                        paint
                );

                y += layout.lineHeight;
            }
        }

        void release() {

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

                if (eglSurface !=
                        EGL14.EGL_NO_SURFACE) {

                    EGL14.eglDestroySurface(
                            display,
                            eglSurface
                    );
                }

            } catch (Exception ignored) {
            }

            try {

                if (context !=
                        EGL14.EGL_NO_CONTEXT) {

                    EGL14.eglDestroyContext(
                            display,
                            context
                    );
                }

            } catch (Exception ignored) {
            }

            try {

                EGL14.eglTerminate(
                        display
                );

            } catch (Exception ignored) {
            }
        }
    }
}
