# SPDX-License-Identifier: GPL-3.0-or-later
cmake_minimum_required(VERSION 3.22)
project(hdricapture_jni C CXX)

find_package(OpenCV REQUIRED CONFIG)
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../../../core ${CMAKE_BINARY_DIR}/core)

add_library(hdricapture SHARED jni_bridge.cpp)
target_link_libraries(hdricapture PRIVATE hdricore log GLESv3)
