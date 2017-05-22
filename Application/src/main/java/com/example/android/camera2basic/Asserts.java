package com.example.android.camera2basic;

import android.support.annotation.NonNull;

import java.util.Collection;

/**
 * Created by grigory on 19.05.17.
 */

public class Asserts
{
    public static <T> void assertNotNull(T ref, @NonNull String what)
    {
        if (ref == null) {
            throw new IllegalArgumentException("Assertion failed: " + what);
        }
    }

    public static <T> void assertNull(T ref, @NonNull String what)
    {
        if (ref != null) {
            throw new IllegalArgumentException("Assertion failed: " + what);
        }
    }

    public static void assertTrue(boolean expression, @NonNull String what)
    {
        if (!expression) {
            throw new IllegalArgumentException("Assertion failed: " + what);
        }
    }

    public static <T> void assertIndex(int index, @NonNull Collection<T> collection, @NonNull String what)
    {
        if (index <= 0 || index >= collection.size()) {
            throw new IllegalArgumentException("Assertion failed: " + what);
        }
    }

    public static void assertString(@NonNull String str)
    {
        Asserts.assertNotNull(str, "str != null");
        if (str.replaceAll(" ", "").isEmpty()) {
            throw new IllegalArgumentException("str.replaceAll(\" \", \"\").isEmpty()");
        }
    }
}
