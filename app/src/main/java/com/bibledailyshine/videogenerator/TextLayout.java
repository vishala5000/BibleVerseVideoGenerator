package com.bibledailyshine.videogenerator;

import android.graphics.Paint;

import java.util.ArrayList;
import java.util.List;

public final class TextLayout {

    private TextLayout() {
    }

    public static class Result {

        public final List<String> lines;
        public final float textSize;
        public final float lineHeight;
        public final float totalHeight;

        public Result(
                List<String> lines,
                float textSize,
                float lineHeight,
                float totalHeight
        ) {

            this.lines = lines;
            this.textSize = textSize;
            this.lineHeight = lineHeight;
            this.totalHeight = totalHeight;
        }
    }

    public static Result createVerseLayout(
            Paint paint,
            String text,
            float maxWidth,
            float maxHeight
    ) {

        float size = 72.0f;

        while (size >= 24.0f) {

            paint.setTextSize(size);

            List<String> lines =
                    wrapText(
                            paint,
                            text,
                            maxWidth
                    );

            Paint.FontMetrics metrics =
                    paint.getFontMetrics();

            float lineHeight =
                    (metrics.bottom - metrics.top) * 1.25f;

            float totalHeight =
                    lineHeight * lines.size();

            if (totalHeight <= maxHeight) {

                return new Result(
                        lines,
                        size,
                        lineHeight,
                        totalHeight
                );
            }

            size -= 2.0f;
        }

        paint.setTextSize(24.0f);

        List<String> lines =
                wrapText(
                        paint,
                        text,
                        maxWidth
                );

        Paint.FontMetrics metrics =
                paint.getFontMetrics();

        float lineHeight =
                (metrics.bottom - metrics.top) * 1.25f;

        float totalHeight =
                lineHeight * lines.size();

        return new Result(
                lines,
                24.0f,
                lineHeight,
                totalHeight
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
                ).replace(
                        "\n",
                        " "
                ).trim();

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

        for (int i = 0; i < word.length(); i++) {

            char c = word.charAt(i);

            String candidate =
                    part.toString() + c;

            if (part.length() > 0 &&
                    paint.measureText(candidate)
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
}
