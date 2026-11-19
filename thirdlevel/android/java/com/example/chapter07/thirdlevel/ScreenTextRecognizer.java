package com.example.chapter07.thirdlevel;

import android.graphics.Bitmap;

import androidx.annotation.NonNull;

import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

/**
 * 对屏幕截图执行本地中文 OCR。
 */
public final class ScreenTextRecognizer implements AutoCloseable {

    public interface Callback {
        void onRecognized(String text);

        void onError(String message);
    }

    private final TextRecognizer recognizer;

    public ScreenTextRecognizer() {
        recognizer = TextRecognition.getClient(
                new ChineseTextRecognizerOptions.Builder().build()
        );
    }

    public void recognize(Bitmap bitmap, Callback callback) {
        InputImage image = InputImage.fromBitmap(bitmap, 0);
        recognizer.process(image)
                .addOnSuccessListener(new OnSuccessListener<Text>() {
                    @Override
                    public void onSuccess(Text text) {
                        callback.onRecognized(text == null ? "" : text.getText());
                    }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(@NonNull Exception error) {
                        callback.onError(error.getMessage() == null
                                ? "屏幕文字识别失败"
                                : error.getMessage());
                    }
                });
    }

    @Override
    public void close() {
        recognizer.close();
    }
}
