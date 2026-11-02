FROM ghcr.io/cirruslabs/flutter:3.41.9
RUN apt-get update && apt-get install -y --no-install-recommends wget gnupg python3 ca-certificates \
    && wget -q -O /tmp/chrome.deb https://dl.google.com/linux/direct/google-chrome-stable_current_amd64.deb \
    && apt-get install -y --no-install-recommends /tmp/chrome.deb \
    && rm -f /tmp/chrome.deb && rm -rf /var/lib/apt/lists/*
# Caché de pub fuera de /root y SDK propiedad del usuario del sandbox (uid 1000, igual que --userns=keep-id):
# la imagen base declara USER root y con eso keep-id no aplica (archivos con uid ajeno en el host).
# Un `chown -R` de todo el SDK copiaba sus ~1 GB de la imagen base a esta capa (2026-09-30): el uid 1000 recibe
# los directorios (crear archivos como bin/cache/lockfile, y git ve el repo del SDK como propio) y los archivos que
# este mismo paso escribió (ya están en esta capa, no se duplican).
ENV PUB_CACHE=/opt/pub-cache
# flutter_tools también se resuelve contra la caché nueva (si no, sin red intenta bajarlo de pub.dev en cada paso).
RUN touch /tmp/antes-del-precache && sleep 1 \
    && mkdir -p /opt/pub-cache && flutter config --no-analytics \
    && cd /sdks/flutter/packages/flutter_tools && flutter pub get && cd / \
    && flutter precache --web \
    && flutter create --platforms=web /opt/seed && cd /opt/seed && flutter pub add http:1.2.2 && flutter pub get \
    && rm -rf /opt/seed && chown -R 1000:1000 /opt/pub-cache \
    && find /sdks/flutter \( -type d -o -newer /tmp/antes-del-precache \) -exec chown -h 1000:1000 {} + \
    && rm -f /tmp/antes-del-precache
# Diseño visual (spec 2026-11-01 §10): catálogo cerrado de fuentes (FontCatalog en company-core). Salen de los paquetes
# de Ubuntu (Roboto y Noto, licencias libres; sin URLs sueltas) y se copian con el nombre exacto que usan Java, las
# maquetas y assets/fonts; después se desinstalan, para que Chrome no las use como fuentes del sistema y un
# @font-face roto se vea en la captura.
RUN apt-get update && apt-get install -y --no-install-recommends fonts-roboto-unhinted fonts-noto-core \
    && mkdir -p /forjai/fonts \
    && for f in Roboto-Regular Roboto-Bold NotoSans-Regular NotoSans-Bold NotoSerif-Regular NotoSerif-Bold; do \
         src=$(find /usr/share/fonts -name "$f.ttf" | sort | head -1); \
         [ -n "$src" ] || { echo "falta la fuente $f"; exit 1; }; cp "$src" "/forjai/fonts/$f.ttf"; \
       done \
    && apt-get purge -y fonts-roboto-unhinted fonts-noto-core && apt-get autoremove -y && rm -rf /var/lib/apt/lists/* \
    && chmod 0644 /forjai/fonts/*.ttf
COPY run.sh failing_tests.py capture.py /forjai/
RUN chmod 0755 /forjai/run.sh /forjai/failing_tests.py /forjai/capture.py
USER 1000:1000
