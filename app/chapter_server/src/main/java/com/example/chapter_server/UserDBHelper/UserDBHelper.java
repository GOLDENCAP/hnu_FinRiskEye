package com.example.chapter_server.UserDBHelper;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import androidx.annotation.Nullable;

import java.sql.SQLClientInfoException;

public class UserDBHelper extends SQLiteOpenHelper {

    private static final String DB_NAME = "user.db";

    public static final String TABLE_NAME = "user_info";

    private static final int DB_VERSION = 1;

    //单例模式：
    private static UserDBHelper mHelper = null;

    private UserDBHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    //创造支持单例模式的外部函数接口
    public static UserDBHelper getInstance(Context context){
        if(mHelper==null){
            mHelper=new UserDBHelper(context);
        }
        return mHelper;
    }


    @Override
    public void onCreate(SQLiteDatabase db) {
        String sql="CREATE TABLE IF NOT EXISTS "+TABLE_NAME+"("+
                "_id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,"+
                " name VARCHAR NOT NULL,"+
                " age INTEGER NOT NULL,"+
                " height LONG NOT NULL,"+
                " weight FLOAT NOT NULL,"+
                " married INTEGER NOT NULL);";
        db.execSQL(sql);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {



    }
}
