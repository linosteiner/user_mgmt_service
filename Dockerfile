FROM gradle:jdk25-alpine AS jre-build
LABEL authors="Lennard Bernet, Lino Steiner"

RUN "$JAVA_HOME/bin/jlink" \
         --verbose \
         --add-modules java.base,java.logging,java.naming,java.management,java.instrument,java.sql,java.desktop,java.xml,java.security.jgss,java.net.http,jdk.crypto.ec,jdk.unsupported \
         --strip-debug --no-man-pages --no-header-files --compress=2 \
         --output /optimized-jdk-25

WORKDIR /app
COPY build.gradle ./
RUN --mount=type=cache,target=/cache/.gradle \
    gradle dependencies --no-daemon --stacktrace
COPY src/ src/
RUN --mount=type=cache,target=/cache/.gradle \
    gradle build -x test --no-daemon --stacktrace --build-cache


FROM alpine:3.23.5
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="${JAVA_HOME}/bin:${PATH}"
COPY --from=jre-build /optimized-jdk-25 $JAVA_HOME

RUN mkdir /opt/app \
&& mkdir /app \
&& addgroup --system app \
&& adduser -S -s /bin/false -G app app

USER app

COPY --from=jre-build /app/build/libs/*.jar /opt/app/app.jar
CMD ["java", "-jar", "/opt/app/app.jar"]
