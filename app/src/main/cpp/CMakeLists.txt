cmake_minimum_required(VERSION 3.22.1)
project(tun2socks_bridge)

set(TUNBRIDGE_JNILIBS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}")
set(TUNBRIDGE_PREBUILT_SO "${TUNBRIDGE_JNILIBS_DIR}/libtunbridge.so")

find_library(log-lib log)

if (EXISTS "${TUNBRIDGE_PREBUILT_SO}")
    add_library(tunbridge SHARED IMPORTED)
    set_target_properties(
        tunbridge
        PROPERTIES
        IMPORTED_LOCATION "${TUNBRIDGE_PREBUILT_SO}"
    )
    set(TUNBRIDGE_LINK_TARGET tunbridge)
else ()
    message(WARNING "libtunbridge.so not found for ABI ${ANDROID_ABI}. Building against local stub fallback.")
    add_library(
        tunbridge_stub
        STATIC
        tunbridge_stub.cpp
    )
    set(TUNBRIDGE_LINK_TARGET tunbridge_stub)
endif ()

add_library(
    tun2socks_bridge
    SHARED
    tun2socks_bridge.cpp
)

target_include_directories(
    tun2socks_bridge
    PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/include"
)

target_link_libraries(
    tun2socks_bridge
    ${TUNBRIDGE_LINK_TARGET}
    ${log-lib}
)

set_target_properties(
    tun2socks_bridge
    PROPERTIES
    OUTPUT_NAME "tun2socks_bridge"
)
