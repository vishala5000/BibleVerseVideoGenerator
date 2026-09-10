package com.bibledailyshine.videogenerator;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private EditText verseInput;

    private Button generateButton;

    private Button shareButton;

    private ProgressBar progressBar;

    private TextView statusText;

    private File outputDirectory;

    private File zipFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);

        outputDirectory =
                new File(
                        getExternalFilesDir(
                                Environment.DIRECTORY_MOVIES
                        ),
                        "BibleVerseVideos"
                );

        if (!outputDirectory.exists()) {
            outputDirectory.mkdirs();
        }

        buildUI();
    }

    private int dp(float value) {

        return (int) (
                value *
                getResources()
                        .getDisplayMetrics()
                        .density
        );
    }

    private TextView makeText(
            String text,
            float size,
            int color
    ) {

        TextView view =
                new TextView(this);

        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);

        return view;
    }

    private void buildUI() {

        LinearLayout root =
                new LinearLayout(this);

        root.setOrientation(
                LinearLayout.VERTICAL
        );

        root.setBackgroundColor(
                Color.BLACK
        );

        root.setPadding(
                dp(18),
                dp(20),
                dp(18),
                dp(18)
        );

        ScrollView scroll =
                new ScrollView(this);

        LinearLayout content =
                new LinearLayout(this);

        content.setOrientation(
                LinearLayout.VERTICAL
        );

        TextView title =
                makeText(
                        "Bible Verse Video Generator",
                        24,
                        Color.YELLOW
                );

        title.setGravity(
                Gravity.CENTER
        );

        title.setTypeface(
                null,
                android.graphics.Typeface.BOLD
        );

        content.addView(
                title,
                new LinearLayout.LayoutParams(
                        -1,
                        -2
                )
        );

        TextView info =
                makeText(
                        "\nEnter one Bible verse per line.\n\n" +
                        "Each line creates a separate 8-second video.\n\n" +
                        "Example:\n" +
                        "In the beginning God created the heaven and the earth.\n" +
                        "For God so loved the world that he gave his only begotten Son.\n" +
                        "I can do all things through Christ which strengtheneth me.",
                        16,
                        Color.WHITE
                );

        info.setPadding(
                0,
                dp(10),
                0,
                dp(12)
        );

        content.addView(info);

        verseInput =
                new EditText(this);

        verseInput.setHint(
                "Enter verses here...\n\nOne verse per line"
        );

        verseInput.setHintTextColor(
                Color.rgb(100, 100, 100)
        );

        verseInput.setTextColor(
                Color.BLACK
        );

        verseInput.setTextSize(17);

        verseInput.setGravity(
                Gravity.TOP | Gravity.START
        );

        verseInput.setSingleLine(false);

        verseInput.setInputType(
                InputType.TYPE_CLASS_TEXT |
                InputType.TYPE_TEXT_FLAG_MULTI_LINE |
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        );

        verseInput.setPadding(
                dp(15),
                dp(15),
                dp(15),
                dp(15)
        );

        verseInput.setBackgroundColor(
                Color.WHITE
        );

        content.addView(
                verseInput,
                new LinearLayout.LayoutParams(
                        -1,
                        dp(360)
                )
        );

        TextView settings =
                makeText(
                        "\nFIXED VIDEO SETTINGS\n\n" +
                        "Heading: Bible Verse\n" +
                        "Resolution: 1080 × 1920\n" +
                        "Duration: 8 seconds\n" +
                        "Video: H.264 / AVC\n" +
                        "Background: Black\n" +
                        "Heading: Yellow\n" +
                        "Verse: White\n" +
                        "Top no-text zone: 200 px\n" +
                        "Bottom no-text zone: 200 px\n" +
                        "Font: font.ttf\n" +
                        "Music: bg.mp3",
                        15,
                        Color.LTGRAY
                );

        content.addView(settings);

        generateButton =
                new Button(this);

        generateButton.setText(
                "GENERATE VIDEOS"
        );

        content.addView(
                generateButton,
                new LinearLayout.LayoutParams(
                        -1,
                        dp(58)
                )
        );

        progressBar =
                new ProgressBar(this);

        progressBar.setVisibility(
                View.GONE
        );

        LinearLayout.LayoutParams progressParams =
                new LinearLayout.LayoutParams(
                        -2,
                        -2
                );

        progressParams.gravity =
                Gravity.CENTER;

        content.addView(
                progressBar,
                progressParams
        );

        statusText =
                makeText(
                        "",
                        15,
                        Color.WHITE
                );

        statusText.setGravity(
                Gravity.CENTER
        );

        statusText.setPadding(
                0,
                dp(15),
                0,
                dp(15)
        );

        content.addView(statusText);

        shareButton =
                new Button(this);

        shareButton.setText(
                "SAVE / SHARE ZIP"
        );

        shareButton.setVisibility(
                View.GONE
        );

        content.addView(
                shareButton,
                new LinearLayout.LayoutParams(
                        -1,
                        dp(58)
                )
        );

        scroll.addView(content);

        root.addView(
                scroll,
                new LinearLayout.LayoutParams(
                        -1,
                        0,
                        1
                )
        );

        setContentView(root);

        generateButton.setOnClickListener(
                v -> generateVideos()
        );

        shareButton.setOnClickListener(
                v -> shareZip()
        );
    }

    private List<String> readVerses() {

        String text =
                verseInput
                        .getText()
                        .toString();

        String[] lines =
                text.split(
                        "\\r?\\n"
                );

        List<String> verses =
                new ArrayList<>();

        for (String line : lines) {

            String cleaned =
                    line.trim();

            if (!cleaned.isEmpty()) {
                verses.add(cleaned);
            }
        }

        return verses;
    }

    private void generateVideos() {

        List<String> verses =
                readVerses();

        if (verses.isEmpty()) {

            Toast.makeText(
                    this,
                    "Enter at least one verse.",
                    Toast.LENGTH_SHORT
            ).show();

            return;
        }

        generateButton.setEnabled(
                false
        );

        shareButton.setVisibility(
                View.GONE
        );

        progressBar.setVisibility(
                View.VISIBLE
        );

        statusText.setText(
                "Starting..."
        );

        new Thread(() -> {

            try {

                cleanOutputDirectory();

                List<File> files =
                        new ArrayList<>();

                for (int i = 0;
                     i < verses.size();
                     i++) {

                    int number = i + 1;

                    String verse =
                            verses.get(i);

                    runOnUiThread(() ->
                            statusText.setText(
                                    "Generating video " +
                                    number +
                                    " of " +
                                    verses.size()
                            )
                    );

                    File output =
                            new File(
                                    outputDirectory,
                                    number + ".mp4"
                            );

                    VideoGenerator.generate(
                            MainActivity.this,
                            verse,
                            output
                    );

                    files.add(output);
                }

                runOnUiThread(() ->
                        statusText.setText(
                                "Creating ZIP..."
                        )
                );

                zipFile =
                        new File(
                                outputDirectory,
                                "BibleVerseVideos.zip"
                        );

                ZipUtils.createZip(
                        files,
                        zipFile
                );

                runOnUiThread(() -> {

                    progressBar.setVisibility(
                            View.GONE
                    );

                    generateButton.setEnabled(
                            true
                    );

                    shareButton.setVisibility(
                            View.VISIBLE
                    );

                    statusText.setText(
                            "DONE\n\n" +
                            files.size() +
                            " video(s) generated.\n\n" +
                            "ZIP is ready."
                    );

                    Toast.makeText(
                            MainActivity.this,
                            "Generation complete.",
                            Toast.LENGTH_LONG
                    ).show();
                });

            } catch (Exception e) {

                e.printStackTrace();

                runOnUiThread(() -> {

                    progressBar.setVisibility(
                            View.GONE
                    );

                    generateButton.setEnabled(
                            true
                    );

                    statusText.setText(
                            "ERROR\n\n" +
                            e.getClass()
                                    .getSimpleName() +
                            "\n\n" +
                            String.valueOf(
                                    e.getMessage()
                            )
                    );

                    Toast.makeText(
                            MainActivity.this,
                            "Video generation failed.",
                            Toast.LENGTH_LONG
                    ).show();
                });
            }

        }).start();
    }

    private void cleanOutputDirectory() {

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
                FileProvider.getUriForFile(
                        this,
                        getPackageName()
                                + ".fileprovider",
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
                        "Save or share BibleVerseVideos.zip"
                )
        );
    }
}
