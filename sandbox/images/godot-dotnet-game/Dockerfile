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
COPY NuGet.Config /forjai/NuGet.Config
COPY run.sh /forjai/run.sh
RUN chmod 0755 /forjai/run.sh && chmod -R a+rX /opt/nuget-packages
