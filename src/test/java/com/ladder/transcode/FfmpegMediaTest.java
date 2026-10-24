package com.ladder.transcode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ladder.transcode.FfmpegTranscoder.TranscodeException;

// Runs the real ffmpeg and ffprobe on tiny generated clips (ffmpeg's lavfi test
// pattern), no fixtures to commit. Skipped, not failed, where they aren't installed.
class FfmpegMediaTest {

    private final FfmpegTranscoder transcoder = new FfmpegTranscoder();

    @TempDir Path dir;

    @BeforeAll
    static void needFfmpeg() {
        assumeTrue(runs("ffmpeg", "-version") && runs("ffprobe", "-version"), "ffmpeg/ffprobe not installed");
    }

    @Test
    void durationIsRoundedToTheNearestSecond() throws Exception {
        assertThat(transcoder.probeDurationSecs(clip("a.mp4", "1280x720", 2.6))).isEqualTo(3);
        assertThat(transcoder.probeDurationSecs(clip("b.mp4", "1280x720", 2.4))).isEqualTo(2);
    }

    @Test
    void aVeryShortClipStillHasADuration() throws Exception {
        assertThat(transcoder.probeDurationSecs(clip("short.mp4", "320x240", 0.4))).isEqualTo(1);
    }

    @Test
    void thumbnailIsAJpegScaledDownTo640Wide() throws Exception {
        Path source = clip("wide.mp4", "1280x720", 4);
        Path jpeg = dir.resolve("wide.jpg");

        transcoder.captureThumbnail(source, jpeg, transcoder.probeDurationSecs(source));

        BufferedImage image = ImageIO.read(jpeg.toFile()); // null if it isn't a decodable image
        assertThat(image).isNotNull();
        assertThat(image.getWidth()).isEqualTo(640);
        assertThat(image.getHeight()).isEqualTo(360); // aspect ratio kept
    }

    @Test
    void aSmallSourceIsNeverUpscaled() throws Exception {
        Path source = clip("small.mp4", "320x240", 2);
        Path jpeg = dir.resolve("small.jpg");

        transcoder.captureThumbnail(source, jpeg, 2);

        assertThat(ImageIO.read(jpeg.toFile()).getWidth()).isEqualTo(320);
    }

    @Test
    void unknownDurationFallsBackToTheFirstFrame() throws Exception {
        Path jpeg = dir.resolve("first.jpg");

        transcoder.captureThumbnail(clip("c.mp4", "640x360", 2), jpeg, null);

        assertThat(Files.size(jpeg)).isPositive();
    }

    @Test
    void notAVideoFailsLoudly() throws Exception {
        Path garbage = Files.writeString(dir.resolve("garbage.mp4"), "this is not a video");

        assertThatThrownBy(() -> transcoder.probeDurationSecs(garbage)).isInstanceOf(TranscodeException.class);
        assertThatThrownBy(() -> transcoder.captureThumbnail(garbage, dir.resolve("g.jpg"), null))
                .isInstanceOf(TranscodeException.class);
    }

    private Path clip(String name, String size, double seconds) throws Exception {
        Path file = dir.resolve(name);
        boolean ok = runs("ffmpeg", "-y", "-f", "lavfi", "-i", "testsrc=size=" + size + ":rate=24:duration=" + seconds,
                "-pix_fmt", "yuv420p", file.toString());
        assertThat(ok).as("generating the test clip").isTrue();
        return file;
    }

    private static boolean runs(String... command) {
        try {
            Process process = new ProcessBuilder(List.of(command)).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            return process.waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }
}
