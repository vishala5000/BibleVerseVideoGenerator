package com.bibledailyshine.videogenerator;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.File;

public final class AudioMixer {

    private AudioMixer() {
    }

    /*
     * Temporary implementation.
     *
     * The final mixer will:
     *
     * voice WAV
     *      +
     * bg.mp3
     *      ↓
     * mixed PCM
     *      ↓
     * AAC
     *      ↓
     * M4A
     *
     * Voice remains dominant and background music stays quiet.
     */

    public static File prepareVoice(
            File voiceWav,
            File output
    ) throws Exception {

        if (!voiceWav.exists()) {
            throw new Exception("Voice WAV does not exist");
        }

        /*
         * For the first integration test, simply use
         * the Kokoro WAV.
         *
         * Background music integration is connected
         * after Kokoro synthesis is verified.
         */

        if (output.exists()) {
            output.delete();
        }

        copyFile(voiceWav, output);

        return output;
    }

    private static void copyFile(
            File source,
            File destination
    ) throws Exception {

        try (
                java.io.FileInputStream in =
                        new java.io.FileInputStream(source);

                java.io.FileOutputStream out =
                        new java.io.FileOutputStream(destination)
        ) {

            byte[] buffer = new byte[64 * 1024];

            int count;

            while ((count = in.read(buffer)) != -1) {
                out.write(buffer, 0, count);
            }

            out.flush();
        }
    }
}
