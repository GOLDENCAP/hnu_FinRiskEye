package com.example.chapter07.drawing_board;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.example.chapter07.R;

public class DrawingBoardActivity extends AppCompatActivity {

    private DrawingBoardView drawingBoardView;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_drawing_board);

        drawingBoardView = findViewById(R.id.drawing_board_view);
        Button btnBack = findViewById(R.id.btn_back);
        Button btnClear = findViewById(R.id.btn_clear);

        btnBack.setOnClickListener(v -> finish());

        btnClear.setOnClickListener(v -> drawingBoardView.clearCanvas());
    }
}
