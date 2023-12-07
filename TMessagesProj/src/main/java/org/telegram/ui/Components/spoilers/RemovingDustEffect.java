package org.telegram.ui.Components.spoilers;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLExt;
import android.opengl.GLES20;
import android.opengl.GLES31;
import android.opengl.GLUtils;
import android.os.Build;
import android.view.TextureView;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import com.google.common.math.IntMath;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.Utilities;
import org.telegram.ui.Components.RLottieDrawable;

import java.math.RoundingMode;
import java.util.ArrayList;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;

public class RemovingDustEffect {

    public final int MAX_FPS;
    private final double MIN_DELTA;
    private final double MAX_DELTA;

    public static boolean supports() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP;
    }

    public static Boolean start(FrameLayout view, Bitmap rectScreenshots, ArrayList<Rect> rects, Runnable firstFrameCallback) {
        if (view == null || !supports() || rects.isEmpty()) return false;

        RemovingDustEffect localInstance = new RemovingDustEffect(makeTextureViewContainer(view), view.getWidth(), view.getHeight(), rectScreenshots, rects, firstFrameCallback);

        //TODO: move to thread
        view.postDelayed(() -> { try { localInstance.destroy(); } catch (Exception e) {} },2_200L);

        return true;
    }


    private static FrameLayout makeTextureViewContainer(ViewGroup rootView) {
        FrameLayout container = new FrameLayout(rootView.getContext()) {};
        rootView.addView(container);
        return container;
    }

    private static int getMaxPoints() {
        switch (SharedConfig.getDevicePerformanceClass()) {
            case SharedConfig.PERFORMANCE_CLASS_HIGH:
                return 250_000;
            case SharedConfig.PERFORMANCE_CLASS_AVERAGE:
                return 180_000;
            default:
            case SharedConfig.PERFORMANCE_CLASS_LOW:
                return 100_000;
        }
    }

    private final ViewGroup textureViewContainer;
    public TextureView textureView;
    private DustThread thread;
    private int width, height;
    public boolean destroyed;

    public void invalidate() {
        if (textureView != null) {
            textureView.invalidate();
        }
    }

    private void destroy() {
        destroyed = true;
        if (thread != null) {
            thread.halt();
            thread = null;
        }
        textureViewContainer.removeView(textureView);
        if (textureViewContainer.getParent() instanceof ViewGroup) {
            ViewGroup rootView = (ViewGroup) textureViewContainer.getParent();
            rootView.removeView(textureViewContainer);
        }
    }

    private RemovingDustEffect(ViewGroup container, int width, int height, Bitmap texture, ArrayList<Rect> cells, Runnable firstFrameCallback) {
        MAX_FPS = (int) AndroidUtilities.screenRefreshRate;
        MIN_DELTA = 1.0 / MAX_FPS;
        MAX_DELTA = MIN_DELTA * 4;

        this.width = width;
        this.height = height;

        textureViewContainer = container;
        textureView = new TextureView(textureViewContainer.getContext()) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                setMeasuredDimension(RemovingDustEffect.this.width, RemovingDustEffect.this.height);
            }
        };
        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
                if (thread == null) {
                    thread = new DustThread(surface, width, height, RemovingDustEffect.this::invalidate, firstFrameCallback, texture, cells);
                    thread.start();
                }
            }

            @Override
            public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
                if (thread != null) {
                    thread.updateSize(width, height);
                }
            }

            @Override
            public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
                if (thread != null) {
                    thread.halt();
                    thread = null;
                }
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {

            }
        });
        textureView.setOpaque(false);
        textureViewContainer.addView(textureView);
    }

    private class DustThread extends Thread {
        private volatile boolean running = true;
        private volatile boolean paused = false;

        private final Runnable invalidate;
        private final Runnable firstFrameCallback;
        private final SurfaceTexture surfaceTexture;
        private final Object resizeLock = new Object();
        private boolean resize;
        private int width, height;
        private int particlesCount;
        private int diameterPx = 1; // default
        private Bitmap texture;
        private ArrayList<Rect> cells;

        public DustThread(SurfaceTexture surfaceTexture, int width, int height, Runnable invalidate, Runnable firstFrame, Bitmap texture, ArrayList<Rect> cells) {
            this.invalidate = invalidate;
            this.firstFrameCallback = firstFrame;
            this.surfaceTexture = surfaceTexture;
            this.width = width;
            this.height = height;
            this.texture = texture;
            this.cells = cells;
            this.particlesCount = particlesCount();
        }

        private int particlesCount() {
            int localInstance = 0;
            for (Rect cell : cells) {
                localInstance += cell.height() * cell.width();
            }

            diameterPx = Math.max(diameterPx, IntMath.divide((int) Math.sqrt(localInstance), (int) Math.sqrt(getMaxPoints()), RoundingMode.CEILING));

            for (Rect pos : cells) {
                pos.right = pos.left + pos.width() / diameterPx * diameterPx;
                pos.bottom = pos.top + pos.height() / diameterPx * diameterPx;
            }

            int finalCount = 0;
            for (Rect pos : cells) {
                finalCount += pos.height() * pos.width();
            }
            finalCount = finalCount / (diameterPx * diameterPx);

            //  Log.e("RemovingDustEffect", "particlesCount result:"+s +":"+(finalCount) +":"+(s / diameter/ diameter)+"/"+SharedConfig.getDevicePerformanceClass());
            return Math.min(getMaxPoints(), finalCount);
        }

        public void updateSize(int width, int height) {
            synchronized (resizeLock) {
                resize = true;
                this.width = width;
                this.height = height;
            }
        }

        public void halt() {
            running = false;
        }

        @Override
        public void run() {
            init();
            long startTime = System.currentTimeMillis();
            long lastTime = System.nanoTime();
            boolean firstFrame = true;
            while (running) {
                if (System.currentTimeMillis() - startTime > 2_000) break;

                final long now = System.nanoTime();
                double Δt = (now - lastTime) / 1_000_000_000.;
                lastTime = now;

                if (Δt < MIN_DELTA) {
                    double wait = MIN_DELTA - Δt;
                    try {
                        long milli = (long) (wait * 1000L);
                        int nano = (int) ((wait - milli / 1000.) * 1_000_000_000);
                        sleep(milli, nano);
                    } catch (Exception ignore) {}
                    Δt = MIN_DELTA;
                } else if (Δt > MAX_DELTA) {
                    Δt = MAX_DELTA;
                }

                while (paused) {
                    try {
                        sleep(1000);
                        shouldInit = true;
                    } catch (Exception ignore) {}
                }

                checkResize();
                drawFrame((float) Δt);
                if(firstFrame){
                    firstFrameCallback.run();
                }

                AndroidUtilities.cancelRunOnUIThread(this.invalidate);
                AndroidUtilities.runOnUIThread(this.invalidate);
                firstFrame = false;
            }
            die();
        }

        private EGL10 egl;
        private EGLDisplay eglDisplay;
        private EGLConfig eglConfig;
        private EGLSurface eglSurface;
        private EGLContext eglContext;

        private int drawProgram;
        private int initHandle;
        private int timeHandle;
        private int deltaTimeHandle;
        private int sizeHandle;
        private int diameterHandle;
        private int seedHandle;

        private int textureID;

        private boolean shouldInit = true;

        private int currentBuffer = 0;
        private int[] particlesData;

        private void init() {
            egl = (EGL10) EGLContext.getEGL();

            eglDisplay = egl.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            if (eglDisplay == egl.EGL_NO_DISPLAY) {
                running = false;
                return;
            }
            int[] version = new int[2];
            if (!egl.eglInitialize(eglDisplay, version)) {
                running = false;
                return;
            }

            int[] configAttributes = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_NONE
            };
            EGLConfig[] eglConfigs = new EGLConfig[1];
            int[] numConfigs = new int[1];
            if (!egl.eglChooseConfig(eglDisplay, configAttributes, eglConfigs, 1, numConfigs)) {
                running = false;
                return;
            }
            eglConfig = eglConfigs[0];

            int[] contextAttributes = {
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
                EGL14.EGL_NONE
            };
            eglContext = egl.eglCreateContext(eglDisplay, eglConfig, egl.EGL_NO_CONTEXT, contextAttributes);
            if (eglContext == null) {
                running = false;
                return;
            }

            eglSurface = egl.eglCreateWindowSurface(eglDisplay, eglConfig, surfaceTexture, null);
            if (eglSurface == null) {
                running = false;
                return;
            }

            if (!egl.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                running = false;
                return;
            }

            genParticlesData();

            // draw program (vertex and fragment shaders)
            int vertexShader = GLES31.glCreateShader(GLES31.GL_VERTEX_SHADER);
            int fragmentShader = GLES31.glCreateShader(GLES31.GL_FRAGMENT_SHADER);
            if (vertexShader == 0 || fragmentShader == 0) {
                running = false;
                return;
            }
            GLES31.glShaderSource(vertexShader, RLottieDrawable.readRes(null, R.raw.dust_vertex) + "\n// " + Math.random());
            GLES31.glCompileShader(vertexShader);
            int[] status = new int[1];
            GLES31.glGetShaderiv(vertexShader, GLES31.GL_COMPILE_STATUS, status, 0);
            if (status[0] == 0) {
                FileLog.e("RemovingDustEffect, compile vertex shader error: " + GLES31.glGetShaderInfoLog(vertexShader));
                GLES31.glDeleteShader(vertexShader);
                running = false;
                return;
            }
            GLES31.glShaderSource(fragmentShader, RLottieDrawable.readRes(null, R.raw.dust_fragment) + "\n// " + Math.random());
            GLES31.glCompileShader(fragmentShader);
            GLES31.glGetShaderiv(fragmentShader, GLES31.GL_COMPILE_STATUS, status, 0);
            if (status[0] == 0) {
                FileLog.e("RemovingDustEffect, compile fragment shader error: " + GLES31.glGetShaderInfoLog(fragmentShader));
                GLES31.glDeleteShader(fragmentShader);
                running = false;
                return;
            }
            drawProgram = GLES31.glCreateProgram();
            if (drawProgram == 0) {
                running = false;
                return;
            }
            GLES31.glAttachShader(drawProgram, vertexShader);
            GLES31.glAttachShader(drawProgram, fragmentShader);
            String[] feedbackVaryings = {"outPosition", "outVelocity", "outTime", "outDuration"};
            GLES31.glTransformFeedbackVaryings(drawProgram, feedbackVaryings, GLES31.GL_INTERLEAVED_ATTRIBS);

            GLES31.glLinkProgram(drawProgram);
            GLES31.glGetProgramiv(drawProgram, GLES31.GL_LINK_STATUS, status, 0);
            if (status[0] == 0) {
                FileLog.e("RemovingDustEffect, link draw program error: " + GLES31.glGetProgramInfoLog(drawProgram));
                running = false;
                return;
            }

            initHandle = GLES31.glGetUniformLocation(drawProgram, "init");
            timeHandle = GLES31.glGetUniformLocation(drawProgram, "time");
            deltaTimeHandle = GLES31.glGetUniformLocation(drawProgram, "deltaTime");
            sizeHandle = GLES31.glGetUniformLocation(drawProgram, "size");
            diameterHandle = GLES31.glGetUniformLocation(drawProgram, "diameter");
            seedHandle = GLES31.glGetUniformLocation(drawProgram, "seed");

            GLES31.glViewport(0, 0, width, height);
            GLES31.glEnable(GLES31.GL_BLEND);
            GLES31.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            GLES31.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);

            int[] textures = new int[1];
            GLES31.glGenTextures(1, textures, 0);
            textureID = textures[0];
            GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, textureID);
            GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_MIN_FILTER, GLES31.GL_LINEAR);
            GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_MAG_FILTER, GLES31.GL_LINEAR);
            GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_WRAP_S, GLES31.GL_CLAMP_TO_EDGE);
            GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_WRAP_T, GLES31.GL_CLAMP_TO_EDGE);
            GLUtils.texImage2D(GLES31.GL_TEXTURE_2D, 0, texture, 0);

            GLES31.glUseProgram(drawProgram);
            GLES31.glUniform2i(sizeHandle, width, height);
            GLES31.glUniform1f(initHandle, shouldInit ? 1 : 0);
            GLES31.glUniform1i(diameterHandle, diameterPx);
            GLES31.glUniform1f(seedHandle, Utilities.fastRandom.nextInt(256) / 256f);

            for (int i = 0; i < cells.size(); i++) {
                Rect rect = cells.get(i);

                int leftLoc = GLES31.glGetUniformLocation(drawProgram, "u_Rects[" + i + "].left");
                GLES31.glUniform1i(leftLoc,  rect.left);

                int topLoc = GLES31.glGetUniformLocation(drawProgram, "u_Rects[" + i + "].top");
                GLES31.glUniform1i(topLoc, rect.top);

                int widthLoc = GLES31.glGetUniformLocation(drawProgram, "u_Rects[" + i + "].width");
                GLES31.glUniform1i(widthLoc, rect.width());

                int heightLoc = GLES31.glGetUniformLocation(drawProgram, "u_Rects[" + i + "].height");
                GLES31.glUniform1i(heightLoc, rect.height());
            }

            int textureLoc = GLES31.glGetUniformLocation(drawProgram, "uTexture");
            GLES31.glUniform1i(textureLoc, 0);
        }

        private float t;
        private final float timeScale = .65f;

        private void drawFrame(float Δt) {
            if (!egl.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                running = false;
                return;
            }

            t += Δt * timeScale;
            if (t > 1000.f) {
                t = 0;
            }

            GLES31.glClear(GLES31.GL_COLOR_BUFFER_BIT);
            GLES31.glBindBuffer(GLES31.GL_ARRAY_BUFFER, particlesData[currentBuffer]);
            GLES31.glVertexAttribPointer(0, 2, GLES31.GL_FLOAT, false, 24, 0); // Position (vec2)
            GLES31.glEnableVertexAttribArray(0);
            GLES31.glVertexAttribPointer(1, 2, GLES31.GL_FLOAT, false, 24, 8); // Velocity (vec2)
            GLES31.glEnableVertexAttribArray(1);
            GLES31.glVertexAttribPointer(2, 1, GLES31.GL_FLOAT, false, 24, 16); // Time (float)
            GLES31.glEnableVertexAttribArray(2);
            GLES31.glVertexAttribPointer(3, 1, GLES31.GL_FLOAT, false, 24, 20); // Duration (float)
            GLES31.glEnableVertexAttribArray(3);
            GLES31.glBindBufferBase(GLES31.GL_TRANSFORM_FEEDBACK_BUFFER, 0, particlesData[1 - currentBuffer]);
            GLES31.glVertexAttribPointer(0, 2, GLES31.GL_FLOAT, false, 24, 0); // Position (vec2)
            GLES31.glEnableVertexAttribArray(0);
            GLES31.glVertexAttribPointer(1, 2, GLES31.GL_FLOAT, false, 24, 8); // Velocity (vec2)
            GLES31.glEnableVertexAttribArray(1);
            GLES31.glVertexAttribPointer(2, 1, GLES31.GL_FLOAT, false, 24, 16); // Time (float)
            GLES31.glEnableVertexAttribArray(2);
            GLES31.glVertexAttribPointer(3, 1, GLES31.GL_FLOAT, false, 24, 20); // Duration (float)
            GLES31.glEnableVertexAttribArray(3);

            GLES31.glActiveTexture(GLES31.GL_TEXTURE0);
            GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, textureID);

            GLES31.glUniform1f(timeHandle, t);
            GLES31.glUniform1f(deltaTimeHandle, Δt * timeScale);
            GLES31.glBeginTransformFeedback(GLES31.GL_POINTS);
            GLES31.glDrawArrays(GLES31.GL_POINTS, 0, particlesCount);
            GLES31.glEndTransformFeedback();

            if (shouldInit) {
                shouldInit = false;
                GLES31.glUniform1f(initHandle, 0f);
            }
            currentBuffer = 1 - currentBuffer;

            egl.eglSwapBuffers(eglDisplay, eglSurface);

            checkGlErrors();
        }

        private void die() {
            if (particlesData != null) {
                try { GLES31.glDeleteBuffers(2, particlesData, 0); } catch (Exception e) { FileLog.e(e); };
                particlesData = null;
            }
            if (drawProgram != 0) {
                try { GLES31.glDeleteProgram(drawProgram); } catch (Exception e) { FileLog.e(e); };
                drawProgram = 0;
            }
            if (egl != null) {
                try { egl.eglMakeCurrent(eglDisplay, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT); } catch (Exception e) { FileLog.e(e); };
                try { egl.eglDestroySurface(eglDisplay, eglSurface); } catch (Exception e) { FileLog.e(e); };
                try { egl.eglDestroyContext(eglDisplay, eglContext); } catch (Exception e) { FileLog.e(e); };
            }
            try { surfaceTexture.release(); } catch (Exception e) { FileLog.e(e); };
            try { texture.recycle(); } catch (Exception e) { FileLog.e(e); };

            checkGlErrors();
        }

        private void checkResize() {
            synchronized (resizeLock) {
                if (resize) {
                    GLES31.glUniform2f(sizeHandle, width, height);
                    GLES31.glViewport(0, 0, width, height);
                    int newParticlesCount = particlesCount();
                    if (newParticlesCount > this.particlesCount) {
                        shouldInit = true;
                        genParticlesData();
                    }
                    this.particlesCount = newParticlesCount;
                    resize = false;
                }
            }
        }

        private void genParticlesData() {
            if (particlesData != null) {
                GLES31.glDeleteBuffers(2, particlesData, 0);
            }

            particlesData = new int[2];
            GLES31.glGenBuffers(2, particlesData, 0);

            for (int i = 0; i < 2; ++i) {
                GLES31.glBindBuffer(GLES31.GL_ARRAY_BUFFER, particlesData[i]);
                GLES31.glBufferData(GLES31.GL_ARRAY_BUFFER, this.particlesCount * 6 * 4, null, GLES31.GL_DYNAMIC_DRAW);
            }

            checkGlErrors();
        }

        private void checkGlErrors() {
            int err;
            while ((err = GLES31.glGetError()) != GLES31.GL_NO_ERROR) {
                FileLog.e("RemovingDustEffect, gles error " + err);
            }
        }
    }
}
