package com.dsh.android.guard;

import java.io.File;
import java.util.*;

public final class KeysDump {
    public static void main(String[] a) throws Exception {
        RiskModel m = RiskModel.load(new File(a[0]));
        for (int i = 1; i < a.length; i++) {
            List<String> k = new ArrayList<>(m.featureKeys(a[i]));
            Collections.sort(k);
            System.out.println("=== " + a[i]);
            for (String s : k) System.out.println(s);
        }
    }
}
