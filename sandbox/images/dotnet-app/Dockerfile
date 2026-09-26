FROM mcr.microsoft.com/dotnet/sdk:8.0
# Feed offline con el catálogo inicial de paquetes (la parte 3 lo hace crecer con aprobación).
RUN mkdir -p /opt/seed && cd /opt/seed \
    && dotnet new xunit -n Seed -o /opt/seed/Seed \
    && dotnet new webapi -n SeedApi -o /opt/seed/SeedApi \
    && dotnet restore /opt/seed/Seed --packages /opt/nuget-packages \
    && dotnet restore /opt/seed/SeedApi --packages /opt/nuget-packages \
    && rm -rf /opt/seed
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
COPY run.sh /forjai/run.sh
RUN chmod 0755 /forjai/run.sh
