FROM mcr.microsoft.com/dotnet/sdk:8.0
RUN apt-get update && apt-get install -y --no-install-recommends unzip wget ca-certificates libfontconfig1 \
    && rm -rf /var/lib/apt/lists/*
RUN wget -q https://github.com/godotengine/godot/releases/download/4.3-stable/Godot_v4.3-stable_mono_linux_x86_64.zip \
    && unzip -q Godot_v4.3-stable_mono_linux_x86_64.zip -d /opt \
    && mv /opt/Godot_v4.3-stable_mono_linux_x86_64 /opt/godot \
    && ln -s /opt/godot/Godot_v4.3-stable_mono_linux.x86_64 /usr/local/bin/godot \
    && rm Godot_v4.3-stable_mono_linux_x86_64.zip
# Todo lo que restaura el seed (incluidos los SDK de MSBuild como Godot.NET.Sdk) queda en el feed offline.
ENV NUGET_PACKAGES=/opt/nuget-packages
RUN mkdir -p /opt/seed && cd /opt/seed \
    && dotnet new xunit -n Seed -o /opt/seed/Seed \
    && dotnet restore /opt/seed/Seed --packages /opt/nuget-packages \
    && printf '<Project Sdk="Godot.NET.Sdk/4.3.0"><PropertyGroup><TargetFramework>net8.0</TargetFramework></PropertyGroup></Project>' > /opt/seed/G.csproj \
    && dotnet restore /opt/seed/G.csproj --packages /opt/nuget-packages \
    && rm -rf /opt/seed
# Entrega del producto (spec 2026-10-17): export templates de Godot 4.3 mono. El .tpz es un zip con templates/;
# solo quedan las plataformas que se entregan (Windows, Linux, macOS y Android), sin web, iOS ni 32 bits.
RUN wget -q https://github.com/godotengine/godot/releases/download/4.3-stable/Godot_v4.3-stable_mono_export_templates.tpz \
    && unzip -q Godot_v4.3-stable_mono_export_templates.tpz -d /tmp/tpz \
    && mkdir -p /opt/godot-templates/4.3.stable.mono \
    && cd /tmp/tpz/templates \
    && mv version.txt windows_debug_x86_64.exe windows_release_x86_64.exe \
          windows_debug_x86_64_console.exe windows_release_x86_64_console.exe \
          linux_debug.x86_64 linux_release.x86_64 macos.zip \
          android_debug.apk android_release.apk android_source.zip /opt/godot-templates/4.3.stable.mono/ \
    && cd / && rm -rf /tmp/tpz Godot_v4.3-stable_mono_export_templates.tpz
# Android: JDK 17, SDK (cmdline-tools, platform-tools, build-tools 34, platform 34) y keystore de depuración.
RUN apt-get update && apt-get install -y --no-install-recommends openjdk-17-jdk-headless \
    && rm -rf /var/lib/apt/lists/*
ENV ANDROID_HOME=/opt/android-sdk
RUN wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip \
    && mkdir -p /opt/android-sdk/cmdline-tools \
    && unzip -q commandlinetools-linux-11076708_latest.zip -d /opt/android-sdk/cmdline-tools \
    && mv /opt/android-sdk/cmdline-tools/cmdline-tools /opt/android-sdk/cmdline-tools/latest \
    && rm commandlinetools-linux-11076708_latest.zip \
    && yes | /opt/android-sdk/cmdline-tools/latest/bin/sdkmanager --licenses > /dev/null \
    && /opt/android-sdk/cmdline-tools/latest/bin/sdkmanager "platform-tools" "build-tools;34.0.0" "platforms;android-34" > /dev/null \
    && chmod -R a+rX /opt/android-sdk
RUN mkdir -p /opt/android && keytool -genkeypair -v -keystore /opt/android/debug.keystore -storepass android \
       -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 \
       -dname "CN=Android Debug,O=Android,C=US" \
    && chmod a+r /opt/android/debug.keystore
# Runtime packs de cada plataforma en el feed offline: Godot exporta con `dotnet publish -r <rid> --self-contained`.
RUN mkdir -p /opt/seed && cd /opt/seed \
    && printf '<Project Sdk="Godot.NET.Sdk/4.3.0"><PropertyGroup><TargetFramework>net8.0</TargetFramework></PropertyGroup></Project>' > /opt/seed/G.csproj \
    && for spec in windows:win-x64 macos:osx-x64 macos:osx-arm64 linuxbsd:linux-x64 android:android-arm64; do \
         dotnet publish /opt/seed/G.csproj -c ExportRelease -r "${spec#*:}" --self-contained \
           -p:GodotTargetPlatform="${spec%%:*}" -o "/tmp/seed-${spec#*:}" || exit 1; \
       done \
    && cd / && rm -rf /opt/seed /tmp/seed-*
COPY NuGet.Config /forjai/NuGet.Config
COPY export_presets.cfg editor_settings-4.3.tres /forjai/
COPY run.sh /forjai/run.sh
RUN chmod 0755 /forjai/run.sh && chmod -R a+rX /opt/nuget-packages /forjai
