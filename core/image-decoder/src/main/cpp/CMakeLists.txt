cmake_minimum_required(VERSION 3.22)
project(imagedecoder C CXX ASM)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_JOB_POOLS decoder_compile=4 decoder_link=1)
set(CMAKE_JOB_POOL_COMPILE decoder_compile)
set(CMAKE_JOB_POOL_LINK decoder_link)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
include(sources.cmake)

file(GLOB LCMS_SOURCES "${lcms_SOURCE_DIR}/src/*.c")
add_library(lcms2 STATIC ${LCMS_SOURCES})
target_include_directories(lcms2 PUBLIC "${lcms_SOURCE_DIR}/include")

set(PNG_SHARED OFF CACHE BOOL "" FORCE)
set(PNG_TOOLS OFF CACHE BOOL "" FORCE)
set(PNG_TESTS OFF CACHE BOOL "" FORCE)
add_subdirectory("${libpng_SOURCE_DIR}" png EXCLUDE_FROM_ALL)

set(WITH_JPEG8 ON CACHE BOOL "" FORCE)
set(WITH_TURBOJPEG OFF CACHE BOOL "" FORCE)
set(ENABLE_SHARED OFF CACHE BOOL "" FORCE)
set(REQUIRE_SIMD ON CACHE BOOL "" FORCE)
set(CMAKE_INSTALL_DOCDIR "share/doc/imagedecoder" CACHE PATH "" FORCE)
add_subdirectory("${jpeg_SOURCE_DIR}" jpeg EXCLUDE_FROM_ALL)

foreach(feature ANIM_UTILS CWEBP DWEBP GIF2WEBP IMG2WEBP VWEBP WEBPINFO WEBPMUX EXTRAS WEBP_JS)
    set(WEBP_BUILD_${feature} OFF CACHE BOOL "" FORCE)
endforeach()
set(WEBP_ENABLE_SWAP_16BIT_CSP ON CACHE BOOL "" FORCE)
add_subdirectory("${webp_SOURCE_DIR}" webp EXCLUDE_FROM_ALL)

set(ENABLE_SDL OFF CACHE BOOL "" FORCE)
set(ENABLE_DECODER OFF CACHE BOOL "" FORCE)
set(ENABLE_ENCODER OFF CACHE BOOL "" FORCE)
# Both upstream projects define an optional source-tarball target named dist.
file(READ "${libde265_SOURCE_DIR}/CMakeLists.txt" de265_cmake)
if(de265_cmake MATCHES "add_custom_target\\(dist")
    string(REPLACE "add_custom_target(dist\n" "add_custom_target(de265_dist\n" de265_cmake "${de265_cmake}")
    file(WRITE "${libde265_SOURCE_DIR}/CMakeLists.txt" "${de265_cmake}")
endif()
add_subdirectory("${libde265_SOURCE_DIR}" de265 EXCLUDE_FROM_ALL)
target_include_directories(de265 PUBLIC
    "$<BUILD_INTERFACE:${libde265_SOURCE_DIR}>"
    "$<BUILD_INTERFACE:${CMAKE_CURRENT_BINARY_DIR}/de265>")
set(LIBDE265_LIBRARY de265)
set(LIBDE265_INCLUDE_DIR "${libde265_SOURCE_DIR}")

find_package(Python3 REQUIRED COMPONENTS Interpreter)
execute_process(
    COMMAND "${Python3_EXECUTABLE}" "${CMAKE_CURRENT_SOURCE_DIR}/build_dav1d.py"
        "${CMAKE_C_COMPILER}" "${CMAKE_AR}" "${CMAKE_STRIP}" "${CMAKE_SYSROOT}"
        "${ANDROID_ABI}" "${ANDROID_PLATFORM_LEVEL}" "${dav1d_SOURCE_DIR}"
        "${CMAKE_CURRENT_BINARY_DIR}/dav1d" "${meson_SOURCE_DIR}/meson.py"
        "${CMAKE_MAKE_PROGRAM}" "${DECODER_NASM}"
    COMMAND_ERROR_IS_FATAL ANY
)
add_library(dav1d STATIC IMPORTED)
set_target_properties(dav1d PROPERTIES
    IMPORTED_LOCATION "${CMAKE_CURRENT_BINARY_DIR}/dav1d/src/libdav1d.a"
    INTERFACE_INCLUDE_DIRECTORIES "${dav1d_SOURCE_DIR}/include;${CMAKE_CURRENT_BINARY_DIR}/dav1d/include;${CMAKE_CURRENT_BINARY_DIR}/dav1d/include/dav1d")
set(DAV1D_LIBRARY dav1d)
set(DAV1D_INCLUDE_DIR "${dav1d_SOURCE_DIR}/include")

set(WITH_LIBDE265 ON CACHE BOOL "" FORCE)
set(WITH_DAV1D ON CACHE BOOL "" FORCE)
set(WITH_LIBDE265_PLUGIN OFF CACHE BOOL "" FORCE)
set(WITH_DAV1D_PLUGIN OFF CACHE BOOL "" FORCE)
set(ENABLE_PLUGIN_LOADING OFF CACHE BOOL "" FORCE)
foreach(feature X265 X264 OpenH264_DECODER AOM_DECODER AOM_ENCODER LIBSHARPYUV GDK_PIXBUF EXAMPLES)
    set(WITH_${feature} OFF CACHE BOOL "" FORCE)
endforeach()
set(BUILD_DOCUMENTATION OFF CACHE BOOL "" FORCE)
add_subdirectory("${heif_SOURCE_DIR}" heif EXCLUDE_FROM_ALL)
target_link_libraries(heif PRIVATE de265)

# Keep JPEG XL's original submodule revisions, including its color transform backend.
foreach(dependency brotli highway skcms)
    file(COPY "${${dependency}_SOURCE_DIR}/" DESTINATION "${jxl_SOURCE_DIR}/third_party/${dependency}")
endforeach()
foreach(feature DOXYGEN MANPAGES EXAMPLES SJPEG OPENEXR TRANSCODE_JPEG TOOLS BENCHMARK JPEGLI JNI DEVTOOLS)
    set(JPEGXL_ENABLE_${feature} OFF CACHE BOOL "" FORCE)
endforeach()
add_subdirectory("${jxl_SOURCE_DIR}" jxl EXCLUDE_FROM_ALL)

add_library(imagedecoder SHARED
    java_stream.cpp java_wrapper.cpp java_objects.cpp borders.cpp row_convert.cpp
    decoder_jpeg.cpp decoder_png.cpp decoder_webp.cpp decoder_heif.cpp decoder_jxl.cpp)
target_compile_definitions(imagedecoder PRIVATE HAVE_LIBJPEG HAVE_LIBPNG HAVE_LIBWEBP HAVE_LIBHEIF HAVE_LIBJXL)
target_include_directories(imagedecoder PRIVATE
    "${lcms_SOURCE_DIR}" "${jpeg_SOURCE_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/jpeg"
    "${heif_SOURCE_DIR}/libheif/api" "${CMAKE_CURRENT_BINARY_DIR}/heif"
    "${jxl_SOURCE_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/jxl"
    $<TARGET_PROPERTY:hwy,INCLUDE_DIRECTORIES>)
target_link_libraries(imagedecoder PRIVATE
    jpeg-static png_static z webpdecoder webpdemux lcms2 heif
    jxl_dec jxl_threads brotlidec brotlienc brotlicommon hwy
    android jnigraphics log)
