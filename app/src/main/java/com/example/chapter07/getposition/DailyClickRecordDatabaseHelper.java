package com.example.chapter07.getposition;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class DailyClickRecordDatabaseHelper extends SQLiteOpenHelper {

    private static final String DATABASE_NAME_PREFIX = "clicks_";
    private static final String DATABASE_NAME_SUFFIX = ".db";
    private static final int DATABASE_VERSION = 2;

    private final String timestamp;

    public DailyClickRecordDatabaseHelper(Context context) {
        this(context, getCurrentTimestamp());
    }

    public DailyClickRecordDatabaseHelper(Context context, String timestamp) {
        super(context, getDatabaseName(timestamp), null, DATABASE_VERSION);
        this.timestamp = timestamp;
    }

    private static String getDatabaseName(String timestamp) {
        return DATABASE_NAME_PREFIX + timestamp + DATABASE_NAME_SUFFIX;
    }

    public static String getCurrentTimestamp() {
        return new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(new Date());
    }

    public static String getDisplayTimestamp(String timestamp) {
        try {
            SimpleDateFormat inputFormat = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault());
            SimpleDateFormat outputFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
            Date date = inputFormat.parse(timestamp);
            return date != null ? outputFormat.format(date) : timestamp;
        } catch (Exception e) {
            return timestamp;
        }
    }

    public static String getDatePart(String timestamp) {
        if (timestamp == null || timestamp.length() < 10) return timestamp;
        return timestamp.substring(0, 10);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(getCreateTableSQL());
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE " + getTableName() + " ADD COLUMN source TEXT DEFAULT 'overlay'");
        }
    }

    private String getCreateTableSQL() {
        return "CREATE TABLE " + getTableName() + " (" +
                COLUMN_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                COLUMN_X + " INTEGER NOT NULL, " +
                COLUMN_Y + " INTEGER NOT NULL, " +
                COLUMN_SCREEN_X + " REAL NOT NULL, " +
                COLUMN_SCREEN_Y + " REAL NOT NULL, " +
                COLUMN_ACTION + " INTEGER NOT NULL, " +
                COLUMN_SOURCE + " TEXT NOT NULL DEFAULT 'overlay', " +
                COLUMN_TIMESTAMP + " DATETIME DEFAULT CURRENT_TIMESTAMP" +
                ")";
    }

    private String getTableName() {
        return "click_records";
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.setForeignKeyConstraintsEnabled(false);
    }

    public long insertClickRecord(int x, int y) {
        return insertClickRecord(x, y, x, y, MotionAction.ACTION_DOWN, "overlay");
    }

    public long insertClickRecord(float screenX, float screenY, int action, String source) {
        int x = Math.round(screenX);
        int y = Math.round(screenY);
        return insertClickRecord(x, y, screenX, screenY, action, source);
    }

    public long insertClickRecord(int x, int y, float screenX, float screenY, int action, String source) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COLUMN_X, x);
        values.put(COLUMN_Y, y);
        values.put(COLUMN_SCREEN_X, screenX);
        values.put(COLUMN_SCREEN_Y, screenY);
        values.put(COLUMN_ACTION, action);
        values.put(COLUMN_SOURCE, source);
        long id = db.insert(getTableName(), null, values);
        db.close();
        return id;
    }

    public List<ClickRecordDatabaseHelper.ClickRecord> getAllClickRecords() {
        List<ClickRecordDatabaseHelper.ClickRecord> records = new ArrayList<>();
        SQLiteDatabase db = this.getReadableDatabase();

        Cursor cursor = db.query(getTableName(),
                new String[]{COLUMN_ID, COLUMN_X, COLUMN_Y, COLUMN_TIMESTAMP},
                null, null, null, null, COLUMN_TIMESTAMP + " ASC");

        if (cursor.moveToFirst()) {
            int idIndex = cursor.getColumnIndex(COLUMN_ID);
            int xIndex = cursor.getColumnIndex(COLUMN_X);
            int yIndex = cursor.getColumnIndex(COLUMN_Y);
            int timeIndex = cursor.getColumnIndex(COLUMN_TIMESTAMP);

            do {
                if (idIndex >= 0 && xIndex >= 0 && yIndex >= 0 && timeIndex >= 0) {
                    ClickRecordDatabaseHelper.ClickRecord record = new ClickRecordDatabaseHelper.ClickRecord();
                    record.id = cursor.getInt(idIndex);
                    record.x = cursor.getInt(xIndex);
                    record.y = cursor.getInt(yIndex);
                    record.timestamp = cursor.getString(timeIndex);
                    records.add(record);
                }
            } while (cursor.moveToNext());
        }

        cursor.close();
        db.close();
        return records;
    }

    public int getRecordCount() {
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM " + getTableName(), null);
        cursor.moveToFirst();
        int count = cursor.getInt(0);
        cursor.close();
        db.close();
        return count;
    }

    public void clearAllRecords() {
        SQLiteDatabase db = this.getWritableDatabase();
        db.delete(getTableName(), null, null);
        db.close();
    }

    public String getTimestamp() {
        return timestamp;
    }

    private static final String COLUMN_ID = "_id";
    private static final String COLUMN_X = "x_coordinate";
    private static final String COLUMN_Y = "y_coordinate";
    private static final String COLUMN_SCREEN_X = "screen_x";
    private static final String COLUMN_SCREEN_Y = "screen_y";
    private static final String COLUMN_ACTION = "touch_action";
    private static final String COLUMN_SOURCE = "source";
    private static final String COLUMN_TIMESTAMP = "timestamp";

    public static List<String> getAllTimestamps(Context context) {
        List<String> timestamps = new ArrayList<>();
        File dbDir = context.getDatabasePath("temp").getParentFile();
        if (dbDir == null) {
            return timestamps;
        }

        File[] files = dbDir.listFiles((dir, name) ->
                name.startsWith(DATABASE_NAME_PREFIX) && name.endsWith(DATABASE_NAME_SUFFIX));

        if (files != null) {
            for (File file : files) {
                String ts = file.getName().replace(DATABASE_NAME_PREFIX, "").replace(DATABASE_NAME_SUFFIX, "");
                timestamps.add(ts);
            }
        }

        Collections.sort(timestamps, (a, b) -> b.compareTo(a));
        return timestamps;
    }

    public static int getRecordCountForTimestamp(Context context, String timestamp) {
        DailyClickRecordDatabaseHelper dbHelper = new DailyClickRecordDatabaseHelper(context, timestamp);
        int count = dbHelper.getRecordCount();
        dbHelper.close();
        return count;
    }

    public static boolean deleteDatabase(Context context, String timestamp) {
        String dbName = getDatabaseName(timestamp);
        File dbDir = context.getDatabasePath("temp").getParentFile();
        if (dbDir == null) {
            return false;
        }
        File dbFile = new File(dbDir, dbName);
        return dbFile.exists() && dbFile.delete();
    }

    public static List<DatabaseInfo> getTodayDatabases(Context context) {
        List<DatabaseInfo> infos = new ArrayList<>();
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
        List<String> allTimestamps = getAllTimestamps(context);

        for (String ts : allTimestamps) {
            if (ts.startsWith(today)) {
                int count = getRecordCountForTimestamp(context, ts);
                infos.add(new DatabaseInfo(ts, count));
            }
        }

        return infos;
    }

    public static class DatabaseInfo {
        public String timestamp;
        public int recordCount;
        public String displayTime;

        public DatabaseInfo(String timestamp, int recordCount) {
            this.timestamp = timestamp;
            this.recordCount = recordCount;
            this.displayTime = getDisplayTimestamp(timestamp);
        }

        @Override
        public String toString() {
            return displayTime + " (" + recordCount + " 条记录)";
        }
    }

    public static class ClickRecord {
        public int id;
        public int x;
        public int y;
        public String timestamp;

        @Override
        public String toString() {
            return "ID: " + id + " | 坐标: (" + x + ", " + y + ") | 时间: " + timestamp;
        }
    }

    public static class MotionAction {
        public static final int ACTION_DOWN = 0;
        public static final int ACTION_UP = 1;
        public static final int ACTION_MOVE = 2;
    }
}

