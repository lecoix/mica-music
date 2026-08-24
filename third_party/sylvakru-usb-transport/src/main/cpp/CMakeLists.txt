cmake_minimum_required(VERSION 3.18.1)
project("sylvakru_usb_exclusive")

set(LIBUSB_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../libusb-1.0.30")

add_library(
    usb-1.0
    SHARED
    "${LIBUSB_ROOT}/libusb/core.c"
    "${LIBUSB_ROOT}/libusb/descriptor.c"
    "${LIBUSB_ROOT}/libusb/hotplug.c"
    "${LIBUSB_ROOT}/libusb/io.c"
    "${LIBUSB_ROOT}/libusb/sync.c"
    "${LIBUSB_ROOT}/libusb/strerror.c"
    "${LIBUSB_ROOT}/libusb/os/linux_usbfs.c"
    "${LIBUSB_ROOT}/libusb/os/events_posix.c"
    "${LIBUSB_ROOT}/libusb/os/threads_posix.c"
    "${LIBUSB_ROOT}/libusb/os/linux_netlink.c"
)
target_include_directories(
    usb-1.0
    PUBLIC "${LIBUSB_ROOT}/libusb"
    PRIVATE
        "${LIBUSB_ROOT}/android"
        "${LIBUSB_ROOT}/libusb/os"
)
target_compile_definitions(usb-1.0 PRIVATE HAVE_CONFIG_H=1 _GNU_SOURCE=1)
target_compile_options(usb-1.0 PRIVATE -fvisibility=hidden -pthread)
target_link_libraries(usb-1.0 log)

add_library(
    sylvakru_usb_exclusive
    SHARED
    usb_exclusive_engine.cpp
    libusb_stream_engine.cpp
)

add_executable(
    usb_async_side_effect_gate_interleaving_test
    usb_async_side_effect_gate_interleaving_test.cpp
)
target_compile_features(usb_async_side_effect_gate_interleaving_test PRIVATE cxx_std_17)

add_executable(
    usb_async_transport_interleaving_test
    usb_async_transport_interleaving_test.cpp
)
target_compile_features(usb_async_transport_interleaving_test PRIVATE cxx_std_17)

add_executable(
    usb_output_service_seam_interleaving_test
    usb_output_service_seam_interleaving_test.cpp
)
target_compile_features(usb_output_service_seam_interleaving_test PRIVATE cxx_std_17)

add_executable(
    usb_tail_padding_interleaving_test
    usb_tail_padding_interleaving_test.cpp
)
target_compile_features(usb_tail_padding_interleaving_test PRIVATE cxx_std_17)

add_executable(
    usb_source_frame_clock_interleaving_test
    usb_source_frame_clock_interleaving_test.cpp
)
target_compile_features(usb_source_frame_clock_interleaving_test PRIVATE cxx_std_17)

add_dependencies(
    sylvakru_usb_exclusive
    usb_async_side_effect_gate_interleaving_test
    usb_async_transport_interleaving_test
    usb_output_service_seam_interleaving_test
    usb_tail_padding_interleaving_test
    usb_source_frame_clock_interleaving_test
)

target_compile_features(sylvakru_usb_exclusive PRIVATE cxx_std_17)
target_compile_options(sylvakru_usb_exclusive PRIVATE -O2 -fomit-frame-pointer)
target_link_libraries(
    sylvakru_usb_exclusive
    android
    log
    usb-1.0
)
