package com.example.chapter07.adb_click;

import android.util.Log;

import com.example.chapter07.getposition.ClickAccessibilityService;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Arrays;

public final class RootShellTap {

    private static final String TAG = "RootShellTap";
    private static final String[] ROOT_PREFIX = new String[]{"su", "0"};

    public enum TapMode {
        ROOT,
        ACCESSIBILITY,
        UNAVAILABLE
    }

    private RootShellTap() {
    }

    public static boolean isRootAvailable() {
        return execForSuccess(new String[]{"su", "0", "id"});
    }

    public static boolean isAccessibilityAvailable() {
        return ClickAccessibilityService.isAlive();
    }

    public static TapMode getAvailableMode() {
        if (isRootAvailable()) {
            return TapMode.ROOT;
        }
        if (isAccessibilityAvailable()) {
            return TapMode.ACCESSIBILITY;
        }
        return TapMode.UNAVAILABLE;
    }

    public static boolean tap(int x, int y) {
        if (execForSuccess(buildCommand("input", "tap", String.valueOf(x), String.valueOf(y)))) {
            return true;
        }
        Log.w(TAG, "root tap failed, fallback to accessibility tap");
        return ClickAccessibilityService.click(x, y);
    }

    public static boolean swipe(int x1, int y1, int x2, int y2) {
        if (execForSuccess(buildCommand(
                "input", "swipe",
                String.valueOf(x1),
                String.valueOf(y1),
                String.valueOf(x2),
                String.valueOf(y2)
        ))) {
            return true;
        }
        Log.w(TAG, "root swipe failed, fallback to accessibility swipe");
        ClickAccessibilityService.swipe(x1, y1, x2, y2, 300L);
        return ClickAccessibilityService.isAlive();
    }

    private static String[] buildCommand(String... commandParts) {
        String[] fullCommand = Arrays.copyOf(ROOT_PREFIX, ROOT_PREFIX.length + commandParts.length);
        System.arraycopy(commandParts, 0, fullCommand, ROOT_PREFIX.length, commandParts.length);
        return fullCommand;
    }

    private static boolean execForSuccess(String[] command) {
        Process process = null;
        try {
            Log.d(TAG, "exec command=" + Arrays.toString(command));
            process = Runtime.getRuntime().exec(command);
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                return true;
            }
            Log.e(TAG, "command failed, exitCode=" + exitCode + ", stderr=" + readStream(process.getErrorStream()));
            return false;
        } catch (IOException | InterruptedException e) {
            Log.e(TAG, "exec exception, command=" + Arrays.toString(command), e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private static String readStream(java.io.InputStream inputStream) {
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (builder.length() > 0) {
                    builder.append('\n');
                }
                builder.append(line);
            }
        } catch (IOException ignored) {
        }
        return builder.toString();
    }
}
