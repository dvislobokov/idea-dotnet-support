FROM mcr.microsoft.com/dotnet/sdk:${DOTNET_VERSION} AS build
WORKDIR /src
COPY . .
RUN dotnet publish "${PROJECT_FILE}" -c Release -o /app/publish

FROM mcr.microsoft.com/dotnet/${RUNTIME_IMAGE}:${DOTNET_VERSION} AS final
WORKDIR /app
COPY --from=build /app/publish .
ENTRYPOINT ["dotnet", "${PROJECT}.dll"]
