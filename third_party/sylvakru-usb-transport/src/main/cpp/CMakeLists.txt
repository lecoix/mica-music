cmake_minimum_required(VERSION 3.18.1)
project("sylvakru_usb_exclusive")

add_library(
    sylvakru_usb_exclusive
    SHARED
    usb_exclusive_engine.cpp
)

target_compile_features(sylvakru_usb_exclusive PRIVATE cxx_std_17)
target_compile_options(sylvakru_usb_exclusive PRIVATE -O2 -fomit-frame-pointer)
target_link_libraries(
    sylvakru_usb_exclusive
    android
    log
)
