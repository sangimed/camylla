add_library(camylla_virtual_camera_core
    src/cadence.cpp
    src/ipc_protocol.cpp
    src/synthetic_frame_provider.cpp
    src/video_mode.cpp
)

target_include_directories(camylla_virtual_camera_core PUBLIC include)
target_compile_features(camylla_virtual_camera_core PUBLIC cxx_std_20)

if(MSVC)
    target_compile_options(camylla_virtual_camera_core PRIVATE /W4 /WX /permissive-)
else()
    target_compile_options(camylla_virtual_camera_core PRIVATE -Wall -Wextra -Wpedantic -Werror)
endif()

if(WIN32)
    add_library(camylla_virtual_camera_source SHARED
        src/camylla_virtual_camera_source.def
        src/dll_main.cpp
        src/media_source.cpp
        src/ipc_frame_provider.cpp
    )
    target_link_libraries(camylla_virtual_camera_source PRIVATE
        camylla_virtual_camera_core
        mfplat
        mf
        mfuuid
        advapi32
        wtsapi32
    )
    target_compile_features(camylla_virtual_camera_source PRIVATE cxx_std_20)
    target_compile_definitions(camylla_virtual_camera_source PRIVATE UNICODE _UNICODE)
    if(MSVC)
        target_compile_options(camylla_virtual_camera_source PRIVATE /W4 /WX /permissive-)
    else()
        target_compile_options(camylla_virtual_camera_source PRIVATE -Wall -Wextra -Wpedantic -Werror)
    endif()

    add_executable(camylla_virtual_camera_ctl src/virtual_camera_ctl.cpp src/diagnose_source.cpp)
    target_link_libraries(camylla_virtual_camera_ctl PRIVATE
        camylla_virtual_camera_core
        mfplat
        mfsensorgroup
        advapi32
        ole32
        mfuuid
    )
    target_compile_features(camylla_virtual_camera_ctl PRIVATE cxx_std_20)
    target_compile_definitions(camylla_virtual_camera_ctl PRIVATE UNICODE _UNICODE)
    if(MSVC)
        target_compile_options(camylla_virtual_camera_ctl PRIVATE /W4 /WX /permissive-)
    else()
        target_compile_options(camylla_virtual_camera_ctl PRIVATE -Wall -Wextra -Wpedantic -Werror)
    endif()

    add_executable(camylla_ipc_source_smoke tests/ipc_source_smoke.cpp src/ipc_frame_provider.cpp)
    target_link_libraries(camylla_ipc_source_smoke PRIVATE camylla_virtual_camera_core advapi32 wtsapi32)
    target_compile_features(camylla_ipc_source_smoke PRIVATE cxx_std_20)
    target_compile_definitions(camylla_ipc_source_smoke PRIVATE UNICODE _UNICODE)
    if(MSVC)
        target_compile_options(camylla_ipc_source_smoke PRIVATE /W4 /WX /permissive-)
    endif()
endif()

if(BUILD_TESTING)
    add_executable(camylla_virtual_camera_core_tests tests/core_test.cpp)
    target_link_libraries(camylla_virtual_camera_core_tests PRIVATE camylla_virtual_camera_core)
    target_compile_features(camylla_virtual_camera_core_tests PRIVATE cxx_std_20)
    add_test(NAME camylla_virtual_camera_core_tests COMMAND camylla_virtual_camera_core_tests)
endif()
