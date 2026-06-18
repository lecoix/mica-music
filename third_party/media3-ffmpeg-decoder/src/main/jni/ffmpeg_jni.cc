/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
#include <android/log.h>
#include <jni.h>
#include <stdlib.h>
#include <string>

extern "C" {
#ifdef __cplusplus
#define __STDC_CONSTANT_MACROS
#ifdef _STDINT_H
#undef _STDINT_H
#endif
#include <stdint.h>
#endif
#include <libavcodec/avcodec.h>
#include <libavutil/channel_layout.h>
#include <libavutil/error.h>
#include <libavutil/opt.h>
#include <libswresample/swresample.h>
}

#define LOG_TAG "ffmpeg_jni"
#define LOGE(...) \
  ((void)__android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__))
#define LOGD(...) \
  ((void)__android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__))

#define LIBRARY_FUNC(RETURN_TYPE, NAME, ...)                             \
  extern "C" {                                                           \
  JNIEXPORT RETURN_TYPE Java_androidx_media3_decoder_ffmpeg_FfmpegLibrary_##NAME( \
      JNIEnv * env, jobject thiz, ##__VA_ARGS__);                        \
  }                                                                      \
  JNIEXPORT RETURN_TYPE Java_androidx_media3_decoder_ffmpeg_FfmpegLibrary_##NAME( \
      JNIEnv * env, jobject thiz, ##__VA_ARGS__)

#define AUDIO_DECODER_FUNC(RETURN_TYPE, NAME, ...)                       \
  extern "C" {                                                           \
  JNIEXPORT RETURN_TYPE                                                  \
  Java_androidx_media3_decoder_ffmpeg_FfmpegAudioDecoder_##NAME(           \
      JNIEnv * env, jobject thiz, ##__VA_ARGS__);                        \
  }                                                                      \
  JNIEXPORT RETURN_TYPE                                                  \
  Java_androidx_media3_decoder_ffmpeg_FfmpegAudioDecoder_##NAME(           \
      JNIEnv * env, jobject thiz, ##__VA_ARGS__)

#define ERROR_STRING_BUFFER_LENGTH 256

static const AVSampleFormat OUTPUT_FORMAT_PCM_16BIT = AV_SAMPLE_FMT_S16;
static const AVSampleFormat OUTPUT_FORMAT_PCM_FLOAT = AV_SAMPLE_FMT_FLT;

static const int AUDIO_DECODER_ERROR_INVALID_DATA = -1;
static const int AUDIO_DECODER_ERROR_OTHER = -2;

static jmethodID growOutputBufferMethod;
static thread_local std::string lastInitializationError;

const AVCodec* getCodecByName(JNIEnv* env, jstring codecName);

AVCodecContext* createContext(JNIEnv* env, const AVCodec* codec,
                             jbyteArray extraData, jboolean outputFloat,
                             jint rawSampleRate, jint rawChannelCount);

struct GrowOutputBufferCallback {
  uint8_t* operator()(int requiredSize) const;

  JNIEnv* env;
  jobject thiz;
  jobject decoderOutputBuffer;
};

int decodePacket(AVCodecContext* context, AVPacket* packet,
                 uint8_t* outputBuffer, int outputSize,
                 GrowOutputBufferCallback growBuffer);

int transformError(int errorNumber);

void logError(const char* functionName, int errorNumber);

void releaseContext(AVCodecContext* context);

jint JNI_OnLoad(JavaVM* vm, void* reserved) {
  JNIEnv* env;
  if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
    LOGE("JNI_OnLoad: GetEnv failed");
    return -1;
  }
  jclass clazz =
      env->FindClass("androidx/media3/decoder/ffmpeg/FfmpegAudioDecoder");
  if (!clazz) {
    LOGE("JNI_OnLoad: FindClass failed");
    return -1;
  }
  growOutputBufferMethod =
      env->GetMethodID(clazz, "growOutputBuffer",
                       "(Landroidx/media3/decoder/"
                       "SimpleDecoderOutputBuffer;I)Ljava/nio/ByteBuffer;");
  if (!growOutputBufferMethod) {
    LOGE("JNI_OnLoad: GetMethodID failed");
    return -1;
  }
  return JNI_VERSION_1_6;
}

LIBRARY_FUNC(jstring, ffmpegGetVersion) {
  return env->NewStringUTF(LIBAVCODEC_IDENT);
}

LIBRARY_FUNC(jint, ffmpegGetInputBufferPaddingSize) {
  return (jint)AV_INPUT_BUFFER_PADDING_SIZE;
}

LIBRARY_FUNC(jboolean, ffmpegHasDecoder, jstring codecName) {
  return getCodecByName(env, codecName) != NULL;
}

AUDIO_DECODER_FUNC(jlong, ffmpegInitialize, jstring codecName,
                   jbyteArray extraData, jboolean outputFloat,
                   jint rawSampleRate, jint rawChannelCount) {
  lastInitializationError.clear();
  const AVCodec* codec = getCodecByName(env, codecName);
  if (!codec) {
    LOGE("Codec not found.");
    lastInitializationError = "codec not found";
    return 0L;
  }
  LOGD("[DEBUG-dsd-init] initialize codec=%s sampleRate=%d channelCount=%d "
       "outputFloat=%d extraDataBytes=%d",
       codec->name, rawSampleRate, rawChannelCount, outputFloat,
       extraData ? env->GetArrayLength(extraData) : 0);
  return (jlong)createContext(env, codec, extraData, outputFloat, rawSampleRate,
                            rawChannelCount);
}

AUDIO_DECODER_FUNC(jstring, ffmpegGetLastInitializationError) {
  return env->NewStringUTF(lastInitializationError.empty()
                               ? "native initialization returned null without detail"
                               : lastInitializationError.c_str());
}

AUDIO_DECODER_FUNC(jint, ffmpegDecode, jlong context, jobject inputData,
                   jint inputSize, jobject decoderOutputBuffer,
                   jobject outputData, jint outputSize) {
  if (!context) {
    LOGE("Context must be non-NULL.");
    return -1;
  }
  if (!inputData || !decoderOutputBuffer || !outputData) {
    LOGE("Input and output buffers must be non-NULL.");
    return -1;
  }
  if (inputSize < 0) {
    LOGE("Invalid input buffer size: %d.", inputSize);
    return -1;
  }
  if (outputSize < 0) {
    LOGE("Invalid output buffer length: %d", outputSize);
    return -1;
  }
  uint8_t* inputBuffer = (uint8_t*)env->GetDirectBufferAddress(inputData);
  uint8_t* outputBuffer = (uint8_t*)env->GetDirectBufferAddress(outputData);
  AVPacket* packet = av_packet_alloc();
  if (!packet) {
    LOGE("Failed to allocate packet.");
    return -1;
  }
  packet->data = inputBuffer;
  packet->size = inputSize;
  const int ret =
      decodePacket((AVCodecContext*)context, packet, outputBuffer, outputSize,
                   GrowOutputBufferCallback{env, thiz, decoderOutputBuffer});
  av_packet_free(&packet);
  return ret;
}

uint8_t* GrowOutputBufferCallback::operator()(int requiredSize) const {
  jobject newOutputData = env->CallObjectMethod(
      thiz, growOutputBufferMethod, decoderOutputBuffer, requiredSize);
  if (env->ExceptionCheck()) {
    LOGE("growOutputBuffer() failed");
    env->ExceptionDescribe();
    return nullptr;
  }
  return static_cast<uint8_t*>(env->GetDirectBufferAddress(newOutputData));
}

AUDIO_DECODER_FUNC(jint, ffmpegGetChannelCount, jlong context) {
  if (!context) {
    LOGE("Context must be non-NULL.");
    return -1;
  }
  return ((AVCodecContext*)context)->ch_layout.nb_channels;
}

AUDIO_DECODER_FUNC(jint, ffmpegGetSampleRate, jlong context) {
  if (!context) {
    LOGE("Context must be non-NULL.");
    return -1;
  }
  return ((AVCodecContext*)context)->sample_rate;
}

AUDIO_DECODER_FUNC(jlong, ffmpegReset, jlong jContext, jbyteArray extraData) {
  AVCodecContext* context = (AVCodecContext*)jContext;
  if (!context) {
    LOGE("Tried to reset without a context.");
    return 0L;
  }

  AVCodecID codecId = context->codec_id;
  if (codecId == AV_CODEC_ID_TRUEHD) {
    jboolean outputFloat =
        (jboolean)(context->request_sample_fmt == OUTPUT_FORMAT_PCM_FLOAT);
    releaseContext(context);
    const AVCodec* codec = avcodec_find_decoder(codecId);
    if (!codec) {
      LOGE("Unexpected error finding codec %d.", codecId);
      return 0L;
    }
    return (jlong)createContext(env, codec, extraData, outputFloat,
                                /* rawSampleRate= */ -1,
                                /* rawChannelCount= */ -1);
  }

  avcodec_flush_buffers(context);
  return (jlong)context;
}

AUDIO_DECODER_FUNC(void, ffmpegRelease, jlong context) {
  if (context) {
    releaseContext((AVCodecContext*)context);
  }
}

const AVCodec* getCodecByName(JNIEnv* env, jstring codecName) {
  if (!codecName) {
    return NULL;
  }
  const char* codecNameChars = env->GetStringUTFChars(codecName, NULL);
  const AVCodec* codec = avcodec_find_decoder_by_name(codecNameChars);
  env->ReleaseStringUTFChars(codecName, codecNameChars);
  return codec;
}

AVCodecContext* createContext(JNIEnv* env, const AVCodec* codec,
                              jbyteArray extraData, jboolean outputFloat,
                              jint rawSampleRate, jint rawChannelCount) {
  AVCodecContext* context = avcodec_alloc_context3(codec);
  if (!context) {
    LOGE("Failed to allocate context.");
    lastInitializationError = "avcodec_alloc_context3 failed";
    return NULL;
  }
  context->request_sample_fmt =
      outputFloat ? OUTPUT_FORMAT_PCM_FLOAT : OUTPUT_FORMAT_PCM_16BIT;
  if (extraData) {
    jsize size = env->GetArrayLength(extraData);
    context->extradata_size = size;
    context->extradata =
        (uint8_t*)av_malloc(size + AV_INPUT_BUFFER_PADDING_SIZE);
    if (!context->extradata) {
      LOGE("Failed to allocate extradata.");
      lastInitializationError = "av_malloc extradata failed";
      releaseContext(context);
      return NULL;
    }
    env->GetByteArrayRegion(extraData, 0, size, (jbyte*)context->extradata);
  }
  if (context->codec_id == AV_CODEC_ID_PCM_MULAW ||
      context->codec_id == AV_CODEC_ID_PCM_ALAW ||
      context->codec_id == AV_CODEC_ID_DSD_LSBF ||
      context->codec_id == AV_CODEC_ID_DSD_LSBF_PLANAR ||
      context->codec_id == AV_CODEC_ID_DSD_MSBF ||
      context->codec_id == AV_CODEC_ID_DSD_MSBF_PLANAR) {
    context->sample_rate = rawSampleRate;
    av_channel_layout_default(&context->ch_layout, rawChannelCount);
  }
  context->err_recognition = AV_EF_IGNORE_ERR;
  int result = avcodec_open2(context, codec, NULL);
  if (result < 0) {
    logError("avcodec_open2", result);
    char errorString[ERROR_STRING_BUFFER_LENGTH];
    if (av_strerror(result, errorString, sizeof(errorString)) < 0) {
      snprintf(errorString, sizeof(errorString), "unknown FFmpeg error %d", result);
    }
    lastInitializationError =
        std::string("avcodec_open2(") + codec->name + ") failed: " +
        errorString + " (" + std::to_string(result) + ")";
    LOGE("[DEBUG-dsd-init] %s; rawSampleRate=%d rawChannelCount=%d "
         "contextSampleRate=%d contextChannels=%d requestSampleFormat=%d",
         lastInitializationError.c_str(), rawSampleRate, rawChannelCount,
         context->sample_rate, context->ch_layout.nb_channels,
         context->request_sample_fmt);
    releaseContext(context);
    return NULL;
  }
  LOGD("[DEBUG-dsd-init] opened codec=%s sampleRate=%d channelCount=%d "
       "sampleFormat=%d requestSampleFormat=%d",
       codec->name, context->sample_rate, context->ch_layout.nb_channels,
       context->sample_fmt, context->request_sample_fmt);
  return context;
}

int decodePacket(AVCodecContext* context, AVPacket* packet,
                 uint8_t* outputBuffer, int outputSize,
                 GrowOutputBufferCallback growBuffer) {
  int result = avcodec_send_packet(context, packet);
  if (result) {
    logError("avcodec_send_packet", result);
    return transformError(result);
  }

  int outSize = 0;
  while (true) {
    AVFrame* frame = av_frame_alloc();
    if (!frame) {
      LOGE("Failed to allocate output frame.");
      return AUDIO_DECODER_ERROR_INVALID_DATA;
    }
    result = avcodec_receive_frame(context, frame);
    if (result) {
      av_frame_free(&frame);
      if (result == AVERROR(EAGAIN)) {
        break;
      }
      logError("avcodec_receive_frame", result);
      return transformError(result);
    }

    AVSampleFormat sampleFormat = context->sample_fmt;
    int channelCount = context->ch_layout.nb_channels;
    int sampleRate = context->sample_rate;
    int sampleCount = frame->nb_samples;
    SwrContext* resampleContext = static_cast<SwrContext*>(context->opaque);
    if (!resampleContext) {
      result = swr_alloc_set_opts2(&resampleContext, &context->ch_layout,
                                   context->request_sample_fmt, sampleRate,
                                   &context->ch_layout, sampleFormat,
                                   sampleRate, 0, NULL);
      if (result < 0) {
        logError("swr_alloc_set_opts2", result);
        av_frame_free(&frame);
        return transformError(result);
      }
      result = swr_init(resampleContext);
      if (result < 0) {
        logError("swr_init", result);
        av_frame_free(&frame);
        return transformError(result);
      }
      context->opaque = resampleContext;
    }

    int outSampleSize = av_get_bytes_per_sample(context->request_sample_fmt);
    int outSamples = swr_get_out_samples(resampleContext, sampleCount);
    int bufferOutSize = outSampleSize * channelCount * outSamples;
    if (outSize + bufferOutSize > outputSize) {
      LOGD("Output buffer size (%d) too small for output data (%d), "
           "reallocating buffer.",
           outputSize, outSize + bufferOutSize);
      outputSize = outSize + bufferOutSize;
      outputBuffer = growBuffer(outputSize);
      if (!outputBuffer) {
        LOGE("Failed to reallocate output buffer.");
        av_frame_free(&frame);
        return AUDIO_DECODER_ERROR_OTHER;
      }
    }
    result = swr_convert(resampleContext, &outputBuffer, bufferOutSize,
                         (const uint8_t**)frame->data, frame->nb_samples);
    av_frame_free(&frame);
    if (result < 0) {
      logError("swr_convert", result);
      return AUDIO_DECODER_ERROR_INVALID_DATA;
    }
    int available = swr_get_out_samples(resampleContext, 0);
    if (available != 0) {
      LOGE("Expected no samples remaining after resampling, but found %d.",
           available);
      return AUDIO_DECODER_ERROR_INVALID_DATA;
    }
    outputBuffer += bufferOutSize;
    outSize += bufferOutSize;
  }
  return outSize;
}

int transformError(int errorNumber) {
  return errorNumber == AVERROR_INVALIDDATA ? AUDIO_DECODER_ERROR_INVALID_DATA
                                            : AUDIO_DECODER_ERROR_OTHER;
}

void logError(const char* functionName, int errorNumber) {
  char* buffer = (char*)malloc(ERROR_STRING_BUFFER_LENGTH * sizeof(char));
  av_strerror(errorNumber, buffer, ERROR_STRING_BUFFER_LENGTH);
  LOGE("Error in %s: %s", functionName, buffer);
  free(buffer);
}

void releaseContext(AVCodecContext* context) {
  if (!context) {
    return;
  }
  SwrContext* swrContext;
  if ((swrContext = (SwrContext*)context->opaque)) {
    swr_free(&swrContext);
    context->opaque = NULL;
  }
  avcodec_free_context(&context);
}
