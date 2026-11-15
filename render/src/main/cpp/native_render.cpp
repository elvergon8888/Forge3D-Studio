#include <jni.h>
#include <android/log.h>
#include <GLES2/gl2.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "Forge3D/Render", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "Forge3D/Render", __VA_ARGS__)

extern "C" JNIEXPORT jstring JNICALL
Java_com_forge3d_studio_render_NativeRender_stringFromJNI(
    JNIEnv* env, jobject /* this */) {
    LOGI("Native render inicializado");
    return env->NewStringUTF("Forge3D native render (GLES2.0) listo");
}

extern "C" JNIEXPORT void JNICALL
Java_com_forge3d_studio_render_NativeRender_initGL(
    JNIEnv* env, jobject /* this */) {
    glClearColor(0.063f, 0.078f, 0.094f, 1.0f);
    glEnable(GL_DEPTH_TEST);
    glDisable(GL_CULL_FACE);
    LOGI("OpenGL ES 2.0 inicializado");
}
