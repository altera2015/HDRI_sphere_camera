# SPDX-License-Identifier: GPL-3.0-or-later
cmake_minimum_required(VERSION 3.22)
project(hdricore C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# OpenCV: the app's CMake provides OpenCV::opencv_java4 via prefab before adding us.
set(HDRI_OPENCV OpenCV::opencv_java4)

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
set_target_properties(hdricore PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_compile_options(hdricore PRIVATE -Wall -Wextra -O3)
set_source_files_properties(src/io/exr.cpp PROPERTIES COMPILE_OPTIONS "-w")
