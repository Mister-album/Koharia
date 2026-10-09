LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := koharia_pdf_styles
LOCAL_SRC_FILES := pdf_styles.cpp
LOCAL_CPPFLAGS := -std=c++17 -fexceptions -fvisibility=hidden
LOCAL_LDLIBS := -ldl
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := koharia_resampling
LOCAL_SRC_FILES := ../resampling/mitchell_jni.cpp
LOCAL_CPPFLAGS := -std=c++17 -fexceptions -fvisibility=hidden -ffp-contract=off
ifeq ($(KOHARIA_RESAMPLING_OPTIMIZED),1)
LOCAL_CPPFLAGS += -O2
endif
LOCAL_LDLIBS := -ljnigraphics
include $(BUILD_SHARED_LIBRARY)
