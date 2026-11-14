cmake_minimum_required(VERSION 3.18)
project(ocr_filter LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

find_package(Python REQUIRED COMPONENTS Interpreter Development.Module)
find_package(Threads REQUIRED)

python_add_library(_ocr_filter MODULE WITH_SOABI src/ocr_filter.cpp)
target_link_libraries(_ocr_filter PRIVATE Python::Module Threads::Threads)

if(MSVC)
    target_compile_options(_ocr_filter PRIVATE /O2 /EHsc /W4 /permissive- /Oi /utf-8)
else()
    target_compile_options(_ocr_filter PRIVATE -O2 -Wall -Wextra)
endif()

install(TARGETS _ocr_filter DESTINATION ExpandGXYxiaoxing)
