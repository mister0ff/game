add_library(gxcore SHARED gxcore.cpp)
target_compile_options(gxcore PRIVATE -fvisibility=hidden -fno-exceptions -fno-rtti -Wall)
target_link_libraries(gxcore PRIVATE log dl)
