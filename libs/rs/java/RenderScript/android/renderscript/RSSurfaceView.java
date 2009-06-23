/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.renderscript;

import java.io.Writer;
import java.util.ArrayList;
import java.util.concurrent.Semaphore;

import android.content.Context;
import android.os.Handler;
import android.os.Message;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

public class RSSurfaceView extends SurfaceView implements SurfaceHolder.Callback {
    private SurfaceHolder mSurfaceHolder;

    /**
     * Standard View constructor. In order to render something, you
     * must call {@link #setRenderer} to register a renderer.
     */
    public RSSurfaceView(Context context) {
        super(context);
        init();
        Log.v(RenderScript.LOG_TAG, "RSSurfaceView");
    }

    /**
     * Standard View constructor. In order to render something, you
     * must call {@link #setRenderer} to register a renderer.
     */
    public RSSurfaceView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
        Log.v(RenderScript.LOG_TAG, "RSSurfaceView");
    }

    private void init() {
        // Install a SurfaceHolder.Callback so we get notified when the
        // underlying surface is created and destroyed
        SurfaceHolder holder = getHolder();
        holder.addCallback(this);
        holder.setType(SurfaceHolder.SURFACE_TYPE_GPU);
    }

    /**
     * This method is part of the SurfaceHolder.Callback interface, and is
     * not normally called or subclassed by clients of RSSurfaceView.
     */
    public void surfaceCreated(SurfaceHolder holder) {
        Log.v(RenderScript.LOG_TAG, "surfaceCreated");
        mSurfaceHolder = holder;
        //mGLThread.surfaceCreated();
    }

    /**
     * This method is part of the SurfaceHolder.Callback interface, and is
     * not normally called or subclassed by clients of RSSurfaceView.
     */
    public void surfaceDestroyed(SurfaceHolder holder) {
        // Surface will be destroyed when we return
        Log.v(RenderScript.LOG_TAG, "surfaceDestroyed");
        //mGLThread.surfaceDestroyed();
    }

    /**
     * This method is part of the SurfaceHolder.Callback interface, and is
     * not normally called or subclassed by clients of RSSurfaceView.
     */
    public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        Log.v(RenderScript.LOG_TAG, "surfaceChanged");

        //mGLThread.onWindowResize(w, h);
    }

    /**
     * Inform the view that the activity is paused. The owner of this view must
     * call this method when the activity is paused. Calling this method will
     * pause the rendering thread.
     * Must not be called before a renderer has been set.
     */
    public void onPause() {
        Log.v(RenderScript.LOG_TAG, "onPause");
        //mGLThread.onPause();
    }

    /**
     * Inform the view that the activity is resumed. The owner of this view must
     * call this method when the activity is resumed. Calling this method will
     * recreate the OpenGL display and resume the rendering
     * thread.
     * Must not be called before a renderer has been set.
     */
    public void onResume() {
        Log.v(RenderScript.LOG_TAG, "onResume");
        //mGLThread.onResume();
    }

    /**
     * Queue a runnable to be run on the GL rendering thread. This can be used
     * to communicate with the Renderer on the rendering thread.
     * Must not be called before a renderer has been set.
     * @param r the runnable to be run on the GL rendering thread.
     */
    public void queueEvent(Runnable r) {
        Log.v(RenderScript.LOG_TAG, "queueEvent");
        //mGLThread.queueEvent(r);
    }

    /**
     * This method is used as part of the View class and is not normally
     * called or subclassed by clients of RSSurfaceView.
     * Must not be called before a renderer has been set.
     */
    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        //mGLThread.requestExitAndWait();
    }

    // ----------------------------------------------------------------------

    public RenderScript createRenderScript() {
        Log.v(RenderScript.LOG_TAG, "createRenderScript 1");
        Surface sur = null;
        while ((sur == null) || (mSurfaceHolder == null)) {
            sur = getHolder().getSurface();
        }
        Log.v(RenderScript.LOG_TAG, "createRenderScript 2");
        RenderScript rs = new RenderScript(sur);
        Log.v(RenderScript.LOG_TAG, "createRenderScript 3 rs");
        return rs;
    }

}

