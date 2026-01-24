FROM ghcr.io/eclipse-sumo/sumo:v1_25_0

USER root

# ---- Install Java 21 (Temurin) ----
RUN apt-get update && apt-get install -y --no-install-recommends \
      ca-certificates \
      curl \
      gnupg \
    && mkdir -p /etc/apt/keyrings \
    && curl -fsSL https://packages.adoptium.net/artifactory/api/gpg/key/public \
      | gpg --dearmor -o /etc/apt/keyrings/adoptium.gpg \
    && echo "deb [signed-by=/etc/apt/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb $(. /etc/os-release && echo $VERSION_CODENAME) main" \
      > /etc/apt/sources.list.d/adoptium.list \
    && apt-get update \
    && apt-get install -y --no-install-recommends temurin-21-jdk \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*

ENV JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64
ENV PATH="${JAVA_HOME}/bin:${PATH}"

# ---- Optional: create a normal user for IntelliJ exec ----
ARG UID=1000
ARG GID=1000
RUN groupadd -g ${GID} dev \
 && useradd -m -u ${UID} -g ${GID} -s /bin/bash dev

WORKDIR /workspace
USER root

# Keep the container running for IntelliJ to attach/exec into
ENTRYPOINT ["sleep", "infinity"]
