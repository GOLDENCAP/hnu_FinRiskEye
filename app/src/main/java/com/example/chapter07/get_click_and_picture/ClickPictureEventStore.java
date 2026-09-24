package com.example.chapter07.get_click_and_picture;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 记录一次点击事件以及与之严格一一对应的周围截图信息。
 *
 * 文件结构：
 * /data/user/0/com.example.chapter07/databases/<customFolderName>/
 *   ├─ click_picture_records.db
 *   └─ pictures/
 *       └─ click_<id>_<timestamp>.png
 */
public class ClickPictureEventStore {

    private static final String TAG = "ClickPictureEventStore";
    private static final String DATABASE_ROOT = "/data/user/0/com.example.chapter07/databases";
    private static final String DEFAULT_DATABASE_NAME = "click_picture_records.db";
    private static final String TABLE_NAME = "click_picture_records";
    private static final String PICTURE_DIR_NAME = "pictures";
    private static final String TIMESTAMP_PATTERN = "yyyy-MM-dd_HH-mm-ss_SSS";

    private static final String COLUMN_ID = "_id";
    private static final String COLUMN_CLICK_X = "click_x";
    private static final String COLUMN_CLICK_Y = "click_y";
    private static final String COLUMN_CROP_LEFT = "crop_left";
    private static final String COLUMN_CROP_TOP = "crop_top";
    private static final String COLUMN_CROP_WIDTH = "crop_width";
    private static final String COLUMN_CROP_HEIGHT = "crop_height";
    private static final String COLUMN_EVENT_TIME = "event_time";
    private static final String COLUMN_EVENT_TIME_TEXT = "event_time_text";
    private static final String COLUMN_IMAGE_PATH = "image_path";
    private static final String COLUMN_IMAGE_NAME = "image_name";
    private static final String COLUMN_IMAGE_WIDTH = "image_width";
    private static final String COLUMN_IMAGE_HEIGHT = "image_height";

    private static final String CREATE_TABLE_SQL =
            "CREATE TABLE IF NOT EXISTS " + TABLE_NAME + " (" +
                    COLUMN_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    COLUMN_CLICK_X + " INTEGER NOT NULL, " +
                    COLUMN_CLICK_Y + " INTEGER NOT NULL, " +
                    COLUMN_CROP_LEFT + " INTEGER NOT NULL, " +
                    COLUMN_CROP_TOP + " INTEGER NOT NULL, " +
                    COLUMN_CROP_WIDTH + " INTEGER NOT NULL, " +
                    COLUMN_CROP_HEIGHT + " INTEGER NOT NULL, " +
                    COLUMN_EVENT_TIME + " INTEGER NOT NULL, " +
                    COLUMN_EVENT_TIME_TEXT + " TEXT NOT NULL, " +
                    COLUMN_IMAGE_PATH + " TEXT NOT NULL, " +
                    COLUMN_IMAGE_NAME + " TEXT NOT NULL, " +
                    COLUMN_IMAGE_WIDTH + " INTEGER NOT NULL, " +
                    COLUMN_IMAGE_HEIGHT + " INTEGER NOT NULL" +
                    ")";

    private final File sessionDir;
    private final File pictureDir;
    private final SQLiteDatabase database;

    public ClickPictureEventStore(Context context, String customFolderName) {
        String safeFolderName = sanitizeFolderName(customFolderName);
        sessionDir = new File(DATABASE_ROOT, safeFolderName);
        pictureDir = new File(sessionDir, PICTURE_DIR_NAME);
        ensureDirectory(sessionDir);
        ensureDirectory(pictureDir);

        File dbFile = new File(sessionDir, DEFAULT_DATABASE_NAME);
        database = SQLiteDatabase.openOrCreateDatabase(dbFile, null);
        database.execSQL(CREATE_TABLE_SQL);

        Log.d(TAG, "事件仓库初始化完成: " + dbFile.getAbsolutePath());
    }

    public synchronized ClickPictureRecord saveEvent(int clickX,
                                                     int clickY,
                                                     int cropLeft,
                                                     int cropTop,
                                                     Bitmap aroundBitmap) throws IOException {
        if (aroundBitmap == null) {
            throw new IOException("aroundBitmap 不能为空");
        }

        long eventTime = System.currentTimeMillis();
        String timeText = formatTimestamp(eventTime);

        ContentValues values = new ContentValues();
        values.put(COLUMN_CLICK_X, clickX);
        values.put(COLUMN_CLICK_Y, clickY);
        values.put(COLUMN_CROP_LEFT, cropLeft);
        values.put(COLUMN_CROP_TOP, cropTop);
        values.put(COLUMN_CROP_WIDTH, aroundBitmap.getWidth());
        values.put(COLUMN_CROP_HEIGHT, aroundBitmap.getHeight());
        values.put(COLUMN_EVENT_TIME, eventTime);
        values.put(COLUMN_EVENT_TIME_TEXT, timeText);
        values.put(COLUMN_IMAGE_PATH, "");
        values.put(COLUMN_IMAGE_NAME, "");
        values.put(COLUMN_IMAGE_WIDTH, aroundBitmap.getWidth());
        values.put(COLUMN_IMAGE_HEIGHT, aroundBitmap.getHeight());

        long rowId = database.insertOrThrow(TABLE_NAME, null, values);
        String imageName = "click_" + rowId + "_" + timeText + ".png";
        File imageFile = new File(pictureDir, imageName);
        saveBitmapToFile(aroundBitmap, imageFile);

        ContentValues updateValues = new ContentValues();
        updateValues.put(COLUMN_IMAGE_PATH, imageFile.getAbsolutePath());
        updateValues.put(COLUMN_IMAGE_NAME, imageName);
        database.update(TABLE_NAME, updateValues, COLUMN_ID + "=?", new String[]{String.valueOf(rowId)});

        return new ClickPictureRecord(
                rowId,
                clickX,
                clickY,
                cropLeft,
                cropTop,
                aroundBitmap.getWidth(),
                aroundBitmap.getHeight(),
                eventTime,
                timeText,
                imageFile.getAbsolutePath(),
                imageName
        );
    }

    public synchronized ClickPictureRecord getRecordById(long recordId) {
        Cursor cursor = null;
        try {
            cursor = database.query(
                    TABLE_NAME,
                    null,
                    COLUMN_ID + "=?",
                    new String[]{String.valueOf(recordId)},
                    null,
                    null,
                    null
            );

            if (cursor.moveToFirst()) {
                return mapRecord(cursor);
            }
            return null;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    public synchronized Bitmap loadBitmapById(long recordId) {
        ClickPictureRecord record = getRecordById(recordId);
        if (record == null) {
            return null;
        }
        return BitmapFactory.decodeFile(record.imagePath);
    }

    public synchronized void close() {
        if (database.isOpen()) {
            database.close();
        }
    }

    public File getSessionDir() {
        return sessionDir;
    }

    public File getDatabaseFile() {
        return new File(sessionDir, DEFAULT_DATABASE_NAME);
    }

    public File getPictureDir() {
        return pictureDir;
    }

    private ClickPictureRecord mapRecord(Cursor cursor) {
        return new ClickPictureRecord(
                cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_ID)),
                cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_CLICK_X)),
                cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_CLICK_Y)),
                cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_CROP_LEFT)),
                cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_CROP_TOP)),
                cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_CROP_WIDTH)),
                cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_CROP_HEIGHT)),
                cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_EVENT_TIME)),
                cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_EVENT_TIME_TEXT)),
                cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_IMAGE_PATH)),
                cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_IMAGE_NAME))
        );
    }

    private static void saveBitmapToFile(Bitmap bitmap, File file) throws IOException {
        FileOutputStream outputStream = null;
        try {
            outputStream = new FileOutputStream(file);
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)) {
                throw new IOException("保存 PNG 失败: " + file.getAbsolutePath());
            }
            outputStream.flush();
        } finally {
            if (outputStream != null) {
                outputStream.close();
            }
        }
    }

    private static String formatTimestamp(long timeMillis) {
        return new SimpleDateFormat(TIMESTAMP_PATTERN, Locale.getDefault()).format(new Date(timeMillis));
    }

    private static void ensureDirectory(File directory) {
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("无法创建目录: " + directory.getAbsolutePath());
        }
    }

    private static String sanitizeFolderName(String customFolderName) {
        String source = customFolderName == null ? "" : customFolderName.trim();
        if (source.isEmpty()) {
            source = "click_picture_session";
        }
        return source.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    public static final class ClickPictureRecord {
        public final long id;
        public final int clickX;
        public final int clickY;
        public final int cropLeft;
        public final int cropTop;
        public final int cropWidth;
        public final int cropHeight;
        public final long eventTime;
        public final String eventTimeText;
        public final String imagePath;
        public final String imageName;

        public ClickPictureRecord(long id,
                                  int clickX,
                                  int clickY,
                                  int cropLeft,
                                  int cropTop,
                                  int cropWidth,
                                  int cropHeight,
                                  long eventTime,
                                  String eventTimeText,
                                  String imagePath,
                                  String imageName) {
            this.id = id;
            this.clickX = clickX;
            this.clickY = clickY;
            this.cropLeft = cropLeft;
            this.cropTop = cropTop;
            this.cropWidth = cropWidth;
            this.cropHeight = cropHeight;
            this.eventTime = eventTime;
            this.eventTimeText = eventTimeText;
            this.imagePath = imagePath;
            this.imageName = imageName;
        }
    }
}
