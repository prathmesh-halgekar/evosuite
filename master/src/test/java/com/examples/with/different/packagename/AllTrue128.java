package com.examples.with.different.packagename;

public class AllTrue128 {
    public void testAllTrue(boolean[] a) {
        boolean alltrue = true;
        for (int i = 0; i < 128; i++) {
            alltrue = alltrue && a[i];
        }
        if (alltrue) {
            // target
            System.out.println("target");
        }
    }
}
