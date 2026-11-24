# SPDX-License-Identifier: GPL-3.0-or-later
cmake_minimum_required(VERSION 3.22)
project(hdricore C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
if(NOT CMAKE_BUILD_TYPE AND NOT CMAKE_CONFIGURATION_TYPES)
    set(CMAKE_BUILD_TYPE Release)
endif()

# OpenCV: on Android the app's CMake provides OpenCV::opencv_java4 via prefab before adding us.
if(TARGET OpenCV::opencv_java4)
    set(HDRI_OPENCV OpenCV::opencv_java4)
else()
    find_package(OpenCV REQUIRED COMPONENTS core imgproc imgcodecs features2d calib3d photo)
    set(HDRI_OPENCV ${OpenCV_LIBS})
endif()

add_library(miniz STATIC third_party/miniz.c)
target_include_directories(miniz PUBLIC third_party)
set_target_properties(miniz PROPERTIES POSITION_INDEPENDENT_CODE ON)

add_library(hdricore STATIC
    src/io/rgbe.cpp
    src/io/exr.cpp
    src/hdr/merge.cpp
    src/raw/dng.cpp
    src/raw/develop.cpp
    src/session/session.cpp
    src/stitch/camera.cpp
    src/stitch/align.cpp
    src/stitch/pipeline.cpp
)
target_include_directories(hdricore PUBLIC include PRIVATE third_party src/stitch)
target_link_libraries(hdricore PUBLIC ${HDRI_OPENCV} PRIVATE miniz)
if(OpenCV_INCLUDE_DIRS)
    target_include_directories(hdricore PUBLIC ${OpenCV_INCLUDE_DIRS})
endif()
set_target_properties(hdricore PROPERTIES POSITION_INDEPENDENT_CODE ON)
if(NOT MSVC)
    target_compile_options(hdricore PRIVATE -Wall -Wextra -O3)
    set_source_files_properties(src/io/exr.cpp PROPERTIES COMPILE_OPTIONS "-w")
endif()

# Desktop-only: tests and CLI.
if(NOT ANDROID)
    option(HDRI_BUILD_TESTS "Build core tests" ON)
    if(HDRI_BUILD_TESTS)
        enable_testing()
        add_executable(hdricore_tests tests/test_main.cpp tests/test_rgbe.cpp tests/test_merge.cpp)
        target_link_libraries(hdricore_tests PRIVATE hdricore)
        add_test(NAME hdricore_tests COMMAND hdricore_tests)
    endif()
    add_executable(hdri-stitch ${CMAKE_CURRENT_SOURCE_DIR}/../tools/cli/main.cpp)
    target_link_libraries(hdri-stitch PRIVATE hdricore)
endif()
