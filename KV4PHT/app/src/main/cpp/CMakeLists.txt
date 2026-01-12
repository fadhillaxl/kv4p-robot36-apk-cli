cmake_minimum_required(VERSION 3.22.1)
project(native_sstv)

add_library(
        native-lib
        SHARED
        native-lib.cpp
        robot36.c
        robot36_decode/robot36_decode.c)

find_library(
        log-lib
        log)

find_library(
        jnigraphics-lib
        jnigraphics)

target_link_libraries(
        native-lib
        ${log-lib}
        ${jnigraphics-lib}
        m)