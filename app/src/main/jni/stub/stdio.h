/* freestanding JNI 编译用占位：jni.h 只需要能 #include 它，不使用 FILE/printf。 */
#ifndef ZNG_STUB_STDIO_H
#define ZNG_STUB_STDIO_H
#ifndef NULL
#define NULL ((void *)0)
#endif
#endif
