package com.ladder.transcode;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Invokes the ffmpeg binary as a subprocess to produce one HLS rendition.
 *
 * WHY ONE RENDITION PER INVOCATION, NOT ALL FOUR AT ONCE:
 *   ffmpeg can emit the whole ladder in a single command via -var_stream_map,
 *   and it is marginally more efficient (the source is decoded once). It was
 *   rejected here for three operational reasons:
 *
 *     1. Retry granularity. If 1080p fails, a single-command job forces you to
 *        redo 240p/480p/720p as well.
 *     2. Parallelism. Four independent jobs spread across four workers finish
 *        in roughly the time of the slowest rendition, not the sum.
 *     3. Progress. "3 of 4 renditions ready" is a state you can surface and
 *        even begin serving; "the transcode is running" is not.
 *
 *   This maps directly onto the RabbitMQ design: one message per
 *   (video_id, resolution) pair, which is also exactly one row in
 *   video_resolutions. Queue message, worker job, and database row line up
 *   one-to-one, so partial failure is representable rather than a mess.
 */
@Slf4j
@Component
public class FfmpegTranscoder {

    /**
     * Keyframe interval in frames. Assumes ~24fps source, so 48 frames = 2s.
     *
     * THIS IS THE MOST IMPORTANT NUMBER IN THIS FILE.
     *
     * Adaptive bitrate switching requires every rendition to have keyframes at
     * IDENTICAL timestamps. A player switching from 720p to 480p mid-playback
     * can only cut at a keyframe -- if the ladder's renditions have keyframes
     * in different places, switching stutters, or fails outright.
     *
     * Left to itself ffmpeg places keyframes adaptively based on scene changes,
     * which yields DIFFERENT positions for each rendition since each sees a
     * differently-scaled image. So we pin it: fixed GOP length, minimum
     * keyframe interval equal to it, and scene-change detection disabled.
     *
     * The interval must divide the segment duration evenly.
     * 10s segments / 2s keyframes = 5 keyframes per segment. Clean.
     */
    private static final int GOP_SIZE = 48;
    private static final int SEGMENT_SECONDS = 10;

    /**
     * Generous, but bounded. A stuck ffmpeg must not pin a worker forever.
     * Kept under RabbitMQ's default consumer_timeout (30 min): past that the
     * broker closes the channel and redelivers the job while ffmpeg still runs.
     */
    private static final Duration TIMEOUT = Duration.ofMinutes(25);

    /** How much of ffmpeg's output to keep for the error message. */
    private static final int LOG_TAIL_BYTES = 8192;

    public record Rendition(String label, int width, int height,
                            int videoBitrateKbps, int audioBitrateKbps) {

        public static final Rendition P240  = new Rendition("240p",   426,  240,   400,  64);
        public static final Rendition P480  = new Rendition("480p",   854,  480,  1400, 128);
        public static final Rendition P720  = new Rendition("720p",  1280,  720,  2800, 128);
        public static final Rendition P1080 = new Rendition("1080p", 1920, 1080,  5000, 192);

        /** Every rung, lowest first. The one list both publisher and worker use. */
        public static final List<Rendition> LADDER = List.of(P240, P480, P720, P1080);

        /** Messages carry only the label ("480p"); the settings are looked up here. */
        public static Optional<Rendition> fromLabel(String label) {
            return LADDER.stream().filter(r -> r.label().equals(label)).findFirst();
        }
    }

    /**
     * Transcodes {@code source} into a single HLS rendition under {@code outputDir}.
     * Produces playlist.m3u8 plus segment_NNN.ts files.
     *
     * @throws TranscodeException on non-zero exit, timeout, or interruption
     */
    public void transcode(Path source, Path outputDir, Rendition r) {

        List<String> cmd = new ArrayList<>(List.of(
            "ffmpeg",
            "-nostdin",              // never wait for terminal input -- there is none
            "-y",                    // overwrite; a retried job must not hang on a prompt
            "-i", source.toString(),

            // Scale to the target height, preserving aspect ratio. The -2 keeps
            // width even (H.264 requires even dimensions) and derived from the source
            // aspect rather than forced to the nominal width.
            "-vf", "scale=-2:" + r.height(),

            "-c:v", "libx264",
            "-preset", "veryfast",   // speed/size trade-off; slower presets shrink files
            "-profile:v", "main",

            // Keyframe pinning -- see GOP_SIZE comment above.
            "-g", String.valueOf(GOP_SIZE),
            "-keyint_min", String.valueOf(GOP_SIZE),
            "-sc_threshold", "0",

            // Constrained bitrate. maxrate/bufsize keep the stream within what a
            // client at this rung can actually sustain; without them libx264 will
            // spike on complex scenes and defeat the point of the ladder.
            "-b:v", r.videoBitrateKbps() + "k",
            "-maxrate", (int) (r.videoBitrateKbps() * 1.07) + "k",
            "-bufsize", (r.videoBitrateKbps() * 2) + "k",

            "-c:a", "aac",
            "-b:a", r.audioBitrateKbps() + "k",
            "-ac", "2",

            "-f", "hls",
            "-hls_time", String.valueOf(SEGMENT_SECONDS),
            "-hls_playlist_type", "vod",
            "-hls_segment_filename", outputDir.resolve("segment_%03d.ts").toString(),
            outputDir.resolve("playlist.m3u8").toString()
        ));

        run(cmd, r.label());
    }

    private void run(List<String> cmd, String label) {
        log.info("ffmpeg start [{}]: {}", label, String.join(" ", cmd));

        // ====================================================================
        // OUTPUT GOES TO A FILE, NOT A PIPE.
        //
        // ffmpeg writes progress to stderr continuously. Through a pipe, the OS
        // buffer (tens of KB) fills unless Java keeps reading it; ffmpeg then
        // blocks on write while Java blocks in waitFor(): a deadlock that only
        // shows up on long videos. Draining it in a loop avoids that, but the
        // loop only ends when ffmpeg exits, so a timeout placed after it can
        // never fire.
        //
        // A file has no buffer to fill and nothing to drain, so waitFor() runs
        // immediately and the timeout actually works. On failure we read only
        // the file's tail, where ffmpeg puts the real error.
        // ====================================================================
        Path logFile;
        try {
            logFile = Files.createTempFile("ffmpeg-" + label + "-", ".log");
        } catch (IOException e) {
            throw new TranscodeException("could not create ffmpeg log file [" + label + "]", e);
        }

        try {
            Process process;
            try {
                process = new ProcessBuilder(cmd)
                        .redirectErrorStream(true)          // stderr and stdout into one file
                        .redirectOutput(logFile.toFile())
                        .start();
            } catch (IOException e) {
                throw new TranscodeException("could not start ffmpeg -- is the binary on PATH?", e);
            }

            boolean finished;
            try {
                finished = process.waitFor(TIMEOUT.toMinutes(), TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                process.destroyForcibly();
                // Restore the flag -- swallowing it breaks graceful shutdown, and the
                // worker's shutdown hook will never see the signal.
                Thread.currentThread().interrupt();
                throw new TranscodeException("interrupted during transcode [" + label + "]", e);
            }

            if (!finished) {
                process.destroyForcibly();
                throw new TranscodeException(
                    "ffmpeg exceeded " + TIMEOUT.toMinutes() + "min [" + label + "]\n" + tail(logFile));
            }

            int exit = process.exitValue();
            if (exit != 0) {
                // The tail is the only useful diagnostic; ffmpeg's real error is
                // almost always in its last few lines.
                throw new TranscodeException("ffmpeg exited " + exit + " [" + label + "]\n" + tail(logFile));
            }

            log.info("ffmpeg done [{}]", label);
        } finally {
            try {
                Files.deleteIfExists(logFile);
            } catch (IOException e) {
                log.warn("could not delete ffmpeg log {}", logFile, e);
            }
        }
    }

    /** Last LOG_TAIL_BYTES of the log, without reading a multi-MB file into memory. */
    private static String tail(Path logFile) {
        try (SeekableByteChannel channel = Files.newByteChannel(logFile)) {
            long start = Math.max(0, channel.size() - LOG_TAIL_BYTES);
            ByteBuffer buffer = ByteBuffer.allocate((int) (channel.size() - start));
            channel.position(start);
            while (buffer.hasRemaining() && channel.read(buffer) > 0) {
                // keep reading until the buffer is full or the file ends
            }
            return new String(buffer.array(), 0, buffer.position(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "(could not read ffmpeg log: " + e.getMessage() + ")";
        }
    }

    public static class TranscodeException extends RuntimeException {
        public TranscodeException(String message) { super(message); }
        public TranscodeException(String message, Throwable cause) { super(message, cause); }
    }
}

/*
 * ---------------------------------------------------------------------------
 * NOTES FOR THE CONSUMER THAT CALLS THIS
 * ---------------------------------------------------------------------------
 *
 * SET prefetch=1 ON THE RABBITMQ LISTENER.
 *   Default prefetch lets a consumer grab many messages at once. Transcoding is
 *   CPU-saturating, so a worker that accepts five jobs runs five ffmpeg
 *   processes competing for the same cores -- all five get slower and all five
 *   risk hitting the timeout. One job at a time; scale by adding workers.
 *
 * MAKE THE JOB IDEMPOTENT.
 *   A message can be redelivered after a worker dies mid-job. Since the output
 *   path is deterministic (video_id + resolution), a retry overwrites cleanly
 *   -- but the video_resolutions INSERT must be an upsert, or the retry
 *   violates uq_video_resolution and dead-letters a job that actually
 *   succeeded.
 *
 * BOUND THE RETRIES AND USE A DLQ.
 *   A corrupt upload fails deterministically. Without a retry ceiling it
 *   requeues forever and starves real work. Cap attempts, then dead-letter and
 *   mark the video FAILED.
 *
 * WRITE TO LOCAL DISK, THEN UPLOAD.
 *   ffmpeg needs a real filesystem for segmented output. Transcode into a temp
 *   directory, upload the segments and playlist to MinIO, record the prefix in
 *   video_resolutions.object_storage_path, then delete the temp directory in a
 *   finally block. Workers that leak temp files fill their disk within a day.
 *
 * THE MASTER PLAYLIST IS A SEPARATE STEP.
 *   Each job writes only its own rendition's playlist.m3u8. The master
 *   playlist -- the one the player actually requests, listing all four rungs
 *   with their BANDWIDTH and RESOLUTION attributes -- can only be written once
 *   every rendition is done. Generate it when the last job completes, then flip
 *   videos.status to READY. That transition is the moment the video becomes
 *   playable, and it belongs to the coordinator, not to any single job.
 * ---------------------------------------------------------------------------
 */
