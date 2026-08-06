package com.kyant.taglib

/**
 * An object that provides access to the native TagLib library.
 */
public object TagLib {

    init {
        System.loadLibrary("taglib")
    }

    @JvmStatic
    private external fun getAudioProperties(
        fd: Int,
        readStyle: Int,
    ): AudioProperties?

    /**
     * Get audio properties from file descriptor.
     *
     * @param fd File descriptor
     * @param readStyle Read style for audio properties to balance speed and accuracy
     */
    @JvmStatic
    public fun getAudioProperties(
        fd: Int,
        readStyle: AudioPropertiesReadStyle = AudioPropertiesReadStyle.Average,
    ): AudioProperties? = getAudioProperties(fd, readStyle.ordinal)

    /**
     * Get metadata from file descriptor.
     *
     * @param fd File descriptor
     * @param readPictures Whether to read pictures
     */
    @JvmStatic
    public external fun getMetadata(
        fd: Int,
        readPictures: Boolean = true,
    ): Metadata?

    /**
     * Read metadata and audio properties in one native [FileRef] session.
     *
     * @param fd File descriptor; ownership is transferred to native code
     * @param readPictures Whether to read embedded pictures
     * @param readStyle Read style for audio properties
     */
    @JvmStatic
    private external fun probeTrackNative(
        fd: Int,
        readPictures: Boolean,
        readStyle: Int,
    ): TrackProbe?

    @JvmStatic
    public fun probeTrack(
        fd: Int,
        readPictures: Boolean = true,
        readStyle: AudioPropertiesReadStyle = AudioPropertiesReadStyle.Average,
    ): TrackProbe? = probeTrackNative(fd, readPictures, readStyle.ordinal)

    /**
     * Get metadata property values from file descriptor.
     *
     * @param fd File descriptor
     * @param propertyName Property name
     */
    @JvmStatic
    public external fun getMetadataPropertyValues(
        fd: Int,
        propertyName: String,
    ): Array<String>?

    /**
     * Get pictures from file descriptor. There may be multiple pictures with different types.
     */
    @JvmStatic
    public external fun getPictures(fd: Int): Array<Picture>

    /**
     * Get front cover from file descriptor.
     */
    @JvmStatic
    public fun getFrontCover(fd: Int): Picture? {
        val pictures = getPictures(fd)
        return pictures.find { picture -> picture.pictureType == "Front Cover" }
            ?: pictures.firstOrNull()
    }

    /**
     * Save metadata by file descriptor.
     *
     * @param fd File descriptor
     * @param propertyMap Property map to save
     *
     * @return Whether the operation was successful
     */
    @JvmStatic
    public external fun savePropertyMap(
        fd: Int,
        propertyMap: PropertyMap,
    ): Boolean

    /**
     * Save pictures by file descriptor.
     *
     * @param fd File descriptor
     * @param pictures Pictures to save
     *
     * @return Whether the operation was successful
     */
    @JvmStatic
    public external fun savePictures(
        fd: Int,
        pictures: Array<Picture>,
    ): Boolean
}
