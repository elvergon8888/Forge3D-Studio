cmake_minimum_required(VERSION 3.22.1)
project("forge3d_render")

add_library(
    native_render
    SHARED
    native_render.cpp
)

target_include_directories(native_render PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

find_library(log-lib log)
find_library(GLESv2 GLESv2)

target_link_libraries(
    native_render
    ${log-lib}
    GLESv2
    android
)
