cmake_minimum_required(VERSION 3.25)

project(Camylla VERSION 0.1.0 LANGUAGES CXX)

include(CTest)
add_subdirectory(src/protocol)

add_subdirectory(src/windows/virtual-camera)
