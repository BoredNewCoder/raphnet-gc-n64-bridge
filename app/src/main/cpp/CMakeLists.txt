cmake_minimum_required(VERSION 3.22.1)
project("raphnetuinput")

add_library(raphnetuinput SHARED
    uinput_gamepad.c
)

target_link_libraries(raphnetuinput
    log
)
