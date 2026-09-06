LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := koharia_pdf_styles
LOCAL_SRC_FILES := pdf_styles.cpp
LOCAL_CPPFLAGS := -std=c++17 -fexceptions -fvisibility=hidden
LOCAL_LDLIBS := -ldl
include $(BUILD_SHARED_LIBRARY)
