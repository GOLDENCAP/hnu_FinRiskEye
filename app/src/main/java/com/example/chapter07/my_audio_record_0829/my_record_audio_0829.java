package com.example.chapter07.my_audio_record_0829;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.chapter07.R;

public class my_record_audio_0829 extends AppCompatActivity {

    private audio_record_interface_encapsulation audioRecorder;
    private Button btnStart, btnStop;
    private String pcmFilePath;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_my_record_audio0829);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        // 初始化按钮
        btnStart = findViewById(R.id.btnStart);
        btnStop = findViewById(R.id.btnStop);

        // 创建录音工具
        audioRecorder = new audio_record_interface_encapsulation();
        audioRecorder.create_audio_builder(this);

        // 录音文件保存路径
        pcmFilePath = getExternalFilesDir(null) + "/recordings/test_record.pcm";

        // 开始录音
        btnStart.setOnClickListener(v -> {
            audioRecorder.startRecording(pcmFilePath);
            Toast.makeText(this, "开始录音", Toast.LENGTH_SHORT).show();
        });

        // 停止录音
        btnStop.setOnClickListener(v -> {
            audioRecorder.stopRecording();
            Toast.makeText(this, "录音已保存: " + pcmFilePath, Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        audioRecorder.release();
    }
}