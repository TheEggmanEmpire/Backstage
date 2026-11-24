cmake_minimum_required(VERSION 3.22.1)
project(backstage_companion_proot LANGUAGES C ASM)

get_filename_component(ANDROID_TOOLCHAIN_BIN "${CMAKE_C_COMPILER}" DIRECTORY)
get_filename_component(ANDROID_TOOLCHAIN_ROOT "${ANDROID_TOOLCHAIN_BIN}" DIRECTORY)
set(DEXTOP_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../../../upstream/dextop")
add_subdirectory("${DEXTOP_ROOT}/android/app/src/main/cpp/proot" "${CMAKE_CURRENT_BINARY_DIR}/proot")
