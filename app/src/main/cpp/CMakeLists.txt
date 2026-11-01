cmake_minimum_required(VERSION 3.22.1)
project(rokid_fusion C)
set(FUSION_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/Fusion/Fusion")
add_library(rokid_fusion SHARED fusion_jni.c
    "${FUSION_ROOT}/FusionAhrs.c" "${FUSION_ROOT}/FusionBias.c")
target_include_directories(rokid_fusion PRIVATE "${FUSION_ROOT}")
target_compile_definitions(rokid_fusion PRIVATE FUSION_USE_NORMAL_SQRT)
target_compile_options(rokid_fusion PRIVATE -Wall -Wextra -Werror)
target_link_libraries(rokid_fusion m)
