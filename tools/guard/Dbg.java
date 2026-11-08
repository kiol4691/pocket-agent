package com.dsh.android.guard;

import java.util.HashMap;
import java.util.Map;

public final class Dbg {
    public static void main(String[] a) {
        Map<String, String> vars = new HashMap<>();
        vars.put("T", "/");
        System.out.println("resolve(\"$T\") = [" + CommandGuard.resolveVars("\"$T\"", vars, 0) + "]");
        System.out.println("norm = [" + Features.normPath("\"/\"", "/root/workspace") + "]");
        System.out.println("pclass = " + Features.pclass("/"));
    }
}
