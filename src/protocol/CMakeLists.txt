add_library(camylla_protocol
    src/video_frame.cpp
)

target_include_directories(camylla_protocol PUBLIC include)
target_compile_features(camylla_protocol PUBLIC cxx_std_20)

if(MSVC)
    target_compile_options(camylla_protocol PRIVATE /W4 /WX /permissive-)
else()
    target_compile_options(camylla_protocol PRIVATE -Wall -Wextra -Wpedantic -Werror)
endif()

if(BUILD_TESTING)
    add_executable(camylla_protocol_tests tests/video_frame_test.cpp)
    target_link_libraries(camylla_protocol_tests PRIVATE camylla_protocol)
    target_compile_features(camylla_protocol_tests PRIVATE cxx_std_20)
    add_test(NAME camylla_protocol_tests COMMAND camylla_protocol_tests)
endif()
