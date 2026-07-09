# SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
# The image is built from the Gradle `installDist` output (bin/ + lib/), produced by CI before the
# docker build — keeping this Dockerfile trivial and letting the Gradle build cache do the work.
# eclipse-temurin's JRE image ships busybox wget, which the compose healthcheck uses.
FROM eclipse-temurin:21-jre-alpine

RUN adduser -D -H -s /sbin/nologin app
WORKDIR /app
COPY build/install/server/ /app/

EXPOSE 8080
USER app

# JAVA_OPTS is appended by the Gradle-generated start script; docker-compose sets the heap caps.
ENTRYPOINT ["/app/bin/server"]
