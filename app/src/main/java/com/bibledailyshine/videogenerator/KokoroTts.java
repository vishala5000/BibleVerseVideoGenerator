package com.bibledailyshine.videogenerator;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import dev.ffmpegkit.kokoro.KokoroTTS;

public final class KokoroTts {

    private static KokoroTts instance;

    private KokoroTts() {
    }

    public static synchronized KokoroTts getInstance() {
        if (instance == null) {
            instance = new KokoroTts();
        }
        return instance;
    }

    public interface Callback {
        void onSuccess(File wavFile);
        void onError(Exception e);
        void onProgress(int percent);
    }

    public void synthesize(
            Context context,
            String text,
            File modelFile,
            File outputWav,
            Callback callback
    ) {

        new Thread(() -> {

            try {

                if (!modelFile.exists()) {
                    throw new Exception(
                            "Kokoro model not found:\n" +
                            modelFile.getAbsolutePath()
                    );
                }

                File parent = outputWav.getParentFile();

                if (parent != null && !parent.exists()) {
                    if (!parent.mkdirs()) {
                        throw new Exception("Cannot create audio directory");
                    }
                }

                callback.onProgress(5);

                /*
                 * Kokoro Android library initialization.
                 */
                KokoroTTS.initialize(
                        context,
                        modelFile.getAbsolutePath()
                );

                callback.onProgress(20);

                /*
                 * The library returns WAV audio.
                 */
                Object result = KokoroTTS.speak(text);

                callback.onProgress(80);

                /*
                 * The library's result contains audioData.
                 * This block writes the returned WAV bytes.
                 */
                byte[] wavBytes =
                        (byte[]) result.getClass()
                                .getField("audioData")
                                .get(result);

                if (wavBytes == null || wavBytes.length == 0) {
                    throw new Exception("Kokoro returned empty audio");
                }

                try (FileOutputStream fos =
                             new FileOutputStream(outputWav)) {

                    fos.write(wavBytes);
                    fos.flush();
                }

                callback.onProgress(100);

                new Handler(Looper.getMainLooper()).post(
                        () -> callback.onSuccess(outputWav)
                );

            } catch (Exception e) {

                try {
                    KokoroTTS.release();
                } catch (Exception ignored) {
                }

                new Handler(Looper.getMainLooper()).post(
                        () -> callback.onError(e)
                );
            }
        }).start();
    }

    public void release() {
        try {
            KokoroTTS.release();
        } catch (Exception ignored) {
        }
    }
}
