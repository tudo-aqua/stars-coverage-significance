FROM ubuntu:24.04

RUN apt-get update && apt-get install -y --no-install-recommends \
      software-properties-common ca-certificates curl gnupg git \
      python3 python3-pip \
    && mkdir -p /etc/apt/keyrings \
    && curl -fsSL https://packages.adoptium.net/artifactory/api/gpg/key/public \
      | gpg --dearmor -o /etc/apt/keyrings/adoptium.gpg \
    && echo "deb [signed-by=/etc/apt/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb $(. /etc/os-release && echo $VERSION_CODENAME) main" \
      > /etc/apt/sources.list.d/adoptium.list \
    && apt-get update \
    && apt-get install -y --no-install-recommends temurin-21-jdk graphviz \
    && rm -rf /var/lib/apt/lists/*

ENV JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64
ENV PATH="${JAVA_HOME}/bin:${PATH}"

RUN add-apt-repository ppa:sumo/stable
RUN apt-get update -y
RUN apt-get install -y sumo sumo-tools sumo-doc

RUN ln -s /usr/lib/x86_64-linux-gnu/liblibsumojni.so /usr/lib/liblibsumojni.so

RUN pip3 install --break-system-packages --no-cache-dir \
    matplotlib \
    numpy \
    pandas \
    scipy \
    lightgbm \
    polars \
    connectorx \
    graphviz \
    pyarrow

# Clone repository into the image
ARG REPO_URL=https://github.com/tudo-aqua/stars-coverage-significance
WORKDIR /app
RUN git clone ${REPO_URL}

ARG STARS_REPO_URL=https://github.com/tudo-aqua/stars
RUN git clone -b coverage-significance ${STARS_REPO_URL}

WORKDIR /app/stars-coverage-significance
RUN chmod +x ./gradlew

RUN ./gradlew assemble

