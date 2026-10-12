# Linux run of the DAP probe: docker build -t dap-probe . && docker run --rm --cap-add=SYS_PTRACE --security-opt seccomp=unconfined dap-probe
FROM mcr.microsoft.com/dotnet/sdk:10.0
RUN apt-get update && apt-get install -y --no-install-recommends python3 procps locales && rm -rf /var/lib/apt/lists/*
ENV DOTNET_NOLOGO=1 DOTNET_CLI_TELEMETRY_OPTOUT=1 PYTHONIOENCODING=utf-8 PYTHONUNBUFFERED=1
WORKDIR /probe
ARG DEBUGGER_VERSION=0.1.0
RUN dotnet tool install dotnet-debugger-dap --version ${DEBUGGER_VERSION} --tool-path /probe/tools
COPY t/ t/
RUN dotnet build t/App/App.csproj -nologo -v q
COPY *.py run_all.sh ./
CMD ["bash", "run_all.sh"]
