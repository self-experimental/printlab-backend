# Runtime image: CI supplies the JAR only after Maven verify passes.
FROM ubuntu:24.04@sha256:68ee22004a9109b44973313b02ff7b1de250f1de0f5ecb628d73f579aaff33ac
ARG DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates curl bzip2 libgtk-3-0 libwebkit2gtk-4.1-0 \
    libgl1 libglu1-mesa libgomp1 libsecret-1-0 \
    && rm -rf /var/lib/apt/lists/*
RUN curl -fL --retry 3 'https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.5%2B8/OpenJDK25U-jre_x64_linux_hotspot_25.0.5_8.tar.gz' -o /tmp/java.tar.gz \
    && echo '6fac98f0c8202efc3135a9e9e42aa61963ec42d1c5cbb40caf231e7b14a24ef6  /tmp/java.tar.gz' | sha256sum -c - \
    && mkdir /opt/java && tar -xzf /tmp/java.tar.gz -C /opt/java --strip-components=1 \
    && rm /tmp/java.tar.gz
RUN curl -fL --retry 3 'https://github.com/bambulab/BambuStudio/releases/download/v02.08.02.61/BambuStudio_ubuntu24.04-v02.08.02.61-20260820225108.AppImage' -o /tmp/bambu.AppImage \
    && echo 'd501b103fac5424513ec0e8d6bc145fb30719de2c7d94d7320d723740c81a7fd  /tmp/bambu.AppImage' | sha256sum -c - \
    && chmod +x /tmp/bambu.AppImage && cd /opt && /tmp/bambu.AppImage --appimage-extract > /dev/null \
    && mv squashfs-root bambu && rm /tmp/bambu.AppImage
RUN groupadd -g 10001 printlab && useradd -u 10001 -g printlab -d /var/lib/printlab -M printlab \
    && mkdir -p /app /var/lib/printlab && chown printlab:printlab /var/lib/printlab
ENV PATH="/opt/java/bin:${PATH}" \
    HOME=/var/lib/printlab XDG_CONFIG_HOME=/var/lib/printlab/config XDG_CACHE_HOME=/var/lib/printlab/cache \
    SERVER_ADDRESS=0.0.0.0 SERVER_PORT=8080 SPRING_PROFILES_ACTIVE=production \
    PRINTLAB_DATA_DIR=/var/lib/printlab PRINTLAB_MODELS_DIR=/var/lib/printlab/uploads \
    PRINTLAB_BAMBU_EXECUTABLE=/opt/bambu/AppRun PRINTLAB_BAMBU_PROFILES=/opt/bambu/resources/profiles/BBL
COPY data/experiments.json data/research-models.json /app/seed/
COPY deploy/container-entrypoint.sh /app/entrypoint.sh
COPY app.jar /app/app.jar
USER 10001:10001
WORKDIR /app
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=5s --start-period=60s --retries=6 \
    CMD curl --fail --silent http://127.0.0.1:8080/api/materials > /dev/null || exit 1
ENTRYPOINT ["/bin/sh", "/app/entrypoint.sh"]
