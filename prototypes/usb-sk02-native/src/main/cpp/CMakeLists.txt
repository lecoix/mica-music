cmake_minimum_required(VERSION 3.22.1)
project(usb_sk02_prototype LANGUAGES CXX)

add_library(
    usb_sk02_prototype
    SHARED
    usb_sk02_prototype.cpp
)

find_library(log-lib log)
target_link_libraries(usb_sk02_prototype PRIVATE ${log-lib})

add_executable(
    usb_underrun_accounting_interleaving_test
    usb_underrun_accounting_interleaving_test.cpp
)
target_include_directories(
    usb_underrun_accounting_interleaving_test
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)
