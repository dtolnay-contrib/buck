LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := foo
LOCAL_CFLAGS += $(BUCK_DEP_CFLAGS)
LOCAL_LDFLAGS += -stdlib=libstdc++ $(BUCK_DEP_LDFLAGS)
LOCAL_SRC_FILES += foo.cpp
include $(BUILD_SHARED_LIBRARY)
