package com.example.offlineeditor.export;
import android.graphics.Bitmap;
import android.opengl.*;
import android.view.Surface;
import java.io.IOException;
import java.nio.*;

/** Upload CPU-composed frames to a timestamped MediaCodec EGL surface. */
final class FrameSurface implements AutoCloseable {
    private EGLDisplay display=EGL14.EGL_NO_DISPLAY;
    private EGLContext context=EGL14.EGL_NO_CONTEXT;
    private EGLSurface surface=EGL14.EGL_NO_SURFACE;
    private int program,texture;
    private final FloatBuffer vertices=ByteBuffer.allocateDirect(16*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    FrameSurface(Surface target,int width,int height) throws IOException {
        try {
            display=EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY); int[] version=new int[2];
            if(!EGL14.eglInitialize(display,version,0,version,1)) throw new IOException("EGL 初始化失败");
            int[] attrs={EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_ALPHA_SIZE,8,EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT,0x3142,1,EGL14.EGL_NONE};
            EGLConfig[] configs=new EGLConfig[1]; int[] count=new int[1];
            if(!EGL14.eglChooseConfig(display,attrs,0,configs,0,1,count,0) || count[0]==0) throw new IOException("设备没有录制用 EGL 配置");
            context=EGL14.eglCreateContext(display,configs[0],EGL14.EGL_NO_CONTEXT,new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE},0);
            surface=EGL14.eglCreateWindowSurface(display,configs[0],target,new int[]{EGL14.EGL_NONE},0);
            if(!EGL14.eglMakeCurrent(display,surface,surface,context)) throw new IOException("无法绑定编码画布");
            program=GLES20.glCreateProgram(); int vs=shader(GLES20.GL_VERTEX_SHADER,"attribute vec2 p;attribute vec2 u;varying vec2 v;void main(){gl_Position=vec4(p,0.,1.);v=u;}");
            int fs=shader(GLES20.GL_FRAGMENT_SHADER,"precision mediump float;uniform sampler2D t;varying vec2 v;void main(){gl_FragColor=texture2D(t,v);}");
            GLES20.glAttachShader(program,vs); GLES20.glAttachShader(program,fs); GLES20.glLinkProgram(program);
            GLES20.glDeleteShader(vs); GLES20.glDeleteShader(fs); int[] ok=new int[1]; GLES20.glGetProgramiv(program,GLES20.GL_LINK_STATUS,ok,0);
            if(ok[0]==0) throw new IOException("画布着色器链接失败");
            GLES20.glUseProgram(program); GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"t"),0);
            int[] textures=new int[1]; GLES20.glGenTextures(1,textures,0); texture=textures[0]; GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,texture);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR); GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE); GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);
            vertices.put(new float[]{-1,-1,0,1,1,-1,1,1,-1,1,0,0,1,1,1,0}).position(0);
            int p=GLES20.glGetAttribLocation(program,"p"),u=GLES20.glGetAttribLocation(program,"u");
            GLES20.glEnableVertexAttribArray(p); GLES20.glVertexAttribPointer(p,2,GLES20.GL_FLOAT,false,16,vertices);
            vertices.position(2); GLES20.glEnableVertexAttribArray(u); GLES20.glVertexAttribPointer(u,2,GLES20.GL_FLOAT,false,16,vertices);
            GLES20.glViewport(0,0,width,height);
        } catch(IOException|RuntimeException e) { close(); throw e; }
    }
    private static int shader(int type,String code) throws IOException {
        int id=GLES20.glCreateShader(type); GLES20.glShaderSource(id,code); GLES20.glCompileShader(id); int[] ok=new int[1]; GLES20.glGetShaderiv(id,GLES20.GL_COMPILE_STATUS,ok,0);
        if(ok[0]==0) { GLES20.glDeleteShader(id); throw new IOException("画布着色器编译失败"); } return id;
    }
    void frame(Bitmap bitmap,long timeUs) throws IOException {
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D,0,bitmap,0); GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);
        if(GLES20.glGetError()!=GLES20.GL_NO_ERROR) throw new IOException("GPU 画面渲染失败");
        if(!EGLExt.eglPresentationTimeANDROID(display,surface,timeUs*1000) || !EGL14.eglSwapBuffers(display,surface)) throw new IOException("无法提交编码画面");
    }
    public void close() {
        if(display!=EGL14.EGL_NO_DISPLAY) {
            if(context!=EGL14.EGL_NO_CONTEXT && surface!=EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(display,surface,surface,context); if(texture!=0) GLES20.glDeleteTextures(1,new int[]{texture},0); if(program!=0) GLES20.glDeleteProgram(program);
            }
            EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT);
            if(surface!=EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display,surface);
            if(context!=EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display,context);
            EGL14.eglReleaseThread(); EGL14.eglTerminate(display); display=EGL14.EGL_NO_DISPLAY;
        }
    }
}