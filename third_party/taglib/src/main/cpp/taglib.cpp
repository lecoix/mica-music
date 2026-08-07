#include "tfilestream.h"
#include "utils.h"

namespace {
std::unique_ptr<TagLib::FileStream> openFileStream(ScopedFd &owner, const bool readOnly) {
    auto stream = std::make_unique<TagLib::FileStream>(owner.get(), readOnly);
    if (!stream->isOpen()) return nullptr;
    owner.release();
    return stream;
}
}

extern "C" {
JNIEXPORT jobject JNICALL
Java_com_kyant_taglib_TagLib_getAudioProperties(
        JNIEnv *env,
        jclass,
        jint fd,
        jint read_style
) {
    ScopedFd owner(fd);
    char *path = getRealPathFromFd(fd);
    if (path == nullptr) {
        return nullptr;
    }
    const auto stream = openFileStream(owner, true);
    if (stream == nullptr) {
        free(path);
        return nullptr;
    }
    const auto style = static_cast<TagLib::AudioProperties::ReadStyle>(read_style);
    const TagLibExt::FileRef f(path, stream.get(), true, style);

    if (f.isNull()) {
        free(path);
        return nullptr;
    }

    jobject audioProperties = getAudioProperties(env, f);
    free(path);
    return audioProperties;
}

JNIEXPORT jobject JNICALL
Java_com_kyant_taglib_TagLib_getMetadata(
        JNIEnv *env,
        jclass,
        jint fd,
        jboolean read_pictures
) {
    ScopedFd owner(fd);
    char *path = getRealPathFromFd(fd);
    if (path == nullptr) {
        return nullptr;
    }
    const auto stream = openFileStream(owner, true);
    if (stream == nullptr) {
        free(path);
        return nullptr;
    }
    const TagLibExt::FileRef f(path, stream.get(), false);

    if (f.isNull()) {
        free(path);
        return nullptr;
    }

    jobject propertiesMap = getPropertyMap(env, f);
    jobjectArray pictures;
    if (read_pictures) {
        pictures = getPictures(env, f);
    } else {
        pictures = emptyPictureArray(env);
    }

    jobject metadata = env->NewObject(
            metadataClass, metadataConstructor,
            propertiesMap, pictures
    );
    free(path);
    return metadata;
}

JNIEXPORT jobject JNICALL
Java_com_kyant_taglib_TagLib_probeTrackNative(
        JNIEnv *env,
        jclass,
        jint fd,
        jboolean read_pictures,
        jint read_style
) {
    ScopedFd owner(fd);
    char *path = getRealPathFromFd(fd);
    if (path == nullptr) {
        return nullptr;
    }
    const auto stream = openFileStream(owner, true);
    if (stream == nullptr) {
        free(path);
        return nullptr;
    }
    const auto style = static_cast<TagLib::AudioProperties::ReadStyle>(read_style);
    const TagLibExt::FileRef f(path, stream.get(), true, style);

    if (f.isNull()) {
        free(path);
        return nullptr;
    }

    jobject trackProbe = buildTrackProbe(env, f, read_pictures);
    free(path);
    return trackProbe;
}

JNIEXPORT jobjectArray JNICALL
Java_com_kyant_taglib_TagLib_getMetadataPropertyValues(
        JNIEnv *env,
        jclass,
        jint fd,
        jstring property_name
) {
    ScopedFd owner(fd);
    const char *propertyName = env->GetStringUTFChars(property_name, nullptr);
    if (propertyName == nullptr) {
        return nullptr;
    }

    char *path = getRealPathFromFd(fd);
    if (path == nullptr) {
        env->ReleaseStringUTFChars(property_name, propertyName);
        return nullptr;
    }
    const auto stream = openFileStream(owner, true);
    if (stream == nullptr) {
        env->ReleaseStringUTFChars(property_name, propertyName);
        free(path);
        return nullptr;
    }
    const TagLibExt::FileRef f(path, stream.get(), false);

    if (f.isNull()) {
        free(path);
        return nullptr;
    }

    const auto propertyMap = f.properties();
    const auto valueList = propertyMap.find(TagLib::String(propertyName));
    if (valueList == propertyMap.end()) {
        env->ReleaseStringUTFChars(property_name, propertyName);
        free(path);
        return env->NewObjectArray(0, stringClass, nullptr);
    }

    jobjectArray result = env->NewObjectArray(static_cast<jsize>(valueList->second.size()),
                                              stringClass, nullptr);
    int i = 0;
    for (const auto &value: valueList->second) {
        jstring jValue = env->NewStringUTF(value.toCString(true));
        env->SetObjectArrayElement(result, i, jValue);
        env->DeleteLocalRef(jValue);
        i++;
    }

    env->ReleaseStringUTFChars(property_name, propertyName);
    free(path);
    return result;
}

JNIEXPORT jobjectArray JNICALL
Java_com_kyant_taglib_TagLib_getPictures(
        JNIEnv *env,
        jclass,
        jint fd
) {
    ScopedFd owner(fd);
    char *path = getRealPathFromFd(fd);
    if (path == nullptr) {
        return nullptr;
    }
    const auto stream = openFileStream(owner, true);
    if (stream == nullptr) {
        free(path);
        return nullptr;
    }
    const TagLibExt::FileRef f(path, stream.get(), false);

    if (f.isNull()) {
        free(path);
        return emptyPictureArray(env);
    }

    jobjectArray pictures = getPictures(env, f);
    free(path);
    return pictures;
}

JNIEXPORT jboolean JNICALL
Java_com_kyant_taglib_TagLib_savePropertyMap(
        JNIEnv *env,
        jclass,
        jint fd,
        jobject property_map
) {
    ScopedFd owner(fd);
    char *path = getRealPathFromFd(fd);
    if (path == nullptr) {
        return false;
    }
    const auto stream = openFileStream(owner, false);
    if (stream == nullptr) {
        free(path);
        return false;
    }
    TagLibExt::FileRef f(path, stream.get(), false);

    if (f.isNull()) {
        free(path);
        return false;
    }

    const PropertyMap propertyMap = JniHashMapToPropertyMap(env, property_map);
    f.setProperties(propertyMap);
    const bool success = f.save();
    free(path);
    return success;
}

JNIEXPORT jboolean JNICALL
Java_com_kyant_taglib_TagLib_savePictures(
        JNIEnv *env,
        jclass,
        jint fd,
        jobjectArray pictures
) {
    ScopedFd owner(fd);
    char *path = getRealPathFromFd(fd);
    if (path == nullptr) {
        return false;
    }
    const auto stream = openFileStream(owner, false);
    if (stream == nullptr) {
        free(path);
        return false;
    }
    TagLibExt::FileRef f(path, stream.get(), false);

    if (f.isNull()) {
        free(path);
        return false;
    }

    auto pictureList = JniPictureArrayToPictureList(env, pictures);
    f.setComplexProperties("PICTURE", pictureList);
    const bool success = f.save();
    free(path);
    return success;
}
}
