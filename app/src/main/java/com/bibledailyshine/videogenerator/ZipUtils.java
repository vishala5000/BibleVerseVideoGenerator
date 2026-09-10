package com.bibledailyshine.videogenerator;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class ZipUtils {

    private ZipUtils() {
    }

    public static void createZip(
            List<File> files,
            File output
    ) throws Exception {

        if (output.exists()) {
            output.delete();
        }

        try (
                FileOutputStream fos =
                        new FileOutputStream(output);

                BufferedOutputStream bos =
                        new BufferedOutputStream(fos);

                ZipOutputStream zos =
                        new ZipOutputStream(bos)
        ) {

            byte[] buffer =
                    new byte[64 * 1024];

            for (File file : files) {

                if (file == null ||
                        !file.exists() ||
                        !file.isFile()) {

                    continue;
                }

                ZipEntry entry =
                        new ZipEntry(
                                file.getName()
                        );

                zos.putNextEntry(entry);

                try (
                        FileInputStream fis =
                                new FileInputStream(file);

                        BufferedInputStream bis =
                                new BufferedInputStream(fis)
                ) {

                    int count;

                    while ((count =
                            bis.read(buffer)) != -1) {

                        zos.write(
                                buffer,
                                0,
                                count
                        );
                    }
                }

                zos.closeEntry();
            }
        }
    }
}
