#include <fstream>
#include <unistd.h>
#include "jvmti.h"
#include "log.h"

using namespace std;

#define LOG_TAG "agent-injector"

void injectDex(JNIEnv *env) {
    // 通过反射调用ActivityThread.currentApplication()来获取全局Application实例
    jclass activityThreadClass = env->FindClass("android/app/ActivityThread");
    jmethodID currentApplicationMethodID = env->GetStaticMethodID(activityThreadClass, "currentApplication", "()Landroid/app/Application;");
    jobject application = env->CallStaticObjectMethod(activityThreadClass, currentApplicationMethodID);
    // 然后调用application的getPackageName获得当前应用包名
    jmethodID getPackageNameMethodID = env->GetMethodID(env->FindClass("android/content/Context"), "getPackageName", "()Ljava/lang/String;");
    jstring packageNameString = static_cast<jstring>(env->CallObjectMethod(application, getPackageNameMethodID));
    const char *packageName = env->GetStringUTFChars(packageNameString, 0);
    // 拼接工作路径
    string workingPathString("/data/data/");
    workingPathString += packageName;
    workingPathString += "/agent-injector/";
    env->ReleaseStringUTFChars(packageNameString, packageName);
    ifstream classInfo(workingPathString + "class-info");
    string mainClassName;
    string mainMethodName;
    // 读取入口类名和方法名
    if (classInfo.is_open()) {
        getline(classInfo, mainClassName);
        getline(classInfo, mainMethodName);
        classInfo.close();
    } else {
        LOGE("inject failed: class-info file not found");
        return;
    }

    string dexPath = workingPathString + "drug";
    ifstream dexFile(dexPath);
    if (dexFile.good()) {
        // 使用dexClassLoader加载dex或apk文件
        jclass classLoaderClass = env->FindClass("java/lang/ClassLoader");
        jmethodID getSystemClassLoaderMethodID = env->GetStaticMethodID(classLoaderClass, "getSystemClassLoader", "()Ljava/lang/ClassLoader;");
        jobject systemClassLoader = env->CallStaticObjectMethod(classLoaderClass, getSystemClassLoaderMethodID);
        jclass dexClassLoaderClass = env->FindClass("dalvik/system/DexClassLoader");
        jmethodID dexClassLoaderConstructorID = env->GetMethodID(dexClassLoaderClass, "<init>", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/ClassLoader;)V");
        jobject dexClassLoader = env->NewObject(dexClassLoaderClass, dexClassLoaderConstructorID,
                                                env->NewStringUTF(dexPath.c_str()), env->NewStringUTF(""), env->NewStringUTF(""), systemClassLoader);
        // 通过反射调用指定的入口类方法
        jmethodID loadClassMethodID = env->GetMethodID(dexClassLoaderClass, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
        auto mainClass = (jclass) env->CallObjectMethod(dexClassLoader, loadClassMethodID, env->NewStringUTF(mainClassName.c_str()));
        env->CallStaticVoidMethod(mainClass, env->GetStaticMethodID(mainClass, mainMethodName.c_str(), "()V"));
    } else {
        LOGE("inject failed: unable to open drug file");
    }
}

JNIEXPORT jint JNICALL
Agent_OnAttach(JavaVM *vm, char *options, void *reserved) {
    JNIEnv *env;
    if (vm->GetEnv((void **) &env, JNI_VERSION_1_6) != JNI_OK) {
        LOGE("inject failed: unable to get JNIEnv");
        return JNI_ERR;
    }
    injectDex(env);
    return JNI_OK;
}