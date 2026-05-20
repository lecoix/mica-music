FROM ubuntu:22.04

ENV DEBIAN_FRONTEND=noninteractive
ENV ROOT=/work/mica-android

RUN apt-get update && apt-get install -y --no-install-recommends \
    curl ca-certificates xz-utils unzip build-essential pkg-config yasm nasm \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /work/mica-android
