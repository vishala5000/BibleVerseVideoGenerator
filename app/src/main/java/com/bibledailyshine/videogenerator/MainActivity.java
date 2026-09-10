package com.bibledailyshine.videogenerator;

import android.app.Activity;
import android.os.Bundle;
import android.os.Environment;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private EditText verseInput;
    private Button generateButton;
    private TextView statusText;
    private ProgressBar progressBar;

    private File outputDirectory;
    private File zipFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        createOutputDirectory();
        buildUserInterface();
    }

    private void createOutputDirectory() {

        outputDirectory = new File(
                getExternalFilesDir(Environment.DIRECTORY_MOVIES),
                "BibleVerseVideos"
        );

        if (!outputDirectory.exists()) {
            outputDirectory.mkdirs();
        }
    }

    private void buildUserInterface() {

        LinearLayout root = new LinearLayout(this);

        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 40, 32, 32);
        root.setBackgroundColor(Color.BLACK);

        ScrollView scrollView = new ScrollView(this);

        LinearLayout content = new LinearLayout(this);

        content.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);

        title.setText("Bible Verse Video Generator");
        title.setTextColor(Color.YELLOW);
        title.setTextSize(25);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, 25);

        content.addView(
                title,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                )
        );

        TextView instructions = new TextView(this);

        instructions.setText(
                "Enter one Bible verse per line.\n\n" +
                "Each line will create a separate 8-second video.\n\n" +
                "Example:\n" +
                "In the beginning God created the heaven and the earth.\n" +
                "And God said, Let there be light: and there was light.\n" +
                "For God so loved the world..."
        );

        instructions.setTextColor(Color.WHITE);
        instructions.setTextSize(16);
        instructions.setPadding(0, 0, 0, 20);

        content.addView(instructions);

        verseInput = new EditText(this);

        verseInput.setHint(
                "Enter verses here...\nOne verse per line"
        );

        verseInput.setHintTextColor(Color.GRAY);
        verseInput.setTextColor(Color.BLACK);
        verseInput.setTextSize(17);

        verseInput.setGravity(
                Gravity.TOP | Gravity.START
        );

        verseInput.setPadding(20, 20, 20, 20);

        verseInput.setBackgroundColor(Color.WHITE);

        verseInput.setSingleLine(false);

        verseInput.setMinLines(10);

        verseInput.setInputType(
                android.text.InputType.TYPE_CLASS_TEXT |
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE |
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        );

        content.addView(
                verseInput,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        500
                )
        );

        TextView fixedSettings = new TextView(this);

        fixedSettings.setText(
                "\nVideo settings\n\n" +
                "Heading: Bible Verse\n" +
                "Resolution: 1080 × 1920\n" +
                "Duration: 8 seconds\n" +
                "Codec: H.264 / AVC\n" +
                "Background: Black\n" +
                "Heading: Yellow\n" +
                "Verse: White\n" +
                "Top safe area: 200 px\n" +
                "Bottom safe area: 200 px"
        );

        fixedSettings.setTextColor(Color.LTGRAY);
        fixedSettings.setTextSize(15);

        content.addView(fixedSettings);

        generateButton = new Button(this);

        generateButton.setText(
                "GENERATE VIDEOS"
        );

        content.addView(
                generateButton,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        65
                )
        );

        progressBar = new ProgressBar(this);

        progressBar.setVisibility(View.GONE);

        content.addView(
                progressBar,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                )
        );

        statusText = new TextView(this);

        statusText.setTextColor(Color.WHITE);
        statusText.setTextSize(15);
        statusText.setGravity(Gravity.CENTER);
        statusText.setPadding(0, 20, 0, 20);

        content.addView(statusText);

        scrollView.addView(content);

        root.addView(
                scrollView,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        0,
                        1
                )
        );

        setContentView(root);

        generateButton.setOnClickListener(
                v -> startGeneration()
        );
    }

    private void startGeneration() {

        String allText = verseInput.getText().toString();

        if (allText.trim().isEmpty()) {

            Toast.makeText(
                    this,
                    "Please enter at least one verse.",
                    Toast.LENGTH_SHORT
            ).show();

            return;
        }

        String[] lines = allText.split("\\r?\\n");

        List<String> verses = new ArrayList<>();

        for (String line : lines) {

            String cleaned = line.trim();

            if (!cleaned.isEmpty()) {
                verses.add(cleaned);
            }
        }

        if (verses.isEmpty()) {
            return;
        }

        generateButton.setEnabled(false);

        progressBar.setVisibility(View.VISIBLE);

        statusText.setText(
                "Preparing " + verses.size() + " video(s)..."
        );

        new Thread(() -> {

            try {

                deleteOldVideos();

                List<File> generatedFiles =
                        new ArrayList<>();

                for (int i = 0; i < verses.size(); i++) {

                    final int number = i + 1;

                    runOnUiThread(() ->
                            statusText.setText(
                                    "Generating video " +
                                    number +
                                    " of " +
                                    verses.size()
                            )
                    );

                    File output = new File(
                            outputDirectory,
                            number + ".mp4"
                    );

                    VideoGenerator.generate(
                            this,
                            verses.get(i),
                            output
                    );

                    generatedFiles.add(output);
                }

                runOnUiThread(() ->
                        statusText.setText(
                                "Creating ZIP file..."
                        )
                );

                zipFile = new File(
                        outputDirectory,
                        "BibleVerseVideos.zip"
                );

                ZipUtils.createZip(
                        generatedFiles,
                        zipFile
                );

                runOnUiThread(() -> {

                    progressBar.setVisibility(
                            View.GONE
                    );

                    generateButton.setEnabled(true);

                    statusText.setText(
                            "DONE\n\n" +
                            generatedFiles.size() +
                            " video(s) created.\n\n" +
                            "ZIP ready."
                    );

                    showShareButton();

                    Toast.makeText(
                            MainActivity.this,
                            "Videos generated successfully.",
                            Toast.LENGTH_LONG
                    ).show();
                });

            } catch (Exception e) {

                e.printStackTrace();

                runOnUiThread(() -> {

                    progressBar.setVisibility(
                            View.GONE
                    );

                    generateButton.setEnabled(true);

                    statusText.setText(
                            "ERROR:\n" +
                            e.getMessage()
                    );

                    Toast.makeText(
                            MainActivity.this,
                            "Generation failed.",
                            Toast.LENGTH_LONG
                    ).show();
                });
            }

        }).start();
    }

    private void deleteOldVideos() {

        if (!outputDirectory.exists()) {
            outputDirectory.mkdirs();
            return;
        }

        File[] files =
                outputDirectory.listFiles();

        if (files == null) {
            return;
        }

        for (File file : files) {

            if (file.isFile()) {
                file.delete();
            }
        }
    }

    private void showShareButton() {

        Button shareButton =
                new Button(this);

        shareButton.setText(
                "SAVE / SHARE ZIP"
        );

        ((LinearLayout)
                ((ScrollView)
                        findViewById(
                                android.R.id.content
                        )
                ).getChildAt(0)
        );

        shareButton.setOnClickListener(
                v -> shareZip()
        );

        View root = findViewById(
                android.R.id.content
        );

        if (root instanceof View) {

            addContentView(
                    shareButton,
                    new android.widget.FrameLayout.LayoutParams(
                            -1,
                            65,
                            Gravity.BOTTOM
                    )
            );
        }
    }

    private void shareZip() {

        if (zipFile == null ||
                !zipFile.exists()) {

            Toast.makeText(
                    this,
                    "ZIP file not found.",
                    Toast.LENGTH_SHORT
            ).show();

            return;
        }

        Uri uri =
                androidx.core.content.FileProvider
                        .getUriForFile(
                                this,
                                getPackageName() +
                                ".fileprovider",
                                zipFile
                        );

        Intent intent =
                new Intent(
                        Intent.ACTION_SEND
                );

        intent.setType(
                "application/zip"
        );

        intent.putExtra(
                Intent.EXTRA_STREAM,
                uri
        );

        intent.addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION
        );

        startActivity(
                Intent.createChooser(
                        intent,
                        "Save or share ZIP"
                )
        );
    }
}
