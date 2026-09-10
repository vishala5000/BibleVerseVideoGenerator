package com.bibledailyshine.videogenerator;

import android.app.Activity;
import android.os.Bundle;
import android.os.Environment;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

public class MainActivity extends Activity {

    private EditText verseInput;
    private Button generateButton;
    private TextView status;
    private ProgressBar progress;

    private File modelFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);

        verseInput = findViewById(R.id.verseInput);
        generateButton = findViewById(R.id.generateButton);
        status = findViewById(R.id.status);
        progress = findViewById(R.id.progress);

        modelFile = new File(
                getExternalFilesDir(null),
                "kokoro.onnx"
        );

        generateButton.setOnClickListener(v -> {

            String verse =
                    verseInput.getText()
                            .toString()
                            .trim();

            if (verse.isEmpty()) {

                Toast.makeText(
                        this,
                        "Enter a Bible verse",
                        Toast.LENGTH_SHORT
                ).show();

                return;
            }

            generateButton.setEnabled(false);

            File audioDirectory =
                    new File(
                            getExternalFilesDir(null),
                            "tts"
                    );

            if (!audioDirectory.exists()) {
                audioDirectory.mkdirs();
            }

            File wav =
                    new File(
                            audioDirectory,
                            "verse.wav"
                    );

            status.setText(
                    "Generating realistic offline voice..."
            );

            KokoroTts.getInstance().synthesize(
                    this,
                    verse,
                    modelFile,
                    wav,
                    new KokoroTts.Callback() {

                        @Override
                        public void onSuccess(File file) {

                            generateButton.setEnabled(true);

                            status.setText(
                                    "Voice created:\n" +
                                    file.getAbsolutePath()
                            );

                            Toast.makeText(
                                    MainActivity.this,
                                    "Kokoro voice created",
                                    Toast.LENGTH_SHORT
                            ).show();
                        }

                        @Override
                        public void onError(Exception e) {

                            generateButton.setEnabled(true);

                            status.setText(
                                    "TTS ERROR:\n" +
                                    e.getMessage()
                            );

                            Toast.makeText(
                                    MainActivity.this,
                                    "TTS failed",
                                    Toast.LENGTH_LONG
                            ).show();
                        }

                        @Override
                        public void onProgress(int percent) {

                            progress.setProgress(percent);

                            status.setText(
                                    "Generating voice: " +
                                    percent +
                                    "%"
                            );
                        }
                    }
            );
        });
    }
}
