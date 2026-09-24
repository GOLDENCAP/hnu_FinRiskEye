package com.example.chapter_server.carmera;

import android.hardware.Camera;  // 只保留这一个
import android.view.Surface;
import android.view.SurfaceView;
// 删除 import android.graphics.Camera;

public class camerahelper {

    private Camera mCarmer;
    private SurfaceView mSurfaceView;
    public void openBackCamera() {
        try {
            mCarmer = Camera.open(0);
            mCarmer.setPreviewDisplay(mSurfaceView.getHolder());


        } catch (Exception e) {
            e.printStackTrace();
        }


    }

    //释放摄像头
    public void releaseCamera(){
        if(mCarmer!=null){
            mCarmer.release();
            mCarmer=null;
        }

    }
}