# Bible Verse Video Generator — Android

A native Android app that generates one 8-second vertical Bible verse video for every non-empty input line.

## Output

- 1080 x 1920 pixels
- Exactly 8 seconds
- H.264 video
- 30 FPS
- Black background
- White verse text
- Yellow `Bible Verse` heading at the top center
- Yellow Bible reference above the verse
- Verse/reference text centered
- Text wrapping limited to 680 px width
- Text block limited to 1320 px height
- Safe text area: y=200 through y=1720; no generated text is intentionally placed outside it
- Background audio from `app/src/main/assets/bg.mp3`
- Font from `app/src/main/assets/font.ttf`
- Files are named `1.mp4`, `2.mp4`, `3.mp4`, ...
- All generated videos are placed into `BibleVerseVideos.zip`

## Input format

Enter one verse per line:

`Be strong and courageous. Do not be afraid; do not be discouraged. — Joshua 1:9`

The app produces:

`Bible Verse`

`Joshua 1:9` (yellow)

`Be strong and courageous.`
`Do not be afraid;`
`do not be discouraged.`

The app automatically reduces the verse font size when a long verse would exceed the 680 x 1320 text area.

## Included assets

The ZIP contains a default `font.ttf` and a short silent `bg.mp3` so the project builds and can generate immediately. Replace those two files with your own assets, keeping the exact filenames:

- `app/src/main/assets/font.ttf`
- `app/src/main/assets/bg.mp3`

## Build on Windows without Android Studio

Install:

1. Eclipse Temurin JDK 17
2. Android SDK Command-Line Tools
3. Android SDK Platform 35
4. Android SDK Build-Tools 35.x

Open CMD in the project folder:

`gradlew.bat assembleDebug`

Release:

`gradlew.bat assembleRelease`

The APK will be under:

`app\\build\\outputs\\apk\\release\\app-release.apk`

The included Gradle wrapper downloads Gradle 8.10.2 automatically the first time it is used.

## GitHub Actions

Push the folder to a GitHub repository. The included workflow:

`.github/workflows/build.yml`

uses Temurin JDK 17 and builds `assembleRelease`. The APK is uploaded as a workflow artifact.

## Important FFmpeg note

The project uses the 16 KB-page-size Android FFmpegKit build `io.github.minorlai:ffmpeg-kit-16kb:6.1.2`. It provides the FFmpegKit API and Android native binaries for `arm64-v8a` and `armeabi-v7a`; the app uses the H.264 `libopenh264` encoder.

The APK is configured for both `arm64-v8a` and `armeabi-v7a`, covering modern 64-bit ARM phones and older 32-bit ARM phones. x86/x86_64 Android devices are not included.
