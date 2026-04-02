package com.zero.recorder;

import android.util.Log;

public final class RecorderLog {
    private RecorderLog() {
    }

    public static void i(String tag, String message) {
        Log.i(tag, message);
        System.out.println("[*] " + message);
    }

    public static void w(String tag, String message) {
        Log.w(tag, message);
        System.err.println("[!] " + message);
    }

    public static void e(String tag, String message) {
        Log.e(tag, message);
        System.err.println("[-] " + message);
    }

    public static void e(String tag, String message, Throwable throwable) {
        Log.e(tag, message, throwable);
        System.err.println("[-] " + message + ": " + throwable.getMessage());
        throwable.printStackTrace();
    }
}
