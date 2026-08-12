ARG ANDROID_BASE_IMAGE
ARG JDK17_IMAGE
ARG ANDROID_BUILD_TOOLS_VERSION

FROM ${JDK17_IMAGE} AS jdk17
FROM ${ANDROID_BASE_IMAGE}
ARG ANDROID_BUILD_TOOLS_VERSION

COPY --from=jdk17 /opt/java/openjdk /opt/java/openjdk-17
COPY build-tools/ /opt/android-sdk-linux/build-tools/${ANDROID_BUILD_TOOLS_VERSION}/
COPY --chmod=0555 arm64-build-tools/ /opt/android-sdk-linux/build-tools/${ANDROID_BUILD_TOOLS_VERSION}/
COPY --chmod=0555 arm64-build-tools/aapt2 /opt/android-sdk-linux/build-tools/36.0.0/aapt2

RUN grep -Fx "Pkg.Revision=${ANDROID_BUILD_TOOLS_VERSION}" "/opt/android-sdk-linux/build-tools/${ANDROID_BUILD_TOOLS_VERSION}/source.properties" \
    && test "$(od -An -tx1 -j18 -N2 "/opt/android-sdk-linux/build-tools/${ANDROID_BUILD_TOOLS_VERSION}/aapt2" | tr -d ' ')" = "b700" \
    && ln -s /opt/java/openjdk-17 /usr/lib/jvm/java-17-openjdk-arm64
