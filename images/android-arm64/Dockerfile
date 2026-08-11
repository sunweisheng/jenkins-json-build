ARG ANDROID_BASE_IMAGE
ARG JDK17_IMAGE

FROM ${JDK17_IMAGE} AS jdk17
FROM ${ANDROID_BASE_IMAGE}

COPY --from=jdk17 /opt/java/openjdk /opt/java/openjdk-17
COPY --chmod=0555 aapt2 /opt/android-sdk-linux/build-tools/36.0.0/aapt2

RUN ln -s /opt/java/openjdk-17 /usr/lib/jvm/java-17-openjdk-arm64
