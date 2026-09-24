package com.example.chapter07.drawing_board;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

public class DrawingBoardView extends SurfaceView implements SurfaceHolder.Callback, Runnable {

    private SurfaceHolder holder;
    private Thread drawThread;
    private boolean isDrawing = false;

    private Paint paint;
    private Path path;

    private float lastX, lastY;

    public DrawingBoardView(Context context) {
        this(context, null);
    }

    public DrawingBoardView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        holder = getHolder();
        holder.addCallback(this);

        paint = new Paint();
        paint.setColor(Color.BLACK);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(10f);
        paint.setAntiAlias(true);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);

        path = new Path();

        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    public void clearCanvas() {
        path.reset();
        if (holder.getSurface().isValid()) {
            Canvas canvas = holder.lockCanvas();
            if (canvas != null) {
                canvas.drawColor(Color.WHITE);
                holder.unlockCanvasAndPost(canvas);
            }
        }
    }

    public void setPaintColor(int color) {
        paint.setColor(color);
    }

    public void setPaintStrokeWidth(float width) {
        paint.setStrokeWidth(width);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                path.moveTo(x, y);
                lastX = x;
                lastY = y;
                return true;

            case MotionEvent.ACTION_MOVE:
                float dx = Math.abs(x - lastX);
                float dy = Math.abs(y - lastY);
                if (dx >= 3 || dy >= 3) {
                    path.quadTo(lastX, lastY, (x + lastX) / 2, (y + lastY) / 2);
                    lastX = x;
                    lastY = y;
                }
                return true;

            case MotionEvent.ACTION_UP:
                path.lineTo(lastX, lastY);
                return true;
        }
        return super.onTouchEvent(event);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        isDrawing = true;
        drawThread = new Thread(this);
        drawThread.start();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (holder.getSurface().isValid()) {
            Canvas canvas = holder.lockCanvas();
            if (canvas != null) {
                canvas.drawColor(Color.WHITE);
                holder.unlockCanvasAndPost(canvas);
            }
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        isDrawing = false;
        try {
            if (drawThread != null) {
                drawThread.join();
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void run() {
        while (isDrawing) {
            draw();
            try {
                Thread.sleep(16);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    private void draw() {
        if (!holder.getSurface().isValid()) {
            return;
        }

        Canvas canvas = holder.lockCanvas();
        if (canvas != null) {
            try {
                canvas.drawColor(Color.WHITE);
                canvas.drawPath(path, paint);
            } finally {
                holder.unlockCanvasAndPost(canvas);
            }
        }
    }
}
