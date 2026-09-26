FROM ghcr.io/cirruslabs/flutter:3.24.3
RUN apt-get update && apt-get install -y --no-install-recommends wget gnupg python3 ca-certificates \
    && wget -q -O /tmp/chrome.deb https://dl.google.com/linux/direct/google-chrome-stable_current_amd64.deb \
    && apt-get install -y --no-install-recommends /tmp/chrome.deb \
    && rm -f /tmp/chrome.deb && rm -rf /var/lib/apt/lists/*
# Caché de pub con el catálogo inicial (sin paquetes externos más allá del SDK).
RUN flutter create --platforms=web /opt/seed && cd /opt/seed && flutter pub get && flutter precache --web \
    && rm -rf /opt/seed && chmod -R a+rX /sdks /root/.pub-cache 2>/dev/null || true
ENV PUB_CACHE=/root/.pub-cache
COPY run.sh /forjai/run.sh
RUN chmod 0755 /forjai/run.sh
