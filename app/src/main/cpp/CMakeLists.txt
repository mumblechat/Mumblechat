cmake_minimum_required(VERSION 3.4.1)

add_library(keys SHARED
            keys.c)

# 16KB page size support for Android 15+ (Google Play requirement)
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")

# Include libraries needed for native-lib lib
target_link_libraries(keys
                      android
                      log)
