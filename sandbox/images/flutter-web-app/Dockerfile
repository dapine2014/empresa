FROM ghcr.io/cirruslabs/flutter:3.24.3
RUN apt-get update && apt-get install -y --no-install-recommends wget gnupg python3 ca-certificates \
    && wget -q -O /tmp/chrome.deb https://dl.google.com/linux/direct/google-chrome-stable_current_amd64.deb \
    && apt-get install -y --no-install-recommends /tmp/chrome.deb \
    && rm -f /tmp/chrome.deb && rm -rf /var/lib/apt/lists/*
# Caché de pub fuera de /root y SDK propiedad del usuario del sandbox (uid 1000, igual que --userns=keep-id):
# la imagen base declara USER root y con eso keep-id no aplica (archivos con uid ajeno en el host).
ENV PUB_CACHE=/opt/pub-cache
# flutter_tools también se resuelve contra la caché nueva (si no, sin red intenta bajarlo de pub.dev en cada paso).
RUN mkdir -p /opt/pub-cache && flutter config --no-analytics \
    && cd /sdks/flutter/packages/flutter_tools && flutter pub get && cd / \
    && flutter precache --web \
    && flutter create --platforms=web /opt/seed && cd /opt/seed && flutter pub get \
    && rm -rf /opt/seed && chown -R 1000:1000 /sdks/flutter /opt/pub-cache
COPY run.sh /forjai/run.sh
RUN chmod 0755 /forjai/run.sh
USER 1000:1000
