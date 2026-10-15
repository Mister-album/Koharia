# Source archives are pinned and verified; no prebuilt Android binaries are used.
include(FetchContent)
function(decoder_source name url sha256)
    FetchContent_Declare(${name} URL "${url}" URL_HASH "SHA256=${sha256}")
    FetchContent_GetProperties(${name})
    if(NOT ${name}_POPULATED)
        FetchContent_Populate(${name})
    endif()
    set(${name}_SOURCE_DIR "${${name}_SOURCE_DIR}" PARENT_SCOPE)
endfunction()

decoder_source(libpng
    "https://codeload.github.com/pnggroup/libpng/zip/cd952f49f95bb27154ae77dbb103032d95f6e580"
    "90e9326b7c9e53758f5a4f244d36283cfe11449ef0870911797614d92bbb1c78")
decoder_source(libde265
    "https://codeload.github.com/strukturag/libde265/zip/ba62bf4cfb3242f3bf0a45617ff09e35236e4d82"
    "965b6590dcbad3d2b51968a42dc8884cd757425cc360fe50e47fb02ff4836e18")
decoder_source(heif
    "https://codeload.github.com/strukturag/libheif/zip/f81f28ac014b1c28dee483ac45b72c6b05bc421a"
    "95342e5df1bf93218a821332daf9f0d3875a4cb6c5d0732939613e9e1ed383ba")
decoder_source(dav1d
    "https://codeload.github.com/videolan/dav1d/zip/bb645893f374ab694b10a79f1205a728e010f2c9"
    "683e0237be7034db6a77db3a7dd196e70d3ccd400196b980829cfd904dc03f1b")
decoder_source(jpeg
    "https://codeload.github.com/libjpeg-turbo/libjpeg-turbo/zip/fd93d98a959ac3700e2da07310a44867c9c46f03"
    "45eb21fd85bc4492e8c798e94b45a0d9ae439d878dd9b52bbd3d4c86c74e8dc7")
decoder_source(webp
    "https://codeload.github.com/webmproject/libwebp/zip/ca332209cb5567c9b249c86788cb2dbf8847e760"
    "74880d7d6d4e164dc5d0b8449336dbfe27b2bdaa2d1f22e7b54f405c31bfd53d")
decoder_source(jxl
    "https://codeload.github.com/libjxl/libjxl/zip/19bcd827dbf44bdc7ef30af8925696a5b82dcdf2"
    "231ce8c0b795cc85e46427e864c0db4303a469a122fecd1bdbf214f3dbac6caa")
decoder_source(meson
    "https://codeload.github.com/mesonbuild/meson/zip/1.9.1"
    "a07d676bf71cbef4a8dd7713e7284daacfca44fdc1a4f52bbd22d444f4d90162")
decoder_source(brotli
    "https://codeload.github.com/google/brotli/zip/36533a866ed1ca4b75cf049f4521e4ec5fe24727"
    "c82d7e4872cf93c5756e3dfdbc960903e777ed9a1ed362aa6bcbed60888d911e")
decoder_source(highway
    "https://codeload.github.com/google/highway/zip/58b52a717469e62b2d9b8eaa2f5dddb44d4a4cbf"
    "d8d696f03acdf9aa5a7be003e85704cbaa0e5c42f6e23c6bdaf9f4d20c55a185")
decoder_source(skcms
    "https://codeload.github.com/google/skcms/zip/42030a771244ba67f86b1c1c76a6493f873c5f91"
    "07d7ff5397737e5d6ef823b34ce6fb8af8edd84ef330f5aac61eb478b326463c")
decoder_source(lcms
    "https://codeload.github.com/mm2/Little-CMS/zip/453bafeb85b4ef96498866b7a8eadcc74dff9223"
    "1ea07d38d4ce5aba2df9a94cb13b56f4a43b7598ae0ba02fedecc0dea844af67")
if(CMAKE_HOST_WIN32)
    decoder_source(nasm
        "https://www.nasm.us/pub/nasm/releasebuilds/2.16.03/win64/nasm-2.16.03-win64.zip"
        "3ee4782247bcb874378d02f7eab4e294a84d3d15f3f6ee2de2f47a46aa7226e6")
    set(DECODER_NASM "${nasm_SOURCE_DIR}/nasm.exe")
else()
    find_program(DECODER_NASM nasm REQUIRED)
endif()
