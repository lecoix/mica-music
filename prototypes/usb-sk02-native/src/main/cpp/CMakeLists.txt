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

add_executable(
    sk02_feedback_rate_filter_test
    sk02_feedback_rate_filter_test.cpp
)
target_include_directories(
    sk02_feedback_rate_filter_test
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

add_executable(
    sk02_stream_metrics_test
    sk02_stream_metrics_test.cpp
)
target_include_directories(
    sk02_stream_metrics_test
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

add_executable(
    sk02_iso_ahead_window_test
    sk02_iso_ahead_window_test.cpp
)
target_include_directories(
    sk02_iso_ahead_window_test
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

add_executable(
    usb_feedback_decoder_test
    usb_feedback_decoder_test.cpp
)
target_include_directories(
    usb_feedback_decoder_test
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

add_executable(
    usb_iso_scheduler_projection_test
    usb_iso_scheduler_projection_test.cpp
)
target_include_directories(
    usb_iso_scheduler_projection_test
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

add_executable(
    usb_iso_scheduler_stress_test
    usb_iso_scheduler_stress_test.cpp
)
target_include_directories(
    usb_iso_scheduler_stress_test
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

add_executable(
    usb_payload_policy_test
    usb_payload_policy_test.cpp
)
target_include_directories(
    usb_payload_policy_test
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)
