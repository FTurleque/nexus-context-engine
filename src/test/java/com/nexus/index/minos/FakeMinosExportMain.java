package com.nexus.index.minos;

import java.nio.file.Path;

/** Test-only executable used to validate the MINOS process boundary. */
public final class FakeMinosExportMain {

    private FakeMinosExportMain() {
    }

    public static void main(String[] arguments) throws Exception {
        String root = null;
        for (int index = 0; index < arguments.length - 1; index++) {
            if ("--root".equals(arguments[index])) {
                root = arguments[index + 1];
                break;
            }
        }
        if (root == null) {
            System.err.println("missing --root");
            System.exit(2);
            return;
        }
        String canonical = Path.of(root).toRealPath().toString();
        System.out.print("{\"contractVersion\":\"1\",\"producer\":\"MINOS\","
                + "\"project\":{\"id\":\"fake\",\"name\":\"fake\",\"rootPath\":\""
                + escape(canonical)
                + "\",\"snapshotId\":\"fake-snapshot\"},"
                + "\"symbols\":[],\"relations\":[],\"limitations\":[]}");
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
