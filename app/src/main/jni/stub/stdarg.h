/* freestanding JNI 编译用占位：jni.h 的函数指针类型里只用到 va_list。 */
#ifndef ZNG_STUB_STDARG_H
#define ZNG_STUB_STDARG_H
typedef __builtin_va_list va_list;
#endif
