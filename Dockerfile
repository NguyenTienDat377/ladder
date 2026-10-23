# Transcode worker image.
#
# This is a SEPARATE image from the API. The API never invokes ffmpeg, so
# shipping the binary there would add ~200MB and a nontrivial CVE surface to a
# container that has no use for it. Splitting also lets the two scale
# independently -- transcode capacity is CPU-bound and bursty, API capacity is
# not, and you do not want to add API replicas just to clear a transcode
# backlog.

FROM eclipse-temurin:21-jre-jammy

# ffmpeg is a native binary, not a Java dependency -- it is installed here
# rather than declared in build.gradle. That is the whole trade-off of the
# ProcessBuilder approach: nothing in the build, one line in the image.
#
# --no-install-recommends matters. Without it apt pulls in a large set of
# optional codec and X11 packages that this container will never use.
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/*

# Fail fast and loudly at build time if the binary is not actually usable,
# rather than at 2am when the first job dead-letters.
RUN ffmpeg -version

# Run as non-root. The worker executes a subprocess on user-supplied media --
# a decoder bug in ffmpeg on a malicious upload is a real (if unlikely) path to
# code execution, and root makes the difference between a contained incident
# and a compromised host.
RUN useradd --system --create-home --uid 10001 worker
USER worker

WORKDIR /app
COPY --chown=worker:worker build/libs/*.jar app.jar

# Scratch space for transcode output before upload to MinIO. Mount a volume
# here in production -- container filesystems are typically small, and a 4K
# source expanded into four renditions of segments is easily several GB.
VOLUME ["/tmp/transcode"]

ENTRYPOINT ["java", "-jar", "/app/app.jar"]