package com.example.chapter07.my_opencv;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;

import androidx.appcompat.app.AppCompatActivity;

import com.example.chapter07.R;
import com.example.chapter07.my_opencv.get_picture_location.get_picture_location;

public class my_opencv_all_activity_list extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_my_opencv_all_activity_list);

        Button btnOpenPictureLocation = findViewById(R.id.btn_open_picture_location);
        btnOpenPictureLocation.setOnClickListener(view -> {
            Intent intent = new Intent(my_opencv_all_activity_list.this, get_picture_location.class);
            startActivity(intent);
        });
    }
}
