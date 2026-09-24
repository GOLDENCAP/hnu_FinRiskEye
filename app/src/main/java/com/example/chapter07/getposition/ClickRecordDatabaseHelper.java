package com.example.chapter07.getposition;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;

public class ClickRecordDatabaseHelper extends SQLiteOpenHelper  {
    
    private static final String DATABASE_NAME = "click_records.db";
    private static final int DATABASE_VERSION = 1;
    private static final String TABLE_NAME = "click_records";
    
    // 列名
    private static final String COLUMN_ID = "_id";
    private static final String COLUMN_X = "x_coordinate";
    private static final String COLUMN_Y = "y_coordinate";
    private static final String COLUMN_TIMESTAMP = "timestamp";
    
    // 创建表的SQL语句
    private static final String CREATE_TABLE_SQL = 
        "CREATE TABLE " + TABLE_NAME + " (" +
        COLUMN_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
        COLUMN_X + " INTEGER NOT NULL, " +
        COLUMN_Y + " INTEGER NOT NULL, " +
        COLUMN_TIMESTAMP + " DATETIME DEFAULT CURRENT_TIMESTAMP" +
        ")";
    
    public ClickRecordDatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }
    
    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(CREATE_TABLE_SQL);
    }
    
    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_NAME);
        onCreate(db);
    }
    
    // 插入点击记录
    public long insertClickRecord(int x, int y) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COLUMN_X, x);
        values.put(COLUMN_Y, y);
        
        long id = db.insert(TABLE_NAME, null, values);
        db.close();
        return id;
    }
    
    // 获取所有点击记录
    public List<ClickRecord> getAllClickRecords() {
        List<ClickRecord> records = new ArrayList<>();
        SQLiteDatabase db = this.getReadableDatabase();

        Cursor cursor = db.query(TABLE_NAME,
            new String[]{COLUMN_ID, COLUMN_X, COLUMN_Y, COLUMN_TIMESTAMP},
            null, null, null, null, COLUMN_TIMESTAMP + " DESC");

        if (cursor.moveToFirst()) {
            int idIndex = cursor.getColumnIndex(COLUMN_ID);
            int xIndex = cursor.getColumnIndex(COLUMN_X);
            int yIndex = cursor.getColumnIndex(COLUMN_Y);
            int timeIndex = cursor.getColumnIndex(COLUMN_TIMESTAMP);

            do {
                if (idIndex >= 0 && xIndex >= 0 && yIndex >= 0 && timeIndex >= 0) {
                    ClickRecord record = new ClickRecord();
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
    
    // 获取记录总数
    public int getRecordCount() {
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM " + TABLE_NAME, null);
        cursor.moveToFirst();
        int count = cursor.getInt(0);
        cursor.close();
        db.close();
        return count;
    }
    
    // 清空所有记录
    public void clearAllRecords() {
        SQLiteDatabase db = this.getWritableDatabase();
        db.delete(TABLE_NAME, null, null);
        db.close();
    }
    
    // 内部类：点击记录实体
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
}