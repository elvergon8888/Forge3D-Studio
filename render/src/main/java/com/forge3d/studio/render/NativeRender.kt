package com.forge3d.studio.render

object NativeRender {
    init {
        System.loadLibrary("native_render")
    }

    external fun stringFromJNI(): String
    external fun initGL()
}
